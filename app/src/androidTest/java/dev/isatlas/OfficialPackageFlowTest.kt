package dev.isatlas

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.isatlas.core.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OfficialPackageFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun packages(): OfficialPackages {
        val assets = context.assets
        return OfficialPackages(assets.open("riscv-packages.json").bufferedReader().use { it.readText() }) { assets.open(it).use { stream -> stream.readBytes() } }
    }
    private fun compiler() = context.assets.open("march-extensions.json").bufferedReader().use { CompilerIsaOptions.decode(it.readText()) }

    @Test fun officialSwitchesWithdrawSupplementsPersistAndKeepOtherControlsEnabled() {
        lateinit var model: AppViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.waitUntil(30_000) { !model.state.value.loading }
        val original = model.state.value.enabledOfficialPackages.toSet()
        val orientation = compose.activity.requestedOrientation
        fun toggle(id: String, enabled: Boolean) {
            if ((id in model.state.value.enabledOfficialPackages) != enabled) {
                compose.onNodeWithTag("official-package-enabled-$id").performScrollTo().performClick()
                compose.onNodeWithTag("local-package-import").assertIsEnabled()
                compose.onNodeWithTag("local-package-progress").assertDoesNotExist()
                compose.waitUntil(20_000) { (id in model.state.value.enabledOfficialPackages) == enabled && model.state.value.packageTogglingIds.isEmpty() }
            }
        }
        try {
            compose.runOnUiThread { model.close(); model.showAbout(true); model.showLocalPackages(true) }
            compose.onNodeWithText("基础包").assertIsDisplayed()
            compose.onNodeWithTag("official-package-enabled-manual").assertDoesNotExist()
            toggle("aia", false)
            toggle("debug", false)
            toggle("spmp", true)
            assertTrue(model.state.value.pack!!.registers.none { it.namespace == "aia" || it.namespace == "debug" })
            assertTrue(model.state.value.pack!!.registers.single { it.name == "dcsr" }.fields.isEmpty())
            assertTrue(model.state.value.pack!!.registers.single { it.name == "sireg2" }.fields.any { it.view == "spmpcfg" })
            assertTrue(model.state.value.pack!!.registers.single { it.name == "siselect" }.fields.single().values.keys == (0x100..0x13f).map(Int::toString).toSet())
            assertNull(model.state.value.packageMessage)
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            compose.activityRule.scenario.recreate()
            compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
            compose.waitUntil(30_000) { !model.state.value.loading }
            assertEquals(setOf("spmp"), model.state.value.enabledOfficialPackages)
            compose.onNodeWithTag("official-package-enabled-aia").performScrollTo().assertIsOff()
            toggle("aia", true)
            toggle("debug", true)
            assertTrue(model.state.value.pack!!.registers.single { it.name == "dcsr" }.fields.isNotEmpty())
            assertEquals(setOf("aia", "spmp"), model.state.value.pack!!.registers.single { it.name == "siselect" }.sources.map { it.namespace }.toSet())
        } finally {
            for (id in listOf("aia", "debug", "spmp")) {
                compose.runOnUiThread { model.enablePackage("official:$id", id in original) }
                compose.waitUntil(20_000) { model.state.value.packageTogglingIds.isEmpty() }
            }
            compose.runOnUiThread {
                compose.activity.requestedOrientation = orientation
                model.showLocalPackages(false); model.showAbout(false); model.namespace(null); model.query("")
            }
        }
    }

    @Test fun rapidRequestsUpdateThumbImmediatelyAndPersistTheLastChoice() {
        lateinit var model: AppViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.waitUntil(30_000) { !model.state.value.loading }
        val original = "aia" in model.state.value.enabledOfficialPackages
        try {
            compose.runOnUiThread {
                model.close(); model.showAbout(true); model.showLocalPackages(true)
                model.enablePackage("official:aia", !original)
                assertEquals(!original, model.state.value.packageToggleTargets["official:aia"])
                assertEquals(original, "aia" in model.state.value.enabledOfficialPackages)
                model.enablePackage("official:aia", original)
                model.enablePackage("official:aia", !original)
                assertEquals(!original, model.state.value.packageToggleTargets["official:aia"])
            }
            compose.onNodeWithTag("official-package-enabled-aia").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("local-package-progress").assertDoesNotExist()
            compose.waitUntil(20_000) { model.state.value.packageTogglingIds.isEmpty() }
            assertEquals(!original, "aia" in model.state.value.enabledOfficialPackages)
            assertEquals(!original, "aia" in context.getSharedPreferences("atlas", 0).getStringSet("officialPackages", emptySet())!!)
            compose.runOnUiThread {
                repeat(10) { model.enablePackage("official:aia", if (it % 2 == 0) !original else original) }
                assertEquals(original, model.state.value.packageToggleTargets["official:aia"])
            }
            compose.waitUntil(20_000) { model.state.value.packageTogglingIds.isEmpty() }
            assertEquals(original, "aia" in model.state.value.enabledOfficialPackages)
            assertNull(model.state.value.packageMessage)
        } finally {
            compose.runOnUiThread { model.enablePackage("official:aia", original) }
            compose.waitUntil(20_000) { model.state.value.packageTogglingIds.isEmpty() }
            compose.runOnUiThread { model.showLocalPackages(false); model.showAbout(false) }
        }
    }

    @Test fun builtinSelectionRebasesLocalPackagesWithoutRewritingOrDisablingThem() {
        val folder = File(context.noBackupFilesDir, "official-package-test-${System.nanoTime()}")
        try {
            val packages = packages()
            val compiler = compiler()
            val store = LocalPackageRepository(context, 9, folder)
            store.load(packages.catalogs(setOf("aia", "debug", "spmp"), compiler))
            val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("thead-csr.zip").use { it.readBytes() }
            store.install(bytes)
            val index = File(folder, "index.json").readBytes()
            val baseOnly = store.rebase(packages.catalogs(emptySet(), compiler))
            assertTrue(baseOnly.packages.single().active)
            assertEquals(403, baseOnly.catalogs.csr.registers.size)
            assertTrue(Search.query(baseOnly.catalogs.csr, "th.sxstatus", 64).isNotEmpty())
            assertEquals(435, store.rebase(packages.catalogs(setOf("aia", "debug", "spmp"), compiler)).catalogs.csr.registers.size)
            assertArrayEquals(index, File(folder, "index.json").readBytes())
        } finally {
            folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete()
        }
    }
}
