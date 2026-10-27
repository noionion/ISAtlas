package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class DebugCatalogTest {
    private val pack = installedCatalog()
    private fun reg(name: String) = pack.registers.single { it.name == name }

    @Test fun `debug mode CSRs and supplemental provenance load correctly`() {
        assertEquals(434, pack.registers.size)
        assertEquals("D", reg("dcsr").privilege)
        assertEquals("fields", reg("dcsr").coverage)
        assertEquals("riscv/manual/csr/dcsr", reg("dcsr").id)
        val source = Catalog.sourceAttributions(pack).single { it.namespace == "debug" }
        assertEquals("5695c0aaa7a3c228e14702e1268eb5a5e50e3cb8", source.revision)
        assertEquals("CC-BY-4.0", source.license)
    }

    @Test fun `trigger format fields remain searchable and identify their view`() {
        val hit = Search.query(pack, "tdata1.mcontrol6.ACTION", 64).single { it.field?.name == "mcontrol6.ACTION" }
        assertEquals("mcontrol6", hit.field!!.view)
        assertEquals("ACTION", hit.field!!.displayName)
        assertEquals("tdata1", hit.register.name)
        assertEquals(0x7a1, (hit.register.locator as RegisterLocator.Csr).address)
        assertTrue(Search.query(pack, "dcsr.EBREAKM", 64, privilege = "D", namespace = "debug").any { it.field?.name == "EBREAKM" })
        assertEquals("dcsr", InstructionCsr.resolve(pack, 0x7b0, 64, "debug").single().name)
    }

    @Test fun `overlapping format views validate but overlap within one view is rejected`() {
        val trigger = reg("tdata1")
        Catalog.validate(pack.copy(registers = listOf(trigger)))
        val wrong = trigger.copy(fields = trigger.fields + trigger.fields.first().copy(name = "DUPLICATE"))
        assertThrows(IllegalArgumentException::class.java) { Catalog.validate(pack.copy(registers = listOf(wrong))) }
        for (view in listOf("", "mcontrol", "mcontrol6", "icount", "itrigger", "etrigger", "tmexttrigger")) {
            val fields = trigger.fields.filter { it.view == view }
            val regions = trigger.regions.filter { it.view == view }
            for (width in listOf(32, 64)) {
                val lanes = registerDiagramLanes(fields, width, 16, regions)
                assertEquals(width, lanes.sumOf { lane -> lane.segments.sumOf { it.width } })
                assertTrue(view, lanes.flatMap { it.segments }.all { it.field != null || it.region != null })
            }
        }
    }

    @Test fun `trace and context definitions do not invent extra CSR addresses`() {
        assertFalse(pack.registers.any { it.name in setOf("mcontrol6", "icount", "dmcontrol", "dmstatus") })
        assertEquals("RO", reg("tinfo").access)
        assertEquals("S", reg("mscontext").privilege)
        val action = reg("tdata1").fields.single { it.name == "mcontrol6.ACTION" }
        assertTrue(action.values["2"]!!.contains("trace"))
        assertEquals(setOf("0", "1"), reg("tdata1").fields.single { it.name == "mcontrol6.HIT0" }.values.keys)
    }
}
