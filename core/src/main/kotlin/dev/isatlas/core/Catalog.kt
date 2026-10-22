package dev.isatlas.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
sealed interface RegisterLocator {
    @Serializable @SerialName("riscv-csr")
    data class Csr(val address: Int) : RegisterLocator
}

val RegisterLocator.label: String
    get() = when (this) {
        is RegisterLocator.Csr -> "0x" + address.toString(16).padStart(3, '0').uppercase()
    }

@Serializable
data class Source(val title: String, val revision: String, val url: String)

@Serializable
data class BitLayout(val xlen: Int, val msb: Int, val lsb: Int) {
    val label: String get() = if (msb == lsb) "[$lsb]" else "[$msb:$lsb]"
}

@Serializable
data class FieldDefinition(
    val name: String,
    val summary: String,
    val access: String,
    val layouts: List<BitLayout>,
    val values: Map<String, String> = emptyMap(),
    val condition: String = "",
    val aliases: List<String> = emptyList(),
    val typeRule: String = "",
    val sourceUrl: String = "",
    val summaryEn: String = "",
    val valuesEn: Map<String, String> = emptyMap(),
    val valuesByXlen: Map<Int, Map<String, String>> = emptyMap(),
    val valuesEnByXlen: Map<Int, Map<String, String>> = emptyMap(),
) {
    fun layout(xlen: Int) = layouts.singleOrNull { it.xlen == xlen }
}

@Serializable
data class BitRegion(
    val label: String,
    val access: String,
    val summary: String,
    val layouts: List<BitLayout>,
    val sourceUrl: String = "",
    val summaryEn: String = "",
) {
    fun layout(xlen: Int) = layouts.singleOrNull { it.xlen == xlen }
}

@Serializable
data class RegisterDefinition(
    val id: String,
    val name: String,
    val title: String,
    val summary: String,
    val locator: RegisterLocator,
    val privilege: String,
    val access: String,
    val xlens: List<Int> = listOf(32, 64),
    val condition: String = "",
    val notes: String,
    val sourceUrl: String,
    val fields: List<FieldDefinition>,
    val regions: List<BitRegion> = emptyList(),
    val namespace: String = "",
    val sourceRevision: String = "",
    val license: String = "",
    val coverage: String = "fields",
    val extensions: List<String> = emptyList(),
    val summaryEn: String = "",
)

@Serializable
data class DataPack(
    val schemaVersion: Int,
    val id: String,
    val architecture: String,
    val namespace: String,
    val version: String,
    val title: String,
    val coverage: String,
    val source: Source,
    val registers: List<RegisterDefinition>,
)

object Catalog {
    fun decode(text: String): DataPack = Json.decodeFromString<DataPack>(text).also(::validate)

    fun decodeFiles(manifest: String, readCsr: (String) -> String): DataPack {
        val metadata = Json.parseToJsonElement(manifest).jsonObject
        require("registers" !in metadata) { "索引不能包含内联 CSR" }
        val files = metadata.getValue("files").jsonArray.map { it.jsonPrimitive.content }
        require(files.isNotEmpty() && files.distinct().size == files.size) { "CSR 文件索引为空或重复" }
        require(files.all { it.matches(Regex("[a-z0-9][a-z0-9._-]*\\.json")) }) { "无效 CSR 文件名" }
        val registers = files.map { Json.parseToJsonElement(readCsr(it)).jsonObject }
        return decode(JsonObject(metadata.filterKeys { it != "files" } + ("registers" to JsonArray(registers))).toString())
    }

    fun validate(pack: DataPack) {
        require(pack.schemaVersion == 1) { "不支持的数据格式版本" }
        require(pack.architecture == "riscv") { "当前版本仅支持 RISC-V 数据包" }
        require(pack.id.isNotBlank() && pack.namespace.isNotBlank() && pack.version.isNotBlank())
        require(pack.registers.isNotEmpty()) { "数据包为空" }
        require(pack.source.revision.isNotBlank() && pack.source.url.startsWith("https://"))
        require(pack.registers.map { it.id }.distinct().size == pack.registers.size) { "寄存器身份重复" }
        pack.registers.forEach { reg ->
            require(reg.id.startsWith("${pack.architecture}/${reg.namespace.ifBlank { pack.namespace }}/csr/"))
            require(reg.coverage in setOf("fields", "address-only"))
            require(reg.coverage != "address-only" || reg.fields.isEmpty())
            require(reg.name.isNotBlank() && reg.sourceUrl.startsWith("https://"))
            require(reg.xlens.isNotEmpty() && reg.xlens.distinct().size == reg.xlens.size)
            require(reg.xlens.all { it == 32 || it == 64 })
            require((reg.locator as RegisterLocator.Csr).address in 0..0xfff)
            require(reg.fields.map { it.name }.distinct().size == reg.fields.size)
            reg.fields.forEach { field ->
                require(field.name.isNotBlank() && field.layouts.isNotEmpty())
                require(field.layouts.map { it.xlen }.distinct().size == field.layouts.size)
                field.layouts.forEach { bits ->
                    require(bits.xlen in reg.xlens && bits.lsb >= 0 && bits.msb >= bits.lsb && bits.msb < bits.xlen) {
                        "${reg.name}.${field.name}: 无效位范围"
                    }
                    require(field.valuesByXlen.keys.all { width -> field.layouts.any { it.xlen == width } })
                    require(field.valuesEn.isEmpty() || field.valuesEn.keys == field.values.keys)
                    require(field.valuesEnByXlen.all { (width, values) -> field.valuesByXlen[width]?.keys == values.keys })
                    (field.valuesByXlen[bits.xlen] ?: field.values).keys.forEach { raw ->
                        val value = raw.toULongOrNull() ?: error("无效枚举编码")
                        val width = bits.msb - bits.lsb + 1
                        require(width == 64 || value < (1uL shl width)) { "枚举编码溢出" }
                    }
                }
            }
            reg.xlens.forEach { xlen ->
                val used = mutableSetOf<Int>()
                reg.fields.mapNotNull { it.layout(xlen) }.forEach { bits ->
                    (bits.lsb..bits.msb).forEach { bit -> require(used.add(bit)) { "${reg.name}: 字段位重叠" } }
                }
                reg.regions.forEach { region ->
                    require(region.label.isNotBlank() && region.access.isNotBlank() && region.summary.isNotBlank())
                    require(region.layouts.isNotEmpty() && region.layouts.map { it.xlen }.distinct().size == region.layouts.size)
                    region.layouts.forEach { bits ->
                        require(bits.xlen in reg.xlens && bits.lsb >= 0 && bits.msb >= bits.lsb && bits.msb < bits.xlen)
                    }
                    region.layout(xlen)?.let { bits ->
                        (bits.lsb..bits.msb).forEach { bit -> require(used.add(bit)) { "${reg.name}: 规范区域与字段重叠" } }
                    }
                }
            }
        }
    }
}
