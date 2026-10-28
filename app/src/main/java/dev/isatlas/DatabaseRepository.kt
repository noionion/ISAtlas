package dev.isatlas

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import dev.isatlas.core.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class DatabaseStage { IDLE, DOWNLOADING, VERIFYING, READY, APPLYING, APPLY_FAILED, CANCELLING }
internal data class DatabaseUpdateState(
    val version: String? = null, val release: DatabaseRelease? = null, val checking: Boolean = false,
    val stage: DatabaseStage = DatabaseStage.IDLE, val progress: Int? = null,
    val message: String? = null, val prompt: Boolean = false,
) {
    val available get() = release != null
    val busy get() = stage != DatabaseStage.IDLE && stage != DatabaseStage.APPLY_FAILED
    val summary get() = when {
        checking -> "正在检查官方与镜像…"
        stage == DatabaseStage.DOWNLOADING -> "后台下载中${progress?.let { " · $it%" }.orEmpty()}"
        stage == DatabaseStage.VERIFYING -> "正在校验数据库…"
        stage == DatabaseStage.APPLYING -> "正在切换数据库…"
        stage == DatabaseStage.CANCELLING -> "正在取消下载…"
        stage == DatabaseStage.READY -> "校验通过，等待切换数据库…"
        message != null -> message
        release != null -> "发现数据库 ${release.version}"
        else -> "数据库可独立于软件更新"
    }
}

