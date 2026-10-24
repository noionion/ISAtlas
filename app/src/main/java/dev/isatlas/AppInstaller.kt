package dev.isatlas

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.isatlas.core.AppVersion
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class InstallPhase { IDLE, PREPARING, SUBMITTED, CONFIRMATION }
internal data class InstallState(
    val phase: InstallPhase = InstallPhase.IDLE,
    val sessionId: Int = -1,
    val confirmation: Intent? = null,
    val confirmationShown: Boolean = false,
    val message: String? = null,
    val completed: Boolean = false,
) {
    val busy get() = phase == InstallPhase.PREPARING || phase == InstallPhase.SUBMITTED
}

/** Thin system adapter; the coordinator below persists and validates the session lifecycle. */
internal interface InstallSessions {
    fun create(size: Long): Int
    fun write(id: Int, file: File)
    fun commit(id: Int)
    fun abandon(id: Int)
    fun exists(id: Int): Boolean
}

internal class PackageInstallSessions(private val context: Context) : InstallSessions {
    private val installer = context.packageManager.packageInstaller
    internal fun parameters(size: Long) = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(context.packageName)
        setSize(size)
        setInstallReason(PackageManager.INSTALL_REASON_USER)
        if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
    }
    override fun create(size: Long) = installer.createSession(parameters(size))
    override fun write(id: Int, file: File) {
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
        }
    }
    internal fun callbackIntent(id: Int) = Intent(context, UpdateInstallReceiver::class.java)
        .setAction("${context.packageName}.INSTALL_STATUS")
        .setData("isatlas-update://install/$id".toUri())

    override fun commit(id: Int) {
        // PackageInstaller fills the result extras, so this explicit PendingIntent must be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val callback = PendingIntent.getBroadcast(context, id, callbackIntent(id), flags)
        installer.openSession(id).use { it.commit(callback.intentSender) }
    }
    override fun abandon(id: Int) { installer.abandonSession(id) }
    override fun exists(id: Int) = installer.getSessionInfo(id) != null
}

internal class AppInstaller(
    private val prefs: SharedPreferences,
    private val sessions: InstallSessions,
    private val installedVersion: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private fun restored(): InstallState {
        val id = prefs.getInt("installSession", -1)
        if (id == -1) return InstallState()
        val phase = runCatching { InstallPhase.valueOf(prefs.getString("installPhase", null).orEmpty()) }.getOrDefault(InstallPhase.PREPARING)
        val confirmation = prefs.getString("installConfirmation", null)?.let {
            runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull()
        }
        return InstallState(phase, id, confirmation, prefs.getBoolean("installConfirmationShown", false))
    }
    private val mutable = MutableStateFlow(restored())
    val state = mutable.asStateFlow()

    suspend fun submit(file: File, targetVersion: String) = lock.withLock {
        if (state.value.busy) return@withLock
        if (state.value.phase == InstallPhase.CONFIRMATION) { requestConfirmation(); return@withLock }
        mutable.value = InstallState(phase = InstallPhase.PREPARING)
        var id = -1
        try {
            withContext(Dispatchers.IO) {
                id = sessions.create(file.length())
                persist {
                    putInt("installSession", id)
                    putString("installTarget", targetVersion)
                    putString("installPhase", InstallPhase.PREPARING.name)
                    remove("installConfirmation")
                    remove("installConfirmationShown")
                }
                mutable.value = InstallState(InstallPhase.PREPARING, id)
                sessions.write(id, file)
                persist { putString("installPhase", InstallPhase.SUBMITTED.name) }
                mutable.value = InstallState(InstallPhase.SUBMITTED, id)
                sessions.commit(id)
            }
        } catch (cancelled: CancellationException) {
            if (id != -1) runCatching { sessions.abandon(id) }
            finish("安装准备已取消，可稍后重试。")
            throw cancelled
        } catch (_: Exception) {
            if (id != -1) runCatching { sessions.abandon(id) }
            finish("无法提交安装，请稍后重试。")
        }
    }

    fun result(intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id == -1 || id != prefs.getInt("installSession", -1)) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) { abandonAndFinish(id, "系统未提供安装确认页，请重试。"); return }
                val encoded = confirmation.toUri(Intent.URI_INTENT_SCHEME)
                val shown = encoded == prefs.getString("installConfirmation", null) && prefs.getBoolean("installConfirmationShown", false)
                persist {
                    putString("installPhase", InstallPhase.CONFIRMATION.name)
                    putString("installConfirmation", encoded)
                    putBoolean("installConfirmationShown", shown)
                }
                mutable.value = InstallState(InstallPhase.CONFIRMATION, id, confirmation, shown)
            }
            PackageInstaller.STATUS_SUCCESS -> finish(null, completed = true)
            PackageInstaller.STATUS_FAILURE_ABORTED -> abandonAndFinish(id, "已取消安装，安装包仍保留，可稍后重试。")
            Int.MIN_VALUE -> Unit
            else -> abandonAndFinish(id, "系统未能完成更新，安装包仍保留，可稍后重试。")
        }
    }

    /** Only the resumed activity consumes this; a receiver never starts background activities. */
    fun takeConfirmation(): Intent? {
        val current = state.value
        if (current.phase != InstallPhase.CONFIRMATION || current.confirmationShown) return null
        val intent = current.confirmation ?: return null
        persist { putBoolean("installConfirmationShown", true) }
        mutable.value = current.copy(confirmationShown = true)
        return Intent(intent)
    }

    fun requestConfirmation() {
        if (state.value.phase != InstallPhase.CONFIRMATION) return
        prefs.edit { putBoolean("installConfirmationShown", false) }
        mutable.update { it.copy(confirmationShown = false, message = null) }
    }

    suspend fun reconcile() = lock.withLock {
        val current = state.value
        if (current.sessionId == -1) return@withLock
        val target = AppVersion.parse(prefs.getString("installTarget", null).orEmpty())
        val installed = AppVersion.parse(installedVersion)
        if (target != null && installed != null && installed >= target) { finish(null, completed = true); return@withLock }
        val exists = withContext(Dispatchers.IO) { runCatching { sessions.exists(current.sessionId) }.getOrDefault(false) }
        if (!exists || current.phase == InstallPhase.PREPARING ||
            (current.phase == InstallPhase.CONFIRMATION && current.confirmation == null))
            abandonAndFinish(current.sessionId, "上次安装未完成，可点击安装重试。")
    }

    fun confirmationUnavailable() {
        val id = state.value.sessionId
        if (id != -1) abandonAndFinish(id, "无法打开系统安装确认页，请重试。")
    }

    private fun abandonAndFinish(id: Int, message: String) {
        finish(message)
        scope.launch(Dispatchers.IO) { runCatching { sessions.abandon(id) } }
    }
    private fun finish(message: String?, completed: Boolean = false) {
        persist {
            remove("installSession"); remove("installPhase"); remove("installTarget")
            remove("installConfirmation"); remove("installConfirmationShown")
        }
        mutable.value = InstallState(message = message, completed = completed)
    }

    @SuppressLint("UseKtx") // The installer must not commit until persistence succeeds; KTX edit discards commit's result.
    private fun persist(change: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.change()
        check(editor.commit()) { "Unable to save installation state" }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "${context.packageName}.INSTALL_STATUS") return
        UpdateRepository.get(context).installationResult(intent)
    }
}
