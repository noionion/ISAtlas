package dev.isatlas

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.isatlas.core.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class UpdateStage { IDLE, CHOOSING, DOWNLOADING, VERIFYING, READY, FAILED }
internal enum class UpdatePrompt { RELEASE, INSTALL }
internal data class UpdateState(
    val release: AppRelease? = null,
    val checking: Boolean = false,
    val stage: UpdateStage = UpdateStage.IDLE,
    val progress: Int? = null,
    val channel: String? = null,
    val message: String? = null,
    val prompt: UpdatePrompt? = null,
    val skipped: Boolean = false,
) {
    val hasUpdate get() = release != null
    val busy get() = stage in setOf(UpdateStage.CHOOSING, UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)
    val summary: String get() = when {
        checking -> "正在并行检查官方与镜像…"
        stage == UpdateStage.CHOOSING -> "正在选择可用下载渠道…"
        stage == UpdateStage.DOWNLOADING -> "后台下载中${progress?.let { " · $it%" }.orEmpty()}${channel?.let { " · $it" }.orEmpty()}"
        stage == UpdateStage.VERIFYING -> "下载完成，正在校验安装包…"
        stage == UpdateStage.READY -> "${release?.version} 已下载，点击安装"
        message != null -> message
        release != null -> "发现 ${release.version}${if (skipped) " · 已跳过自动提醒" else ""}"
        else -> "官方与镜像同步检查"
    }
}

