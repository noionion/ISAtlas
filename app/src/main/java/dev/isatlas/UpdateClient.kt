package dev.isatlas

import dev.isatlas.core.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*

internal class UpdateClient(private val read: (String) -> String = ::readReleaseResponse) {
    suspend fun check(current: String): AppRelease? = coroutineScope {
        val endpoint = if (AppVersion.parse(current)?.prerelease?.isNotEmpty() == true)
            "${AppUpdates.API}?per_page=30" else "${AppUpdates.API}/latest"
        // Start all sources together so a blocked GitHub DNS cannot prevent a mirror check.
        val results = UpdateChannels.all.map { channel -> async(Dispatchers.IO) {
            runCatching { AppUpdates.decodeResponse(read(channel.url(endpoint)), current) }
        } }.awaitAll()
        if (results.none { it.isSuccess }) throw IOException("官方与镜像暂不可用，请检查网络后重试。")
        AppUpdates.newest(results.flatMap { it.getOrDefault(emptyList()) })
    }

    suspend fun downloadOrder(release: AppRelease): List<Int> = coroutineScope {
        val probes = UpdateChannels.all.mapIndexed { index, channel -> async(Dispatchers.IO) {
            val start = System.nanoTime()
            val available = runCatching {
                val connection = openConnection(channel.url(release.apkUrl))
                try {
                    connection.setRequestProperty("Range", "bytes=0-3")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    require(connection.responseCode in 200..299)
                    connection.inputStream.use { stream -> require(stream.read() == 0x50 && stream.read() == 0x4b) }
                } finally { connection.disconnect() }
            }.isSuccess
            Triple(index, available, System.nanoTime() - start)
        } }.awaitAll()
        if (probes.none { it.second }) throw IOException("官方与镜像下载渠道暂不可用，请稍后重试。")
        probes.sortedWith(compareByDescending<Triple<Int, Boolean, Long>> { it.second }.thenBy { it.third }).map { it.first }
    }
}

private fun openConnection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 5_000
    readTimeout = 7_000
    setRequestProperty("User-Agent", "ISAtlas-Android")
    instanceFollowRedirects = true
}

private fun readReleaseResponse(url: String): String {
    val connection = openConnection(url)
    try {
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
        return connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1_000_000) { "Release response too large" }
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    } finally { connection.disconnect() }
}
