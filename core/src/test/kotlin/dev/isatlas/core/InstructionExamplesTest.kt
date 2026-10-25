package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Test

class InstructionExamplesTest {
    companion object {
        private fun asset(name: String) = InstructionExamplesTest::class.java.classLoader!!.getResource(name)!!.readText()
        private val catalog = InstructionCatalog.decodeFiles(asset("riscv-instructions.json")) { asset("inst/$it") }
    }
    private fun example(name: String, xlen: Int = 64, abi: Boolean = true): InstructionUsageExample {
        val instruction = catalog.instructions.single { it.name == name }
        return InstructionExamples.create(instruction, instruction.encodings.first { xlen in it.xlens }, xlen, abi)!!
    }

    @Test fun `arithmetic examples give concrete operands and results in either naming mode`() {
        val add = example("add")
        assertEquals("add a0, a1, a2", add.code)
        assertTrue(add.explanation.contains("a0 = 8"))
        assertEquals("add x10, x11, x12", example("add", abi = false).code)
        assertTrue(example("addi").explanation.contains("= 40"))
        assertTrue(example("lw").code.contains("16(a1)"))
        assertTrue(example("csrrw").explanation.contains("mscratch"))
        assertTrue(example("ret").explanation.contains("返回地址"))
        assertEquals("${add.code}\n# ${add.explanation}", add.copyText)
    }

    @Test fun `all catalog examples satisfy encoding restrictions and preserve source data`() {
        var count = 0
        val missing = mutableListOf<String>()
        catalog.instructions.forEach { instruction -> instruction.encodings.forEachIndexed { index, encoding ->
            encoding.xlens.forEach { xlen ->
                val label = "${instruction.name}:$index/RV$xlen"
                val example = InstructionExamples.create(instruction, encoding, xlen)
                if (example == null) missing += label
                else {
                    count++
                    assertTrue(label, encoding.matches(example.word!!, xlen))
                    assertEquals(label, example.code, InstructionAssembly.render(InstructionHit(instruction, encoding, index, example.word), xlen = xlen)!!.text)
                    assertFalse(label, example.explanation.contains("null"))
                    assertFalse(label, example.code.contains("null"))
                    assertTrue(label, example.explanationEn.isNotBlank())
                    assertNull(label, InstructionExamples.create(instruction, encoding, 128))
                }
            }
        } }
        println("Instruction examples: $count; unsupported: ${missing.joinToString()}")
        assertTrue("Missing examples: ${missing.joinToString()}", missing.isEmpty())
        assertEquals(catalog.instructions.sumOf { instruction -> instruction.encodings.sumOf { it.xlens.size } }, count)
    }

    @Test fun `examples do not depend on partial query values`() {
        val instruction = catalog.instructions.single { it.name == "add" }
        val encoding = instruction.encodings.first()
        val query = InstructionInput.parse("0x00?50533").pattern!!
        val before = query.operands(encoding)
        example("add")
        assertEquals(before, query.operands(encoding))
        assertNull(query.operands(encoding)["rs2"])
        assertNull(InstructionAssembly.render(InstructionHit(instruction, encoding, 0, pattern = query)))
    }

    @Test fun `reference examples distinguish symbols from encodings and exclude historical names`() {
        catalog.pseudos.forEach { pseudo ->
            val example = InstructionExamples.create(pseudo)
            if (pseudo.referenceStatus == "historical") assertNull(pseudo.name, example)
            else {
                assertNotNull(pseudo.name, example)
                assertTrue(pseudo.name, example!!.code.startsWith(pseudo.name + " "))
                assertNull(example.word)
                assertFalse(example.code, Regex("\\b(vd|va|vt|offset)\\b").containsMatchIn(example.code))
            }
        }
    }
}
