package dev.isatlas

import android.content.Context
import android.content.pm.PackageInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.isatlas.core.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class UpdateTrackTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun prefs(name: String) = context.getSharedPreferences("update-track-test-$name", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private fun installed(version: String = "0.3.0") = PackageInfo().apply { versionName = version; versionCode = 3 }
    private fun response(version: String, preview: Boolean = false) = """{
        "tag_name":"v$version","draft":false,"prerelease":$preview,"body":"测试更新日志",
        "assets":[{"name":"ISAtlas-$version.apk","state":"uploaded","size":123,
        "browser_download_url":"${AppUpdates.REPOSITORY}/releases/download/v$version/ISAtlas-$version.apk"}]}"""
    private fun release(version: String = "0.5.0-beta.1") = AppRelease("v$version", version, "", "${AppUpdates.REPOSITORY}/releases/download/v$version/ISAtlas-$version.apk", 123)
    private suspend fun await(condition: () -> Boolean) { withTimeout(15_000) { while (!condition()) delay(50) } }

    @Test fun explicitTrackControlsBothOfficialAndMirrorEndpoints() = runBlocking {
        val urls = ConcurrentLinkedQueue<String>()
        val client = UpdateClient { url -> urls += url; "[${response("0.4.0")},${response("0.5.0-beta.1", true)}]" }
        assertEquals("0.4.0", client.check("0.3.0", UpdateTrack.STABLE)!!.version)
        assertEquals(2, urls.size)
        assertTrue(urls.all { it.endsWith("/latest") })
        urls.clear()
        assertEquals("0.5.0-beta.1", client.check("0.3.0", UpdateTrack.PREVIEW)!!.version)
        assertEquals(2, urls.size)
        assertTrue(urls.all { it.endsWith("?per_page=30") })
    }

    @Test fun aboutPickerPersistsChoiceWithoutDownloadingOrInstalling() = runBlocking {
        val prefs = prefs("picker")
        val client = UpdateClient { "[${response("0.4.0")},${response("0.5.0-beta.1", true)}]" }
        val repository = UpdateRepository(context, prefs, installed(), client)
        try {
            compose.setContent { MaterialTheme {
                val state by repository.state.collectAsState()
                Box(Modifier.fillMaxSize()) { AboutScreen(null, {}, state, repository::selectTrack) { repository.check(true) } }
            } }
            compose.onNodeWithTag("about-update-track").performScrollTo().performClick()
            compose.onNodeWithTag("update-track-STABLE").assertIsSelected().performClick()
            assertEquals("STABLE", prefs.getString("updateTrack", null))
            compose.onNodeWithTag("about-update-track").performClick()
            compose.onNodeWithTag("update-track-PREVIEW").performClick()
            await { !repository.state.value.checking && repository.state.value.release != null }
            assertEquals(UpdateTrack.PREVIEW, repository.state.value.track)
            assertEquals("0.5.0-beta.1", repository.state.value.release!!.version)
            assertEquals(UpdateStage.IDLE, repository.state.value.stage)
            assertEquals(-1, prefs.getLong("downloadId", -1))
            assertEquals(InstallPhase.IDLE, repository.state.value.installation.phase)
            val recreated = UpdateRepository(context, prefs, installed("0.4.0"), client)
            assertEquals(UpdateTrack.PREVIEW, recreated.state.value.track)
            recreated.pause()
        } finally { repository.pause(); prefs.edit().clear().commit() }
    }

    @Test fun switchingToStableRemovesPreviewCacheAndRedDot() = runBlocking {
        val prefs = prefs("cache")
        prefs.edit().putString("updateTrack", "PREVIEW").putString("release", release().encode()).putLong("lastCheck", System.currentTimeMillis()).commit()
        val repository = UpdateRepository(context, prefs, installed(), UpdateClient { response("0.3.0") })
        try {
            assertTrue(repository.state.value.hasUpdate)
            repository.selectTrack(UpdateTrack.STABLE)
            assertFalse(repository.state.value.hasUpdate)
            assertNull(repository.state.value.prompt)
            await { !repository.state.value.checking }
            assertNull(prefs.getString("release", null))
            assertEquals("STABLE", prefs.getString("updateTrack", null))
            val restored = UpdateRepository(context, prefs, installed("0.5.0-beta.1"), UpdateClient { response("0.4.1") })
            try {
                assertEquals(UpdateTrack.STABLE, restored.state.value.track)
                restored.check(true)
                await { !restored.state.value.checking }
                assertFalse(restored.state.value.hasUpdate)
                assertEquals("稳定版通道暂无高于 0.5.0-beta.1 的可升级版本", restored.state.value.message)
            } finally { restored.pause() }
        } finally { repository.pause(); prefs.edit().clear().commit() }
    }

    @Test fun oldPreviewCheckCannotOverwriteNewStableResult() = runBlocking {
        val prefs = prefs("race")
        prefs.edit().putString("updateTrack", "PREVIEW").commit()
        val started = CountDownLatch(2)
        val finish = CountDownLatch(1)
        val client = UpdateClient { url ->
            if (url.endsWith("?per_page=30")) {
                started.countDown()
                check(finish.await(10, TimeUnit.SECONDS))
                response("0.6.0-beta.1", true)
            } else response("0.4.0")
        }
        val repository = UpdateRepository(context, prefs, installed(), client)
        try {
            repository.check(false)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repository.selectTrack(UpdateTrack.STABLE)
            await { !repository.state.value.checking }
            assertEquals("0.4.0", repository.state.value.release!!.version)
            finish.countDown()
            delay(250)
            assertEquals(UpdateTrack.STABLE, repository.state.value.track)
            assertEquals("0.4.0", repository.state.value.release!!.version)
            assertFalse(AppUpdates.restore(prefs.getString("release", null))!!.prerelease)
        } finally { finish.countDown(); repository.pause(); prefs.edit().clear().commit() }
    }

    @Test fun switchingTrackKeepsPreviouslyConfirmedBackgroundTransfer() = runBlocking {
        val prefs = prefs("transfer")
        val task = release()
        prefs.edit().putString("updateTrack", "PREVIEW").putString("taskRelease", task.encode()).putLong("downloadId", Long.MAX_VALUE).commit()
        val calls = ConcurrentLinkedQueue<String>()
        val repository = UpdateRepository(context, prefs, installed(), UpdateClient { url -> calls += url; response("0.4.0") })
        try {
            assertEquals(UpdateStage.DOWNLOADING, repository.state.value.stage)
            repository.selectTrack(UpdateTrack.STABLE)
            assertEquals(UpdateTrack.STABLE, repository.state.value.track)
            assertEquals(task, repository.state.value.release)
            assertEquals(UpdateStage.DOWNLOADING, repository.state.value.stage)
            assertEquals(Long.MAX_VALUE, prefs.getLong("downloadId", -1))
            assertEquals(task, AppUpdates.restore(prefs.getString("taskRelease", null)))
            assertTrue(calls.isEmpty())
            assertNull(repository.state.value.prompt)
            assertTrue(repository.state.value.trackSummary.contains("后续检查生效"))
            // A removed old-track task must not leave an ineligible retry/install badge.
            repository.completed(Long.MAX_VALUE) {}
            await { repository.state.value.stage == UpdateStage.FAILED }
            assertFalse(repository.state.value.hasUpdate)
            repository.openPrompt()
            assertNull(repository.state.value.prompt)
        } finally { repository.pause(); prefs.edit().clear().commit() }
    }

    @Test fun skippedPreviewRemainsSkippedAfterTrackRoundTrip() = runBlocking {
        val prefs = prefs("skip")
        val version = "0.5.0-beta.1"
        val repository = UpdateRepository(context, prefs, installed(), UpdateClient { response(version, true) })
        try {
            repository.selectTrack(UpdateTrack.PREVIEW)
            await { !repository.state.value.checking }
            repository.skip()
            repository.selectTrack(UpdateTrack.STABLE)
            await { !repository.state.value.checking }
            assertFalse(repository.state.value.hasUpdate)
            repository.selectTrack(UpdateTrack.PREVIEW)
            await { !repository.state.value.checking }
            assertTrue(repository.state.value.skipped)
            assertNull(repository.state.value.prompt)
            repository.check(true)
            await { !repository.state.value.checking }
            assertEquals(UpdatePrompt.RELEASE, repository.state.value.prompt)
        } finally { repository.pause(); prefs.edit().clear().commit() }
    }
}
