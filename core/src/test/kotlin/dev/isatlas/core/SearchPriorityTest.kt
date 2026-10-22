package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class SearchPriorityTest {
    private val sample = Catalog.decode(javaClass.getResource("/riscv-standard-sample.json")!!.readText())
    private val template = sample.registers.first()
    private fun reg(name: String, privilege: String, summary: String = "", fields: List<FieldDefinition> = emptyList()) =
        template.copy(id = "riscv/standard/csr/$name", name = name, title = "", summary = summary,
            condition = "", privilege = privilege, fields = fields)
    private fun pack(vararg registers: RegisterDefinition) = sample.copy(registers = registers.toList())

    @Test fun `CSR identity matches precede exact fields and description matches`() {
        val field = FieldDefinition("needle", "plain", "RW", listOf(BitLayout(64, 0, 0)))
        val data = pack(reg("needle_csr", "U"), reg("other", "M", fields = listOf(field)), reg("plain", "M", "needle in description"))
        val hits = Search.query(data, "needle", 64)
        assertEquals(listOf(SearchCategory.CSR, SearchCategory.FIELD, SearchCategory.DESCRIPTION), hits.map { it.category })
        assertEquals(listOf("needle_csr", "other", "plain"), hits.map { it.register.name })
    }

    @Test fun `privilege priority precedes relevance within each group`() {
        val data = pack(reg("status", "U"), reg("zzstatus", "M"), reg("status_s", "S"), reg("status_h", "H"), reg("status_vs", "VS"), reg("status_d", "D"))
        assertEquals(listOf("M", "S", "H", "VS", "U", "D"), Search.query(data, "status", 64).map { it.register.privilege })
        assertEquals(listOf("M", "S", "H", "VS", "U", "D"), Search.query(data, "", 64).map { it.register.privilege })
    }

    @Test fun `CSR number precedes relevance and name within the same privilege`() {
        val data = pack(
            reg("status", "M").copy(locator = RegisterLocator.Csr(0x342)),
            reg("zzstatus", "M").copy(locator = RegisterLocator.Csr(0x300)),
            reg("astatus", "M").copy(locator = RegisterLocator.Csr(0x305)),
            reg("sstatus", "S").copy(locator = RegisterLocator.Csr(0x100)),
        )
        for (query in listOf("", "status")) {
            assertEquals(listOf("zzstatus", "astatus", "status", "sstatus"),
                Search.query(data, query, 64).map { it.register.name })
        }
    }

    @Test fun `field description matches stay in description group without duplicates`() {
        val field = FieldDefinition("FLAGS", "needle", "RW", listOf(BitLayout(64, 0, 0)))
        val data = pack(reg("plain", "M", fields = listOf(field)))
        val hits = Search.query(data, "needle", 64)
        assertEquals(SearchCategory.DESCRIPTION, hits.single().category)
        assertEquals("FLAGS", hits.single().field!!.name)
        assertEquals(SearchCategory.FIELD, Search.query(data, "plain.FLAGS", 64).single().category)
    }

    @Test fun `address and aliases use identity groups and keep filtering`() {
        val field = FieldDefinition("CODE", "", "RW", listOf(BitLayout(64, 0, 0)), aliases = listOf("ExceptionCode"))
        val data = pack(reg("cause", "S", fields = listOf(field)))
        assertEquals(SearchCategory.FIELD, Search.query(data, "cause.ExceptionCode", 64).single().category)
        assertEquals(SearchCategory.CSR, Search.query(data, template.locator.label, 64).single().category)
        assertTrue(Search.query(data, "cause", 64, "M").isEmpty())
        assertEquals(1, Search.query(data, "cause", 64).size)
    }

    @Test fun `namespace text cannot promote unrelated CSR names`() {
        val data = pack(reg("cycle", "M"))
        assertEquals(SearchCategory.DESCRIPTION, Search.query(data, "standard", 64).single().category)
        assertEquals(SearchCategory.CSR, Search.query(data, "standard.cycle", 64).single().category)
    }
}
