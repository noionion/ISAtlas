package dev.isatlas

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.isatlas.core.DatabaseRelease
import dev.isatlas.core.LocalPackages
import dev.isatlas.core.AppVersion
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DatabaseUpdateUiFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun verifiedDownloadAutomaticallyAppliesAndPreservesBaseAndLocalPacks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val active = File(context.noBackupFilesDir, "official-database.zip")
        lateinit var model: AppViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.waitUntil(30_000) { !model.state.value.loading && !model.databaseUpdates.state.value.checking }
        val repository = model.databaseUpdates
        org.junit.Assume.assumeTrue(repository.state.value.stage == DatabaseStage.IDLE)
        val originalBytes = if (active.exists()) active.readBytes() else null
        val parts = AppVersion.parse(repository.state.value.version ?: "0.0.0")!!.numbers
        val version = "${parts[0]}.${parts[1]}.${parts[2] + 1}"
        val originalEnabled = model.state.value.enabledOfficialPackages
        val local = model.state.value.localPackages.map { it.record }
        val bytes = DatabaseUpdateFlowTest().fixture(version)
        val release = DatabaseRelease(version, "数据库更新测试", "https://github.com/noionion/ISAtlas-data/releases/download/v$version/ISAtlas-data-$version.zip",
            bytes.size.toLong(), LocalPackages.sha256(bytes))
        try {
            compose.runOnUiThread { model.close(); model.showAbout(true) }
            runBlocking { repository.verify(bytes, release) }
            compose.waitUntil(30_000) { repository.state.value.version == version && !model.state.value.packageBusy }
            compose.onNodeWithTag("database-apply").assertDoesNotExist()
            compose.onNodeWithTag("database-update-dialog").assertDoesNotExist()
            assertEquals(listOf("manual", "aia", "debug", "spmp"), model.state.value.officialPackages.map { it.id })
            assertEquals(originalEnabled, model.state.value.enabledOfficialPackages)
            assertEquals(local, model.state.value.localPackages.map { it.record })
            compose.onNodeWithTag("about-database-version").assertTextEquals(version)
            compose.runOnUiThread { model.showLocalPackages(true) }
            if ("debug" !in model.state.value.enabledOfficialPackages)
                compose.onNodeWithTag("official-package-enabled-debug").performScrollTo().performClick()
            compose.waitUntil(30_000) { model.state.value.packageTogglingIds.isEmpty() && "debug" in model.state.value.enabledOfficialPackages }
            assertTrue(model.state.value.pack!!.registers.single { it.name == "dcsr" }.fields.isNotEmpty())
            compose.runOnUiThread { model.load() }
            compose.waitUntil(30_000) { !model.state.value.loading }
            assertEquals(version, repository.state.value.version)
            assertTrue("debug" in model.state.value.enabledOfficialPackages)
            assertEquals(local, model.state.value.localPackages.map { it.record })
        } finally {
            for (id in model.state.value.enabledOfficialPackages - originalEnabled) {
                compose.runOnUiThread { model.enablePackage("official:$id", false) }
                compose.waitUntil(30_000) { model.state.value.packageTogglingIds.isEmpty() }
            }
            val atomic = AtomicFile(active)
            if (originalBytes == null) atomic.delete() else {
                val output = atomic.startWrite()
                try { output.write(originalBytes); atomic.finishWrite(output) }
                catch (error: Exception) { atomic.failWrite(output); throw error }
            }
            compose.runOnUiThread { repository.dismiss(); model.showLocalPackages(false); model.showAbout(false); model.load() }
            compose.waitUntil(30_000) { !model.state.value.loading }
        }
    }
}
