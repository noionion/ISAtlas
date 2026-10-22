package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class RegisterDiagramTest {
    private val pack = Catalog.decode(javaClass.getResource("/riscv-manual-sample.json")!!.readText())

    @Test fun `drag hit testing resolves exact one bit fields and ignores reserved regions`() {
        val reg = pack.registers.first { it.name == "mstatus" }
        val lane = registerDiagramLanes(reg.fields, 64, 16, reg.regions).last()
        assertEquals("MPIE", lane.fieldAtFraction(8.5f / 16)!!.name)
        assertEquals("MIE", lane.fieldAtFraction(12.5f / 16)!!.name)
        assertNull(lane.fieldAtFraction(13.5f / 16)) // WPRI bit 2
        assertEquals("SIE", lane.fieldAtFraction(14.5f / 16)!!.name)
        assertNull(lane.fieldAtFraction(-0.01f))
        assertNull(lane.fieldAtFraction(1f))
        assertNull(lane.fieldAtFraction(Float.NaN))
    }

    @Test fun `hit testing at lane boundary keeps cross lane fields intact`() {
        val reg = pack.registers.first { it.name == "mcause" }
        val lanes = registerDiagramLanes(reg.fields, 64, 16, reg.regions)
        assertEquals("INT", lanes.first().fieldAtFraction(0f)!!.name)
        assertEquals("CODE", lanes.first().fieldAtFraction(1f / 16)!!.name)
        assertEquals("CODE", lanes.first().fieldAtFraction(0.99999f)!!.name)
        assertEquals("CODE", lanes[1].fieldAtFraction(0f)!!.name)
    }

    @Test fun `wide fields continue across lane boundaries without losing or duplicating bits`() {
        val fields = pack.registers.first { it.name == "mcause" }.fields
        val lanes = registerDiagramLanes(fields, 64, 16)
        assertEquals(listOf(63, 47, 31, 15), lanes.map { it.msb })
        assertEquals(listOf("INT", "CODE"), lanes.first().segments.map { it.field!!.name })
        assertEquals(63, lanes.flatMap { it.segments }.filter { it.field!!.name == "CODE" }.sumOf { it.width })
        assertEquals((63 downTo 0).toList(), lanes.flatMap { it.segments }.flatMap { (it.msb downTo it.lsb).toList() })
    }

    @Test fun `spec reserved regions remain separate from named fields`() {
        val reg = pack.registers.first { it.name == "mstatus" }
        val lanes = registerDiagramLanes(reg.fields, 64, 32, reg.regions)
        val upper = lanes.first().segments
        assertEquals("SD", upper.first().field!!.name)
        assertEquals("[62:43]", upper[1].range)
        assertNull(upper[1].field)
        assertEquals("WPRI", upper[1].region!!.access)
        assertEquals(2, lanes.size)
        assertTrue(lanes.all { it.segments.sumOf { segment -> segment.width } == 32 })
    }

    @Test fun `manual status layouts cover explicit bits while unsupported areas remain unknown`() {
        val reg = pack.registers.first { it.name == "mstatus" }
        for (width in reg.xlens) {
            val segments = registerDiagramLanes(reg.fields, width, 16, reg.regions).flatMap { it.segments }
            assertTrue(segments.all { it.field != null || it.region != null })
            assertEquals(width, segments.sumOf { it.width })
        }
        val misa = pack.registers.first { it.name == "misa" }
        assertTrue(misa.regions.all { it.access == "WARL" })
        val vendor = pack.registers.first { it.name == "mvendorid" }
        assertTrue(vendor.fields.all { it.access == "RO" })
    }

    @Test fun `unclassified input stays unknown rather than silently becoming WPRI`() {
        val reg = pack.registers.first { it.name == "mstatus" }
        val lanes = registerDiagramLanes(reg.fields.filter { it.name == "MIE" }, 32, 16)
        assertTrue(lanes.flatMap { it.segments }.any { it.field == null && it.region == null })
    }

    @Test fun `region overlapping a named field is rejected`() {
        val reg = pack.registers.first { it.name == "mstatus" }
        val region = BitRegion("WPRI", "WPRI", "invalid overlap", listOf(BitLayout(32, 3, 3)))
        assertThrows(IllegalArgumentException::class.java) {
            Catalog.validate(pack.copy(registers = listOf(reg.copy(regions = reg.regions + region))))
        }
    }

    @Test fun `layout changes follow RV32 field positions`() {
        val fields = pack.registers.first { it.name == "mstatus" }.fields
        val lanes = registerDiagramLanes(fields, 32, 16)
        assertEquals("SD", lanes.first().segments.first().field!!.name)
        assertEquals("[31]", lanes.first().segments.first().range)
        assertFalse(lanes.flatMap { it.segments }.any { it.field?.name == "SXL" })
    }
}
