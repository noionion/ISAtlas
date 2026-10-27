package dev.isatlas.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

fun officialPackages() = OfficialPackages(CatalogFilesTest::class.java.getResource("/riscv-packages.json")!!.readText()) {
    CatalogFilesTest::class.java.getResource("/$it")!!.readBytes()
}
fun installedCatalog(): DataPack = officialPackages().catalogs(setOf("aia", "debug", "spmp"),
    CompilerIsaOptions.decode(CatalogFilesTest::class.java.getResource("/march-extensions.json")!!.readText())).csr

class CatalogFilesTest {
    private val sample = Catalog.decode(javaClass.getResource("/riscv-manual-sample.json")!!.readText())
    private fun manifest(files: List<String>): String {
        val metadata = Json.parseToJsonElement(Json.encodeToString(sample)).jsonObject.filterKeys { it != "registers" }
        return JsonObject(metadata + ("files" to JsonArray(files.map(::JsonPrimitive)))).toString()
    }

    @Test fun `split catalog preserves CSR fields and metadata`() {
        val actual = Catalog.decodeFiles(manifest(listOf("manual-sample.json"))) { Json.encodeToString(sample.registers.first()) }
        assertEquals(sample.copy(registers = listOf(sample.registers.first())), actual)
        assertEquals(434, installedCatalog().registers.size)
    }

    @Test fun `unsafe paths and duplicate references fail before reading`() {
        for (files in listOf(listOf("../escape.json"), listOf("one.json", "one.json"), emptyList())) {
            assertThrows(IllegalArgumentException::class.java) {
                Catalog.decodeFiles(manifest(files)) { error("must not read") }
            }
        }
    }

    @Test fun `duplicate identities across different files are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Catalog.decodeFiles(manifest(listOf("a.json", "b.json"))) { Json.encodeToString(sample.registers.first()) }
        }
    }
}
