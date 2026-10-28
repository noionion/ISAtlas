package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class AiaCatalogTest {
    private val pack = reviewedCatalog()
    private fun reg(name: String) = pack.registers.single { it.name == name }

    @Test fun `AIA entries load without duplicating existing register identities`() {
        assertEquals(26, pack.registers.count { it.namespace == "aia" })
        assertEquals("riscv/manual/csr/mip", reg("mip").id)
        assertEquals(0xFB0, (reg("mtopi").locator as RegisterLocator.Csr).address)
        assertEquals(0x35C, (reg("mtopei").locator as RegisterLocator.Csr).address)
        assertEquals("RO", reg("mtopi").access)
        assertEquals("RW", reg("mtopei").access)
    }

    @Test fun `AIA source filter includes both new CSRs and manual supplements`() {
        assertTrue(Search.query(pack, "mip", 64, namespace = "aia").any { it.register.id == "riscv/manual/csr/mip" })
        assertTrue(Search.query(pack, "hvictl", 64, namespace = "aia").any { it.register.name == "hvictl" })
        assertTrue(Search.query(pack, "fflags", 64, namespace = "aia").isEmpty())
        assertEquals("mip", InstructionCsr.resolve(pack, 0x344, 64, "aia").single().name)
        assertEquals("mtopei", InstructionCsr.resolve(pack, 0x35C, 64, "aia").single().name)
    }

    @Test fun `RV32 high halves preserve shifted RAS and priority fields`() {
        assertTrue(Search.query(pack, "mieh", 64).isEmpty())
        assertTrue(Search.query(pack, "mieh", 32).any { it.register.name == "mieh" })
        assertEquals(BitLayout(32, 3, 3), reg("mieh").fields.single { it.name == "RASLIE" }.layout(32))
        assertEquals(BitLayout(32, 15, 8), reg("hviprio1h").fields.single { it.name == "IPRIO13" }.layout(32))
    }

    @Test fun `AIA dependency tags and indirect selector names are searchable`() {
        assertTrue(Search.query(pack, "mtopei", 64, extensions = setOf("smaia")).isNotEmpty())
        assertTrue(Search.query(pack, "mtopei", 64, extensions = setOf("ssaia")).isEmpty())
        assertTrue(Search.query(pack, "stopei", 64, extensions = setOf("ssaia")).isNotEmpty())
        assertTrue(Search.query(pack, "eithreshold", 64).any { it.register.name in setOf("miselect", "mireg", "siselect", "sireg", "vsiselect", "vsireg") })
        assertFalse(pack.registers.any { it.name in setOf("eidelivery", "eithreshold", "eip0", "domaincfg") })
    }

    @Test fun `data sources expose pinned supplemental AIA provenance and license once`() {
        val sources = Catalog.sourceAttributions(pack)
        assertEquals(setOf("aia", "debug", "manual", "spmp"), sources.map { it.namespace }.toSet())
        val aia = sources.single { it.namespace == "aia" }
        assertEquals("20318d1b03f0ac8253458b82f8244d0d0db074e9", aia.revision)
        assertEquals("CC-BY-4.0", aia.license)
        assertTrue(aia.url.contains(aia.revision))
        assertTrue(aia.title.contains("Advanced Interrupt"))
    }
}
