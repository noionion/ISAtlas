package dev.isatlas.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** The same union-of-extensions filter is used by both catalogs. */
data class CatalogFilter(val xlen: Int, val privilege: String? = null, val namespace: String? = null,
                         val extensions: Set<String> = emptySet()) {
    fun accepts(widths: List<Int>, mode: String, source: String, tags: Set<String>) =
        xlen in widths && (privilege == null || privilege == normalizePrivilege(mode)) &&
            (namespace == null || namespace == source) &&
            (extensions.isEmpty() || extensions.any { it.lowercase() in tags })
}
fun normalizePrivilege(mode: String) = if (mode == "HS") "H" else mode
fun privilegeRank(mode: String) = listOf("M", "S", "H", "VS", "U", "D").indexOf(normalizePrivilege(mode)).let { if (it < 0) Int.MAX_VALUE else it }

@Serializable data class InstructionSource(val url: String)
@Serializable data class InstructionField(val name: String, val msb: Int, val lsb: Int, val value: Long? = null,
    val operand: String? = null, val operandBits: List<Int>? = null, val operandSigned: Boolean = false,
    val registerOffset: Int = 0, val registerMap: List<Int>? = null, val registerClass: String? = null,
    val values: Map<String, String> = emptyMap())
@Serializable data class EncodingConstraint(val mask: String, val operator: String, val values: List<Long>, val xlens: List<Int> = listOf(32, 64))
@Serializable data class InstructionContext(val summary: String, val summaryEn: String)
@Serializable data class InstructionEncoding(val length: Int, val xlens: List<Int>, val requires: List<List<String>>,
    val mask: String, val match: String, val fields: List<InstructionField>, val privilege: String = "U",
    val summary: String? = null, val summaryEn: String? = null, val constraints: List<String> = emptyList(),
    val encodingConstraints: List<EncodingConstraint> = emptyList(), val contextRules: List<InstructionContext> = emptyList(),
    val sources: List<InstructionSource> = emptyList()) {
    val tags get() = requires.flatten().map { it.lowercase() }.toSet()
    val requirements get() = requires.joinToString(" / ") { it.joinToString(" + ") }
    fun matches(word: Long, xlen: Int): Boolean = word in 0 until (1L shl length) &&
        word and mask.hex() == match.hex() && encodingConstraints.filter { xlen in it.xlens }.all {
            val value = word and it.mask.hex()
            when (it.operator) { "notIn" -> value !in it.values; "in" -> value in it.values; else -> false }
        }
    fun operands(word: Long): Map<String, String> = fields.filter { it.operand != null }.groupBy { it.operand!! }.mapValues { (_, pieces) ->
        var value = 0L
        var highest = 0
        pieces.forEach { field ->
            val bits = field.operandBits ?: (field.msb - field.lsb downTo 0).toList()
            bits.forEachIndexed { index, target ->
                value = value or (((word shr (field.msb - index)) and 1) shl target)
                highest = maxOf(highest, target)
            }
        }
        val field = pieces.first()
        if (pieces.any { it.operandSigned } && value and (1L shl highest) != 0L) value -= 1L shl (highest + 1)
        if (field.registerClass != null) {
            val number = field.registerMap?.getOrNull(value.toInt()) ?: (value.toInt() + field.registerOffset)
            "${when (field.registerClass) { "float" -> "f"; "vector" -> "v"; else -> "x" }}$number"
        } else value.toString()
    }
}
@Serializable data class InstructionDefinition(val schemaVersion: Int, val id: String, val namespace: String,
    val name: String, val summary: String, val summaryEn: String, val kind: String,
    val aliasOf: String? = null, val sourceRevision: String, val license: String,
    val encodings: List<InstructionEncoding>, val semanticSources: List<InstructionSource> = emptyList())
