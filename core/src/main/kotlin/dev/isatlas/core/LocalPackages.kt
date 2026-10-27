package dev.isatlas.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class PackageDependency(val id: String, val version: String)
@Serializable data class LocalPackageManifest(
    val schemaVersion: Int, val id: String, val namespace: String, val name: String, val version: String,
    val publisher: String, val architecture: String = "riscv", val minAppVersionCode: Int = 9,
    val description: String = "", val license: String = "私有数据", val licenseText: String = "",
    val csr: List<String> = emptyList(), val instructions: List<String> = emptyList(),
    val hashes: Map<String, String>, val dependencies: List<PackageDependency> = emptyList(),
    val extensions: Map<String, List<String>> = emptyMap(),
)
data class LocalPackageData(val manifest: LocalPackageManifest, val registers: List<RegisterDefinition>,
    val instructions: List<InstructionDefinition>, val sha256: String, val expandedBytes: Int = 0)
data class PackageConflict(val csrAddresses: Int = 0, val instructionEncodings: Int = 0) {
    val any get() = csrAddresses + instructionEncodings > 0
}
@Serializable data class LocalPackageRecord(val id: String, val name: String, val version: String, val file: String, val enabled: Boolean)
data class ManagedLocalPackage(val record: LocalPackageRecord, val data: LocalPackageData?, val problem: String? = null) {
    val active get() = record.enabled && data != null && problem == null
}
data class LocalCatalogs(val csr: DataPack, val instructions: InstructionCatalog, val compiler: CompilerIsaOptions)
class PackageValidationException(val location: String, val reason: String) : IllegalArgumentException("$location：$reason")

/** Local references identify provenance without inventing web URLs or publishing a file path. */
fun isSourceReference(value: String) = value.startsWith("https://") || value.matches(Regex("local://[a-z0-9][a-z0-9.-]{2,79}"))

