package dev.isatlas

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

@Suppress("DEPRECATION")
class AppInstallerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs: SharedPreferences = context.getSharedPreferences("install-session-tests", Context.MODE_PRIVATE)
    private val file by lazy { File(context.cacheDir, "install-session-test.apk").apply { writeText("test session payload") } }
    private lateinit var sessions: FakeSessions
    private lateinit var installer: AppInstaller
    @Before fun setup() {
        prefs.edit().clear().commit()
        sessions = FakeSessions()
        installer = AppInstaller(prefs, sessions, "0.4.0")
    }
    @After fun cleanup() { prefs.edit().clear().commit(); file.delete() }

    @Test fun realPackageRequestsNoNotificationPermissionAndProtectsInstallReceiver() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"))
        assertFalse(context.packageManager.getReceiverInfo(ComponentName(context, UpdateInstallReceiver::class.java), 0).exported)
    }

    @Test fun realSystemSessionStagesExactAPKAndRequestsSelfUpdateWithoutAConfirmation() = runBlocking {
        val real = PackageInstallSessions(context)
        val apk = File(context.applicationInfo.sourceDir)
        val id = real.create(apk.length())
        try {
            real.write(id, apk)
            val info = requireNotNull(context.packageManager.packageInstaller.getSessionInfo(id))
            assertEquals(context.packageName, info.appPackageName)
            assertEquals(PackageManager.INSTALL_REASON_USER, info.installReason)
            if (Build.VERSION.SDK_INT >= 31) assertEquals(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED, info.requireUserAction)
            if (Build.VERSION.SDK_INT >= 33) assertEquals(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE, info.packageSource)
            context.packageManager.packageInstaller.openSession(id).use { session ->
                assertEquals(listOf("base.apk"), session.names.toList())
                fun digest(input: java.io.InputStream): ByteArray = input.use {
                    val sha = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(16_384)
                    while (true) { val count = it.read(buffer); if (count < 0) break; sha.update(buffer, 0, count) }
                    sha.digest()
                }
                assertArrayEquals(digest(apk.inputStream()), digest(session.openRead("base.apk")))
            }
            val callback = real.callbackIntent(id)
            assertEquals(ComponentName(context, UpdateInstallReceiver::class.java), callback.component)
            assertEquals("${context.packageName}.INSTALL_STATUS", callback.action)
            assertEquals("isatlas-update://install/$id", callback.dataString)
        } finally { real.abandon(id) }
        withTimeout(5_000) { while (real.exists(id)) delay(50) }
        assertFalse(real.exists(id))
    }

    @Test fun repeatedInstallClicksCommitOnlyOneSession() = runBlocking {
        coroutineScope { (1..3).map { async { installer.submit(file, "0.5.0") } }.awaitAll() }
        assertEquals(1, sessions.created)
        assertEquals(1, sessions.writes)
        assertEquals(1, sessions.commits)
        assertEquals(InstallPhase.SUBMITTED, installer.state.value.phase)
        assertEquals(installer.state.value.sessionId, prefs.getInt("installSession", -1))
    }

    @Test fun pendingConfirmationRestoresAndLaunchesOnceUntilExplicitlyRequestedAgain() = runBlocking {
        installer.submit(file, "0.5.0")
        val id = installer.state.value.sessionId
        val confirmation = Intent("android.content.pm.action.CONFIRM_INSTALL")
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
        installer.result(result(id, PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirmation))
        val restored = AppInstaller(prefs, sessions, "0.4.0")
        assertEquals(InstallPhase.CONFIRMATION, restored.state.value.phase)
        assertEquals(id, requireNotNull(restored.takeConfirmation()).getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))
        assertNull(restored.takeConfirmation())
        val recreated = AppInstaller(prefs, sessions, "0.4.0")
        assertNull(recreated.takeConfirmation())
        recreated.requestConfirmation()
        assertNotNull(recreated.takeConfirmation())
        recreated.result(result(id, PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirmation))
        assertNull(recreated.takeConfirmation())
    }

    @Test fun unrelatedOrMalformedCallbacksCannotStartAnInstallConfirmation() = runBlocking {
        installer.submit(file, "0.5.0")
        val id = installer.state.value.sessionId
        installer.result(result(id + 10, PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, Intent("arbitrary")))
        assertEquals(InstallPhase.SUBMITTED, installer.state.value.phase)
        assertNull(installer.takeConfirmation())
        installer.result(result(id, PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals(InstallPhase.IDLE, installer.state.value.phase)
        assertNotNull(installer.state.value.message)
        assertEquals(-1, prefs.getInt("installSession", -1))
    }

    @Test fun userCancellationKeepsPayloadAndAllowsRetry() = runBlocking {
        installer.submit(file, "0.5.0")
        installer.result(result(installer.state.value.sessionId, PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(InstallPhase.IDLE, installer.state.value.phase)
        assertTrue(requireNotNull(installer.state.value.message).contains("取消"))
        assertTrue(file.exists())
        installer.submit(file, "0.5.0")
        assertEquals(2, sessions.commits)
    }

    @Test fun writeFailureAbandonsTheSessionAndAllowsRetry() = runBlocking {
        sessions.failWrite = true
        installer.submit(file, "0.5.0")
        assertEquals(0, sessions.commits)
        assertEquals(1, sessions.abandoned)
        assertEquals(InstallPhase.IDLE, installer.state.value.phase)
        assertEquals(-1, prefs.getInt("installSession", -1))
        sessions.failWrite = false
        installer.submit(file, "0.5.0")
        assertEquals(1, sessions.commits)
    }

    @Test fun processRestartKeepsSubmittedSessionsButCleansInterruptedStaging() = runBlocking {
        installer.submit(file, "0.5.0")
        val restored = AppInstaller(prefs, sessions, "0.4.0")
        restored.reconcile()
        assertEquals(InstallPhase.SUBMITTED, restored.state.value.phase)
        prefs.edit().putString("installPhase", InstallPhase.PREPARING.name).commit()
        val interrupted = AppInstaller(prefs, sessions, "0.4.0")
        interrupted.reconcile()
        assertEquals(InstallPhase.IDLE, interrupted.state.value.phase)
        assertNotNull(interrupted.state.value.message)
        assertEquals(-1, prefs.getInt("installSession", -1))
    }

    @Test fun successAndAlreadyUpdatedProcessesClearStoredSessionState() = runBlocking {
        installer.submit(file, "0.5.0")
        installer.result(result(installer.state.value.sessionId, PackageInstaller.STATUS_SUCCESS))
        assertTrue(installer.state.value.completed)
        assertEquals(-1, prefs.getInt("installSession", -1))
        installer.submit(file, "0.5.0")
        val updated = AppInstaller(prefs, sessions, "0.5.0")
        updated.reconcile()
        assertTrue(updated.state.value.completed)
        assertEquals(-1, prefs.getInt("installSession", -1))
    }

    private fun result(id: Int, status: Int) = Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
        .putExtra(PackageInstaller.EXTRA_STATUS, status)

    private class FakeSessions : InstallSessions {
        var created = 0
        var writes = 0
        var commits = 0
        var abandoned = 0
        var failWrite = false
        private val live = mutableSetOf<Int>()
        override fun create(size: Long): Int = (++created).also { live += it }
        override fun write(id: Int, file: File) { if (failWrite) error("No space"); writes++ }
        override fun commit(id: Int) { commits++ }
        override fun abandon(id: Int) { abandoned++; live -= id }
        override fun exists(id: Int) = id in live
    }
}
