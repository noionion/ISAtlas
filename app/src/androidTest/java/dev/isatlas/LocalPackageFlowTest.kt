package dev.isatlas

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.isatlas.core.*
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalPackageFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context
    private fun fixture(name: String) = testContext.assets.open(name).use { it.readBytes() }
    private fun base(): LocalCatalogs {
        val a=context.assets
        val packages=OfficialPackages(testContext.assets.open("official-data/riscv-packages.json").bufferedReader().use { it.readText() }) { path ->
            (if (path == "packages/manual.zip") a.open(path) else testContext.assets.open("official-data/$path")).use { it.readBytes() }
        }
        val compiler=a.open("march-extensions.json").bufferedReader().use { CompilerIsaOptions.decode(it.readText()) }
        return packages.catalogs(setOf("aia","debug","spmp"),compiler)
    }

    @Test fun failedEnableRestoresTheCommittedSwitchAndKeepsTheCatalogUnchanged() {
        val id = "org.isatlas.switch-test"
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(fixture("demo-combined.zip"))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) files[entry.name] = zip.readBytes()
            }
        }
        val metadata = Json.parseToJsonElement(files.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject.toMutableMap()
        files.filterKeys { it != "manifest.json" }.forEach { (path, bytes) ->
            files[path] = bytes.toString(Charsets.UTF_8).replace("riscv/demo/", "riscv/switchtest/")
                .replace("\"demo\"", "\"switchtest\"").replace("xdemo", "xswitchtest").toByteArray()
        }
        metadata["id"] = JsonPrimitive(id)
        metadata["namespace"] = JsonPrimitive("switchtest")
        metadata["extensions"] = JsonObject(mapOf("xswitchtest" to JsonArray(listOf(JsonPrimitive("i")))))
        metadata["dependencies"] = JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("org.isatlas.missing-parent"), "version" to JsonPrimitive("1.0.0")))))
        metadata["hashes"] = JsonObject(files.filterKeys { it != "manifest.json" }.mapValues { JsonPrimitive(LocalPackages.sha256(it.value)) })
        files["manifest.json"] = JsonObject(metadata).toString().toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> files.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
        val file = File(context.cacheDir, "switch-rollback-test.zip").apply { writeBytes(output.toByteArray()) }
        lateinit var model: AppViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.waitUntil(30_000) { !model.state.value.loading }
        try {
            compose.runOnUiThread { model.close(); model.showAbout(true); model.showLocalPackages(true); model.previewPackage(Uri.fromFile(file)) }
            compose.waitUntil(20_000) { model.state.value.packagePreview != null || model.state.value.packageMessage != null }
            assertNotNull(model.state.value.packagePreview)
            compose.runOnUiThread { model.confirmPackageImport() }
            compose.waitUntil(20_000) { !model.state.value.packageBusy }
            assertFalse(model.state.value.localPackages.single { it.record.id == id }.active)
            compose.runOnUiThread {
                model.packageMessage(null)
                model.enablePackage(id, true)
                assertEquals(true, model.state.value.packageToggleTargets[id])
                assertFalse(model.state.value.localPackages.single { it.record.id == id }.active)
            }
            compose.waitUntil(20_000) { model.state.value.packageTogglingIds.isEmpty() }
            assertNotNull(model.state.value.packageMessage)
            assertTrue(model.state.value.pack!!.registers.none { it.namespace == "switchtest" })
            compose.onNodeWithTag("local-package-enabled-$id").performScrollTo().assertIsOff().assertIsEnabled()
        } finally {
            if (model.state.value.localPackages.any { it.record.id == id }) {
                compose.runOnUiThread { model.removePackage(id) }
                compose.waitUntil(20_000) { !model.state.value.packageBusy }
            }
            compose.runOnUiThread { model.packageMessage(null); model.showLocalPackages(false); model.showAbout(false) }
            file.delete()
        }
    }

    @Test fun repositoryPersistsEnableDisableAndRejectsBadUpgradeWithoutChangingIndex() {
        val folder=File(context.noBackupFilesDir,"local-package-test-${System.nanoTime()}")
        val store=LocalPackageRepository(context,9,folder)
        val initial=store.load(base())
        assertTrue(initial.catalogs.csr.registers.none { it.namespace=="thead" })
        val bytes=fixture("thead-csr.zip")
        assertEquals("玄铁 CSR 扩展",store.preview(bytes).data.manifest.name)
        assertTrue(store.load(base()).packages.isEmpty())
        val added=store.install(bytes)
        assertTrue(added.packages.single().active)
        assertTrue(Search.query(added.catalogs.csr,"th.sxstatus",64,namespace="thead").isNotEmpty())
        val index=File(folder,"index.json").readBytes()
        try { store.install("bad ZIP".toByteArray()); fail() } catch (_: PackageValidationException) { }
        assertArrayEquals(index,File(folder,"index.json").readBytes())
        assertFalse(store.setEnabled("org.thead.csr",false).packages.single().active)
        val restarted=LocalPackageRepository(context,9,folder)
        assertFalse(restarted.load(base()).packages.single().active)
        assertTrue(restarted.setEnabled("org.thead.csr",true).packages.single().active)
        assertEquals(435,restarted.install(bytes).catalogs.csr.registers.size)
        assertTrue(restarted.remove("org.thead.csr").packages.isEmpty())
        assertTrue(folder.listFiles().orEmpty().none { it.extension=="zip" })
        // Delete only this test's verified, private children; never the user's actual package store.
        folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete()
    }

    @Test fun brokenPackageDoesNotBlockBuiltinDataAndCanBeRemoved() {
        val folder=File(context.noBackupFilesDir,"local-package-test-${System.nanoTime()}")
        val store=LocalPackageRepository(context,9,folder)
        store.load(base()); store.install(fixture("demo-combined.zip"))
        folder.listFiles()!!.single { it.extension=="zip" }.writeText("damaged")
        val restarted=LocalPackageRepository(context,9,folder)
        val result=restarted.load(base())
        assertEquals(434,result.catalogs.csr.registers.size)
        assertNotNull(result.packages.single().problem)
        restarted.install(fixture("demo-combined.zip"))
        assertTrue(LocalPackageRepository(context,9,folder).load(base()).packages.single().active)
        assertTrue(restarted.remove("org.isatlas.demo").packages.isEmpty())
        folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete()
    }

    @Test fun failedAtomicRegistryWriteKeepsOldInstalledVersion() {
        val folder=File(context.noBackupFilesDir,"local-package-test-${System.nanoTime()}")
        val store=LocalPackageRepository(context,9,folder)
        store.load(base()); store.install(fixture("thead-csr.zip"))
        val index=File(folder,"index.json"); val saved=index.readBytes()
        // Simulate an index write failure without changing app/device storage settings.
        val staging=File(folder,"index.json.new"); staging.mkdir(); File(staging,"block").writeText("test")
        try { store.setEnabled("org.thead.csr",false); fail() } catch (_: Exception) { }
        assertArrayEquals(saved,index.readBytes())
        File(staging,"block").delete(); staging.delete()
        assertTrue(LocalPackageRepository(context,9,folder).load(base()).packages.single().active)
        store.remove("org.thead.csr")
        folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete()
    }

    @Test fun aboutImportPreviewCancellationThenQueriesToggleAndUninstall() {
        lateinit var model: AppViewModel
        compose.runOnUiThread {
            model=ViewModelProvider(compose.activity)[AppViewModel::class.java]
            model.showSources(false); model.showLocalPackages(false); model.showAbout(false); model.close(); model.page(0)
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("catalog-tab-CSR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("关于 ISAtlas").performClick()
        compose.onNodeWithTag("about-local-packages").performScrollTo().performClick()
        compose.onNodeWithTag("local-packages-screen").assertIsDisplayed()
        val file=File(context.cacheDir,"local-package-ui.zip").apply { writeBytes(fixture("demo-combined.zip")) }
        compose.runOnUiThread { model.previewPackage(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.packagePreview!=null || model.state.value.packageMessage!=null }
        compose.onNodeWithTag("local-package-preview").assertIsDisplayed()
        assertTrue(model.state.value.pack!!.registers.none { it.namespace=="demo" })
        compose.onNodeWithText("取消").performClick()
        assertTrue(model.state.value.pack!!.registers.none { it.namespace=="demo" })
        compose.runOnUiThread { model.previewPackage(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.packagePreview!=null }
        compose.onNodeWithTag("local-package-confirm").performClick()
        compose.waitUntil(20_000) { model.state.value.localPackages.any { it.record.id=="org.isatlas.demo" && it.active } }
        assertTrue(model.state.value.results.none { it.register.name=="not-real" })
        compose.runOnUiThread { model.showLocalPackages(false); model.showAbout(false); model.namespace("demo"); model.query("demo.control") }
        compose.onNode(hasText("demo.control") and hasText("M · CSR")).performClick()
        compose.onNodeWithTag("register-diagram").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回搜索").performClick()
        compose.runOnUiThread { model.page(1); model.query("0x00c5850b") }
        assertTrue(model.state.value.instructionResults.hits.any { it.instruction.name=="demo.add" })
        compose.runOnUiThread { model.showAbout(true); model.showLocalPackages(true) }
        compose.onNodeWithTag("local-package-enabled-org.isatlas.demo").performScrollTo().performClick()
        compose.waitUntil(20_000) { model.state.value.localPackages.none { it.record.id=="org.isatlas.demo" && it.active } }
        compose.onNodeWithTag("local-package-progress").assertDoesNotExist()
        compose.onNodeWithTag("local-package-message").assertDoesNotExist()
        compose.onNodeWithText("选择本地数据包").assertIsEnabled()
        compose.onNodeWithText("已停用").assertDoesNotExist()
        assertTrue(model.state.value.pack!!.registers.none { it.namespace=="demo" })
        compose.onNodeWithTag("local-package-enabled-org.isatlas.demo").performScrollTo().performClick()
        compose.waitUntil(20_000) { model.state.value.localPackages.any { it.record.id=="org.isatlas.demo" && it.active } }
        compose.onNodeWithTag("local-package-progress").assertDoesNotExist()
        compose.onNodeWithTag("local-package-message").assertDoesNotExist()
        compose.onNodeWithText("已启用").assertDoesNotExist()
        compose.onNodeWithTag("local-package-remove-org.isatlas.demo").performScrollTo().performClick()
        compose.onNodeWithTag("local-package-remove-confirm").performClick()
        compose.waitUntil(20_000) { model.state.value.localPackages.none { it.record.id=="org.isatlas.demo" } }
        compose.runOnUiThread { model.showLocalPackages(false); model.showAbout(false); model.namespace(null); model.query(""); model.page(0) }
        file.delete()
    }
}