object LocalPackages {
    const val MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 1024 * 1024
    private const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    private val json = Json
    private val token = Regex("[a-z][a-z0-9]{0,63}")
    private fun check(ok: Boolean, where: String, why: String) { if (!ok) throw PackageValidationException(where, why) }
    private inline fun <T> at(where: String, action: () -> T): T = try { action() } catch (e: PackageValidationException) {
        throw e
    } catch (_: Exception) { throw PackageValidationException(where, "JSON 字段缺失、类型错误或格式不受支持") }

    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun readBounded(input: InputStream, limit: Int = MAX_ARCHIVE_BYTES): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            check(output.size().toLong() + n <= limit, "文件", "数据超过大小限制")
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray, appVersionCode: Int, compilerTokens: Set<String>, reservedNamespaces: Set<String>): LocalPackageData {
        check(bytes.size <= MAX_ARCHIVE_BYTES && bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte(), "文件", "请选择 ISAtlas ZIP 数据包（最大 16 MiB）")
        val files = linkedMapOf<String, ByteArray>()
        at("ZIP") {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var total = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name
                    if (entry.isDirectory) {
                        check(path in setOf("csr/", "inst/"), "ZIP", "不允许的目录")
                        continue
                    }
                    check(path == "manifest.json" || path.matches(Regex("(?:csr|inst)/[a-z0-9][a-z0-9._-]{0,95}\\.json")), "ZIP", "路径不合法；只允许 manifest.json 和 csr/inst 中的 JSON")
                    check(path !in files && files.size < 2049, "ZIP", "重复文件或文件数量超过限制")
                    val content = readBounded(zip, if (path == "manifest.json") 256 * 1024 else MAX_ENTRY_BYTES)
                    total += content.size
                    check(total <= MAX_TOTAL_BYTES, "ZIP", "解压后的数据超过 8 MiB")
                    files[path] = content
                }
            }
        }
        val root = parse(files["manifest.json"] ?: throw PackageValidationException("manifest.json", "文件缺失"), "manifest.json")
        val manifest = at("manifest.json") { json.decodeFromJsonElement<LocalPackageManifest>(root) }
        validateManifest(manifest, appVersionCode, compilerTokens, reservedNamespaces)
        val declared = manifest.csr + manifest.instructions
        check(files.keys == (declared + "manifest.json").toSet(), "manifest.json", "声明与 ZIP 文件不一致，不能含有未声明内容")
        val csr = manifest.csr.map { path ->
            check(path.startsWith("csr/"), path, "CSR 文件应位于 csr/")
            val value = entry(files, manifest, path)
            val name = value["name"]?.jsonPrimitive?.content.orEmpty()
            val defaults = mapOf("id" to JsonPrimitive("riscv/${manifest.namespace}/csr/$name"), "namespace" to JsonPrimitive(manifest.namespace),
                "title" to (value["summary"] ?: JsonPrimitive("")), "summaryEn" to (value["summary"] ?: JsonPrimitive("")),
                "sourceUrl" to JsonPrimitive("local://${manifest.id}"), "sourceRevision" to JsonPrimitive(manifest.version),
                "license" to JsonPrimitive(manifest.license), "notes" to JsonPrimitive(""),
                "coverage" to JsonPrimitive(if (value["fields"]?.jsonArray.isNullOrEmpty()) "address-only" else "fields"))
            val fields = value["fields"]?.jsonArray?.map { f ->
                val obj = f.jsonObject
                JsonObject(mapOf("summaryEn" to (obj["summary"] ?: JsonPrimitive("")), "sourceUrl" to JsonPrimitive("local://${manifest.id}")) + obj)
            } ?: emptyList()
            val obj = JsonObject(defaults + value + ("fields" to JsonArray(fields)))
            at(path) { json.decodeFromJsonElement<RegisterDefinition>(obj) }.also { validateRegister(it, manifest, path, compilerTokens) }
        }
        check(csr.map { it.id }.distinct().size == csr.size, "csr", "重复 CSR 标识")
        check(csr.map { (it.locator as RegisterLocator.Csr).address }.distinct().size == csr.size, "csr", "包内 CSR 地址重复；不同布局请使用 view")
        val instructions = manifest.instructions.map { path ->
            check(path.startsWith("inst/"), path, "指令文件应位于 inst/")
            val obj = entry(files, manifest, path)
            val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
            val defaults = mapOf("schemaVersion" to JsonPrimitive(1), "id" to JsonPrimitive("riscv/${manifest.namespace}/inst/$name"),
                "namespace" to JsonPrimitive(manifest.namespace), "summaryEn" to (obj["summary"] ?: JsonPrimitive("")),
                "kind" to JsonPrimitive("instruction"), "sourceRevision" to JsonPrimitive(manifest.version), "license" to JsonPrimitive(manifest.license),
                "semanticSources" to JsonArray(listOf(buildJsonObject { put("url", "local://${manifest.id}") })))
            at(path) { json.decodeFromJsonElement<InstructionDefinition>(JsonObject(defaults + obj)) }.also { validateInstruction(it, manifest, path, compilerTokens) }
        }
        check(instructions.map { it.id }.distinct().size == instructions.size, "inst", "重复指令标识")
        instructions.filter { it.kind == "alias" }.forEach { alias ->
            check(instructions.any { it.name == alias.aliasOf && it.kind != "alias" }, "inst/${alias.name}", "aliasOf 必须指向包内基础指令")
        }
        return LocalPackageData(manifest, csr, instructions, sha256(bytes), files.values.sumOf { it.size })
    }

    private fun parse(bytes: ByteArray, location: String): JsonObject = at(location) {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        JsonShape(text, location).validate()
        json.parseToJsonElement(text).jsonObject
    }
    private fun entry(files: Map<String, ByteArray>, m: LocalPackageManifest, path: String): JsonObject {
        val content = files[path] ?: throw PackageValidationException(path, "文件缺失")
        check(sha256(content) == m.hashes[path], path, "SHA-256 不匹配")
        return parse(content, path)
    }
    private fun validateManifest(m: LocalPackageManifest, app: Int, known: Set<String>, reserved: Set<String>) {
        val p = "manifest.json"
        check(m.schemaVersion == 1 && m.architecture == "riscv", p, "仅支持 schemaVersion=1、architecture=riscv")
        check(m.minAppVersionCode in 1..app, p, "需要更新版本的 ISAtlas")
        check(m.id.matches(Regex("[a-z0-9][a-z0-9.-]{2,79}")) && token.matches(m.namespace), p, "包 ID 或命名空间格式错误")
        check(m.namespace !in reserved && m.namespace !in setOf("manual", "standard", "andes", "aia", "debug", "spmp", "catalog"), p, "命名空间与内置来源冲突")
        check(listOf(m.name, m.publisher, m.version, m.license).all { it.isNotBlank() && it.length <= 160 }, p, "名称、发布者、版本和许可必填且不超过 160 字符")
        check(m.description.length <= 4096 && m.licenseText.length <= 32768, p, "描述或许可正文过长")
        val paths = m.csr + m.instructions
        check(paths.isNotEmpty() && paths.size <= 2048 && paths.distinct().size == paths.size, p, "文件列表为空、重复或超过限制")
        check(m.hashes.keys == paths.toSet() && m.hashes.values.all { it.matches(Regex("[0-9a-f]{64}")) }, p, "hashes 必须覆盖全部数据文件")
        check(m.dependencies.size <= 32 && m.dependencies.map { it.id }.distinct().size == m.dependencies.size && m.dependencies.none { it.id == m.id || it.version.isBlank() }, p, "依赖标识重复、自引用或版本缺失")
        check(m.extensions.size <= 128 && m.extensions.keys.all { it.matches(Regex("x[a-z0-9]{1,63}")) && it !in known }, p, "自定义扩展须使用未占用的 x 前缀名称")
        m.extensions.forEach { (name, dependencies) ->
            check(dependencies.size <= 32 && dependencies.all { it in known || it in m.extensions }, "$p/extensions/$name", "前置扩展未定义")
            fun visit(key: String, path: Set<String>) {
                check(key !in path, "$p/extensions/$name", "前置扩展存在循环")
                m.extensions[key].orEmpty().filter { it in m.extensions }.forEach { visit(it, path + key) }
            }
            visit(name, emptySet())
        }
    }

    private fun validateRegister(r: RegisterDefinition, m: LocalPackageManifest, p: String, known: Set<String>) = at(p) {
        check(r.namespace == m.namespace && r.id == "riscv/${m.namespace}/csr/${r.name}", p, "CSR 标识与命名空间不一致")
        check(r.name.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,95}")), p, "CSR 名称格式错误")
        check(r.privilege in setOf("M", "S", "H", "HS", "VS", "U", "D") && r.access in setOf("RW", "RO"), p, "特权态或访问属性不合法")
        check(r.summary.isNotBlank() && r.summaryEn.isNotBlank() && r.summary.length <= 4096 && r.notes.length <= 16384, p, "描述缺失或过长")
        check(r.fields.size + r.regions.size <= 512, p, "字段过多")
        check(r.extensions.all { it.lowercase() in known || it.lowercase() in m.extensions }, p, "扩展未定义；自定义扩展需在 manifest.extensions 声明")
        r.fields.forEach { f ->
            check(f.name.length <= 160 && f.access.isNotBlank() && f.summary.isNotBlank() && f.summaryEn.isNotBlank() && isSourceReference(f.sourceUrl), "$p/${f.name}", "字段描述、访问属性或来源不合法")
            check(f.aliases.size <= 128 && f.values.size <= 512 && f.values.keys == f.valuesEn.keys, "$p/${f.name}", "别名/枚举过多或中英文枚举编码不一致")
            check(f.values.values.all { it.isNotBlank() } && f.valuesEn.values.all { it.isNotBlank() }, "$p/${f.name}", "枚举说明不能为空")
        }
        val pack = DataPack(1, m.id, "riscv", m.namespace, m.version, m.name, "本地导入", Source(m.name, m.version, "local://${m.id}"), listOf(r))
        try { Catalog.validate(pack) } catch (_: Exception) { throw PackageValidationException(p, "位段越界、重叠、枚举溢出、来源或布局不合法") }
    }

    private fun validateInstruction(i: InstructionDefinition, m: LocalPackageManifest, p: String, known: Set<String>) = at(p) {
        check(i.schemaVersion == 1 && i.namespace == m.namespace && i.id == "riscv/${m.namespace}/inst/${i.name}", p, "指令标识、命名空间或格式版本不一致")
        check(i.name.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,95}")) && i.summary.isNotBlank() && i.summaryEn.isNotBlank(), p, "指令名称或描述不合法")
        check(i.kind in setOf("instruction", "alias", "hint") && i.encodings.size in 1..64 && i.encodings.distinct().size==i.encodings.size, p, "指令类型、重复编码或编码数量不合法")
        check(i.semanticSources.all { isSourceReference(it.url) }, p, "指令来源不合法")
        i.encodings.forEachIndexed { index, e ->
            val path = "$p/encodings/$index"
            check(e.length in setOf(16,32) && e.xlens.isNotEmpty() && e.xlens.distinct().size == e.xlens.size && e.xlens.all { it in setOf(32,64) }, path, "指令长度或架构位宽不合法")
            check(e.privilege in setOf("M","S","H","HS","VS","U","D"), path, "执行权限不合法")
            check(e.requires.size in 1..32 && e.requires.all { group -> group.isNotEmpty() && group.size <= 32 && group.all { it in known || it in m.extensions } }, path, "扩展要求缺失或未声明")
            val mask = hex(e.mask, path); val match = hex(e.match, path); val bound = (1L shl e.length)-1
            check(mask and bound == mask && match and mask == match, path, "mask/match 越界或 match 含非固定编码位")
            check(mask and 3L == 3L && (if (e.length == 16) match and 3L != 3L else match and 3L == 3L && mask and 31L == 31L && match and 31L != 31L), path, "固定低位与 16/32 位指令长度不一致")
            check(e.fields.size in 1..64 && e.fields.map { it.name }.distinct().size == e.fields.size, path, "字段为空、重复或过多")
            var used=0L
            val operands=mutableMapOf<String,MutableSet<Int>>()
            e.fields.forEach { f ->
                check(f.name.isNotBlank() && f.lsb >= 0 && f.msb in f.lsb until e.length, "$path/${f.name}", "字段位段越界")
                val width=f.msb-f.lsb+1; val bits=((1L shl width)-1) shl f.lsb
                check(used and bits == 0L, "$path/${f.name}", "字段位段重叠"); used=used or bits
                if (f.value != null) check(f.value in 0 until (1L shl width) && mask and bits == bits && (match and bits) shr f.lsb == f.value, "$path/${f.name}", "固定字段与 mask/match 不一致")
                else check(mask and bits == 0L && !f.operand.isNullOrBlank(), "$path/${f.name}", "可变字段须声明 operand，且不能覆盖固定编码位")
                f.operand?.let { operand ->
                    val positions=f.operandBits ?: (width-1 downTo 0).toList()
                    check(positions.size == width && positions.distinct().size == width && positions.all { it in 0..31 }, "$path/${f.name}", "operandBits 长度或位置不合法")
                    val seen=operands.getOrPut(operand) { mutableSetOf() }
                    check(positions.none { it in seen }, "$path/${f.name}", "操作数位重复映射"); seen.addAll(positions)
                    check(f.registerClass == null || f.registerClass in setOf("integer","float","vector"), "$path/${f.name}", "寄存器类型不受支持")
                    if (f.registerClass != null) {
                        check(!f.operandSigned && width <= 5 && (f.registerMap?.let { map -> map.size == (1 shl width) && map.all { it in 0..31 } } ?: (f.registerOffset >= 0 && (1 shl width)+f.registerOffset<=32)), "$path/${f.name}", "寄存器编号映射超出范围")
                    }
                }
            }
            check(used == bound, path, "编码字段必须覆盖全部指令位")
            check(e.encodingConstraints.size<=8 && e.operandRelations.size<=4, path, "静态约束数量超过限制")
            var constraintBits=0L
            e.encodingConstraints.forEach { c ->
                val cm=hex(c.mask,path)
                check(c.operator in setOf("in","notIn") && cm != 0L && cm and bound == cm && c.values.size in 1..32 && c.values.all { it>=0 && it and cm==it } && c.xlens.isNotEmpty() && c.xlens.all { it in e.xlens }, path, "编码约束不合法")
                val variable=cm and mask.inv()
                check(java.lang.Long.bitCount(variable)<=12 && variable and constraintBits==0L, path, "可变约束最多覆盖 12 位，多个约束不可重复覆盖可变位")
                constraintBits=constraintBits or variable
            }
            check(e.operandRelations.all { it.operator=="eq" && it.left in operands && it.right in operands }, path, "操作数关系引用不存在的操作数或运算不受支持")
            check(i.assemblyOperands?.all { it in operands } != false && e.sources.all { isSourceReference(it.url) }, path, "汇编操作数或来源引用无效")
            check(e.xlens.all { instructionExampleWord(e,it,0)!=null },path,"静态约束相互矛盾或操作数关系无法求解")
        }
    }
    private fun hex(value: String, p: String): Long {
        check(value.matches(Regex("0x[0-9a-fA-F]{1,8}")), p, "编码须使用最多 8 位的 0x 十六进制")
        return value.drop(2).toLong(16)
    }

    fun statuses(items: List<ManagedLocalPackage>): List<ManagedLocalPackage> {
        val byId=items.associateBy { it.record.id }
        fun reason(item: ManagedLocalPackage, seen: Set<String>): String? {
            if (item.data==null) return item.problem ?: "数据包不可读取"
            if (item.record.id in seen) return "数据包依赖循环"
            for (dep in item.data.manifest.dependencies) {
                val target=byId[dep.id]
                if (target==null || !target.record.enabled || target.record.version!=dep.version || reason(target,seen+item.record.id)!=null)
                    return "依赖未满足：${dep.id} ${dep.version}"
            }
            return null
        }
        return items.map { it.copy(problem=reason(it,emptySet())) }
    }

    fun merge(base: LocalCatalogs, enabled: List<LocalPackageData>): LocalCatalogs {
        check(enabled.map { it.manifest.namespace }.distinct().size==enabled.size,"数据包","命名空间已被其他本地包使用")
        val csr=base.csr.copy(registers=base.csr.registers+enabled.flatMap { it.registers })
        Catalog.validate(csr)
        val instructions=base.instructions.copy(instructions=base.instructions.instructions+enabled.flatMap { it.instructions })
        check(instructions.instructions.map { it.id }.distinct().size==instructions.instructions.size,"数据包","指令标识冲突")
        val custom=enabled.flatMap { it.manifest.extensions.keys }
        check(custom.distinct().size==custom.size && custom.none { it in base.compiler.tokens },"数据包","自定义扩展名称被其他包占用")
        return LocalCatalogs(csr,instructions,base.compiler.copy(tokens=base.compiler.tokens+custom,
            dependencies=base.compiler.dependencies+enabled.flatMap { it.manifest.extensions.entries }.associate { it.key to it.value }))
    }

    fun conflicts(data: LocalPackageData, current: LocalCatalogs): PackageConflict {
        val addresses=current.csr.registers.map { (it.locator as RegisterLocator.Csr).address }.toSet()
        val csr=data.registers.count { (it.locator as RegisterLocator.Csr).address in addresses }
        val existing=current.instructions.instructions.flatMap { it.encodings }.groupBy { it.length }.mapValues { it.value.toMutableList() }.toMutableMap()
        var count=0
        data.instructions.forEach { instruction -> instruction.encodings.forEach { e ->
            val mask=e.mask.drop(2).toLong(16); val match=e.match.drop(2).toLong(16)
            if (existing[e.length].orEmpty().any { other -> e.xlens.any { it in other.xlens } &&
                (match xor other.match.removePrefix("0x").toLong(16)) and mask and other.mask.removePrefix("0x").toLong(16)==0L }) count++
            existing.getOrPut(e.length) { mutableListOf() }.add(e)
        } }
        return PackageConflict(csr,count)
    }
}

