package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class InstructionSearchTest {
    companion object {
        private fun asset(name: String) = InstructionSearchTest::class.java.classLoader!!.getResource(name)!!.readText()
        private val catalog = InstructionCatalog.decodeFiles(asset("riscv-instructions.json")) { asset("inst/$it") }
        private val compiler = CompilerIsaOptions.decode(asset("march-extensions.json"))
    }
    private fun search(query: String, width: Int = 64, privilege: String? = null, extensions: Set<String> = emptySet(), namespace: String? = null) =
        InstructionSearch.query(catalog, query, CatalogFilter(width, privilege, namespace, extensions), compiler)

    @Test fun `all installed instruction files load with their hashes`() {
        assertEquals(1287, catalog.instructions.size)
        assertEquals(1410, catalog.instructions.sumOf { it.encodings.size })
        assertTrue(catalog.instructions.all { it.summary.isNotBlank() && it.summaryEn.isNotBlank() })
    }
    @Test fun `hex binary and spaced binary decode the same operands`() {
        val words = listOf("0x00b50533", "00b50533", "0b00000000101101010000010100110011", "0000000 01011 01010 000 01010 0110011")
        words.forEach { query ->
            val hit = search(query).hits.single { it.instruction.name == "add" }
            assertEquals(mapOf("rd" to "x10", "rs1" to "x10", "rs2" to "x11"), hit.encoding.operands(hit.word!!))
        }
    }
    @Test fun `immediates reconstruct scattered bits and sign extend`() {
        val addi = search("0xfff00093").hits.single { it.instruction.name == "addi" }
        assertEquals("-1", addi.encoding.operands(addi.word!!)["imm"])
        val jump = search("0xbffd").hits.single { it.instruction.name == "c.j" }
        assertEquals("-2", jump.encoding.operands(jump.word!!)["imm"])
    }
    @Test fun `static reserved encodings and xlen restrictions are honored`() {
        assertFalse(search("0x02001013", 32).hits.any { it.instruction.name == "slli" })
        assertTrue(search("0x02001013", 64).hits.any { it.instruction.name == "slli" })
        assertTrue(search("0x0000").hits.isEmpty())
    }
    @Test fun `aliases and hints remain separate candidates`() {
        assertTrue(search("0x00008067").hits.map { it.instruction.name }.containsAll(listOf("ret", "jalr")))
        assertTrue(search("0x0001").hits.any { it.instruction.name == "c.nop" })
    }
    @Test fun `privilege normalization and ordering match csr filters`() {
        val results = search("").hits
        assertEquals(results.map { privilegeRank(it.encoding.privilege) }.sorted(), results.map { privilegeRank(it.encoding.privilege) })
        assertTrue(search("mret", privilege = "U").hits.isEmpty())
        assertTrue(search("mret", privilege = "M").hits.any { it.instruction.name == "mret" })
        assertTrue(search("hfence", privilege = "H").hits.isNotEmpty())
    }
    @Test fun `shared filters use source and extension union without inventing source data`() {
        assertTrue(search("add", namespace = "thead").hits.isEmpty())
        assertTrue(search("vadd", extensions = setOf("v")).hits.isNotEmpty())
        assertTrue(search("mul", extensions = setOf("m")).hits.any { it.instruction.name == "mul" })
        assertTrue(search("mret", extensions = setOf("zba")).hits.isEmpty())
        val union = search("", extensions = setOf("zba", "zbb")).hits.map { it.key }.toSet()
        assertEquals(search("", extensions = setOf("zba")).hits.map { it.key }.toSet() + search("", extensions = setOf("zbb")).hits.map { it.key }.toSet(), union)
    }
    @Test fun `bad numeric input is explained and never silently truncated`() {
        listOf("0xzz", "0x100000000", "0b102").forEach { assertNotNull(search(it).error) }
    }
    @Test fun `compressed shadow stack needs both extensions while mop remains searchable`() {
        listOf("6081", "0x6081", "0b0110000010000001").forEach { input ->
            val mop = search(input, extensions = setOf("zcmop")).hits.map { it.instruction.name }
            assertTrue(input, "c.mop.1" in mop)
            assertFalse(input, "c.sspush" in mop)
            val shadow = search(input, extensions = setOf("zcmop", "zicfiss")).hits.map { it.instruction.name }
            assertTrue(input, "c.sspush" in shadow)
        }
        assertTrue(search("c.sspush", extensions = setOf("zcmop")).hits.isEmpty())
        assertEquals(setOf(InstructionFilterDimension.EXTENSION), search("c.sspush", extensions = setOf("zcmop")).diagnostic!!.clear)
        assertTrue(search("6081").hits.any { it.instruction.name == "c.sspush" })
    }
    @Test fun `alternative extension groups are not incorrectly combined into one requirement`() {
        assertTrue(search("mul", extensions = setOf("zmmul")).hits.any { it.instruction.name == "mul" })
        assertTrue(search("mul", extensions = setOf("m")).hits.any { it.instruction.name == "mul" })
        assertTrue(search("fadd.d", extensions = setOf("d")).hits.isNotEmpty())
        assertTrue(search("fadd.d", extensions = setOf("zdinx")).hits.isNotEmpty())
        assertTrue(search("fadd.d", extensions = setOf("f")).hits.isEmpty())
    }
    @Test fun `ret matches mnemonic fragments without matching return in amo prose`() {
        val hits = search("ret").hits
        assertTrue(hits.map { it.instruction.name }.containsAll(listOf("ret", "mret", "sret")))
        assertFalse(hits.any { it.instruction.name.startsWith("amo") })
        assertTrue(search("return").hits.any { it.instruction.name.startsWith("amo") && it.category == InstructionMatchCategory.DESCRIPTION })
        assertTrue(search("原子").hits.any { it.instruction.name.startsWith("amo") })
    }
    @Test fun `name matches precede description matches while each group keeps privilege ordering`() {
        val hits = search("and").hits
        assertTrue(hits.any { it.category == InstructionMatchCategory.NAME })
        assertTrue(hits.any { it.category == InstructionMatchCategory.DESCRIPTION })
        assertEquals(hits.map { it.category.ordinal }.sorted(), hits.map { it.category.ordinal })
        hits.groupBy { it.category }.values.forEach { group ->
            assertEquals(group.map { privilegeRank(it.encoding.privilege) }.sorted(), group.map { privilegeRank(it.encoding.privilege) })
        }
    }
    @Test fun `tampered instruction data fails loading`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstructionCatalog.decodeFiles(asset("riscv-instructions.json")) { asset("inst/$it") + " " }
        }
    }
}
