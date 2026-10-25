package dev.isatlas

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.isatlas.core.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class UpdateFlowTest {
    @get:Rule val compose = createComposeRule()
    private val release = AppRelease("v0.4.0", "0.4.0", "## 新版功能\n- 更新日志可见\n- 可在后台下载并继续查询",
        "${AppUpdates.REPOSITORY}/releases/download/v0.4.0/ISAtlas-0.4.0.apk", 7_000_000, "a".repeat(64))

    private fun response(version: String = "0.4.0") = """{
        "tag_name":"v$version","draft":false,"prerelease":false,"body":"功能更新",
        "assets":[{"name":"ISAtlas-$version.apk","state":"uploaded","size":7000000,
        "browser_download_url":"${AppUpdates.REPOSITORY}/releases/download/v$version/ISAtlas-$version.apk"}]}"""

    @Test fun officialFailureStillChecksMirrorConcurrently() = runBlocking {
        val started = CountDownLatch(2)
        val client = UpdateClient { url ->
            started.countDown()
            check(started.await(3, TimeUnit.SECONDS)) { "Sources were not started concurrently" }
            if (url.startsWith("https://api.github.com")) throw IOException("DNS blocked")
            response()
        }
        assertEquals("0.4.0", client.check("0.3.0")?.version)
    }

    @Test fun staleMirrorCannotHideNewerOfficialVersionAndTotalFailureIsAnError() = runBlocking {
        val client = UpdateClient { url -> response(if (url.startsWith("https://api.github.com")) "0.10.0" else "0.4.0") }
        assertEquals("0.10.0", client.check("0.3.0")?.version)
        assertTrue(runCatching { UpdateClient { throw IOException("offline") }.check("0.3.0") }.isFailure)
        assertNull(UpdateClient { response("0.3.0") }.check("0.3.0"))
    }

    @Test fun manualCheckDuringAutomaticCheckStillReportsItsResult() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("update-test-coalescing", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val started = CountDownLatch(2)
        val finish = CountDownLatch(1)
        val client = UpdateClient {
            started.countDown()
            check(finish.await(3, TimeUnit.SECONDS))
            response("0.3.0")
        }
        val installed = PackageInfo().apply { versionName = "0.3.0"; versionCode = 3 }
        val repository = UpdateRepository(context, prefs, installed, client)
        try {
            repository.check(manual = false)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            repository.check(manual = true)
            finish.countDown()
            await { !repository.state.value.checking }
            assertEquals("已是最新版本 0.3.0", repository.state.value.message)
        } finally { finish.countDown(); repository.pause(); prefs.edit().clear().commit() }
    }

    @Test fun badgesNotesSkipAndDownloadDoNotBlockUnderlyingQuery() {
        var update by mutableStateOf(UpdateState(release = release))
        var query by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                Column {
                    BadgedBox(badge = { if (update.hasUpdate) UpdateBadge("home-update-badge") }) { Icon(Icons.Default.Info, null) }
                    OutlinedTextField(query, { query = it }, label = { Text("查询") })
                    Box(Modifier.weight(1f)) {
                        AboutScreen(null, {}, update) { update = update.copy(prompt = UpdatePrompt.RELEASE) }
                    }
                }
                UpdatePromptDialog(update, onDismiss = { update = update.copy(prompt = null) },
                    onSkip = { update = update.copy(skipped = true, prompt = null) },
                    onDownload = { update = update.copy(stage = UpdateStage.DOWNLOADING, prompt = null, progress = 12) },
                    onInstall = {})
            }
        }
        compose.onNodeWithTag("home-update-badge").assertExists()
        compose.onNodeWithTag("about-update-badge", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("about-check-update").performClick()
        compose.onNodeWithText("发现新版本 0.4.0").assertIsDisplayed()
        compose.onNodeWithText("新版功能\n- 更新日志可见\n- 可在后台下载并继续查询").assertIsDisplayed()
        compose.onNodeWithTag("update-skip").performClick()
        compose.onNodeWithTag("update-dialog").assertDoesNotExist()
        compose.onNodeWithText("发现 0.4.0 · 已跳过自动提醒").assertExists()
        compose.onNodeWithTag("about-check-update").performClick()
        compose.onNodeWithTag("update-download").performClick()
        compose.onNodeWithTag("update-dialog").assertDoesNotExist()
        compose.onNodeWithText("后台下载中 · 12%").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("mstatus.MIE")
        compose.runOnIdle { assertEquals("mstatus.MIE", query) }
    }

    @Test fun finishedDownloadOffersInstallAndCanBeDeferred() {
        var update by mutableStateOf(UpdateState(release = release, stage = UpdateStage.READY, prompt = UpdatePrompt.INSTALL))
        var installCount = 0
        compose.setContent { MaterialTheme {
            UpdatePromptDialog(update, onDismiss = { update = update.copy(prompt = null) }, onSkip = {}, onDownload = {},
                onInstall = { installCount++; update = update.copy(prompt = null) })
        } }
        compose.onNodeWithText("0.4.0 已准备好安装").assertIsDisplayed()
        compose.onNodeWithTag("update-skip").assertDoesNotExist()
        compose.onNodeWithText("稍后安装").performClick()
        compose.runOnIdle { assertEquals(0, installCount); update = update.copy(prompt = UpdatePrompt.INSTALL) }
        compose.onNodeWithTag("update-install").performClick()
        compose.runOnIdle { assertEquals(1, installCount) }
    }

    @Test fun readyNoticeDoesNotInterruptQueryAndRequiresAnInstallClick() {
        var update by mutableStateOf(UpdateState(release = release, stage = UpdateStage.READY, readyNotice = true))
        var query by mutableStateOf("")
        var installs = 0
        var hideKeyboard: () -> Unit = {}
        var rootView: android.view.View? = null
        compose.setContent { MaterialTheme {
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            val view = androidx.compose.ui.platform.LocalView.current
            SideEffect { hideKeyboard = { keyboard?.hide() }; rootView = view }
            Box(Modifier.fillMaxSize()) {
                OutlinedTextField(query, { query = it }, label = { Text("查询") })
                UpdateReadyNotice(update, onDismiss = { update = update.copy(readyNotice = false) }, onInstall = { installs++ })
            }
        } }
        compose.onNodeWithTag("update-dialog").assertDoesNotExist()
        compose.onNodeWithTag("update-ready-notice").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("mstatus.MIE")
        compose.runOnIdle { assertEquals("mstatus.MIE", query); assertEquals(0, installs) }
        // Settle the platform IME animation before clicking a newly re-created bottom action.
        compose.runOnIdle { hideKeyboard() }
        compose.waitUntil(5_000) {
            rootView?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom } == 0
        }
        compose.onNodeWithContentDescription("稍后安装").performClick()
        compose.onNodeWithTag("update-ready-notice").assertDoesNotExist()
        compose.runOnIdle { assertTrue(update.hasUpdate); update = update.copy(readyNotice = true) }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("update-notice-install").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag("update-notice-install").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { installs == 1 }
    }

    @Test fun skipPersistsAcrossRepositoryRecreationAndManualCheckCanOverrideIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("update-test-skip", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val installed = PackageInfo().apply { versionName = "0.3.0"; versionCode = 3 }
        val client = UpdateClient { response() }
        val first = UpdateRepository(context, prefs, installed, client)
        try {
            first.check(manual = true)
            await { !first.state.value.checking }
            assertEquals(UpdatePrompt.RELEASE, first.state.value.prompt)
            first.skip()
            val restored = UpdateRepository(context, prefs, installed, client)
            try {
                restored.resume()
                delay(200)
                assertTrue(restored.state.value.hasUpdate)
                assertTrue(restored.state.value.skipped)
                assertNull(restored.state.value.prompt)
                restored.check(manual = true)
                await { !restored.state.value.checking }
                assertEquals(UpdatePrompt.RELEASE, restored.state.value.prompt)
            } finally { restored.pause() }
        } finally { first.pause(); prefs.edit().clear().commit() }
    }

    @Test fun systemDownloadSurvivesBackgroundAndRestoresVerifiedInstallerPrompt() = runBlocking {
        // Use the real signed 0.3.0 release with an isolated older installed-version fixture.
        // No test release or APK is published, and the system installer is never confirmed.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("update-test-transfer", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val installed = PackageInfo().apply { versionName = "0.2.0"; versionCode = 2 }
        val first = UpdateRepository(context, prefs, installed)
        val downloads = context.getSystemService(DownloadManager::class.java)
        var restored: UpdateRepository? = null
        try {
            first.check(manual = true)
            await { !first.state.value.checking }
            assertNotNull("Official and mirror must yield the published APK", first.state.value.release)
            first.download()
            await(30_000) { first.state.value.stage != UpdateStage.CHOOSING }
            assertEquals(UpdateStage.DOWNLOADING, first.state.value.stage)
            first.pause()
            val id = prefs.getLong("downloadId", -1)
            assertTrue(id != -1L)
            await(120_000) {
                downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) in
                        setOf(DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED)
                }
            }
            restored = UpdateRepository(context, prefs, installed)
            restored.resume()
            await(120_000) { restored.state.value.stage in setOf(UpdateStage.READY, UpdateStage.FAILED) }
            assertEquals(restored.state.value.message, UpdateStage.READY, restored.state.value.stage)
            assertNull(restored.state.value.prompt)
            assertTrue(restored.state.value.readyNotice)
            restored.dismissReadyNotice()
            assertFalse(restored.state.value.readyNotice)
            assertTrue(restored.state.value.hasUpdate)
        } finally {
            first.pause()
            restored?.pause()
            val id = prefs.getLong("downloadId", -1)
            if (id != -1L) downloads.remove(id)
            prefs.edit().clear().commit()
        }
    }

    @Test fun repeatedManualCheckAndContinueQueryKeepTheSystemDownloadAlive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("update-test-repeat-check", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val installed = PackageInfo().apply { versionName = "0.3.0"; versionCode = 3 }
        val repository = UpdateRepository(context, prefs, installed)
        try {
            compose.setContent { MaterialTheme {
                val update by repository.state.collectAsState()
                AboutScreen(null, {}, update) { repository.check(manual = true) }
                UpdatePromptDialog(update, repository::dismiss, repository::skip, repository::download, {})
            } }
            repository.resume()
            compose.onNodeWithTag("about-check-update").performScrollTo().performClick()
            await { repository.state.value.prompt == UpdatePrompt.RELEASE }
            compose.onNodeWithTag("update-download").performClick()
            assertTrue(repository.state.value.busy)
            compose.onNodeWithTag("about-check-update").performScrollTo().performClick()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("update-dialog").assertDoesNotExist()
            assertTrue(repository.state.value.hasTransfer)
            val release = repository.state.value.release
            repeat(3) {
                repository.check(manual = true)
                repository.dismiss()
                assertEquals(release, repository.state.value.release)
                assertTrue(repository.state.value.hasTransfer)
            }
            await(120_000) { repository.state.value.stage in setOf(UpdateStage.READY, UpdateStage.FAILED) }
            assertEquals(repository.state.value.message, UpdateStage.READY, repository.state.value.stage)
            assertTrue(repository.state.value.readyNotice)
        } finally {
            repository.pause()
            val id = prefs.getLong("downloadId", -1)
            if (id != -1L) context.getSystemService(DownloadManager::class.java).remove(id)
            prefs.edit().clear().commit()
        }
    }

    @Test fun failedSystemDownloadStillFallsBackAfterCheckingAndDismissing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("update-test-repeat-check-fallback", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val published = requireNotNull(UpdateClient().check("0.3.0"))
        val downloads = context.getSystemService(DownloadManager::class.java)
        val id = downloads.enqueue(DownloadManager.Request(android.net.Uri.parse(
            "${AppUpdates.REPOSITORY}/releases/download/v0.0.0/ISAtlas-missing.apk"))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE))
        prefs.edit().putString("release", published.encode()).putString("taskRelease", published.encode())
            .putLong("downloadId", id).putString("channelOrder", "0,1").putInt("channelPosition", 0).commit()
        val installed = PackageInfo().apply { versionName = "0.3.0"; versionCode = 3 }
        val repository = UpdateRepository(context, prefs, installed)
        try {
            repository.check(manual = true)
            assertEquals(UpdatePrompt.RELEASE, repository.state.value.prompt)
            repository.dismiss()
            repository.resume()
            await(120_000) { repository.state.value.stage in setOf(UpdateStage.READY, UpdateStage.FAILED) }
            assertEquals(1, prefs.getInt("channelPosition", -1))
            assertFalse(repository.state.value.busy)
            assertTrue(repository.state.value.stage == UpdateStage.READY || repository.state.value.message != null)
        } finally {
            repository.pause()
            downloads.remove(id)
            val currentId = prefs.getLong("downloadId", -1)
            if (currentId != -1L) downloads.remove(currentId)
            prefs.edit().clear().commit()
        }
    }

    @Test fun downloadDialogOffersPauseResumeAndCancelInsteadOfSkipOrContinueQuery() {
        var update by mutableStateOf(UpdateState(release = release, stage = UpdateStage.DOWNLOADING,
            progress = 25, prompt = UpdatePrompt.RELEASE))
        var cancels = 0
        var toggles = 0
        compose.setContent { MaterialTheme {
            UpdatePromptDialog(update, { update = update.copy(prompt = null) }, {}, {}, {},
                onCancelDownload = { cancels++; update = update.copy(stage = UpdateStage.IDLE, prompt = null) },
                onToggleDownloadPause = { toggles++; update = update.copy(stage = if (update.stage == UpdateStage.PAUSED)
                    UpdateStage.DOWNLOADING else UpdateStage.PAUSED) })
        } }
        compose.onNodeWithTag("update-transfer-status").assertIsDisplayed().assertTextEquals("后台下载中 · 25%")
        compose.onNodeWithTag("update-skip").assertDoesNotExist()
        compose.onNodeWithText("继续查询").assertDoesNotExist()
        compose.onNodeWithText("稍后").assertDoesNotExist()
        compose.onNodeWithTag("update-pause-resume").assertTextEquals("暂停下载").performClick()
        compose.onNodeWithTag("update-pause-resume").assertTextEquals("继续下载").performClick()
        compose.runOnIdle { assertEquals(2, toggles); update = update.copy(downloadControlPending = true) }
        compose.onNodeWithTag("update-pause-resume").assertIsNotEnabled()
        compose.onNodeWithTag("update-cancel-download").assertIsNotEnabled()
        compose.runOnIdle { update = update.copy(downloadControlPending = false, stage = UpdateStage.VERIFYING) }
        compose.onNodeWithTag("update-pause-resume").assertIsNotEnabled()
        compose.onNodeWithTag("update-cancel-download").performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
        compose.onNodeWithTag("update-dialog").assertDoesNotExist()
    }

    @Test fun pausedTransferRestoresWithoutCheckingAndCancelsAfterTrackSwitch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("update-test-paused-restore", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val task = release.copy(version = "0.5.0-beta.1", tag = "v0.5.0-beta.1", prerelease = true,
            apkUrl = "${AppUpdates.REPOSITORY}/releases/download/v0.5.0-beta.1/ISAtlas-0.5.0-beta.1.apk")
        prefs.edit().putString("taskRelease", task.encode()).putBoolean("downloadPaused", true)
            .putString("updateTrack", "PREVIEW").commit()
        var checks = 0
        val installed = PackageInfo().apply { versionName = "0.3.0"; versionCode = 3 }
        val repository = UpdateRepository(context, prefs, installed, UpdateClient { checks++; response() })
        try {
            repository.resume()
            delay(200)
            assertEquals(UpdateStage.PAUSED, repository.state.value.stage)
            assertNull(repository.state.value.prompt)
            assertTrue(repository.state.value.hasTransfer)
            repository.selectTrack(UpdateTrack.STABLE)
            repository.check(manual = true)
            assertEquals(task, repository.state.value.release)
            assertEquals(UpdatePrompt.RELEASE, repository.state.value.prompt)
            assertEquals(0, checks)
            repository.cancelDownload()
            await { !repository.state.value.downloadControlPending }
            assertEquals(UpdateStage.IDLE, repository.state.value.stage)
            assertFalse(repository.state.value.hasUpdate)
            assertNull(prefs.getString("taskRelease", null))
            assertFalse(prefs.getBoolean("downloadPaused", false))
        } finally { repository.pause(); prefs.edit().clear().commit() }
    }

    @Test fun cancellingChannelSelectionCannotEnqueueALateDownload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("update-test-cancel-selection", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("release", release.encode()).commit()
        val installed = PackageInfo().apply { versionName = "0.3.0"; versionCode = 3 }
        val repository = UpdateRepository(context, prefs, installed)
        try {
            repository.download()
            repository.cancelDownload()
            repository.download() // A second start while stopping must be ignored.
            await { !repository.state.value.downloadControlPending }
            delay(300)
            assertEquals(UpdateStage.IDLE, repository.state.value.stage)
            assertEquals(-1L, prefs.getLong("downloadId", -1))
            assertNull(prefs.getString("taskRelease", null))
            assertNotNull(repository.state.value.release)
            assertFalse(repository.state.value.hasTransfer)
        } finally { repository.pause(); prefs.edit().clear().commit() }
    }

    @Test fun realDownloadCanPauseRestoreContinueAndFinishVerification() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("update-test-pause-transfer", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val installed = PackageInfo().apply { versionName = "0.3.0"; versionCode = 3 }
        val repository = UpdateRepository(context, prefs, installed)
        val downloads = context.getSystemService(DownloadManager::class.java)
        var restored: UpdateRepository? = null
        try {
            repository.check(manual = true)
            await { !repository.state.value.checking }
            assertNotNull(repository.state.value.release)
            repository.download()
            await(30_000) { prefs.getLong("downloadId", -1) != -1L }
            val firstId = prefs.getLong("downloadId", -1)
            repository.toggleDownloadPause()
            await { !repository.state.value.downloadControlPending }
            assertEquals(UpdateStage.PAUSED, repository.state.value.stage)
            assertEquals(-1L, prefs.getLong("downloadId", -1))
            downloads.query(DownloadManager.Query().setFilterById(firstId)).use { assertFalse(it.moveToFirst()) }
            assertTrue(prefs.getBoolean("downloadPaused", false))
            repository.pause()
            restored = UpdateRepository(context, prefs, installed)
            restored.resume()
            delay(200)
            assertEquals(UpdateStage.PAUSED, restored.state.value.stage)
            assertEquals(-1L, prefs.getLong("downloadId", -1))
            restored.check(manual = true)
            assertEquals(UpdatePrompt.RELEASE, restored.state.value.prompt)
            restored.toggleDownloadPause()
            await(120_000) { restored.state.value.stage in setOf(UpdateStage.READY, UpdateStage.FAILED) }
            assertEquals(restored.state.value.message, UpdateStage.READY, restored.state.value.stage)
            assertNotEquals(firstId, prefs.getLong("downloadId", -1))
            assertFalse(prefs.getBoolean("downloadPaused", false))
        } finally {
            repository.pause()
            restored?.pause()
            val id = prefs.getLong("downloadId", -1)
            if (id != -1L) downloads.remove(id)
            prefs.edit().clear().commit()
        }
    }

    @Test fun mismatchedChecksumRetriesChannelsAndNeverOffersInstallation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("update-test-rejection", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val published = requireNotNull(UpdateClient().check("0.2.0"))
        val corruptedMetadata = response(published.version).replace("7000000", published.size.toString())
            .replace("\"state\":\"uploaded\"", "\"state\":\"uploaded\",\"digest\":\"sha256:${"0".repeat(64)}\"")
        val installed = PackageInfo().apply { versionName = "0.2.0"; versionCode = 2 }
        val repository = UpdateRepository(context, prefs, installed, UpdateClient { corruptedMetadata })
        try {
            repository.check(manual = true)
            await { !repository.state.value.checking }
            assertNotNull(repository.state.value.release)
            repository.resume()
            repository.download()
            await(120_000) { repository.state.value.stage in setOf(UpdateStage.READY, UpdateStage.FAILED) }
            assertEquals(UpdateStage.FAILED, repository.state.value.stage)
            assertEquals(1, prefs.getInt("channelPosition", -1))
            assertEquals(-1L, prefs.getLong("downloadId", -1))
            assertNotEquals(UpdatePrompt.INSTALL, repository.state.value.prompt)
        } finally {
            repository.pause()
            val id = prefs.getLong("downloadId", -1)
            if (id != -1L) context.getSystemService(DownloadManager::class.java).remove(id)
            prefs.edit().clear().commit()
        }
    }

    private suspend fun await(timeout: Long = 20_000, condition: () -> Boolean) {
        withTimeout(timeout) { while (!condition()) delay(100) }
    }
}
