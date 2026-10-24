package dev.isatlas.core

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Numeric version ordering, including SemVer prerelease precedence. */
class AppVersion private constructor(val numbers: List<Long>, val prerelease: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        numbers.zip(other.numbers).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        if (prerelease.isEmpty() != other.prerelease.isEmpty()) return if (prerelease.isEmpty()) 1 else -1
        prerelease.zip(other.prerelease).forEach { (a, b) ->
            val comparison = when {
                a.all(Char::isDigit) && b.all(Char::isDigit) -> a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
                a.all(Char::isDigit) -> -1
                b.all(Char::isDigit) -> 1
                else -> a.compareTo(b)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        fun parse(value: String): AppVersion? {
            val match = Regex("^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$").matchEntire(value) ?: return null
            val parts = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
            val pre = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
            if (pre.any { it.isEmpty() || (it.all(Char::isDigit) && it.length > 1 && it.startsWith('0')) }) return null
            if (match.groupValues[5].split('.').any { it.isEmpty() } && match.groupValues[5].isNotEmpty()) return null
            return AppVersion(parts, pre)
        }
    }
}

@Serializable
data class AppRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String? = null,
) {
    val pageUrl get() = "https://github.com/noionion/ISAtlas/releases/tag/$tag"
    fun encode(): String = Json.encodeToString(this)
}

object AppUpdates {
    const val REPOSITORY = "https://github.com/noionion/ISAtlas"
    const val API = "https://api.github.com/repos/noionion/ISAtlas/releases"
    private val json = Json { ignoreUnknownKeys = true }

    fun restore(value: String?): AppRelease? = runCatching {
        json.decodeFromString<AppRelease>(value ?: return null).takeIf { valid(it) }
    }.getOrNull()

    fun valid(release: AppRelease): Boolean {
        if (AppVersion.parse(release.tag) == null || release.tag.removePrefix("v") != release.version) return false
        val uri = runCatching { URI(release.apkUrl) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.path == "/noionion/ISAtlas/releases/download/${release.tag}/ISAtlas-${release.version}.apk" &&
            release.size in 1..200_000_000 &&
            (release.sha256 == null || release.sha256.matches(Regex("[a-fA-F0-9]{64}")))
    }

    /** A successful response without a newer release is distinct from an unavailable source. */
    fun decodeResponse(body: String, current: String): List<AppRelease> {
        val installed = requireNotNull(AppVersion.parse(current))
        val payload = json.parseToJsonElement(body)
        val entries = when (payload) {
            is JsonArray -> payload
            is JsonObject -> { require("tag_name" in payload); JsonArray(listOf(payload)) }
            else -> error("Invalid release response")
        }
        return entries.mapNotNull { element -> runCatching {
            val obj = element.jsonObject
            if (obj["draft"]?.jsonPrimitive?.booleanOrNull != false) return@mapNotNull null
            val tag = obj["tag_name"]!!.jsonPrimitive.content
            val version = AppVersion.parse(tag) ?: return@mapNotNull null
            val pre = obj["prerelease"]?.jsonPrimitive?.booleanOrNull ?: true
            if (installed.prerelease.isEmpty() && (pre || version.prerelease.isNotEmpty())) return@mapNotNull null
            if (version <= installed) return@mapNotNull null
            val number = tag.removePrefix("v")
            val asset = obj["assets"]!!.jsonArray.map { it.jsonObject }.singleOrNull {
                it["name"]?.jsonPrimitive?.content == "ISAtlas-$number.apk" &&
                    it["state"]?.jsonPrimitive?.content == "uploaded"
            } ?: return@mapNotNull null
            val digest = asset["digest"]?.jsonPrimitive?.contentOrNull?.let {
                if (!it.startsWith("sha256:")) return@mapNotNull null
                it.removePrefix("sha256:")
            }
            AppRelease(tag, number, obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(64_000),
                asset["browser_download_url"]!!.jsonPrimitive.content, asset["size"]!!.jsonPrimitive.long, digest)
                .takeIf(::valid)
        }.getOrNull() }
    }

    fun newest(releases: List<AppRelease>): AppRelease? = releases.filter(::valid)
        .maxByOrNull { requireNotNull(AppVersion.parse(it.version)) }
}

data class UpdateChannel(val name: String, val prefix: String) {
    fun url(officialUrl: String) = prefix + officialUrl
}

object UpdateChannels {
    // Public requests only: never send credentials or authorization headers to mirrors.
    val all = listOf(UpdateChannel("GitHub", ""), UpdateChannel("GH-Proxy 镜像", "https://gh-proxy.com/"))
}