/** DownloadManager owns the transfer; this process only observes and verifies it. */
internal class UpdateRepository(
    context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE),
    private val installed: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0),
    private val client: UpdateClient = UpdateClient(),
) {
    private val context = context.applicationContext as Application
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transferLock = Mutex()
    private val current = installed.versionName.orEmpty()
    private val cached = AppUpdates.restore(prefs.getString("release", null))?.takeIf(::newer)
    private val mutable = MutableStateFlow(UpdateState(release = cached,
        skipped = cached?.version == prefs.getString("skipped", null)))
    val state = mutable.asStateFlow()
    @Volatile private var foreground = false
    private var observer: Job? = null
    private var checkJob: Job? = null
    private var manualCheckRequested = false
    private var lastAttempt = 0L
    private var promptedVersion: String? = null
    private var installPrompted = false
    private var lastBytes = -1L
    private var lastProgressAt = System.currentTimeMillis()

    init {
        if (prefs.getLong("downloadId", -1) != -1L) {
            val task = AppUpdates.restore(prefs.getString("taskRelease", null))?.takeIf(::newer)
            if (task != null) mutable.update { it.copy(release = task, stage = UpdateStage.DOWNLOADING) }
            else {
                val obsoleteId = prefs.getLong("downloadId", -1)
                prefs.edit { remove("downloadId"); remove("taskRelease") }
                scope.launch(Dispatchers.IO) { runCatching { downloads.remove(obsoleteId) } }
            }
        }
    }

    private fun newer(release: AppRelease) = AppVersion.parse(release.version)?.let {
        it > (AppVersion.parse(current) ?: return false)
    } == true

    fun resume() {
        foreground = true
        observer?.cancel()
        observer = scope.launch {
            refreshDownload()
            maybePrompt()
            if (!state.value.busy && state.value.stage != UpdateStage.READY &&
                System.currentTimeMillis() - prefs.getLong("lastCheck", 0) >= 6 * 60 * 60 * 1000L &&
                System.currentTimeMillis() - lastAttempt >= 5 * 60 * 1000L) check(false)
            while (isActive) {
                delay(1_500)
                refreshDownload()
            }
        }
    }

    fun pause() { foreground = false; observer?.cancel(); observer = null }

    fun check(manual: Boolean) {
        if (state.value.busy || state.value.stage == UpdateStage.READY) { if (manual) openPrompt(); return }
        if (checkJob?.isActive == true) { manualCheckRequested = manualCheckRequested || manual; return }
        manualCheckRequested = manual
        lastAttempt = System.currentTimeMillis()
        mutable.update { it.copy(checking = true, message = null) }
        checkJob = scope.launch {
            try {
                val release = client.check(current)
                prefs.edit { putLong("lastCheck", System.currentTimeMillis()) }
                // A stale mirror must not erase a previously known newer version.
                val best = AppUpdates.newest(listOfNotNull(release, state.value.release))
                if (best != null) prefs.edit { putString("release", best.encode()) }
                mutable.update { it.copy(release = best, checking = false,
                    skipped = best?.version == prefs.getString("skipped", null),
                    message = if (manualCheckRequested && best == null) "已是最新版本 $current" else null) }
                if (manualCheckRequested && best != null) openPrompt() else maybePrompt()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(checking = false, message = if (manualCheckRequested) "官方与镜像暂不可用，请检查网络后重试。" else null) }
            }
        }
    }

    private fun maybePrompt() {
        if (!foreground) return
        val value = state.value
        if (value.stage == UpdateStage.READY && !installPrompted) {
            installPrompted = true
            mutable.update { it.copy(prompt = UpdatePrompt.INSTALL) }
        } else if (!value.busy && value.stage != UpdateStage.READY && !value.skipped &&
            value.release != null && promptedVersion != value.release.version) openPrompt()
    }

    fun openPrompt() {
        val release = state.value.release ?: return
        promptedVersion = release.version
        if (state.value.stage == UpdateStage.READY) installPrompted = true
        mutable.update { it.copy(prompt = if (it.stage == UpdateStage.READY) UpdatePrompt.INSTALL else UpdatePrompt.RELEASE) }
    }
    fun dismiss() { mutable.update { it.copy(prompt = null) } }
    fun skip() {
        val version = state.value.release?.version ?: return
        prefs.edit { putString("skipped", version) }
        mutable.update { it.copy(skipped = true, prompt = null) }
    }

    fun download() {
        if (state.value.busy || state.value.stage == UpdateStage.READY) return
        val release = state.value.release ?: return
        mutable.update { it.copy(stage = UpdateStage.CHOOSING, prompt = null, message = null, progress = null) }
        scope.launch {
            transferLock.withLock {
                try {
                    val order = client.downloadOrder(release)
                    prefs.edit {
                        putString("taskRelease", release.encode())
                        putString("channelOrder", order.joinToString(","))
                        putInt("channelPosition", 0)
                    }
                    installPrompted = false
                    enqueue(release, order.first())
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { fail("官方与镜像下载渠道暂不可用，请重试。") }
            }
        }
    }

    private suspend fun enqueue(release: AppRelease, channelIndex: Int) = withContext(Dispatchers.IO) {
        val channel = UpdateChannels.all[channelIndex]
        val oldId = prefs.getLong("downloadId", -1)
        if (oldId != -1L) runCatching { downloads.remove(oldId) }
        val file = apkFile(release)
        file.parentFile?.mkdirs()
        if (file.exists() && !file.delete()) error("Unable to clear old APK")
        val request = DownloadManager.Request(channel.url(release.apkUrl).toUri())
            .setTitle("ISAtlas ${release.version}").setDescription("通过 ${channel.name} 下载更新，可继续查询")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(android.net.Uri.fromFile(file))
            .setAllowedOverMetered(true).setAllowedOverRoaming(false)
        val id = downloads.enqueue(request)
        prefs.edit { putLong("downloadId", id) }
        lastBytes = -1
        lastProgressAt = System.currentTimeMillis()
        mutable.update { it.copy(stage = UpdateStage.DOWNLOADING, channel = channel.name, progress = null, message = null) }
    }

    private suspend fun tryNextChannel(release: AppRelease) {
        val order = prefs.getString("channelOrder", "0,1").orEmpty().split(',').mapNotNull(String::toIntOrNull)
            .filter { it in UpdateChannels.all.indices }
        val next = prefs.getInt("channelPosition", 0) + 1
        if (next >= order.size) {
            runCatching { downloads.remove(prefs.getLong("downloadId", -1)) }
            prefs.edit { remove("downloadId") }
            fail("官方与镜像均下载失败，请检查网络后重试。")
        } else {
            prefs.edit { putInt("channelPosition", next) }
            enqueue(release, order[next])
        }
    }

    private suspend fun refreshDownload() = transferLock.withLock {
        val id = prefs.getLong("downloadId", -1)
        if (id == -1L || state.value.stage == UpdateStage.READY) return@withLock
        val release = AppUpdates.restore(prefs.getString("taskRelease", null))?.takeIf(::newer) ?: return@withLock
        try {
            withContext(Dispatchers.IO) {
                downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        prefs.edit { remove("downloadId") }
                        fail("下载任务已被移除，可点击重新下载。")
                        return@withContext
                    }
                    when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            mutable.update { it.copy(stage = UpdateStage.VERIFYING, progress = 100) }
                            try {
                                verifyApk(release)
                                mutable.update { it.copy(stage = UpdateStage.READY, message = null) }
                                if (foreground) maybePrompt() else runCatching { notifyReady(release) }
                            } catch (_: Exception) {
                                // A corrupt or unexpected mirror response is not installable; try the next source.
                                tryNextChannel(release)
                            }
                        }
                        DownloadManager.STATUS_FAILED -> tryNextChannel(release)
                        else -> {
                            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            if (bytes != lastBytes) { lastBytes = bytes; lastProgressAt = System.currentTimeMillis() }
                            if (System.currentTimeMillis() - lastProgressAt > 90_000) tryNextChannel(release)
                            else mutable.update { it.copy(stage = UpdateStage.DOWNLOADING,
                                progress = if (total > 0) ((bytes * 100 / total).toInt()).coerceIn(0, 99) else null) }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { fail("无法读取下载任务，请重新下载。") }
    }

    fun completed(id: Long, finish: () -> Unit) {
        scope.launch {
            try { if (id == prefs.getLong("downloadId", -1)) refreshDownload() }
            finally { finish() }
        }
    }

    private fun apkFile(release: AppRelease): File {
        val root = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
        return File(root, "updates/ISAtlas-${release.version}.apk")
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).hex()
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun signedInfo(path: String): PackageInfo? = context.packageManager.getPackageArchiveInfo(path,
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)

    private fun verifyApk(release: AppRelease): File {
        val file = apkFile(release)
        require(file.isFile && file.length() == release.size) { "APK size mismatch" }
        if (release.sha256 != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            require(digest.digest().hex().equals(release.sha256, ignoreCase = true)) { "APK checksum mismatch" }
        }
        val info = requireNotNull(signedInfo(file.absolutePath))
        require(info.packageName == context.packageName && info.versionName == release.version) { "Unexpected APK version or package" }
        @Suppress("DEPRECATION")
        fun code(value: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) value.longVersionCode else value.versionCode.toLong()
        require(code(info) > code(installed)) { "APK is not newer" }
        require(signers(info) == setOf(RELEASE_CERTIFICATE)) { "Untrusted APK signer" }
        return file
    }

    fun install(activity: Activity) {
        if (state.value.stage != UpdateStage.READY) return
        val release = state.value.release ?: return
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) { verifyApk(release) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                fail("安装包已失效或校验不通过，请重新下载。")
                mutable.update { it.copy(prompt = UpdatePrompt.RELEASE) }
                return@launch
            }
            try {
                @Suppress("DEPRECATION")
                val own = context.packageManager.getPackageInfo(context.packageName,
                    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
                require(signers(own) == setOf(RELEASE_CERTIFICATE)) { "SIGNATURE_CONFLICT" }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                dismiss()
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(message = if (error.message == "SIGNATURE_CONFLICT")
                    "当前为不同签名的安装包，无法覆盖安装发布版。请先备份设置并手动安装发布版。"
                    else "无法打开安装程序，请重新下载或稍后重试。") }
            }
        }
    }

    fun permissionDenied() { mutable.update { it.copy(message = "未获得安装权限，可稍后点击安装并授权。") } }
    private fun fail(message: String) {
        val id = prefs.getLong("downloadId", -1)
        prefs.edit { remove("downloadId") }
        if (id != -1L) scope.launch(Dispatchers.IO) { runCatching { downloads.remove(id) } }
        mutable.update { it.copy(stage = UpdateStage.FAILED, message = message, progress = null) }
    }

    private fun notifyReady(release: AppRelease) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra("show_update", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update_notification).setContentTitle("ISAtlas ${release.version} 已下载")
            .setContentText("安装包已校验，点击安装更新").setContentIntent(pending).setAutoCancel(true).build())
    }

    companion object {
        // Public release certificate fingerprint, not a private signing key.
        private const val RELEASE_CERTIFICATE = "28938b131f0753fb4f6def96ad4af00bb915f984bb17d8f47ddfeb4c5ba9a2d8"
        private const val CHANNEL_ID = "app-updates"
        private const val NOTIFICATION_ID = 3001
        @Volatile private var instance: UpdateRepository? = null
        fun get(context: Context): UpdateRepository = instance ?: synchronized(this) {
            instance ?: UpdateRepository(context.applicationContext).also { instance = it }
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id == -1L) return
        val pending = goAsync()
        UpdateRepository.get(context).completed(id) { pending.finish() }
    }
}
