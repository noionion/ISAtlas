package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class InstructionGroupingTest {
    companion object {
        private fun asset(name: String) = InstructionGroupingTest::class.java.classLoader!!.getResource(name)!!.readText()
        private val catalog = InstructionCatalog.decodeFiles(asset("riscv-instructions.json")) { asset("inst/$it") }
        private val compiler = CompilerIsaOptions.decode(asset("march-extensions.json"))
    }
    private fun search(query: String, filter: CatalogFilter = CatalogFilter(64)) = InstructionSearch.query(catalog, query, filter, compiler)

    @Test fun `complete name precedes privileged fragments without hiding alternatives`() {
        val result = search(" ReT ")
        assertEquals("ret", result.hits.first().instruction.name)
        assertEquals("ret", result.groups.first().name)
        assertTrue(result.hits.map { it.instruction.name }.containsAll(listOf("mret", "sret", "ret")))
        val other = result.hits.filter { it.instruction.name != "ret" }
        assertEquals(other.map { privilegeRank(it.encoding.privilege) }.sorted(), other.map { privilegeRank(it.encoding.privilege) })
        assertTrue(search("return").hits.any { it.category == InstructionMatchCategory.DESCRIPTION })
    }
    @Test fun `equal prefix no longer enables a special search mode`() {
        listOf("=ret", "=ret extra").forEach {
            assertTrue(search(it).hits.isEmpty())
            assertNull(search(it).error)
            assertEquals(InstructionDiagnosticKind.UNKNOWN, search(it).diagnostic!!.kind)
        }
        val result = search("fadd.s")
        assertTrue(result.hits.takeWhile { it.instruction.name == "fadd.s" }.size > 1)
        assertTrue(result.hits.all { it.word == null })
    }
    @Test fun `name search retains extension width and permission diagnosis`() {
        assertEquals(setOf(InstructionFilterDimension.PRIVILEGE), search("mret", CatalogFilter(64, "U")).diagnostic!!.clear)
        assertEquals(setOf(InstructionFilterDimension.EXTENSION), search("c.sspush", CatalogFilter(64, extensions = setOf("zcmop"))).diagnostic!!.clear)
        assertTrue(search("c.sspush", CatalogFilter(64, extensions = setOf("zcmop", "zicfiss"))).hits.isNotEmpty())
        assertTrue(search("fadd.s", CatalogFilter(64, extensions = setOf("f"))).hits.all { "F" in it.encoding.requires.flatten() })
    }
    @Test fun `same name variants collapse without losing any source encoding or priority`() {
        val result = search("fadd.s")
        assertTrue(result.hits.size > 1)
        assertEquals(1, result.groups.size)
        assertEquals(result.hits, result.groups.single().hits)
        assertTrue(result.groups.single().hits.map { it.encoding.requirements }.distinct().size > 1)
        val all = search("")
        assertEquals(all.hits.map { it.key }.toSet(), all.groups.flatMap { it.hits }.map { it.key }.toSet())
        assertEquals(all.hits.size, all.groups.sumOf { it.hits.size })
        assertEquals(all.groups.size, all.groups.map { it.key }.distinct().size)
        assertEquals(all.groups.map { privilegeRank(it.hits.first().encoding.privilege) }.sorted(), all.groups.map { privilegeRank(it.hits.first().encoding.privilege) })
    }
    @Test fun `multiple sources remain explicit variants rather than overwritten records`() {
        val original = search("add").hits.single { it.instruction.name == "add" }
        val vendor = original.instruction.copy(id = "vendor/add", namespace = "vendor", summary = "vendor meaning")
        val result = InstructionSearch.query(InstructionCatalog(listOf(original.instruction, vendor), "test"), "add", CatalogFilter(64))
        assertEquals(1, result.groups.size)
        assertEquals(setOf("manual", "vendor"), result.groups.single().hits.map { it.instruction.namespace }.toSet())
        assertEquals(setOf(original.instruction.summary, "vendor meaning"), result.groups.single().hits.map { it.instruction.summary }.toSet())
        val encoded = result.hits.map { it.copy(word = 0x00b50533) }
        assertTrue(InstructionCandidates.explanations(encoded).any { "多个数据来源" in it })
    }
    @Test fun `categories omit empty groups and preserve name before description`() {
        assertTrue(search("nothing.matched").groups.isEmpty())
        val groups = search("and").groups
        assertEquals(listOf(InstructionMatchCategory.NAME, InstructionMatchCategory.DESCRIPTION), groups.map { it.category }.distinct())
        assertEquals(listOf(InstructionMatchCategory.ENCODING), search("0x00b50533").groups.map { it.category }.distinct())
    }
    @Test fun `alias and base remain distinct candidates with an explicit relationship`() {
        val result = search("0x00008067")
        assertTrue(result.groups.map { it.name }.containsAll(listOf("ret", "jalr")))
        assertTrue(InstructionCandidates.explanations(result.hits).any { "ret 是 jalr 的别名" in it })
        assertTrue(InstructionCandidates.explanations(search("ret").hits).isEmpty())
    }
    @Test fun `alias relationships never attach a different source base by name alone`() {
        val hits = search("0x00008067").hits.filter { it.instruction.name in setOf("ret", "jalr") }.map {
            if (it.instruction.name == "jalr") it.copy(instruction = it.instruction.copy(namespace = "vendor", id = "vendor/jalr")) else it
        }
        val explanations = InstructionCandidates.explanations(hits)
        assertFalse(explanations.any { "是 jalr 的别名" in it })
        assertTrue(explanations.any { "多个数据来源" in it })
    }
    @Test fun `overlap explanations follow only surviving extension candidates`() {
        val all = search("6081")
        assertTrue(InstructionCandidates.explanations(all.hits).any { "扩展要求不同" in it })
        val mop = search("6081", CatalogFilter(64, extensions = setOf("zcmop")))
        assertEquals(listOf("c.mop.1"), mop.hits.map { it.instruction.name })
        assertTrue(InstructionCandidates.explanations(mop.hits).isEmpty())
    }
}
