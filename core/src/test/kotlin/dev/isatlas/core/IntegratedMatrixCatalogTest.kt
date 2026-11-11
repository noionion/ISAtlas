package dev.isatlas.core

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class IntegratedMatrixCatalogTest {
    private fun catalogs(): Pair<LocalCatalogs, LocalCatalogs> {
        val loader = javaClass.classLoader
        assumeTrue(loader.getResource("packages/ime.zip") != null)
        val packages = OfficialPackages(loader.getResource("riscv-packages.json")!!.readText()) { loader.getResource(it)!!.readBytes() }
        val compiler = CompilerIsaOptions.decode(loader.getResource("march-extensions.json")!!.readText())
        return packages.catalogs(emptySet(), compiler) to packages.catalogs(setOf("ime"), compiler)
    }

    @Test fun independentPackageRestoresBaseAndAddsExactInventory() {
        val (base, matrix) = catalogs()
        assertEquals(base.csr.registers.size, matrix.csr.registers.size)
        assertEquals(base.instructions.instructions.size + 15, matrix.instructions.instructions.size)
        assertEquals(base.instructions.pseudos.size, matrix.instructions.pseudos.size)
        assertEquals(18, matrix.instructions.instructions.filter { it.namespace == "ime" }.sumOf { it.encodings.size })
        val reg = matrix.csr.registers.single { it.name == "vtype" }
        assertTrue(reg.sources.any { it.namespace == "ime" })
        assertEquals(BitLayout(64, 62, 60), reg.fields.single { it.name == "LAMBDA" }.layout(64))
        assertEquals(BitLayout(32, 26, 26), reg.fields.single { it.name == "ALTFMT_A" }.layout(32))
        assertEquals("16 个元素", reg.fields.single { it.name == "BS" }.values["1"])
        assertFalse(base.csr.registers.single { it.name == "vtype" }.fields.any { it.name == "LAMBDA" })
    }

    @Test fun exactDependenciesAndScaledEncodingRequirements() {
        val (_, matrix) = catalogs()
        val caps = matrix.compiler.selectionClosure(setOf("zvvi8i32mm"))
        assertTrue(caps.containsAll(listOf("zvvi8i32mm", "zve32x")))
        assertFalse("v" in caps)
        val scaled = matrix.compiler.selectionClosure(setOf("zvvxnofp4fp16mm"))
        assertTrue(scaled.containsAll(listOf("zvvxnofp4fp16mm", "zvvxofp4fp16mm", "zvvofp4fp16mm", "zve32f", "f")))
        fun hit(word: String, selected: Set<String>) = InstructionSearch.query(matrix.instructions, word,
            CatalogFilter(64, namespace = "ime", extensions = matrix.compiler.selectionClosure(selected)), matrix.compiler).hits
        assertEquals("v8wmmacc.vv", hit("0xee208457", setOf("zvvi8i64mm")).single().instruction.name)
        assertTrue(hit("0xec208457", setOf("zvvi8i64mm")).isEmpty())
        assertEquals("vf8wimmacc.vv", hit("0xec208457", setOf("zvvxi4fp32mm")).single().instruction.name)
        assertEquals("vfwmmacc.vv", hit("0x54209457", setOf("zvvxofp4ofp8mm")).single().instruction.name)
        assertTrue(hit("0x54209457", setOf("zvvofp4ofp8mm")).isEmpty())
    }

    @Test fun registerOperandsLambdaAndContextArePreserved() {
        val (_, matrix) = catalogs()
        val instruction = matrix.instructions.instructions.single { it.namespace == "ime" && it.name == "vmtl.v" }
        val operands = instruction.encodings.single().operands(0x32257407)
        assertEquals(mapOf("vd" to "v8", "rs1" to "x10", "rs2" to "x2", "lambda" to "1", "vm" to "1"), operands)
        val fp = matrix.instructions.instructions.single { it.namespace == "ime" && it.name == "vfmmacc.vv" }.encodings.single()
        assertTrue(fp.requires.any { it.containsAll(listOf("zvvfp16mm", "zvvbf16mm")) })
        assertTrue(fp.contextRules.any { it.summary.contains("altfmt_A=0, altfmt_B=1") })
        assertTrue(fp.contextRules.any { it.summaryEn.contains("runtime CSR") })
    }
}
