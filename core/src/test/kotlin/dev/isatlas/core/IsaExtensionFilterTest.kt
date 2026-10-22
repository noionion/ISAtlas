package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class IsaExtensionFilterTest {
    private val options = CompilerIsaOptions.decode(javaClass.getResource("/march-extensions.json")!!.readText())
    private val pack = installedCatalog()

    @Test fun `catalog options omit extensions without CSR associations but preserve dependency metadata`() {
        val visible = options.catalogOptions(pack)
        assertTrue(visible.containsAll(listOf("zicfiss", "zicfilp", "sstc", "f", "d", "h")))
        assertFalse(visible.any { it in setOf("zba", "zbb", "za128rs", "xtheadba") })
        assertTrue(visible.all { token -> pack.registers.any { token in IsaExtensionTags.associated(it) } })
        assertEquals(emptyList<String>(), options.catalogOptions(pack.copy(registers = emptyList())))
        assertTrue("zicsr" in options.selectionClosure(setOf("d")))
    }

    @Test fun `mode capabilities remain selectable separately from compiler tokens`() {
        assertTrue("f" in options.tokens && "sstc" in options.tokens && "smstateen" in options.tokens)
        assertFalse("s" in options.tokens || "u" in options.tokens || "sm" in options.tokens)
        assertTrue(options.selectable.containsAll(listOf("s", "u")))
        assertFalse("sm" in options.selectable)
    }

    @Test fun `floating and vector prerequisite closures are transitive`() {
        assertTrue(options.selectionClosure(setOf("d")).containsAll(listOf("d", "f", "zicsr")))
        assertTrue(options.selectionClosure(setOf("v")).containsAll(listOf("v", "d", "f", "zicsr")))
        assertEquals(options.selectionClosure(setOf("v")), options.selectionClosure(options.selectionClosure(setOf("v"))))
    }

    @Test fun `hypervisor selects I S U but hides internal Sm marker`() {
        val selected = options.selectionClosure(setOf("h"))
        assertTrue(selected.containsAll(listOf("h", "i", "s", "u")))
        assertFalse("sm" in selected)
        assertFalse("sm" in options.associatedTags(selected))
    }

    @Test fun `extension association finds containing CSR but not unrelated fields`() {
        val floating = options.associatedTags(setOf("f"))
        val hits = Search.query(pack, "", 64, extensions = floating)
        assertTrue(hits.any { it.register.name == "fcsr" })
        assertTrue(hits.any { it.register.name == "mstatus" })
        assertTrue(Search.query(pack, "mstatus.MIE", 64, extensions = floating).isEmpty())
        assertTrue(Search.query(pack, "mstatus.FS", 64, extensions = floating).any { it.field?.name == "FS" })
        assertTrue(Search.query(pack, "mcache_ctl", 32, namespace = "andes", extensions = floating).isEmpty())
    }

    @Test fun `multiple selected associations use OR and cycles terminate`() {
        val hits = Search.query(pack, "", 64, extensions = options.associatedTags(setOf("f", "h")))
        assertTrue(hits.any { it.register.name == "fcsr" })
        assertTrue(hits.any { it.register.name == "hstatus" })
        val cycle = options.copy(dependencies = mapOf("f" to listOf("d"), "d" to listOf("f")))
        assertEquals(setOf("d", "f"), cycle.selectionClosure(setOf("d")))
    }
}
