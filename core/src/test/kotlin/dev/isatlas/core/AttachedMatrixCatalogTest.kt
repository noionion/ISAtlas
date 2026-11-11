package dev.isatlas.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AttachedMatrixCatalogTest {
    private fun catalogs(): Pair<LocalCatalogs, LocalCatalogs> {
        val loader = javaClass.classLoader
        assumeTrue(loader.getResource("packages/ame.zip") != null)
        val packages = OfficialPackages(loader.getResource("riscv-packages.json")!!.readText()) { loader.getResource(it)!!.readBytes() }
        val compiler = CompilerIsaOptions.decode(loader.getResource("march-extensions.json")!!.readText())
        return packages.catalogs(emptySet(), compiler) to packages.catalogs(setOf("ame"), compiler)
    }

    @Test fun optionalPackageAddsCsrAndInstructionsWithoutInventingStatusBits() {
        val (base, ame) = catalogs()
        assertEquals(base.instructions.instructions.size + 138, ame.instructions.instructions.size)
        assertEquals(base.csr.registers.size + 7, ame.csr.registers.size)
        assertEquals(11, ame.csr.registers.filter { it.namespace == "ame" }.sumOf { it.fields.size })
        assertEquals(133, ame.csr.registers.single { it.name == "amestype" }.fields.single().values.size)
        assertEquals(0xcc2, (ame.csr.registers.single { it.name == "amestype" }.locator as RegisterLocator.Csr).address)
        assertTrue(ame.csr.registers.single { it.name == "amestype" }.summary.contains("冲突"))
        for (name in listOf("mstatus", "sstatus", "vsstatus")) {
            val before = base.csr.registers.single { it.name == name }
            val after = ame.csr.registers.single { it.name == name }
            assertEquals(before.fields, after.fields)
            assertEquals(before.regions, after.regions)
            assertTrue(after.sources.any { it.namespace == "ame" })
            assertTrue(after.notes.contains("未分配"))
        }
        assertEquals(setOf("ztt"), ame.compiler.selectionClosure(setOf("ztt")))
        assertFalse(base.compiler.tokens.contains("ztt"))
    }

    @Test fun matrixAccumulatorAndDatatypeOperandsRenderInTheirOwnNamespaces() {
        val (_, ame) = catalogs()
        fun query(word: Long): InstructionHit = InstructionSearch.query(ame.instructions, "0x${word.toString(16)}",
            CatalogFilter(64, namespace = "ame", extensions = setOf("ztt")), ame.compiler).hits.single()
        val add = query(0x045201ab)
        assertEquals("madd.ew", add.instruction.name)
        assertEquals(mapOf("md" to "m3", "ms1" to "m4", "ms2" to "m5"), add.encoding.operands(0x045201ab))
        assertEquals("madd.ew m3, m4, m5", InstructionAssembly.render(add, false)?.text)
        assertEquals("agettyp a0, ad2", InstructionAssembly.render(query(0xa201052b))?.text)
        assertEquals("mmulacc.2d acc1, m2, m3", InstructionAssembly.render(query(0x943100ab), false)?.text)
        assertEquals("mls.1r m0, a1", InstructionAssembly.render(query(0xa365802b))?.text)
    }

    @Test fun staticRestrictionsApplyToConcretePartialAndExampleQueries() {
        val (_, ame) = catalogs()
        fun instruction(name: String) = ame.instructions.instructions.single { it.namespace == "ame" && it.name == name }
        val zip = instruction("mcolzip.ew").encodings.single()
        assertFalse(instruction("agettyp").encodings.single().matches(0xa208002b, 64))
        assertFalse(instruction("mmulacc.2d").encodings.single().matches(0x9400082b, 64))
        assertFalse(zip.matches(0xc410802b, 64))
        assertTrue(zip.matches(0xc420802b, 64))
        assertFalse(InstructionPattern(0xc410802b, 0xffffffff, 32).matches(zip, 64))
        assertTrue(InstructionPattern(0xc400002b, 0xfe007fff, 32).matches(zip, 64))
        for (i in ame.instructions.instructions.filter { it.namespace == "ame" }) {
            val encoding = i.encodings.single()
            for (xlen in encoding.xlens) {
                val example = InstructionExamples.create(i, encoding, xlen) ?: error("Missing example for ${i.name}")
                assertTrue(encoding.matches(example.word!!, xlen))
                assertFalse(example.code.contains(" x0, x0, x0"))
            }
        }
        assertEquals(listOf(64), instruction("mmove64.x.m").encodings.single().xlens)
        val rv32 = InstructionSearch.query(ame.instructions, "0xc000002b", CatalogFilter(32, namespace = "ame"), ame.compiler)
        assertTrue(rv32.hits.isEmpty())
    }

    @Test fun matrixRegisterFeatureMustBeDeclaredAndOptionalCatalogsStayLazy() {
        val (_, ame) = catalogs()
        val entry = ame.instructions.instructions.single { it.namespace == "ame" && it.name == "madd.ew" }
        val text = Json.encodeToString(InstructionDefinition.serializer(), entry)
        val hash = LocalPackages.sha256(text.toByteArray())
        val index = """{"schemaVersion":2,"version":"test","requiredFeatures":["alias-assembly-v1"],"files":["madd.json"],"fileHashes":{"madd.json":"$hash"}}"""
        assertThrows(IllegalArgumentException::class.java) { InstructionCatalog.decodeFiles(index) { text } }
        val loader = javaClass.classLoader
        val source = Json.parseToJsonElement(loader.getResource("riscv-packages.json")!!.readText()).jsonObject
        val entries = source.getValue("packages").jsonArray.filter { it.jsonObject.getValue("id").jsonPrimitive.content in setOf("manual", "ame") }
        val malformed = byteArrayOf(1, 2, 3)
        // Keep a valid ZIP/manifest but make the instruction file invalid: disabled packages must not be decoded.
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipInputStream(loader.getResource("packages/ame.zip")!!.openStream()).use { input ->
                java.util.zip.ZipOutputStream(out).use { output ->
                    while (true) {
                        val e = input.nextEntry ?: break
                        output.putNextEntry(java.util.zip.ZipEntry(e.name))
                        output.write(if (e.name == "riscv-instructions.json") malformed else input.readBytes())
                        output.closeEntry()
                    }
                }
            }
        }.toByteArray()
        val changed = entries.map { e -> if (e.jsonObject.getValue("id").jsonPrimitive.content == "ame")
            JsonObject(e.jsonObject + ("sha256" to JsonPrimitive(LocalPackages.sha256(bytes)))) else e }
        val packages = OfficialPackages(JsonObject(source + ("packages" to JsonArray(changed))).toString()) {
            if (it == "packages/ame.zip") bytes else loader.getResource(it)!!.readBytes()
        }
        val compiler = CompilerIsaOptions.decode(loader.getResource("march-extensions.json")!!.readText())
        assertEquals(1287, packages.catalogs(emptySet(), compiler).instructions.instructions.size)
        assertThrows(Exception::class.java) { packages.catalogs(setOf("ame"), compiler) }
    }
}
