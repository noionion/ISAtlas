package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class ImportedCatalogTest {
    private val pack = installedCatalog()
    private fun standard(name: String) = pack.registers.single { it.namespace == "standard" && it.name == name }

    @Test fun `pinned import has explicit coverage and provenance`() {
        assertEquals(552, pack.registers.size)
        assertEquals(2027, pack.registers.sumOf { it.fields.size })
        assertEquals(460, pack.registers.count { it.namespace == "standard" })
        assertEquals(91, pack.registers.count { it.namespace == "andes" })
        assertTrue(pack.registers.all { it.sourceRevision.matches(Regex("[0-9a-f]{40}")) && it.license.isNotBlank() })
        assertTrue(pack.registers.all { it.sourceRevision in it.sourceUrl })
    }

    @Test fun `addresses and width constraints include high halves correctly`() {
        assertEquals(0x644, (standard("hip").locator as RegisterLocator.Csr).address)
        assertEquals(0x608, (standard("hvien").locator as RegisterLocator.Csr).address)
        assertEquals(listOf(32), standard("cycleh").xlens)
        assertEquals(listOf(32), standard("mstatush").xlens)
        assertTrue(Search.query(pack, "cycleh", 64).isEmpty())
        assertEquals(31, standard("mcycle").fields.single().layout(32)!!.msb)
        assertEquals(63, standard("mcycle").fields.single().layout(64)!!.msb)
        assertTrue(standard("mstatus").fields.first { it.name == "MDT" }.layout(32) == null)
    }

    @Test fun `extension field conditions and canonical aliases remain searchable`() {
        assertTrue(Search.query(pack, "Smstateen", 64).any { it.register.name == "mstateen0" })
        assertTrue(Search.query(pack, "mstatus.MPV", 64).any { it.field?.condition?.contains("H") == true })
        assertEquals("CODE", Search.query(pack, "mcause.ExceptionCode", 64).single().field!!.name)
        assertEquals("SCRATCH", Search.query(pack, "sscratch.Scratch", 64).first().field!!.name)
        assertTrue(standard("mcause").fields.all { "WLRL" in it.access })
        val status = standard("mstatus")
        assertTrue(status.regions.any { it.label == "WPRI" && it.layout(64)?.let { bits -> 48 in bits.lsb..bits.msb } == true })
        assertTrue(status.fields.first { it.name == "MDT" }.layout(64)!!.msb == 42)
    }

    @Test fun `vendor filtering separates same address definitions`() {
        val andes = Search.query(pack, "mcache_ctl", 32, namespace = "andes")
        assertTrue(andes.any { it.register.namespace == "andes" && it.register.fields.isNotEmpty() })
        assertTrue(Search.query(pack, "mcache_ctl", 64, namespace = "andes").isEmpty())
        assertTrue(Search.query(pack, "th.sxstatus", 64, namespace = "thead").single().register.fields.any { it.name == "THEADISAEE" })
        assertTrue(Search.query(pack, "0x7c0", 32, namespace = "andes").all { it.register.namespace == "andes" })
    }

    @Test fun `unknown address-only definitions do not acquire invented fields`() {
        assertEquals(45, pack.registers.count { it.namespace == "standard" && it.coverage == "address-only" })
        assertTrue(pack.registers.filter { it.coverage == "address-only" }.all { it.fields.isEmpty() && it.regions.isEmpty() })
        val thead = pack.registers.single { it.namespace == "thead" }
        assertTrue(registerDiagramLanes(thead.fields, 64, 32, thead.regions).last().segments.any { it.field == null && it.region == null })
        val broken = standard("stopei").copy(coverage = "address-only", fields = standard("mcycle").fields)
        assertThrows(IllegalArgumentException::class.java) { Catalog.validate(pack.copy(registers = listOf(broken))) }
    }
}
