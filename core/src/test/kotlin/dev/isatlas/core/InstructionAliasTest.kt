package dev.isatlas.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class InstructionAliasTest {
    companion object {
        private fun asset(name: String) = InstructionAliasTest::class.java.classLoader!!.getResource(name)!!.readText()
        private val catalog = InstructionCatalog.decodeFiles(asset("riscv-instructions.json")) { asset("inst/$it") }
        private val compiler = CompilerIsaOptions.decode(asset("march-extensions.json"))
    }
    private fun definition(name: String) = catalog.instructions.single { it.name == name }
    private fun hit(name: String, values: Map<String, Long>, index: Int = 0): InstructionHit {
        val instruction = definition(name)
        val encoding = instruction.encodings[index]
        var word = encoding.match.removePrefix("0x").toLong(16)
        encoding.fields.filter { it.operand != null }.forEach { field ->
            val value = values[field.operand] ?: if (field.operand == "vm") 1L else 0L
            val positions = field.operandBits ?: (field.msb - field.lsb downTo 0).toList()
            positions.forEachIndexed { index, target -> word = word or (((value shr target) and 1) shl (field.msb - index)) }
        }
        return InstructionHit(instruction, encoding, index, word)
    }
    private fun render(hit: InstructionHit) = InstructionAssembly.render(hit, abi = false)!!.text
    private fun search(input: String, extensions: Set<String> = emptySet()) = InstructionSearch.query(catalog, input, CatalogFilter(64, extensions = extensions), compiler)

    @Test fun `all thirteen csr pseudo names load with correct argument ordering and register files`() {
        val csrNames = "csrr csrw csrwi csrs csrc csrsi csrci frcsr fscsr frrm fsrm frflags fsflags".split(" ")
        csrNames.forEach { name ->
            assertEquals("alias", definition(name).kind)
            definition(name).encodings.forEach { encoding ->
                assertTrue(name, encoding.fields.filter { it.operand in setOf("rd", "rs1") }.all { it.registerClass == "integer" })
            }
        }
        val values = mapOf("rd" to 10L, "rs1" to 11L, "csr" to 0x300L, "imm" to 31L)
        assertEquals("csrr x10, 0x300", render(hit("csrr", values)))
        assertEquals("csrw 0x300, x11", render(hit("csrw", values)))
        assertEquals("csrwi 0x300, 31", render(hit("csrwi", values)))
        assertEquals("csrs 0x300, x11", render(hit("csrs", values)))
        assertEquals("csrc 0x300, x11", render(hit("csrc", values)))
        assertEquals("csrsi 0x300, 31", render(hit("csrsi", values)))
        assertEquals("csrci 0x300, 31", render(hit("csrci", values)))
    }
    @Test fun `fixed csr aliases retain csr navigation including existing counter aliases`() {
        listOf("frcsr" to 3, "frrm" to 2, "frflags" to 1, "rdcycle" to 0xc00).forEach { (name, address) ->
            val hit = hit(name, mapOf("rd" to 10L))
            assertEquals(name, address, InstructionCsr.address(hit.encoding, hit.word!!))
        }
        assertEquals("fscsr x10, x11", render(hit("fscsr", mapOf("rd" to 10L, "rs1" to 11L))))
        assertTrue(search("frcsr", setOf("f")).hits.any { it.instruction.name == "frcsr" })
        assertTrue(search("frcsr", setOf("zfinx")).hits.any { it.instruction.name == "frcsr" })
        assertFalse(search("frcsr", setOf("zicsr")).hits.any { it.instruction.name == "frcsr" })
    }
    @Test fun `all floating precisions enforce repeated source registers and render two operands`() {
        for (suffix in listOf("h", "s", "d", "q")) for (op in listOf("fmv", "fneg", "fabs")) {
            val name = "$op.$suffix"
            val valid = hit(name, mapOf("rd" to 10L, "rs1" to 11L, "rs2" to 11L))
            val invalid = hit(name, mapOf("rd" to 10L, "rs1" to 11L, "rs2" to 12L))
            assertTrue(name, valid.encoding.matches(valid.word!!, 64))
            assertFalse(name, invalid.encoding.matches(invalid.word!!, 64))
            assertEquals("$name f10, f11", render(valid))
            assertFalse(search("0x${invalid.word.toString(16)}").hits.any { it.instruction.name == name })
        }
    }
    @Test fun `integer register fp aliases retain the zfinx variant operand namespace`() {
        val instruction = definition("fneg.s")
        val index = instruction.encodings.indexOfFirst { it.requires.any { group -> "Zfinx" in group } }
        assertTrue(index >= 0)
        assertEquals("fneg.s x10, x11", render(hit("fneg.s", mapOf("rd" to 10L, "rs1" to 11L, "rs2" to 11L), index)))
    }
    @Test fun `mask copy clear set and invert reject unrelated source registers`() {
        listOf("vmmv.m", "vmclr.m", "vmset.m", "vmnot.m", "vmcpy.m").forEach { name ->
            val good = hit(name, mapOf("vd" to 2L, "vs1" to 2L, "vs2" to 2L))
            val bad = hit(name, mapOf("vd" to 2L, "vs1" to 3L, "vs2" to 2L))
            assertTrue(name, good.encoding.matches(good.word!!, 64))
            assertFalse(name, bad.encoding.matches(bad.word!!, 64))
        }
        assertEquals("vmclr.m v2", render(hit("vmclr.m", mapOf("vd" to 2L, "vs1" to 2L, "vs2" to 2L))))
    }
    @Test fun `branch and vector compare aliases display swapped operands instead of base operand order`() {
        assertEquals("bgt x11, x10, .+8", render(hit("bgt", mapOf("rs1" to 10L, "rs2" to 11L, "imm" to 8L))))
        assertEquals("vmsgt.vv v2, v4, v3", render(hit("vmsgt.vv", mapOf("vd" to 2L, "vs2" to 3L, "vs1" to 4L))))
        assertEquals("vmfge.vv v2, v4, v3, v0.t", render(hit("vmfge.vv", mapOf("vd" to 2L, "vs2" to 3L, "vs1" to 4L, "vm" to 0L))))
    }
    @Test fun `compatibility names use the documented current operand forms`() {
        assertEquals("fmv.x.s x10, f11", render(hit("fmv.x.s", mapOf("rd" to 10L, "rs1" to 11L))))
        assertEquals("vle1.v v2, (x10)", render(hit("vle1.v", mapOf("vd" to 2L, "rs1" to 10L))))
        assertEquals("vl2r.v v2, (x10)", render(hit("vl2r.v", mapOf("vd" to 2L, "rs1" to 10L))))
        assertEquals("scall", render(hit("scall", emptyMap())))
        assertEquals("sbreak", render(hit("sbreak", emptyMap())))
    }
    @Test fun `immediate transformations remain symbolic references rather than incorrect encoded aliases`() {
        for (name in listOf("vmslt.vi", "vmsltu.vi", "vmsge.vi", "vmsgeu.vi")) {
            assertTrue(catalog.instructions.none { it.name == name })
            val pseudo = search(name).pseudoHits.single { it.instruction.name == name }.instruction
            assertEquals(1, pseudo.expansions.single().steps.size)
            assertTrue(pseudo.expansions.single().steps.single().assembly.contains("i-1"))
        }
    }
    @Test fun `example and historical names never pretend to have known current encodings`() {
        for (name in listOf("li", "la", "rev", "mftx.s", "mftx.d", "mxtf.s", "mxtf.d", "mffsr", "mtfsr")) {
            val pseudo = catalog.pseudos.single { it.name == name }
            assertTrue(pseudo.referenceOnly && pseudo.expansions.isEmpty())
            assertTrue(pseudo.notes.isNotBlank())
            assertTrue(catalog.instructions.none { it.name == name })
        }
        assertTrue(search("rev", setOf("zbkb")).pseudoHits.none { it.instruction.name == "rev" })
    }
    @Test fun `schema one catalogs without new alias features still load`() {
        val file = "manual-add.json"
        val text = asset("inst/$file")
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val root = buildJsonObject {
            put("schemaVersion", 1); put("version", "legacy")
            put("files", JsonArray(listOf(JsonPrimitive(file))))
            put("fileHashes", buildJsonObject { put(file, hash) })
        }
        assertEquals("add", InstructionCatalog.decodeFiles(root.toString()) { text }.instructions.single().name)
    }
    @Test fun `required alias features cannot be stripped or silently ignored`() {
        val root = Json.parseToJsonElement(asset("riscv-instructions.json")).jsonObject
        for (features in listOf(listOf("unknown-reader-feature"), listOf("pseudo-references-v1"))) {
            val manifest = JsonObject(root + ("requiredFeatures" to JsonArray(features.map { JsonPrimitive(it) }))).toString()
            assertThrows(IllegalArgumentException::class.java) { InstructionCatalog.decodeFiles(manifest) { asset("inst/$it") } }
        }
        val legacy = JsonObject(root + ("schemaVersion" to JsonPrimitive(1)) - "requiredFeatures").toString()
        assertThrows(IllegalArgumentException::class.java) { InstructionCatalog.decodeFiles(legacy) { asset("inst/$it") } }
    }
}
