package dev.isatlas.core

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class OfficialPackageInfo(
    val id: String, val name: String, val version: String, val source: SourceAttribution,
    val license: String, val required: Boolean, val csrCount: Int, val supplementCount: Int,
    val file: String, val sha256: String,
)
@Serializable private data class OfficialPackageIndex(val schemaVersion: Int, val packages: List<OfficialPackageInfo>)

/** Only APK assets are allowed here; local ZIP imports use the separate untrusted validator. */
class OfficialPackages(index: String, read: (String) -> ByteArray) {
    val packages: List<OfficialPackageInfo>
    private val archives: Map<String, Map<String, String>>
    private val instructions by lazy {
        val manual = archives.getValue("manual")
        InstructionCatalog.decodeFiles(manual.getValue("riscv-instructions.json")) { manual.getValue("inst/$it") }
    }
    init {
        val decoded = Json.decodeFromString<OfficialPackageIndex>(index)
        require(decoded.schemaVersion == 1 && decoded.packages.map { it.id } == listOf("manual", "aia", "debug", "spmp"))
        packages = decoded.packages
        require(packages.filter { it.required }.map { it.id } == listOf("manual"))
        archives = packages.associate { info ->
            require(info.file == "packages/${info.id}.zip")
            val bytes = read(info.file)
            require(LocalPackages.sha256(bytes) == info.sha256) { "内置数据包校验失败：${info.id}" }
            val files = linkedMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var total = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && !entry.name.contains("..") && !entry.name.startsWith("/"))
                    require(entry.name !in files && files.size < 4096)
                    val data = LocalPackages.readBounded(zip, 1024 * 1024)
                    total += data.size
                    require(total <= 24 * 1024 * 1024)
                    files[entry.name] = data.toString(Charsets.UTF_8)
                }
            }
            val manifest = Json.parseToJsonElement(files.getValue("manifest.json")).jsonObject
            require(manifest.getValue("schemaVersion").jsonPrimitive.int == 1)
            require(manifest.getValue("id").jsonPrimitive.content == info.id)
            require(manifest.getValue("version").jsonPrimitive.content == info.version)
            require(manifest.getValue("csr").jsonArray.size == info.csrCount)
            require(manifest.getValue("overlays").jsonArray.size == info.supplementCount)
            info.id to files
        }
    }

    fun catalogs(enabled: Set<String>, compiler: CompilerIsaOptions): LocalCatalogs {
        require(enabled.all { id -> packages.any { it.id == id } })
        val registers = linkedMapOf<String, JsonElement>()
        var metadata: JsonObject? = null
        packages.filter { it.required || it.id in enabled }.forEach { info ->
            val files = archives.getValue(info.id)
            val manifest = Json.parseToJsonElement(files.getValue("manifest.json")).jsonObject
            if (info.required) metadata = manifest.getValue("catalog").jsonObject
            manifest.getValue("csr").jsonArray.forEach { path ->
                val reg = Json.parseToJsonElement(files.getValue(path.jsonPrimitive.content)).jsonObject
                val id = reg.getValue("id").jsonPrimitive.content
                require(registers.put(id, reg) == null) { "内置 CSR 重复：$id" }
            }
            manifest.getValue("overlays").jsonArray.forEach { path ->
                val overlay = Json.parseToJsonElement(files.getValue(path.jsonPrimitive.content)).jsonObject
                val id = overlay.getValue("id").jsonPrimitive.content
                registers[id] = patch(registers.getValue(id), overlay.getValue("patch").jsonObject)
            }
        }
        val pack = Catalog.decode(JsonObject(metadata!! + ("registers" to JsonArray(registers.values.toList()))).toString())
        return LocalCatalogs(pack.copy(coverage = "${pack.registers.size} 个 CSR · ${pack.registers.sumOf { it.fields.size }} 个字段"), instructions, compiler)
    }

    private fun key(value: JsonElement, kind: String): String {
        val obj = value.jsonObject
        return when (kind) {
            "fields" -> obj.getValue("name").jsonPrimitive.content
            "sources" -> obj.getValue("namespace").jsonPrimitive.content
            "regions" -> listOf(obj["view"]?.jsonPrimitive?.content.orEmpty(), obj.getValue("label").jsonPrimitive.content,
                obj.getValue("layouts").jsonArray.joinToString(",") { b ->
                    val bits = b.jsonObject
                    listOf("xlen", "msb", "lsb").joinToString(":") { bits.getValue(it).jsonPrimitive.content }
                }).joinToString("|")
            else -> error("不支持的补充列表")
        }
    }

    private fun patch(value: JsonElement?, change: JsonObject): JsonElement {
        change["set"]?.let { return it }
        change["append"]?.let {
            val parts = listOf(value?.jsonPrimitive?.content.orEmpty(), it.jsonPrimitive.content)
            return JsonPrimitive(if (change["join"]?.jsonPrimitive?.boolean == true)
                parts.map(String::trim).filter(String::isNotEmpty).joinToString(" ") else parts.joinToString(""))
        }
        val remove = change.getValue("remove").jsonArray.map { it.jsonPrimitive.content }.toSet()
        change["object"]?.let { edits ->
            val result = value?.jsonObject?.toMutableMap() ?: linkedMapOf()
            remove.forEach(result::remove)
            edits.jsonObject.forEach { (k, v) -> result[k] = patch(result[k], v.jsonObject) }
            return JsonObject(result)
        }
        change["list"]?.let { kind ->
            val result = value?.jsonArray.orEmpty().associateBy { key(it, kind.jsonPrimitive.content) }.toMutableMap()
            remove.forEach(result::remove)
            change.getValue("items").jsonArray.forEach { item ->
                val edit = item.jsonObject
                val k = edit.getValue("key").jsonPrimitive.content
                result[k] = patch(result[k], edit.getValue("patch").jsonObject)
            }
            return JsonArray(result.values.toList())
        }
        val result = value!!.jsonArray.filter { it.jsonPrimitive.content !in remove }.toMutableList()
        change.getValue("add").jsonArray.forEach { if (it !in result) result.add(it) }
        return JsonArray(result)
    }
}