data class InstructionCatalog(val instructions: List<InstructionDefinition>, val version: String) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decodeFiles(manifest: String, read: (String) -> String): InstructionCatalog {
            val root = json.parseToJsonElement(manifest).jsonObject
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
            val files = root.getValue("files").jsonArray.map { it.jsonPrimitive.content }
            require(files.isNotEmpty() && files.distinct().size == files.size)
            val entries = files.map { file ->
                require(file.matches(Regex("[a-z0-9][a-z0-9._-]*\\.json")))
                val text = read(file)
                val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                require(hash == root.getValue("fileHashes").jsonObject.getValue(file).jsonPrimitive.content) { "Instruction hash mismatch: $file" }
                json.decodeFromString<InstructionDefinition>(text).also { instruction ->
                    require(instruction.schemaVersion == 1 && instruction.encodings.isNotEmpty())
                    instruction.encodings.forEach { require(it.length in listOf(16, 32) && it.fields.all { f -> f.lsb >= 0 && f.msb in f.lsb until it.length }) }
                }
            }
            require(entries.map { it.id }.distinct().size == entries.size)
            return InstructionCatalog(entries, root.getValue("version").jsonPrimitive.content)
        }
    }
}
private fun String.hex() = removePrefix("0x").toLong(16)
enum class InstructionMatchCategory { NAME, DESCRIPTION, ENCODING }
data class InstructionHit(val instruction: InstructionDefinition, val encoding: InstructionEncoding, val index: Int, val word: Long? = null,
    val category: InstructionMatchCategory = InstructionMatchCategory.NAME) {
    val key get() = "${instruction.id}:$index"
}
data class InstructionResults(val hits: List<InstructionHit>, val error: String? = null)
object InstructionSearch {
    fun query(catalog: InstructionCatalog, input: String, filter: CatalogFilter, compiler: CompilerIsaOptions? = null): InstructionResults {
        val query = input.trim().lowercase()
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        // Mnemonics support fragments (ret -> mret); English prose matches whole words.
        val descriptionPatterns = tokens.associateWith { token ->
            if (token.matches(Regex("[a-z0-9_.]+"))) Regex("(?<![a-z0-9_])${Regex.escape(token)}(?![a-z0-9_])") else null
        }
        val compact = query.replace(Regex("[\\s_]"), "")
        val numeric = compact.startsWith("0x") || compact.startsWith("0b") || compact.matches(Regex("[01]{16}|[01]{32}|[0-9a-f]{4}|[0-9a-f]{8}"))
        val word = if (!numeric) null else when {
            compact.startsWith("0x") -> compact.drop(2).toLongOrNull(16)
            compact.startsWith("0b") -> compact.drop(2).toLongOrNull(2)
            compact.length in listOf(16, 32) && compact.all { it in "01" } -> compact.toLongOrNull(2)
            else -> compact.toLongOrNull(16)
        }
        if (numeric && (word == null || word !in 0..0xffffffffL)) return InstructionResults(emptyList(), "请输入 16 或 32 位指令编码（0x 十六进制 / 0b 二进制）。")
        val parentTags = compiler?.tokens.orEmpty().filter { it in setOf("m", "a", "b", "c", "v") }.associateWith { compiler!!.associatedTags(setOf(it)) - setOf("i", "e", "f", "d", "zicsr") }
        val hits = catalog.instructions.flatMap { instruction -> instruction.encodings.mapIndexedNotNull { index, encoding ->
            val tags = encoding.tags + parentTags.filterValues { tags -> tags.any { it in encoding.tags } }.keys
            val nameMatch = tokens.all { it in instruction.name.lowercase() }
            val description = "${instruction.summary} ${instruction.summaryEn} ${encoding.summary.orEmpty()} ${encoding.summaryEn.orEmpty()}".lowercase()
            val textMatch = tokens.all { token -> token in instruction.name.lowercase() ||
                (descriptionPatterns[token]?.containsMatchIn(description) ?: (token in description)) }
            if (!filter.accepts(encoding.xlens, encoding.privilege, instruction.namespace, tags)) null
            else if (word != null && !encoding.matches(word, filter.xlen)) null
            else if (word == null && !textMatch) null
            else InstructionHit(instruction, encoding, index, word, when {
                word != null -> InstructionMatchCategory.ENCODING
                nameMatch -> InstructionMatchCategory.NAME
                else -> InstructionMatchCategory.DESCRIPTION
            })
        } }.sortedWith(compareBy<InstructionHit> { it.category.ordinal }
            .thenBy { privilegeRank(it.encoding.privilege) }
            .thenBy { if (it.instruction.name == query) 0 else if (it.instruction.name.startsWith(query)) 1 else 2 }
            .thenBy { if (word != null) -java.lang.Long.bitCount(it.encoding.mask.hex()) else 0 }
            .thenBy { it.instruction.name }.thenBy { it.index })
        return InstructionResults(hits)
    }
}
