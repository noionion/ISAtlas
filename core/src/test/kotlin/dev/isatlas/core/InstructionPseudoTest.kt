package dev.isatlas.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class InstructionPseudoTest {
    companion object {
        private fun asset(name: String) = InstructionPseudoTest::class.java.classLoader!!.getResource(name)!!.readText()
        private val catalog = InstructionCatalog.decodeFiles(asset("riscv-instructions.json")) { asset("inst/$it") }
        private val compiler = CompilerIsaOptions.decode(asset("march-extensions.json"))
    }
    private fun search(input: String, filter: CatalogFilter = CatalogFilter(64)) = InstructionSearch.query(catalog, input, filter, compiler)

    @Test fun `catalog has audited aliases expansions and explicitly unresolved historical names`() {
        assertEquals(1287, catalog.instructions.size)
        assertEquals(1410, catalog.instructions.sumOf { it.encodings.size })
        assertEquals("0.2.0", catalog.version)
        assertEquals(17, catalog.pseudos.size)
        assertEquals(88, catalog.instructions.count { it.kind == "alias" })
        assertEquals(9, catalog.pseudos.count { it.referenceOnly })
        assertEquals(18, catalog.pseudos.sumOf { it.expansions.size })
        catalog.pseudos.forEach { pseudo ->
            assertTrue(pseudo.summary.isNotBlank() && pseudo.summaryEn.isNotBlank() && pseudo.notesEn.isNotBlank())
            assertTrue(pseudo.semanticSources.all { it.url.contains(pseudo.sourceRevision) })
            assertTrue(pseudo.expansions.flatMap { it.steps }.all { step -> catalog.instructions.any { it.name == step.name } })
        }
    }
    @Test fun `pseudo names and descriptions use ordinary search and complete names remain first`() {
        val call = search("call")
        assertEquals("call", call.groups.first().name)
        assertTrue(call.hits.any { it.instruction.name == "ecall" })
        assertEquals(InstructionMatchCategory.NAME, call.pseudoHits.single { it.instruction.name == "call" }.category)
        assertTrue(call.pseudoHits.any { it.instruction.name == "tail" && it.category == InstructionMatchCategory.DESCRIPTION })
        assertTrue(search("尾调用").pseudoHits.any { it.instruction.name == "tail" && it.category == InstructionMatchCategory.DESCRIPTION })
        assertTrue(search("VMSGE").pseudoHits.map { it.instruction.name }.containsAll(listOf("vmsge.vx", "vmsgeu.vx")))
    }
    @Test fun `machine code lookup never invents a pseudo match`() {
        listOf("0x00000097", "0x000080e7", "0x00008067", "0x6c004057").forEach {
            val result = search(it)
            assertTrue(result.pseudoHits.isEmpty())
            assertTrue(result.groups.all { group -> group.pseudoHits.isEmpty() })
        }
    }
    @Test fun `pseudos share source permission width and complete extension filters`() {
        assertTrue(search("vmsge.vx", CatalogFilter(64, extensions = setOf("i"))).pseudoHits.isEmpty())
        assertEquals(setOf(InstructionFilterDimension.EXTENSION), search("vmsge.vx", CatalogFilter(64, extensions = setOf("i"))).diagnostic!!.clear)
        assertTrue(search("vmsge.vx", CatalogFilter(32, extensions = setOf("zve32x"))).pseudoHits.isNotEmpty())
        assertTrue(search("vmsge.vx", CatalogFilter(64, extensions = setOf("v"))).pseudoHits.isNotEmpty())
        assertEquals(setOf(InstructionFilterDimension.PRIVILEGE), search("tail", CatalogFilter(64, "M")).diagnostic!!.clear)
        assertEquals(setOf(InstructionFilterDimension.SOURCE), search("tail", CatalogFilter(64, namespace = "vendor")).diagnostic!!.clear)
        val wide = catalog.pseudos.first { it.name == "tail" }.copy(xlens = listOf(64))
        val result = InstructionSearch.query(InstructionCatalog(emptyList(), "test", listOf(wide)), "tail", CatalogFilter(32))
        assertEquals(setOf(InstructionFilterDimension.WIDTH), result.diagnostic!!.clear)
        assertEquals(64, result.diagnostic!!.suggestedXlen)
    }
    @Test fun `combined groups keep every reference and machine variant without reporting empty results`() {
        val result = search("")
        assertEquals(result.hits.size, result.groups.sumOf { it.hits.size })
        assertEquals(result.pseudoHits.size, result.groups.sumOf { it.pseudoHits.size })
        assertEquals(result.groups.size, result.groups.map { it.key }.distinct().size)
        assertNull(search("tail").diagnostic)
        assertTrue(search("tail").groups.isNotEmpty())
    }
    @Test fun `unsigned and masked expansion conditions preserve the manual sequence`() {
        val vector = catalog.pseudos.single { it.name == "vmsgeu.vx" }
        assertTrue(vector.expansions.take(4).all { it.steps.first().name == "vmsltu.vx" })
        assertEquals("addi", vector.expansions[4].steps.first().name)
        assertEquals(listOf("vmxor.mm vd, vd, v0"), vector.expansions[1].steps.drop(1).map { it.assembly })
        assertEquals(listOf("vt"), vector.expansions[2].clobbers)
        assertEquals(4, vector.expansions[3].steps.size)
        assertTrue(catalog.pseudos.single { it.name == "tail" }.notesEn.contains("not a universal assembler"))
    }
    @Test fun `old instruction manifests still load without pseudo entries`() {
        val original = Json.parseToJsonElement(asset("riscv-instructions.json")).jsonObject
        val old = JsonObject(original - setOf("pseudoFiles", "pseudoFileHashes")).toString()
        assertTrue(InstructionCatalog.decodeFiles(old) { asset("inst/$it") }.pseudos.isEmpty())
    }
    @Test fun `tampered pseudo bytes cannot bypass catalog integrity`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstructionCatalog.decodeFiles(asset("riscv-instructions.json")) { asset("inst/$it") + if (it.startsWith("pseudo-")) " " else "" }
        }
    }
    @Test fun `reference with fake encodings is rejected even with matching file hash`() {
        val file = "pseudo-call.json"
        val text = JsonObject(Json.parseToJsonElement(asset("inst/$file")).jsonObject + ("encodings" to JsonArray(emptyList()))).toString()
        val root = Json.parseToJsonElement(asset("riscv-instructions.json")).jsonObject
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val manifest = JsonObject(root + ("pseudoFileHashes" to JsonObject(root.getValue("pseudoFileHashes").jsonObject + (file to JsonPrimitive(hash))))).toString()
        assertThrows(IllegalArgumentException::class.java) {
            InstructionCatalog.decodeFiles(manifest) { if (it == file) text else asset("inst/$it") }
        }
    }
}
