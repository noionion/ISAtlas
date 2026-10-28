package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class SpmpCatalogTest {
    private val pack = reviewedCatalog()
    private fun reg(name: String) = pack.registers.single { it.name == name }

    @Test fun `numbered CSRs and manual windows keep separate identities`() {
        assertEquals(setOf("mpmpdeleg", "spmpen", "spmpenh"), pack.registers.filter { it.namespace == "spmp" }.map { it.name }.toSet())
        assertEquals(0x316, (reg("mpmpdeleg").locator as RegisterLocator.Csr).address)
        assertEquals("riscv/manual/csr/sireg2", reg("sireg2").id)
        assertEquals(listOf(32), reg("spmpenh").xlens)
        assertTrue(pack.registers.none { it.name in setOf("spmpaddr0", "spmpcfg0", "hspmpdeleg", "vspmpen") })
    }

    @Test fun `source filter resolves both existing PMP and indirect SPMP fields`() {
        assertTrue(Search.query(pack, "pmpaddr0", 64, namespace = "spmp").any { it.register.id == "riscv/manual/csr/pmpaddr0" })
        val hit = Search.query(pack, "sireg2.spmpcfg.SHARED", 64, namespace = "spmp").single()
        assertEquals("spmpcfg", hit.field!!.view)
        assertEquals("SHARED", hit.field.displayName)
        assertEquals(BitLayout(64, 9, 9), hit.field.layout(64))
        assertTrue(Search.query(pack, "spmpen", 64, privilege = "S", namespace = "spmp").any { it.register.name == "spmpen" })
        assertTrue(Search.query(pack, "spmpenh", 64).isEmpty())
        assertEquals("mpmpdeleg", InstructionCsr.resolve(pack, 0x316, 64, "spmp").single().name)
    }

    @Test fun `exclusive windows and RV32 delegation layout validate without overlap`() {
        Catalog.validate(pack)
        val boundary = reg("mpmpdeleg").fields.single()
        assertEquals(BitLayout(32, 6, 2), boundary.layout(32))
        assertEquals(BitLayout(64, 6, 0), boundary.layout(64))
        assertEquals(setOf("", "spmpcfg"), reg("sireg2").fields.map { it.view }.toSet())
        assertTrue(reg("sireg2").fields.single { it.name == "spmpcfg.R" }.typeRule.contains("U 仅执行"))
        assertTrue(reg("mireg").fields.filter { it.view == "spmpaddr" }.any { it.layout(64) == BitLayout(64, 53, 0) })
    }

    @Test fun `SPMP attribution is pinned and dependencies preserve compiler vocabulary`() {
        val source = Catalog.sourceAttributions(pack).single { it.namespace == "spmp" }
        assertEquals("4ce228207193c55247200110e3d5b6881a17c367", source.revision)
        assertEquals("CC-BY-4.0", source.license)
        assertTrue(source.title.contains("1.0"))
        assertTrue(reg("sireg2").extensions.contains("Sscsrind"))
        assertTrue(reg("mireg2").extensions.contains("Smcsrind"))
    }
}
