package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class InstructionPresentationTest {
    companion object {
        private fun asset(name: String) = InstructionPresentationTest::class.java.classLoader!!.getResource(name)!!.readText()
        private val catalog = InstructionCatalog.decodeFiles(asset("riscv-instructions.json")) { asset("inst/$it") }
        private val compiler = CompilerIsaOptions.decode(asset("march-extensions.json"))
        private val csrs = installedCatalog()
    }
    private fun search(query: String, filter: CatalogFilter = CatalogFilter(64)) = InstructionSearch.query(catalog, query, filter, compiler)
    private fun hit(name: String, word: Long) = search("0x${word.toString(16)}").hits.first { it.instruction.name == name }
    private fun render(name: String, word: Long, abi: Boolean = true, csr: String? = null) = InstructionAssembly.render(hit(name, word), abi, csr)!!.text

    @Test fun `assembly has correct destination source order and naming`() {
        assertEquals("add a0, a0, a1", render("add", 0x00b50533))
        assertEquals("add x10, x10, x11", render("add", 0x00b50533, false))
        assertEquals("addi ra, zero, -1", render("addi", 0xfff00093))
        assertEquals("lw a0, 8(sp)", render("lw", 0x00812503))
        assertEquals("sw a0, 8(sp)", render("sw", 0x00a12423))
        assertEquals("jalr zero, 0(ra)", render("jalr", 0x00008067))
        assertEquals("ret", render("ret", 0x00008067))
    }
    @Test fun `csr assembly preserves address when not uniquely resolved`() {
        assertEquals("csrrw ra, mstatus, sp", render("csrrw", 0x300110f3, csr = "mstatus"))
        assertEquals("csrrw x1, 0x300, x2", render("csrrw", 0x300110f3, false))
        val instruction = hit("csrrw", 0x300110f3)
        assertEquals(0x300, InstructionCsr.address(instruction.encoding, instruction.word!!))
        assertEquals("mstatus", InstructionCsr.resolve(csrs, 0x300, 64, "manual").single().name)
        assertTrue(InstructionCsr.resolve(csrs, 0x300, 64, "thead").isEmpty())
        assertTrue(InstructionCsr.resolve(csrs, 0xfff, 64).isEmpty())
        val original = InstructionCsr.resolve(csrs, 0x300, 64).single()
        val merged = csrs.copy(registers = csrs.registers + original.copy(id = "vendor.mstatus", namespace = "vendor"))
        assertEquals(2, InstructionCsr.resolve(merged, 0x300, 64).size)
        assertEquals(1, InstructionCsr.resolve(merged, 0x300, 64, "manual").size)
    }
    @Test fun `compressed implicit registers and pc relative offsets are explicit`() {
        assertEquals("c.addi16sp sp, 16", render("c.addi16sp", 0x6141))
        assertEquals("c.j .-2", render("c.j", 0xbffd))
        assertEquals("jal zero, .+0", render("jal", 0x6f))
        assertEquals("c.sspush ra", render("c.sspush", 0x6081))
        assertEquals("c.sspopchk t0", render("c.sspopchk", 0x6281))
        assertEquals("lui ra, 0x1", render("lui", 0x10b7))
    }
    @Test fun `atomic flags floating registers and rounding are displayed`() {
        assertEquals("amoadd.w.aqrl ra, a1, (a0)", render("amoadd.w", 0x06b520af))
        assertEquals("fadd.s fa0, fa1, fa2, rne", render("fadd.s", 0x00c58553))
        assertEquals("fadd.s f10, f11, f12, rne", render("fadd.s", 0x00c58553, false))
    }
    @Test fun `vector source order masks and memory operands follow syntax`() {
        assertEquals("vadd.vv v1, v2, v3", render("vadd.vv", 0x022180d7))
        assertEquals("vadd.vv v1, v2, v3, v0.t", render("vadd.vv", 0x002180d7))
        assertEquals("vmacc.vv v1, v3, v2", render("vmacc.vv", 0xb621a0d7))
        assertEquals("vle8.v v1, (a0)", render("vle8.v", 0x02050087))
        assertEquals("vsetvl ra, sp, gp", render("vsetvl", 0x803170d7))
    }
    @Test fun `floating immediates and zcmp stack adjustments depend on format and xlen`() {
        assertEquals("fli.s ft0, min", render("fli.s", 0xf0108053))
        assertEquals("fli.h ft0, inf", render("fli.h", 0xf41e8053))
        val push = hit("cm.push", 0xb8f2)
        assertEquals("cm.push {ra, s0-s11}, -112", InstructionAssembly.render(push, xlen = 64)!!.text)
        assertEquals("cm.push {ra, s0-s11}, -64", InstructionAssembly.render(push, xlen = 32)!!.text)
    }
    @Test fun `every catalog variant can be presented without guessing unsupported syntax`() {
        var complete = 0
        var fallback = 0
        catalog.instructions.forEach { instruction -> instruction.encodings.forEachIndexed { index, encoding ->
            val word = encoding.match.drop(2).toLong(16)
            val result = InstructionAssembly.render(InstructionHit(instruction, encoding, index, word))!!
            assertTrue(instruction.name, result.text.isNotBlank())
            if (result.complete) complete++ else fallback++
        } }
        assertTrue(complete > 1200)
        assertTrue(fallback > 0)
        assertEquals("fli.s ft0, -1.0", render("fli.s", 0xf0100053))
    }
    @Test fun `numeric format errors explain missing or invalid input`() {
        listOf("0x", "0x+1", "0b102", "123", "0010009g", "0x100000000", "0x12340001", "0x1f").forEach { query ->
            assertEquals(query, InstructionDiagnosticKind.INPUT, search(query).diagnostic?.kind)
        }
        assertEquals(0x73L, InstructionInput.parse("0x73").word)
        assertEquals(1L, InstructionInput.parse("0x00000001").word)
        assertNull(InstructionInput.parse("ret").word)
    }
    @Test fun `empty results distinguish the filter dimension to relax`() {
        val permission = search("mret", CatalogFilter(64, privilege = "U")).diagnostic!!
        assertEquals(InstructionDiagnosticKind.FILTERED, permission.kind)
        assertEquals(setOf(InstructionFilterDimension.PRIVILEGE), permission.clear)
        val width = search("addw", CatalogFilter(32)).diagnostic!!
        assertEquals(setOf(InstructionFilterDimension.WIDTH), width.clear)
        assertEquals(64, width.suggestedXlen)
        assertEquals(setOf(InstructionFilterDimension.SOURCE), search("mret", CatalogFilter(64, namespace = "thead")).diagnostic!!.clear)
        assertEquals(setOf(InstructionFilterDimension.EXTENSION), search("mret", CatalogFilter(64, extensions = setOf("zba"))).diagnostic!!.clear)
        assertEquals(InstructionDiagnosticKind.FILTERED, search("0x02001013", CatalogFilter(32)).diagnostic!!.kind)
    }
    @Test fun `known static rejection is different from unknown instruction`() {
        assertEquals(InstructionDiagnosticKind.RESERVED, search("0x0000").diagnostic!!.kind)
        assertEquals(InstructionDiagnosticKind.UNKNOWN, search("zz_unknown_instruction").diagnostic!!.kind)
        assertEquals(InstructionDiagnosticKind.UNKNOWN, search("0x0000000b").diagnostic!!.kind)
    }
}
