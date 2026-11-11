package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PackedDraftCatalogTest {
    @Test fun optionalDraftSupportsSearchAndDoesNotChangeManual() {
        val loader = javaClass.classLoader
        assumeTrue(loader.getResource("packages/p.zip") != null)
        val packages = OfficialPackages(loader.getResource("riscv-packages.json")!!.readText()) {
            loader.getResource(it)!!.readBytes()
        }
        val compiler = CompilerIsaOptions.decode(loader.getResource("march-extensions.json")!!.readText())
        val base = packages.catalogs(emptySet(), compiler)
        val packed = packages.catalogs(setOf("p"), compiler)
        assertEquals(base.csr.registers.size, packed.csr.registers.size)
        assertEquals(base.instructions.instructions.size + 605, packed.instructions.instructions.size)
        assertEquals(base.instructions.pseudos.size + 84, packed.instructions.pseudos.size)
        assertTrue(packed.csr.registers.single { it.name == "vxsat" }.sources.any { it.namespace == "p" })
        assertFalse("p" in base.compiler.tokens)
        val caps = packed.compiler.selectionClosure(setOf("p"))
        assertTrue(caps.containsAll(listOf("i", "p", "zmmul", "zba", "zbb")))
        val filter = CatalogFilter(64, namespace = "p", extensions = caps)
        val hit = InstructionSearch.query(packed.instructions, "0x84c5853b", filter, packed.compiler).hits.single()
        assertEquals("padd.b", hit.instruction.name)
        assertEquals(mapOf("rd" to "x10", "rs1" to "x11", "rs2" to "x12"), hit.encoding.operands(0x84c5853b))
        assertTrue(InstructionSearch.query(packed.instructions, "0x8200003b", filter.copy(xlen = 32), packed.compiler).hits.isEmpty())
        assertEquals("padd.w", InstructionSearch.query(packed.instructions, "0x8200003b", filter, packed.compiler).hits.single().instruction.name)
        fun operands(name: String, xlen: Int, word: Long) = packed.instructions.instructions.single { it.namespace == "p" && it.name == name }
            .encodings.single { xlen in it.xlens }.operands(word)
        assertEquals("x10", operands("padd.db", 32, 0x8400651b)["rd_p"])
        assertEquals("1", operands("sati", 32, 0xe200401b)["width"])
        assertEquals("-512", operands("pli.h", 64, 0xb000a01b)["imm"])
        assertTrue(packed.instructions.pseudos.any { it.namespace == "p" && it.name == "pneg.b" })
    }
}
