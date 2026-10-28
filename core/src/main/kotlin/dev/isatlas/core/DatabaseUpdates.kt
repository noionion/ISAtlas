package dev.isatlas.core

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class DatabaseRelease(val version: String, val notes: String, val url: String, val size: Long, val sha256: String)

data class OfficialDatabase(val version: String, val packages: OfficialPackages, val compiler: CompilerIsaOptions, val licenses: Map<String, String>)

/** The official distribution is separate from untrusted local-import packages. Never extract paths. */
object DatabaseUpdates {
    const val API = "https://api.github.com/repos/noionion/ISAtlas-data/releases/latest"
    const val MAX_DOWNLOAD = 32 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun release(body: String): DatabaseRelease? {
        val obj = json.parseToJsonElement(body).jsonObject
        require("tag_name" in obj)
        if (obj["draft"]?.jsonPrimitive?.booleanOrNull != false || obj["prerelease"]?.jsonPrimitive?.booleanOrNull != false) return null
        val tag = obj.getValue("tag_name").jsonPrimitive.content
        val version = tag.removePrefix("v")
        require(AppVersion.parse(version)?.prerelease?.isEmpty() == true && tag == "v$version")
        val asset = obj.getValue("assets").jsonArray.singleOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.content == "ISAtlas-data-$version.zip"
        }?.jsonObject ?: return null
        val result = DatabaseRelease(version, obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(20000),
            asset.getValue("browser_download_url").jsonPrimitive.content,
            asset.getValue("size").jsonPrimitive.long,
            asset.getValue("digest").jsonPrimitive.content.removePrefix("sha256:").lowercase())
        require(valid(result))
        return result
    }

    fun valid(release: DatabaseRelease) = AppVersion.parse(release.version)?.prerelease?.isEmpty() == true &&
        release.url == "https://github.com/noionion/ISAtlas-data/releases/download/v${release.version}/ISAtlas-data-${release.version}.zip" &&
        release.size in 1..MAX_DOWNLOAD && release.sha256.matches(Regex("[0-9a-f]{64}"))

    fun decode(bytes: ByteArray, release: DatabaseRelease? = null): OfficialDatabase {
        require(bytes.size in 1..MAX_DOWNLOAD) { "数据库文件大小不符合要求" }
        if (release != null) require(valid(release) && bytes.size.toLong() == release.size && LocalPackages.sha256(bytes) == release.sha256) { "数据库下载校验失败" }
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name.matches(Regex("[A-Za-z0-9_./-]+")) &&
                    !entry.name.startsWith("/") && entry.name.split('/').none { it == ".." || it.isEmpty() } &&
                    entry.name !in files && files.size < 32) { "数据库 ZIP 路径无效" }
                val data = LocalPackages.readBounded(zip, MAX_DOWNLOAD)
                total += data.size
                require(total <= 48 * 1024 * 1024) { "数据库解压大小超限" }
                files[entry.name] = data
            }
        }
        val manifest = json.parseToJsonElement(files.getValue("database.json").toString(Charsets.UTF_8)).jsonObject
        require(manifest.getValue("schemaVersion").jsonPrimitive.int == 1 &&
            manifest.getValue("format").jsonPrimitive.content == "isatlas-official-database") { "数据库格式不受支持，请更新应用" }
        val version = manifest.getValue("version").jsonPrimitive.content
        require(AppVersion.parse(version)?.prerelease?.isEmpty() == true && (release == null || release.version == version))
        val listed = manifest.getValue("files").jsonArray.map { it.jsonObject }
        val names = listed.map { it.getValue("path").jsonPrimitive.content }
        require(names.distinct().size == names.size && files.keys == (names + "database.json").toSet())
        listed.forEach { item ->
            val data = files.getValue(item.getValue("path").jsonPrimitive.content)
            require(data.size.toLong() == item.getValue("size").jsonPrimitive.long &&
                LocalPackages.sha256(data) == item.getValue("sha256").jsonPrimitive.content) { "数据库内容校验失败" }
        }
        val packages = OfficialPackages(files.getValue("riscv/riscv-packages.json").toString(Charsets.UTF_8)) { files.getValue("riscv/$it") }
        val compiler = CompilerIsaOptions.decode(files.getValue("riscv/march-extensions.json").toString(Charsets.UTF_8))
        val optional = packages.packages.filterNot { it.required }.map { it.id }
        // Decode all combinations before committing a candidate. Unsupported data leaves the old version active.
        repeat(1 shl optional.size) { mask -> packages.catalogs(optional.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet(), compiler) }
        val licenses = packages.packages.map { it.license }.distinct().associateWith { license ->
            require(license.matches(Regex("[A-Za-z0-9.+-]+")))
            files.getValue("riscv/licenses/$license.txt").toString(Charsets.UTF_8)
        }
        return OfficialDatabase(version, packages, compiler, licenses)
    }
}
