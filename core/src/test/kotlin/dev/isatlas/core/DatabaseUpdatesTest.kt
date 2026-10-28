package dev.isatlas.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DatabaseUpdatesTest {
    private fun asset(path: String) = (javaClass.getResource("/official-data/$path") ?: javaClass.getResource("/$path"))!!.readBytes()
    private fun files(): MutableMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        put("riscv/riscv-packages.json", asset("riscv-packages.json"))
        put("riscv/march-extensions.json", asset("march-extensions.json"))
        put("riscv/licenses/CC-BY-4.0.txt", asset("licenses/CC-BY-4.0.txt"))
        listOf("manual", "aia", "debug", "spmp").forEach { put("riscv/packages/$it.zip", asset("packages/$it.zip")) }
        val entries = map { (name, data) -> buildJsonObject {
            put("path", name); put("size", data.size); put("sha256", LocalPackages.sha256(data))
        } }
        put("database.json", buildJsonObject {
            put("schemaVersion", 1); put("format", "isatlas-official-database"); put("version", "0.1.0")
            put("files", JsonArray(entries))
        }.toString().toByteArray())
    }
    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> files.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
    }.toByteArray()
    private fun release(bytes: ByteArray) = DatabaseRelease("0.1.0", "notes",
        "https://github.com/noionion/ISAtlas-data/releases/download/v0.1.0/ISAtlas-data-0.1.0.zip", bytes.size.toLong(), LocalPackages.sha256(bytes))

    @Test fun `distribution retains independent packages and all instructions`() {
        val bytes = zip(files())
        val result = DatabaseUpdates.decode(bytes, release(bytes))
        assertEquals("0.1.0", result.version)
        assertEquals(402, result.packages.catalogs(emptySet(), result.compiler).csr.registers.size)
        val all = result.packages.catalogs(setOf("aia", "debug", "spmp"), result.compiler)
        assertEquals(434, all.csr.registers.size)
        assertEquals(2012, all.csr.registers.sumOf { it.fields.size })
        assertEquals(1287, all.instructions.instructions.size)
    }
    @Test fun `corruption checksum mismatch unexpected file and unsafe paths are rejected`() {
        val files = files()
        val original = zip(files)
        assertThrows(IllegalArgumentException::class.java) { DatabaseUpdates.decode(original, release(original).copy(sha256 = "0".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { DatabaseUpdates.decode(original, release(original).copy(size = 1)) }
        for (path in listOf("../escape", "/absolute", "riscv/extra", "riscv/packages/manual.zip")) {
            val bad = zip(files + (path to byteArrayOf(1)))
            assertThrows(IllegalArgumentException::class.java) { DatabaseUpdates.decode(bad) }
        }
    }
    @Test fun `unsupported format and wrong release version fail before activation`() {
        val files = files()
        val meta = Json.parseToJsonElement(files.getValue("database.json").toString(Charsets.UTF_8)).jsonObject
        val bad = zip(files + ("database.json" to JsonObject(meta + ("schemaVersion" to JsonPrimitive(2))).toString().toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { DatabaseUpdates.decode(bad) }
        val original = zip(files)
        assertThrows(IllegalArgumentException::class.java) { DatabaseUpdates.decode(original, release(original).copy(version = "0.2.0",
            url = "https://github.com/noionion/ISAtlas-data/releases/download/v0.2.0/ISAtlas-data-0.2.0.zip")) }
    }
    @Test fun `release restricts official asset URLs and requires a published sha256`() {
        val bytes = zip(files())
        val expected = release(bytes)
        fun body(url: String = expected.url, digest: String = "sha256:${expected.sha256}", pre: Boolean = false) = buildJsonObject {
            put("tag_name", "v0.1.0"); put("draft", false); put("prerelease", pre); put("body", "notes")
            put("assets", buildJsonArray { add(buildJsonObject {
                put("name", "ISAtlas-data-0.1.0.zip"); put("browser_download_url", url); put("size", bytes.size); put("digest", digest)
            }) })
        }.toString()
        assertEquals(expected, DatabaseUpdates.release(body()))
        assertNull(DatabaseUpdates.release(body(pre = true)))
        assertThrows(IllegalArgumentException::class.java) { DatabaseUpdates.release(body(url = "https://example.com/payload.zip")) }
        assertThrows(IllegalArgumentException::class.java) { DatabaseUpdates.release(body(digest = "missing")) }
    }
}
