package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class OfficialPackagesTest {
    private val packages = officialPackages()
    private val compiler = CompilerIsaOptions.decode(javaClass.getResource("/march-extensions.json")!!.readText())
    private fun catalogs(vararg enabled: String) = packages.catalogs(enabled.toSet(), compiler)

    @Test fun `all eight independent addon configurations validate and retain the instruction library`() {
        for (mask in 0..7) {
            val enabled = listOf("aia", "debug", "spmp").filterIndexed { bit, _ -> mask and (1 shl bit) != 0 }.toSet()
            val result = packages.catalogs(enabled, compiler)
            assertEquals(402 + (if ("aia" in enabled) 26 else 0) + (if ("debug" in enabled) 3 else 0) + (if ("spmp" in enabled) 3 else 0), result.csr.registers.size)
            assertEquals(1287, result.instructions.instructions.size)
            assertEquals(17, result.instructions.pseudos.size)
            assertTrue(result.csr.registers.all { reg -> reg.namespace == "manual" || reg.namespace in enabled })
            assertTrue(result.csr.registers.flatMap { it.sources }.all { it.namespace in enabled })
        }
    }

    @Test fun `disabling addons restores original fields and removes supplementary enum meanings`() {
        val base = catalogs().csr
        val all = catalogs("aia", "debug", "spmp").csr
        assertTrue(base.registers.single { it.name == "dcsr" }.fields.isEmpty())
        assertTrue(all.registers.single { it.name == "dcsr" }.fields.isNotEmpty())
        assertFalse(base.registers.single { it.name == "mie" }.fields.any { it.name == "LOCAL16_23" })
        assertTrue(all.registers.single { it.name == "mie" }.fields.any { it.name == "LOCAL16_23" })
        assertTrue(base.registers.single { it.name == "siselect" }.fields.single().values.isEmpty())
        assertEquals(base, catalogs().csr)
    }

    @Test fun `SPMP works without AIA and selector values remain composable`() {
        val spmp = catalogs("spmp").csr
        val all = catalogs("aia", "debug", "spmp").csr
        val selector = spmp.registers.single { it.name == "siselect" }
        assertEquals((0x100..0x13f).map(Int::toString).toSet(), selector.fields.single().values.keys)
        assertFalse(selector.condition.contains("Ssaia"))
        assertTrue(spmp.registers.single { it.name == "sireg2" }.fields.any { it.view == "spmpcfg" })
        val merged = all.registers.single { it.name == "siselect" }
        assertTrue(merged.fields.single().values.keys.containsAll(selector.fields.single().values.keys))
        assertTrue(merged.fields.single().values.size > selector.fields.single().values.size)
        assertEquals(setOf("aia", "spmp"), merged.sources.map { it.namespace }.toSet())
    }

    @Test fun `manual owns all generic indirect windows and uses normative addresses`() {
        val base = catalogs().csr
        for ((prefix, number) in listOf("m" to 0x350, "s" to 0x150, "vs" to 0x250)) {
            for ((suffix, offset) in listOf("iselect" to 0, "ireg" to 1, "ireg2" to 2, "ireg3" to 3, "ireg4" to 5, "ireg5" to 6, "ireg6" to 7)) {
                val reg = base.registers.single { it.name == prefix + suffix }
                assertEquals("manual", reg.namespace)
                assertEquals(number + offset, (reg.locator as RegisterLocator.Csr).address)
                assertTrue(reg.sourceUrl.contains("/indirect-csr.adoc"))
            }
        }
    }

    @Test fun `asset archive checksum failure is rejected before decoding`() {
        val index = javaClass.getResource("/riscv-packages.json")!!.readText()
        assertThrows(IllegalArgumentException::class.java) {
            OfficialPackages(index) { javaClass.getResource("/$it")!!.readBytes() + byteArrayOf(0) }
        }
    }
}
