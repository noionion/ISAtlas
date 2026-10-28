package dev.isatlas.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalPackagesTest {
    private val compiler = CompilerIsaOptions.decode(javaClass.getResource("/march-extensions.json")!!.readText())
    private val known = compiler.tokens.toSet()+setOf("s","u")
    private fun fixture(name: String = "demo-combined.zip") = javaClass.getResource("/$name")!!.readBytes()
    private fun decode(bytes: ByteArray) = LocalPackages.decode(bytes,9,known,setOf("manual","aia","debug","spmp"))
    private fun entries(bytes: ByteArray) = linkedMapOf<String,ByteArray>().also { result ->
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val entry=z.nextEntry ?: break; result[entry.name]=z.readBytes() } }
    }
    private fun zip(files: Map<String,ByteArray>) = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z ->
        files.forEach { (name,bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
    } }.toByteArray()
    private fun edit(path: String = "manifest.json", transform: (JsonObject)->JsonObject): ByteArray {
        val files=entries(fixture())
        files[path]=transform(Json.parseToJsonElement(files.getValue(path).decodeToString()).jsonObject).toString().toByteArray()
        if(path!="manifest.json") {
            val m=Json.parseToJsonElement(files.getValue("manifest.json").decodeToString()).jsonObject
            val hashes=JsonObject(m.getValue("hashes").jsonObject+(path to JsonPrimitive(LocalPackages.sha256(files.getValue(path)))))
            files["manifest.json"]=JsonObject(m+("hashes" to hashes)).toString().toByteArray()
        }
        return zip(files)
    }
    private fun failure(bytes: ByteArray, location: String? = null) {
        val ex=assertThrows(PackageValidationException::class.java) { decode(bytes) }
        if(location!=null) assertTrue(ex.message,ex.location.contains(location))
    }
    private fun base(): LocalCatalogs {
        val csrs=installedCatalog()
        val instructions=InstructionCatalog.decodeFiles(javaClass.getResource("/riscv-instructions.json")!!.readText()) { javaClass.getResource("/inst/$it")!!.readText() }
        return LocalCatalogs(csrs,instructions,compiler)
    }

    @Test fun `thead is optional with preserved source license and unknown lower bits`() {
        assertTrue(installedCatalog().registers.none { it.namespace=="thead" })
        val data=decode(fixture("thead-csr.zip"))
        val csr=data.registers.single()
        assertEquals("th.sxstatus",csr.name)
        assertEquals("Apache-2.0",csr.license)
        assertTrue(csr.sourceUrl.contains("5a75db269688"))
        assertEquals(listOf(64),csr.xlens)
        assertEquals(BitLayout(64,22,22),csr.fields.single().layout(64))
        assertTrue(data.manifest.licenseText.contains("Apache License"))
        assertTrue(csr.regions.none { it.layouts.any { loc -> loc.lsb<22 } })
    }
    @Test fun `combined package joins search CSR resolution custom filtering and binary decoding`() {
        val result=LocalPackages.merge(base(),listOf(decode(fixture())))
        assertTrue(Search.query(result.csr,"demo.control",64,namespace="demo").any { it.register.namespace=="demo" })
        assertEquals("demo.control",InstructionCsr.resolve(result.csr,0x7c0,64,"demo").single().name)
        assertTrue(result.compiler.selectionClosure(setOf("xdemo")).contains("i"))
        val word="0x00c5850b"
        val hits=InstructionSearch.query(result.instructions,word,CatalogFilter(64,namespace="demo",extensions=setOf("i","xdemo")),result.compiler).hits
        assertEquals("demo.add",hits.single().instruction.name)
        assertEquals("x10",hits.single().encoding.operands(0x00c5850b)["rd"])
        assertTrue(InstructionSearch.query(result.instructions,"demo.add",CatalogFilter(64,extensions=setOf("xdemo")),result.compiler).hits.isNotEmpty())
    }
    @Test fun `CSR-only and instruction-only packages need no dummy records`() {
        val csrOnly=entries(fixture())
        val manifest=Json.parseToJsonElement(csrOnly.getValue("manifest.json").decodeToString()).jsonObject
        for (section in listOf("csr","instructions")) {
            val removed=manifest.getValue(section).jsonArray.map { it.jsonPrimitive.content }
            val m=JsonObject(manifest+(section to JsonArray(emptyList()))+("hashes" to JsonObject(manifest.getValue("hashes").jsonObject.filterKeys { it !in removed })))
            val bytes=zip(csrOnly.filterKeys { it !in removed }+("manifest.json" to m.toString().toByteArray()))
            val data=decode(bytes)
            assertEquals(section=="csr",data.registers.isEmpty())
            assertEquals(section=="instructions",data.instructions.isEmpty())
        }
    }
    @Test fun `hash mismatch is localized and never changes the base`() {
        val files=entries(fixture()); files["csr/demo-control.json"]="{}".toByteArray()
        failure(zip(files),"csr/demo-control.json")
        assertEquals(434,installedCatalog().registers.size)
    }
    @Test fun `unknown files unsafe paths and duplicate JSON keys are rejected`() {
        for (name in listOf("../escape.json","/absolute.json","csr\\escape.json","extra.json")) failure(zip(entries(fixture())+(name to "{}".toByteArray())),"ZIP")
        val files=entries(fixture())
        files["manifest.json"]="{\"schemaVersion\":1,\"schemaVersion\":1}".toByteArray()
        failure(zip(files),"manifest.json")
    }
    @Test fun `unsupported format architecture minimum app and reserved namespaces fail early`() {
        for ((key,value) in listOf("schemaVersion" to JsonPrimitive(2),"architecture" to JsonPrimitive("arm"),"minAppVersionCode" to JsonPrimitive(99),"namespace" to JsonPrimitive("manual")))
            failure(edit { JsonObject(it+(key to value)) },"manifest.json")
    }
    @Test fun `CSR overlapping out of range and duplicate enum keys are rejected`() {
        failure(edit("csr/demo-control.json") { obj ->
            JsonObject(obj+("fields" to JsonArray(obj.getValue("fields").jsonArray+obj.getValue("fields").jsonArray)))
        },"csr/demo-control.json")
        failure(edit("csr/demo-control.json") { obj ->
            val f=obj.getValue("fields").jsonArray.single().jsonObject
            val wrong=JsonObject(f+("values" to buildJsonObject { put("2","overflow") })+("valuesEn" to buildJsonObject { put("2","overflow") }))
            JsonObject(obj+("fields" to JsonArray(listOf(wrong))))
        },"csr/demo-control.json")
    }
    @Test fun `bad instruction mask match operands and lengths give encoding locations`() {
        for ((key,value) in listOf("mask" to JsonPrimitive("0x00000001"),"match" to JsonPrimitive("0xffffffff"),"length" to JsonPrimitive(48))) {
            failure(edit("inst/demo-add.json") { obj ->
                val e=obj.getValue("encodings").jsonArray.single().jsonObject
                JsonObject(obj+("encodings" to JsonArray(listOf(JsonObject(e+(key to value))))))
            },"encodings/0")
        }
        failure(edit("inst/demo-add.json") { obj ->
            val e=obj.getValue("encodings").jsonArray.single().jsonObject
            val invalid=JsonObject(e+("assemblyOperands" to JsonArray(listOf(JsonPrimitive("missing")))))
            JsonObject(obj+("encodings" to JsonArray(listOf(invalid))))
        }) // Unknown encoding keys are not silently accepted.
    }
    @Test fun `undefined or cyclic custom prerequisites and namespace identity mismatches fail`() {
        failure(edit { JsonObject(it+("extensions" to buildJsonObject { put("xdemo",JsonArray(listOf(JsonPrimitive("xmissing")))) })) },"extensions")
        failure(edit { JsonObject(it+("extensions" to buildJsonObject { put("xdemo",JsonArray(listOf(JsonPrimitive("xdemo")))) })) },"extensions")
        failure(edit("csr/demo-control.json") { JsonObject(it+("namespace" to JsonPrimitive("other"))) },"csr")
    }
    @Test fun `package dependency failures pause dependents and recover when dependency returns`() {
        val a=decode(fixture()); val b=a.copy(manifest=a.manifest.copy(id="org.example.dependent",namespace="dependent",dependencies=listOf(PackageDependency(a.manifest.id,a.manifest.version))))
        fun item(d: LocalPackageData,enabled: Boolean=true)=ManagedLocalPackage(LocalPackageRecord(d.manifest.id,d.manifest.name,d.manifest.version,"hash.zip",enabled),d)
        assertNotNull(LocalPackages.statuses(listOf(item(b))).single().problem)
        assertTrue(LocalPackages.statuses(listOf(item(a),item(b))).all { it.active })
        assertNotNull(LocalPackages.statuses(listOf(item(a,false),item(b))).last().problem)
        assertNotNull(LocalPackages.statuses(listOf(item(a.copy(manifest=a.manifest.copy(version="2"))),item(b))).last().problem)
    }
    @Test fun `coexistence reports address and encoding overlaps without overriding originals`() {
        val data=decode(fixture()); val initial=base()
        val existing=initial.copy(csr=initial.csr.copy(registers=initial.csr.registers+data.registers),instructions=initial.instructions.copy(instructions=initial.instructions.instructions+data.instructions))
        assertEquals(PackageConflict(1,1),LocalPackages.conflicts(data,existing))
        assertThrows(IllegalArgumentException::class.java) { LocalPackages.merge(initial,listOf(data,data)) }
        assertEquals(434,initial.csr.registers.size)
    }
    @Test fun `archive expansion UTF8 nesting and byte bounds are enforced`() {
        failure(zip(mapOf("manifest.json" to ByteArray(256*1024+1))))
        failure(zip(mapOf("manifest.json" to byteArrayOf(0xc0.toByte(),0xaf.toByte()))))
        failure(zip(mapOf("manifest.json" to ("[".repeat(40)+"0"+"]".repeat(40)).toByteArray())))
        assertThrows(PackageValidationException::class.java) { LocalPackages.readBounded(ByteArray(33).inputStream(),32) }
        failure("not a ZIP".toByteArray())
    }
    @Test fun `contradictory fixed constraint is rejected instead of creating an unqueryable instruction`() {
        failure(edit("inst/demo-add.json") { obj ->
            val e=obj.getValue("encodings").jsonArray.single().jsonObject
            val c=buildJsonObject { put("mask","0x0000007f"); put("operator","in"); put("values",JsonArray(listOf(JsonPrimitive(0x13)))) }
            val invalid=JsonObject(e+("encodingConstraints" to JsonArray(listOf(c))))
            JsonObject(obj+("encodings" to JsonArray(listOf(invalid))))
        },"encodings/0")
    }
    @Test fun `local instructions do not inherit semantics merely by sharing a standard name`() {
        val data=decode(fixture())
        val original=data.instructions.single()
        val entry=original.copy(name="add",summary="私有语义",summaryEn="Private semantics")
        val encoding=entry.encodings.single()
        val hit=InstructionHit(entry,encoding,0,0x00c5850b)
        assertEquals("add a0, a1, a2",InstructionAssembly.render(hit)!!.text)
        assertEquals("私有语义",InstructionExamples.create(entry,encoding,64)!!.explanation)
        assertFalse(InstructionAssembly.render(hit.copy(instruction=entry.copy(assemblyOperands=null)))!!.complete)
        val customVm=encoding.copy(fields=encoding.fields.map { if (it.operand=="rs1") it.copy(operand="vm",registerClass=null) else it })
        val vmEntry=entry.copy(assemblyOperands=listOf("vm"))
        assertEquals("add 11",InstructionAssembly.render(InstructionHit(vmEntry,customVm,0,0x00c5850b))!!.text)
    }
}
