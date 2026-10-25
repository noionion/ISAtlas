package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class InstructionPatternTest {
    companion object {
        private fun asset(name: String) = InstructionPatternTest::class.java.classLoader!!.getResource(name)!!.readText()
        private val catalog = InstructionCatalog.decodeFiles(asset("riscv-instructions.json")) { asset("inst/$it") }
        private val compiler = CompilerIsaOptions.decode(asset("march-extensions.json"))
    }
    private fun search(input: String, filter: CatalogFilter = CatalogFilter(64)) = InstructionSearch.query(catalog, input, filter, compiler)
    private fun pattern(input: String) = InstructionInput.parse(input).pattern!!
    private fun encoding(name: String) = catalog.instructions.single { it.name == name }.encodings.first()

    @Test fun `hex binary grouped and unprefixed patterns preserve the same known bits`() {
        val hex = pattern("0x00?50533")
        val binary = "00000000????01010000010100110011"
        for (input in listOf("00?50533", "0X00?5_0533", "0b$binary", binary, "0b00000000 ???? 01010 000 01010 0110011")) {
            val parsed = pattern(input)
            assertEquals(input, hex.value, parsed.value)
            assertEquals(input, hex.knownMask, parsed.knownMask)
            assertEquals(input, hex.length, parsed.length)
            assertEquals(search("0x00?50533").hits.map { it.key }, search(input).hits.map { it.key })
        }
        assertEquals(28, hex.knownBits)
        assertEquals("0x00?50533", hex.query)
        assertEquals("二进制", pattern(binary).notation)
        assertTrue(pattern(binary).query.startsWith("0b"))
        assertEquals("十六进制", pattern("000?001?").notation)
        assertEquals(32, pattern("000?001?").length)
        assertTrue(InstructionInput.parse("000?0011").error!!.contains("32 位"))
        val bit = pattern("0b00000000101?01010000010100110011")
        assertTrue(bit.query.startsWith("0b"))
        assertEquals(bit, pattern(bit.query))
    }

    @Test fun `invalid wildcard alphabets lengths and parcel conflicts are input diagnostics`() {
        for (input in listOf("0x00g?0533", "0b00000000000000?2", "0x?13", "0b????", "0x?????????", "0x000000?1", "0x00?f", "0x00000?1f")) {
            assertNotNull(input, InstructionInput.parse(input).error)
            assertEquals(input, InstructionDiagnosticKind.INPUT, search(input).diagnostic?.kind)
        }
        assertNull(InstructionInput.parse("add").pattern)
        assertEquals(0x13L, InstructionInput.parse("0x13").word)
    }

    @Test fun `partial queries find candidates without inventing a word or an assembly`() {
        val result = search("0x00?50533")
        assertTrue(result.hits.any { it.instruction.name == "add" })
        assertTrue(result.pseudoHits.isEmpty())
        assertTrue(result.hits.all { it.word == null && it.pattern != null && it.category == InstructionMatchCategory.ENCODING })
        val add = result.hits.single { it.instruction.name == "add" }
        assertNull(InstructionAssembly.render(add))
        assertEquals(mapOf("rd" to "x10", "rs1" to "x10", "rs2" to null), add.pattern!!.operands(add.encoding))
    }

    @Test fun `matching does not substitute zero for unknown bits`() {
        // C.ADDI4SPN reserves a zero immediate, but this pattern has valid nonzero completions.
        val p = pattern("0x00?0")
        val e = encoding("c.addi4spn")
        assertFalse(e.matches(p.value, 64))
        assertTrue(p.matches(e, 64))
        assertTrue(search("0x00?0").hits.any { it.instruction.name == "c.addi4spn" })
    }

    @Test fun `operand equality admits unknown registers but rejects incompatible known bits`() {
        val good = pattern("0x20b5?553") // both source registers can be f11
        val bad = pattern("0x20c5?553") // rs2=f12 conflicts with known high bits of rs1
        assertTrue(good.matches(encoding("fneg.s"), 64))
        assertFalse(bad.matches(encoding("fneg.s"), 64))
        assertTrue(search(good.query).hits.any { it.instruction.name == "fneg.s" })
        assertFalse(search(bad.query).hits.any { it.instruction.name == "fneg.s" })
    }

    @Test fun `compressed length and signed immediate pieces remain distinct from full words`() {
        val compressed = search("0x60?1")
        assertTrue(compressed.hits.isNotEmpty())
        assertTrue(compressed.hits.all { it.encoding.length == 16 })
        val p = pattern("0xff?00093")
        assertNull(p.operands(encoding("addi"))["imm"])
        assertEquals("x1", p.operands(encoding("addi"))["rd"])
    }

    @Test fun `filters and diagnostics retain full requirement groups and xlen constraints`() {
        val zcmop = search("0x60?1", CatalogFilter(64, extensions = setOf("zcmop")))
        assertFalse(zcmop.hits.any { it.instruction.name == "c.sspush" })
        assertTrue(search("0x60?1", CatalogFilter(64, extensions = setOf("zcmop", "zicfiss"))).hits.any { it.instruction.name == "c.sspush" })
        val denied = search("0x00?50533", CatalogFilter(64, namespace = "missing-source"))
        assertEquals(InstructionDiagnosticKind.FILTERED, denied.diagnostic?.kind)
        assertEquals(setOf(InstructionFilterDimension.SOURCE), denied.diagnostic?.clear)
        assertFalse(search("0x020010?3", CatalogFilter(32)).hits.any { it.instruction.name == "slli" })
        assertTrue(search("0x020010?3", CatalogFilter(64)).hits.any { it.instruction.name == "slli" })
    }

    @Test fun `too broad queries ask for known bits or filters instead of flooding results`() {
        assertEquals(InstructionDiagnosticKind.TOO_BROAD, search("0x????????").diagnostic?.kind)
        val vectorOpcode = "0b?????????????????????????1010111"
        val broad = search(vectorOpcode)
        assertEquals(InstructionDiagnosticKind.TOO_BROAD, broad.diagnostic?.kind)
        assertTrue(broad.hits.isEmpty())
        assertNotEquals(InstructionDiagnosticKind.TOO_BROAD, search(vectorOpcode, CatalogFilter(64, extensions = setOf("zvkned"))).diagnostic?.kind)
    }

    @Test fun `all fully known patterns agree with the existing matcher across every variant`() {
        catalog.instructions.forEach { instruction -> instruction.encodings.forEach { e ->
            val mask = (1L shl e.length) - 1
            for (word in listOf(e.match.drop(2).toLong(16), mask, 0L)) for (xlen in e.xlens) {
                assertEquals("${instruction.name} RV$xlen $word", e.matches(word, xlen), InstructionPattern(word, mask, e.length).matches(e, xlen))
            }
        } }
    }

    @Test fun `partial matcher agrees with exhaustive completions for every variant`() {
        // Vary bits spanning operands, fixed opcode, constraint masks and alias equalities.
        catalog.instructions.forEach { instruction -> instruction.encodings.forEach { e ->
            val all = (1L shl e.length) - 1
            val seed = e.match.drop(2).toLong(16)
            val starts = if (e.length == 16) listOf(0, 7, 9, 12) else listOf(0, 7, 15, 20)
            for (start in starts) {
                val unknown = 15L shl start
                val p = InstructionPattern(seed, all and unknown.inv(), e.length)
                for (xlen in e.xlens) {
                    val expected = (0L..15L).any { raw -> e.matches((seed and unknown.inv()) or (raw shl start), xlen) }
                    assertEquals("${instruction.name} RV$xlen unknown@$start", expected, p.matches(e, xlen))
                }
            }
        } }
    }

    @Test fun `overlapping restrictions need one common completion`() {
        val base = encoding("add").copy(encodingConstraints = listOf(
            EncodingConstraint("0x00000380", "in", listOf(0, 128)),
            EncodingConstraint("0x00000380", "notIn", listOf(0, 128))))
        assertFalse(InstructionPattern(0, 0, 32).matches(base, 64))
        val mapped = base.copy(encodingConstraints = emptyList(), operandRelations = listOf(OperandRelation("rs1", "rs2", "eq")),
            fields = base.fields.map { if (it.operand == "rs2") it.copy(registerOffset = 1) else it })
        val valid = (1L shl 15) // x1 == encoded rs2=0 + offset 1
        assertTrue(InstructionPattern(valid, 0x01ff8000, 32).matches(mapped, 64))
        assertFalse(InstructionPattern(0, 0x01ff8000, 32).matches(mapped, 64))
    }
}