/** Reject duplicate keys and excessive nesting before kotlinx.serialization parses untrusted JSON. */
private class JsonShape(private val text: String, private val location: String) {
    private var i=0
    private fun fail(): Nothing = throw PackageValidationException(location,"JSON 结构错误、重复键或嵌套超过 32 层")
    private fun ws() { while (i<text.length && text[i] in " \r\n\t") i++ }
    private fun string(): String {
        val start=i
        if (i>=text.length || text[i++]!='"') fail()
        while (i<text.length) {
            when (text[i++]) { '\\' -> { if (i>=text.length) fail(); i++ }; '"' -> return Json.parseToJsonElement(text.substring(start,i)).jsonPrimitive.content }
        }
        fail()
    }
    private fun value(depth: Int) {
        if (depth>32) fail()
        ws(); if (i>=text.length) fail()
        when (text[i]) {
            '{' -> {
                i++; ws(); val keys=mutableSetOf<String>()
                if (i<text.length && text[i]=='}') { i++; return }
                while (true) {
                    ws(); if (!keys.add(string())) fail(); ws()
                    if (i>=text.length || text[i++]!=':') fail()
                    value(depth+1); ws()
                    if (i>=text.length) fail()
                    if (text[i]=='}') { i++; break }
                    if (text[i++]!=',') fail()
                }
            }
            '[' -> {
                i++; ws(); if (i<text.length && text[i]==']') { i++; return }
                while (true) { value(depth+1); ws(); if (i>=text.length) fail(); if (text[i]==']') { i++; break }; if (text[i++]!=',') fail() }
            }
            '"' -> string()
            else -> { val start=i; while(i<text.length && text[i] !in ",]} \r\n\t") i++; if(i==start) fail() }
        }
    }
    fun validate() { value(0); ws(); if(i!=text.length) fail() }
}
