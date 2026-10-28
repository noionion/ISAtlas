package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class ImportedCatalogTest {
    private val pack = reviewedCatalog()
    private fun manual(name: String) = pack.registers.single { it.namespace == "manual" && it.name == name }

    @Test fun `official manual replaces old sources and vendor data is optional`() {
        assertEquals(434, pack.registers.size)
        assertEquals(402, pack.registers.count { it.namespace == "manual" })
        assertEquals(0, pack.registers.count { it.namespace == "thead" })
        assertFalse(pack.registers.any { it.namespace in setOf("standard", "andes") })
        assertTrue(pack.registers.filter { it.namespace == "manual" }.all {
            it.sourceRevision == "584390d9d872835b1bc45ab9ea946b56db3ad485" && it.license == "CC-BY-4.0" &&
                it.sourceUrl.startsWith("https://github.com/riscv/riscv-isa-manual/blob/${it.sourceRevision}/")
        })
    }

    @Test fun `manual addresses width constraints and counter families are explicit`() {
        assertEquals(0x644, (manual("hip").locator as RegisterLocator.Csr).address)
        assertEquals(0x14D, (manual("stimecmp").locator as RegisterLocator.Csr).address)
        assertEquals(listOf(32), manual("cycleh").xlens)
        assertEquals(listOf(32), manual("pmpcfg1").xlens)
        assertEquals(listOf(32, 64), manual("pmpcfg2").xlens)
        assertTrue(Search.query(pack, "cycleh", 64).isEmpty())
        assertEquals(31, manual("mcycle").fields.single().layout(32)!!.msb)
        assertEquals(63, manual("mcycle").fields.single().layout(64)!!.msb)
        assertEquals(0xB1F, (manual("mhpmcounter31").locator as RegisterLocator.Csr).address)
    }

    @Test fun `field layouts aliases access rules and figure provenance come from the manual`() {
        assertTrue(Search.query(pack, "mstatus.MPV", 64).any { it.field?.condition?.contains("H") == true })
        assertEquals("CODE", Search.query(pack, "mcause.ExceptionCode", 64).single().field!!.name)
        assertEquals("SCRATCH", Search.query(pack, "sscratch.Scratch", 64).first().field!!.name)
        assertEquals("WLRL", manual("mcause").fields.single { it.name == "CODE" }.access)
        assertEquals("RO", manual("misa").fields.single { it.name == "MXL" }.access)
        assertEquals(BitLayout(64, 63, 60), manual("satp").fields.single { it.name == "MODE" }.layout(64))
        assertEquals(BitLayout(64, 31, 7), manual("mvendorid").fields.single { it.name == "BANK" }.layout(64))
        assertTrue(manual("mstatus").regions.any { it.access == "WPRI" && it.layout(64)?.let { bits -> 48 in bits.lsb..bits.msb } == true })
        assertTrue(pack.registers.filter { it.namespace == "manual" }.all { reg -> reg.fields.all { field ->
            field.sourceUrl.contains("/blob/584390d") || reg.sources.any { source ->
                source.namespace in setOf("aia", "debug", "spmp") && field.sourceUrl.startsWith("https://github.com/riscv/riscv-${if (source.namespace == "debug") "debug-spec" else source.namespace}/blob/${source.revision}/")
            }
        } })
    }

    @Test fun `removed sources and optional vendor data are absent before import`() {
        assertTrue(Search.query(pack, "mcache_ctl", 32).isEmpty())
        assertTrue(Search.query(pack, "", 64, namespace = "standard").isEmpty())
        assertTrue(Search.query(pack, "", 32, namespace = "andes").isEmpty())
        assertTrue(Search.query(pack, "th.sxstatus", 64, namespace = "thead").isEmpty())
    }

    @Test fun `unsupported layouts remain address only without invented rules`() {
        val incomplete = manual("dcsr").copy(coverage = "address-only", fields = emptyList(), regions = emptyList())
        assertEquals("address-only", incomplete.coverage)
        assertTrue(incomplete.fields.isEmpty() && incomplete.regions.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            Catalog.validate(pack.copy(registers = listOf(incomplete.copy(fields = manual("mcycle").fields))))
        }
    }

    @Test fun `all manual fields have concise bilingual descriptions and matching enums`() {
        val registers = pack.registers.filter { it.namespace == "manual" }
        val fields = registers.flatMap { it.fields }
        assertEquals(1779, fields.size)
        assertTrue(registers.all { it.summary.any { char -> char in '\u4e00'..'\u9fff' } && it.summaryEn.isNotBlank() })
        fields.forEach { field ->
            assertTrue(field.summary, field.summary.any { it in '\u4e00'..'\u9fff' })
            assertTrue(field.summary, field.summary.length <= 120)
            assertTrue(field.name, field.summaryEn.isNotBlank())
            assertEquals(field.values.keys, field.valuesEn.keys)
            assertEquals(field.valuesByXlen.keys, field.valuesEnByXlen.keys)
            field.valuesByXlen.forEach { (width, values) -> assertEquals(values.keys, field.valuesEnByXlen[width]!!.keys) }
        }
    }

    @Test fun `enum encodings distinguish architecture widths and rounding contexts`() {
        val mode = manual("satp").fields.single { it.name == "MODE" }
        assertEquals("Sv32", mode.valuesByXlen[32]!!["1"])
        assertEquals("Sv39", mode.valuesByXlen[64]!!["8"])
        assertFalse(mode.valuesByXlen[32]!!.containsKey("8"))
        val pmm = manual("menvcfg").fields.single { it.name == "PMM" }
        assertTrue(manual("menvcfgh").fields.single { it.name == "PMM" }.valuesByXlen[32]!!["0"]!!.contains("只读零"))
        assertEquals("PMLEN=7", pmm.valuesByXlen[64]!!["2"])
        val frm = manual("frm").fields.single()
        assertTrue(frm.values["7"]!!.contains("保留"))
        assertEquals(setOf("0", "1", "2", "3", "4", "5", "6", "7"), frm.values.keys)
        assertEquals("LMUL=1/8", manual("vtype").fields.single { it.name == "VLMUL" }.values["5"])
    }

    @Test fun `trap cause encodings preserve interrupt exception and extension distinctions`() {
        val values = manual("mcause").fields.single { it.name == "CODE" }.values
        assertEquals(64, values.size)
        assertTrue(values["3"]!!.contains("断点") && values["3"]!!.contains("M 软件中断"))
        assertTrue(values["20"]!!.contains("H：指令 guest-page"))
        assertTrue(values["24"]!!.contains("自定义用途"))
        assertTrue(manual("mncause").fields.single { it.name == "CODE" }.values["0"]!!.contains("未知 RNMI"))
    }

    @Test fun `catalog rejects inconsistent localized and width specific enums`() {
        val reg = manual("satp")
        val mode = reg.fields.single { it.name == "MODE" }
        fun validate(field: FieldDefinition) = Catalog.validate(pack.copy(registers = listOf(reg.copy(fields = reg.fields.map { if (it.name == field.name) field else it }))))
        assertThrows(IllegalArgumentException::class.java) { validate(mode.copy(valuesByXlen = mode.valuesByXlen + (32 to mapOf("8" to "bad")))) }
        assertThrows(IllegalArgumentException::class.java) { validate(mode.copy(valuesEnByXlen = mapOf(32 to mapOf("0" to "Bare")))) }
        assertThrows(IllegalArgumentException::class.java) { validate(mode.copy(valuesByXlen = mode.valuesByXlen + (128 to emptyMap()))) }
    }
}
