package dev.isatlas

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.isatlas.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseUpdateFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    internal fun fixture(version: String = "0.1.0"): ByteArray {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val files = linkedMapOf<String, ByteArray>()
        files["riscv/riscv-packages.json"] = testAssets.open("official-data/riscv-packages.json").use { it.readBytes() }
        listOf("manual", "aia", "debug", "spmp").forEach { id ->
            files["riscv/packages/$id.zip"] = (if (id == "manual") context.assets.open("packages/manual.zip") else
                testAssets.open("official-data/packages/$id.zip")).use { it.readBytes() }
        }
        listOf("march-extensions.json", "licenses/CC-BY-4.0.txt").forEach { name -> files["riscv/$name"] = context.assets.open(name).use { it.readBytes() } }
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("version", version); put("format", "isatlas-official-database")
            put("files", buildJsonArray { files.forEach { (name, bytes) -> add(buildJsonObject {
                put("path", name); put("size", bytes.size); put("sha256", LocalPackages.sha256(bytes))
            }) } })
        }
        files["database.json"] = manifest.toString().toByteArray()
        return ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            files.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        } }.toByteArray()
    }
    private fun release(bytes: ByteArray) = DatabaseRelease("0.1.0", "更新日志",
        "https://github.com/noionion/ISAtlas-data/releases/download/v0.1.0/ISAtlas-data-0.1.0.zip", bytes.size.toLong(), LocalPackages.sha256(bytes))
    private fun response(release: DatabaseRelease) = buildJsonObject {
        put("tag_name", "v${release.version}"); put("draft", false); put("prerelease", false); put("body", release.notes)
        put("assets", buildJsonArray { add(buildJsonObject {
            put("name", "ISAtlas-data-${release.version}.zip"); put("browser_download_url", release.url)
            put("size", release.size); put("digest", "sha256:${release.sha256}")
        }) })
    }.toString()
    private fun await(timeout: Long = 30_000, test: () -> Boolean) {
        val until = android.os.SystemClock.uptimeMillis() + timeout
        while (!test()) { check(android.os.SystemClock.uptimeMillis() < until); android.os.SystemClock.sleep(20) }
    }
    private fun isolated(action: (File, android.content.SharedPreferences) -> Unit) {
        val directory = File(context.cacheDir, "database-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val prefs = context.getSharedPreferences(directory.name, 0)
        try { action(File(directory, "active.zip"), prefs) }
        finally { context.deleteSharedPreferences(directory.name); directory.deleteRecursively() }
    }

    @Test fun validatedDatabasePersistsAcrossRepositoryRecreationAndRejectsCorruption() = isolated { file, prefs ->
        val bytes = fixture()
        val repository = DatabaseRepository(context, prefs, AtomicFile(file))
        try {
            assertNull(repository.load())
            runBlocking { repository.verify(bytes, release(bytes)) }
            assertEquals(DatabaseStage.READY, repository.state.value.stage)
            repository.commit(bytes); repository.applied("0.1.0")
            val saved = file.readBytes()
            try { runBlocking { repository.verify(byteArrayOf(0, 1), release(bytes)) }; fail("Corrupted data accepted") }
            catch (_: IllegalArgumentException) { }
            assertArrayEquals(saved, file.readBytes())
            val restored = DatabaseRepository(context, prefs, AtomicFile(file))
            try {
                val database = restored.load()!!
                assertEquals("0.1.0", restored.state.value.version)
                assertEquals(434, database.packages.catalogs(setOf("aia", "debug", "spmp"), database.compiler).csr.registers.size)
                try { runBlocking { restored.verify(bytes, release(bytes)) }; fail("Reinstall/downgrade accepted") }
                catch (_: IllegalArgumentException) { }
            } finally { restored.close() }
        } finally { repository.close() }
    }

    @Test fun mirrorCanFindReleaseWhenOfficialFailsAndRepeatedCheckKeepsReadyCandidate() = isolated { file, prefs ->
        val bytes = fixture()
        val release = release(bytes)
        val repository = DatabaseRepository(context, prefs, AtomicFile(file)) { url ->
            if (url.startsWith("https://api.github.com/")) error("blocked DNS") else response(release)
        }
        try {
            repository.check()
            await { !repository.state.value.checking }
            assertEquals(release, repository.state.value.release)
            runBlocking { repository.verify(bytes, release) }
            repository.dismiss(); repository.check()
            assertTrue(repository.state.value.prompt)
            assertEquals(DatabaseStage.READY, repository.state.value.stage)
            assertArrayEquals(bytes, repository.candidate()!!.first)
            repository.cancel()
            await { repository.state.value.stage == DatabaseStage.IDLE }
            assertNull(repository.candidate())
            assertFalse(file.exists())
        } finally { repository.close() }
    }

    @Test fun unreadableStoredDatabaseFallsBackWithoutDeletingIt() = isolated { file, prefs ->
        file.writeBytes(byteArrayOf(1, 2, 3))
        val repository = DatabaseRepository(context, prefs, AtomicFile(file))
        try {
            assertNull(repository.load())
            assertTrue(repository.state.value.message!!.contains("内置 Manual"))
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        } finally { repository.close() }
    }

    @Test fun publishedReleaseDownloadsWithoutBlockingQueriesAndSurvivesRecreation() = isolated { file, prefs ->
        val repository = DatabaseRepository(context, prefs, AtomicFile(file))
        try {
            repository.check()
            await { !repository.state.value.checking }
            assertNotNull(repository.state.value.summary, repository.state.value.release)
            repository.download()
            // Re-checking while downloading opens status without replacing/cancelling the task.
            repository.check()
            assertEquals(DatabaseStage.DOWNLOADING, repository.state.value.stage)
            val compiler = context.assets.open("march-extensions.json").bufferedReader().use { CompilerIsaOptions.decode(it.readText()) }
            val builtin = OfficialPackages(context.assets.open("riscv-packages.json").bufferedReader().use { it.readText() }) {
                context.assets.open(it).use { stream -> stream.readBytes() }
            }.catalogs(emptySet(), compiler)
            assertTrue(Search.query(builtin.csr, "mstatus", 64, null, null, emptySet()).isNotEmpty())
            await(180_000) { repository.state.value.stage == DatabaseStage.READY || repository.state.value.stage == DatabaseStage.IDLE }
            assertEquals(repository.state.value.summary, DatabaseStage.READY, repository.state.value.stage)
            val (bytes, database) = repository.candidate()!!
            repository.commit(bytes); repository.applied(database.version)
            val restored = DatabaseRepository(context, prefs, AtomicFile(file))
            try {
                assertEquals(database.version, restored.load()!!.version)
                restored.check()
                await { !restored.state.value.checking }
                assertNull(restored.state.value.release)
            } finally { restored.close() }
        } finally { repository.cancel(); await { repository.state.value.stage == DatabaseStage.IDLE }; repository.close() }
    }
}
