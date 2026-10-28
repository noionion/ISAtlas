package dev.isatlas

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assume.assumeTrue

/** Optional-spec UI checks run against an explicitly built full candidate APK. */
internal class OfficialPackageTestSession(private val compose: AndroidComposeTestRule<*, MainActivity>, required: Set<String>) : AutoCloseable {
    private lateinit var model: AppViewModel
    private val original: Set<String>
    init {
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.waitUntil(30_000) { !model.state.value.loading }
        assumeTrue("Build a full candidate APK with Test-Data.ps1 to run optional-spec UI checks",
            model.state.value.officialPackages.map { it.id }.containsAll(required))
        original = model.state.value.enabledOfficialPackages.toSet()
        compose.runOnUiThread {
            model.close(); model.showAbout(false); model.namespace(null); model.clearExtensions(); model.query(""); model.page(0)
            required.forEach { model.enablePackage("official:$it", true) }
        }
        compose.waitUntil(30_000) { model.state.value.packageTogglingIds.isEmpty() }
    }
    override fun close() {
        val available = model.state.value.officialPackages.filterNot { it.required }.map { it.id }
        compose.runOnUiThread {
            available.forEach { model.enablePackage("official:$it", it in original) }
        }
        compose.waitUntil(30_000) { model.state.value.packageTogglingIds.isEmpty() }
        compose.runOnUiThread { model.close(); model.showAbout(false); model.namespace(null); model.query("") }
    }
}
