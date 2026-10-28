package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class IsaExtensionFilterTest {
    private val options = CompilerIsaOptions.decode(javaClass.getResource("/march-extensions.json")!!.readText())
    private val pack = reviewedCatalog()

    @Test fun `catalog options omit extensions without CSR associations but preserve dependency metadata`() {
        val visible = options.catalogOptions(pack)
        assertTrue(visible.containsAll(listOf("zicfiss", "zicfilp", "sstc", "f", "d", "h")))
        assertFalse(visible.any { it in setOf("s", "u", "zba", "zbb", "za128rs", "xtheadba") })
        assertTrue(visible.all { token -> pack.registers.any { token in IsaExtensionTags.associated(it) } })
        assertEquals(emptyList<String>(), options.catalogOptions(pack.copy(registers = emptyList())))
        assertTrue("zicsr" in options.selectionClosure(setOf("d")))
    }

    @Test fun `mode capabilities remain internal prerequisites without selectable filters`() {
        assertTrue("f" in options.tokens && "sstc" in options.tokens && "smstateen" in options.tokens)
        assertFalse("s" in options.tokens || "u" in options.tokens || "sm" in options.tokens)
        assertFalse("s" in options.selectable || "u" in options.selectable)
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

    @Test fun `vector FP selects embedded vector and scalar FP prerequisites without full V`() {
        val half = options.selectionClosure(setOf("zvfh"))
        assertTrue(half.containsAll(setOf("zvfhmin", "zve32f", "zve32x", "zfhmin", "f", "zicsr", "zvl32b")))
        assertFalse("v" in half || "d" in half)
        val bf16 = options.selectionClosure(setOf("zvfbfwma"))
        assertTrue(bf16.containsAll(setOf("zvfbfmin", "zfbfmin", "zve32f", "f", "zicsr")))
        assertFalse("v" in bf16)
    }

    @Test fun `integer vector crypto does not imply full V or floating point`() {
        val crypto = options.selectionClosure(setOf("zvknc"))
        assertTrue(crypto.containsAll(setOf("zvkn", "zvkned", "zvknhb", "zvkb", "zvkt", "zvbc", "zve64x", "zve32x", "zicsr", "zvl64b", "zvl32b")))
        assertFalse(crypto.any { it in setOf("v", "f", "d", "zve32f") })
        val bits = options.selectionClosure(setOf("zvbb"))
        assertTrue(bits.containsAll(setOf("zvkb", "zve32x", "zicsr")))
        assertFalse("zve64x" in bits)
    }

    @Test fun `scalar FP and integer register FP have different dependency chains`() {
        for (token in listOf("zfa", "zfh", "zfhmin", "zfbfmin")) {
            assertTrue(token, options.selectionClosure(setOf(token)).containsAll(setOf("f", "zicsr")))
        }
        for (token in listOf("zfinx", "zdinx", "zhinx", "zhinxmin")) {
            val closure = options.selectionClosure(setOf(token))
            assertTrue(token, "zfinx" in closure && "zicsr" in closure)
            assertFalse(token, "f" in closure || "d" in closure)
        }
    }

    @Test fun `full V expands embedded subsets and longer vector lengths include shorter lengths`() {
        assertTrue(options.selectionClosure(setOf("v")).containsAll(setOf("zve64d", "zve64f", "zve64x", "zve32f", "zve32x", "zvl128b", "zvl64b", "zvl32b", "d", "f", "zicsr")))
        val lengths = options.selectionClosure(setOf("zvl256b"))
        assertTrue(lengths.containsAll(setOf("zvl256b", "zvl128b", "zvl64b", "zvl32b")))
        assertFalse("v" in lengths || "zve32x" in lengths)
        for (token in options.tokens.filter { it.startsWith("zv") || it.startsWith("zf") }) {
            val closure = options.selectionClosure(setOf(token))
            assertEquals(token, closure, options.selectionClosure(closure))
            assertTrue(token, token == "zvl32b" || closure.size > 1)
        }
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