/** DownloadManager survives process death. Only fully validated bytes reach the atomic active file. */
internal class DatabaseRepository(
    context: Context,
    private val prefs: android.content.SharedPreferences = context.getSharedPreferences("database-updates", 0),
    private val active: AtomicFile = AtomicFile(File(context.noBackupFilesDir, "official-database.zip")),
    private val read: (String) -> String = ::readText,
) {
    private val context = context.applicationContext
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(DatabaseUpdateState(release = runCatching {
        Json.decodeFromString<DatabaseRelease>(prefs.getString("release", null)!!).also { require(DatabaseUpdates.valid(it)) }
    }.getOrNull()))
    val state = mutable.asStateFlow()
    private var task: Job? = null
    private var checkJob: Job? = null
    private var generation = 0
    private var validated: Pair<ByteArray, OfficialDatabase>? = null
    private fun taskRelease() = runCatching { Json.decodeFromString<DatabaseRelease>(prefs.getString("task", null)!!).also { require(DatabaseUpdates.valid(it)) } }.getOrNull()

    init {
        if (prefs.getLong("id", -1) != -1L) {
            val release = taskRelease()
            if (release != null) {
                mutable.update { it.copy(release = release, stage = DatabaseStage.DOWNLOADING) }
                observe(release)
            } else clearTransfer()
        }
    }

    fun load(): OfficialDatabase? {
        if (!active.baseFile.exists() && !File(active.baseFile.path + ".bak").exists()) {
            mutable.update { it.copy(version = null) }
            return null
        }
        return try {
            val database = active.openRead().use { DatabaseUpdates.decode(LocalPackages.readBounded(it, DatabaseUpdates.MAX_DOWNLOAD)) }
            mutable.update { it.copy(version = database.version, release = it.release?.takeIf { release ->
                AppVersion.parse(release.version)!! > AppVersion.parse(database.version)!!
            }) }
            database
        } catch (_: Exception) {
            mutable.update { it.copy(version = null, message = "已下载的数据库不可读取，已使用内置 Manual；可重新下载修复。") }
            null
        }
    }

    fun check(manual: Boolean = true) {
        if (state.value.stage != DatabaseStage.IDLE || checkJob?.isActive == true) {
            if (manual) mutable.update { it.copy(prompt = true) }
            return
        }
        if (!manual && System.currentTimeMillis() - prefs.getLong("checked", 0) < 6 * 60 * 60 * 1000L) return
        mutable.update { it.copy(checking = true, message = null, prompt = manual) }
        checkJob = scope.launch {
            val result = coroutineScope {
                UpdateChannels.all.map { channel -> async(Dispatchers.IO) {
                    runCatching { DatabaseUpdates.release(read(channel.url(DatabaseUpdates.API))) }
                } }.awaitAll()
            }
            if (result.none { it.isSuccess }) {
                mutable.update { it.copy(checking = false, message = "官方与镜像暂不可用，请稍后重试。") }
            } else {
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
                val release = result.mapNotNull { it.getOrNull() }.maxByOrNull { AppVersion.parse(it.version)!! }
                    ?.takeIf { AppVersion.parse(it.version)!! > AppVersion.parse(state.value.version ?: "0.0.0")!! }
                prefs.edit().apply { if (release == null) remove("release") else putString("release", Json.encodeToString(release)) }.apply()
                mutable.update { it.copy(checking = false, release = release,
                    message = if (release == null) "数据库已是最新版本" else null,
                    prompt = it.prompt || (!manual && release != null && prefs.getString("skipped", null) != release.version)) }
            }
        }
    }

    fun dismiss() { mutable.update { it.copy(prompt = false) } }
    fun skip() { prefs.edit().putString("skipped", state.value.release?.version).apply(); dismiss() }
    fun download() {
        val release = state.value.release ?: return
        if (state.value.stage != DatabaseStage.IDLE || state.value.checking) return
        mutable.update { it.copy(stage = DatabaseStage.DOWNLOADING, prompt = false, message = null, progress = null) }
        task = scope.launch {
            try {
                val order = UpdateClient().downloadOrder(release.url)
                prefs.edit().putString("order", order.joinToString(",")).apply()
                enqueue(release, order.first()); observe(release)
            }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed() }
        }
    }

    private suspend fun enqueue(release: DatabaseRelease, channel: Int) = withContext(Dispatchers.IO + NonCancellable) {
        val file = File(context.getExternalFilesDir(null), "database-update-${java.util.UUID.randomUUID()}.zip")
        val oldId = prefs.getLong("id", -1)
        if (oldId != -1L) downloads.remove(oldId)
        if (file.exists()) require(file.delete())
        val id = downloads.enqueue(DownloadManager.Request(Uri.parse(UpdateChannels.all[channel].url(release.url)))
            .setTitle("ISAtlas 数据库 ${release.version}").setDescription("后台下载，可继续查询")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file)).setAllowedOverRoaming(false))
        if (!prefs.edit().putLong("id", id).putString("task", Json.encodeToString(release)).putInt("channel", channel).commit()) {
            downloads.remove(id); error("无法保存下载任务")
        }
    }

    private fun observe(release: DatabaseRelease) {
        val token = generation
        task = scope.launch {
            var lastBytes = -1L
            var advanced = System.currentTimeMillis()
            try {
                while (isActive && token == generation) {
                    val row = withContext(Dispatchers.IO) {
                        downloads.query(DownloadManager.Query().setFilterById(prefs.getLong("id", -1))).use { c ->
                            if (!c.moveToFirst()) null else Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                        }
                    }
                    if (row?.first == DownloadManager.STATUS_SUCCESSFUL) {
                        if (AppVersion.parse(release.version)!! <= AppVersion.parse(state.value.version ?: "0.0.0")!!) {
                            clearTransfer()
                            mutable.update { it.copy(stage = DatabaseStage.IDLE, release = null, prompt = false) }
                            return@launch
                        }
                        mutable.update { it.copy(stage = DatabaseStage.VERIFYING) }
                        val bytes = withContext(Dispatchers.IO) {
                            val bytes = downloads.openDownloadedFile(prefs.getLong("id", -1)).use { descriptor ->
                                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { LocalPackages.readBounded(it, DatabaseUpdates.MAX_DOWNLOAD) }
                            }
                            bytes
                        }
                        if (token != generation) return@launch
                        verify(bytes, release)
                        return@launch
                    }
                    if (row != null && row.second != lastBytes) { lastBytes = row.second; advanced = System.currentTimeMillis() }
                    if (row == null || row.first == DownloadManager.STATUS_FAILED ||
                        System.currentTimeMillis() - advanced > 60_000 || row.second > release.size) {
                        val order = prefs.getString("order", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
                            ?.takeIf { it.toSet() == UpdateChannels.all.indices.toSet() && it.size == UpdateChannels.all.size }
                            ?: UpdateChannels.all.indices.toList()
                        val position = order.indexOf(prefs.getInt("channel", 0)) + 1
                        if (position >= order.size) error("下载不可用")
                        enqueue(release, order[position]); lastBytes = -1; advanced = System.currentTimeMillis()
                    } else mutable.update { it.copy(progress = if (row.third > 0) (row.second * 100 / row.third).toInt().coerceIn(0, 100) else null) }
                    delay(1000)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (token == generation) failed() }
        }
    }

    fun cancel() {
        generation++
        val running = task
        running?.cancel()
        mutable.update { it.copy(stage = DatabaseStage.CANCELLING, prompt = false) }
        scope.launch {
            running?.join()
            validated = null; clearTransfer()
            mutable.update { it.copy(stage = DatabaseStage.IDLE, progress = null, message = "下载已取消") }
        }
    }
    private fun clearTransfer() {
        val id = prefs.getLong("id", -1)
        prefs.edit().remove("id").remove("task").remove("channel").remove("order").apply()
        if (id != -1L) scope.launch(Dispatchers.IO) { runCatching { downloads.remove(id) } }
    }
    private fun failed() {
        validated = null; clearTransfer()
        mutable.update { it.copy(stage = DatabaseStage.IDLE, progress = null, prompt = true, message = "下载或校验失败，当前数据库保持不变，请重试。") }
    }
    fun candidate(): Pair<ByteArray, OfficialDatabase>? = validated
    internal suspend fun verify(bytes: ByteArray, release: DatabaseRelease) {
        val decoded = withContext(Dispatchers.IO) { DatabaseUpdates.decode(bytes, release) }
        require(AppVersion.parse(decoded.version)!! > AppVersion.parse(state.value.version ?: "0.0.0")!!) { "不允许降级数据库" }
        validated = bytes to decoded
        mutable.update { it.copy(release = release, stage = DatabaseStage.READY, prompt = false, progress = 100) }
    }
    internal fun close() { scope.cancel() }
    fun applying() { mutable.update { it.copy(stage = DatabaseStage.APPLYING) } }
    fun commit(bytes: ByteArray) {
        require(validated?.first?.contentEquals(bytes) == true)
        val previous = if (active.baseFile.exists()) active.openRead().use { it.readBytes() } else null
        val stream = active.startWrite()
        try { stream.write(bytes); active.finishWrite(stream) }
        catch (e: Exception) { active.failWrite(stream); throw e }
        if (!active.openRead().use { it.readBytes().contentEquals(bytes) }) {
            if (previous != null) {
                val restore = active.startWrite()
                try { restore.write(previous); active.finishWrite(restore) }
                catch (e: Exception) { active.failWrite(restore); throw e }
            } else active.delete()
            error("数据库保存失败")
        }
    }
    fun applied(version: String) {
        validated = null; clearTransfer()
        prefs.edit().remove("release").apply()
        mutable.update { it.copy(version = version, release = null, stage = DatabaseStage.IDLE, prompt = false, message = "数据库已更新至 $version") }
    }
    fun applyFailed() { mutable.update { it.copy(stage = DatabaseStage.APPLY_FAILED, message = "更新未能应用，当前数据库保持不变。", prompt = true) } }
    fun retryApply() {
        if (state.value.stage == DatabaseStage.APPLY_FAILED && validated != null)
            mutable.update { it.copy(stage = DatabaseStage.READY, prompt = false, message = null) }
    }

    companion object {
        @Volatile private var instance: DatabaseRepository? = null
        fun get(context: Context): DatabaseRepository = instance ?: synchronized(this) { instance ?: DatabaseRepository(context).also { instance = it } }
        private fun readText(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5000; connection.readTimeout = 7000
                connection.setRequestProperty("User-Agent", "ISAtlas-Android")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                require(connection.responseCode in 200..299)
                return connection.inputStream.use { LocalPackages.readBounded(it, 1024 * 1024).toString(Charsets.UTF_8) }
            } finally { connection.disconnect() }
        }
    }
}
