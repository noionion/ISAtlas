package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    private val pack = Catalog.decode(javaClass.getResource("/riscv-standard-sample.json")!!.readText())

    @Test fun `bundled pack passes validation with explicit coverage`() {
        assertEquals(15, pack.registers.size)
        assertTrue(pack.coverage.isNotBlank())
        assertEquals("ISA 20240411 / Machine & Supervisor 1.13", pack.source.revision)
    }

    @Test fun `hex decimal and case-insensitive names find same register`() {
        listOf("0x300", "768", "  MSTATUS  ").forEach {
            assertEquals("mstatus", Search.query(pack, it, 64).first().register.name)
        }
        assertTrue(Search.query(pack, "0x100000000000000", 64).isEmpty())
    }

    @Test fun `field names retain register and field collisions`() {
        val hits = Search.query(pack, "MIE", 64)
        assertTrue(hits.any { it.register.name == "mie" && it.field == null })
        assertTrue(hits.any { it.register.name == "mstatus" && it.field?.name == "MIE" })
        assertEquals("MIE", Search.query(pack, "mstatus.MIE", 64).single().field!!.name)
    }

    @Test fun `empty and register-only queries do not enumerate child fields`() {
        assertTrue(Search.query(pack, "", 64).all { it.field == null })
        assertEquals(listOf("mstatus", "mstatush"), Search.query(pack, "mstatus", 32).map { it.register.name })
    }

    @Test fun `width filters register availability and field layouts`() {
        assertTrue(Search.query(pack, "mstatush", 64).isEmpty())
        assertEquals("mstatush", Search.query(pack, "mstatush", 32).single().register.name)
        val sd = pack.registers.first { it.name == "mstatus" }.fields.first { it.name == "SD" }
        assertEquals(31, sd.layout(32)!!.msb)
        assertEquals(63, sd.layout(64)!!.msb)
        assertTrue(Search.query(pack, "mstatus.SXL", 32).isEmpty())
        assertEquals("SXL", Search.query(pack, "mstatus.SXL", 64).single().field!!.name)
    }

    @Test fun `text tokens and privilege filters apply together`() {
        assertEquals("satp", Search.query(pack, "地址 转换", 64, "S").first().register.name)
        assertTrue(Search.query(pack, "satp", 64, "M").isEmpty())
        assertEquals("mtvec", Search.query(pack, "trap vector", 32).first().register.name)
    }

    @Test fun `vendor id bank stays 32 bit wide even on RV64 and misa MXL is read only`() {
        val bank = pack.registers.first { it.name == "mvendorid" }.fields.first { it.name == "Bank" }
        assertEquals(BitLayout(64, 31, 7), bank.layout(64))
        assertEquals("RO", pack.registers.first { it.name == "misa" }.fields.first { it.name == "MXL" }.access)
    }

    @Test fun `schema and duplicate identities are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Catalog.validate(pack.copy(schemaVersion = 2)) }
        assertThrows(IllegalArgumentException::class.java) { Catalog.validate(pack.copy(registers = pack.registers + pack.registers.first())) }
    }

    @Test fun `bad bit range overlapping bits and overflowing enums are rejected`() {
        val reg = pack.registers.first()
        val field = reg.fields.first()
        fun invalid(fields: List<FieldDefinition>) = Catalog.validate(pack.copy(registers = listOf(reg.copy(fields = fields))))
        assertThrows(IllegalArgumentException::class.java) { invalid(listOf(field.copy(layouts = listOf(BitLayout(32, 32, 0))))) }
        assertThrows(IllegalArgumentException::class.java) { invalid(listOf(field, field.copy(name = "Overlap"))) }
        assertThrows(IllegalArgumentException::class.java) { invalid(listOf(field.copy(values = mapOf("2" to "overflow")))) }
    }
}
