package dev.isatlas.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Shared catalog filtering; instructions additionally validate complete requirement groups. */
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
    /** Alternative groups are OR; capabilities within one group are AND. */
    fun supportsExtensions(capabilities: Set<String>): Boolean = capabilities.isEmpty() ||
        requires.any { group -> group.all { it.lowercase() in capabilities } }
    fun fixedMatches(word: Long): Boolean = word in 0 until (1L shl length) && word and mask.hex() == match.hex()
    fun matches(word: Long, xlen: Int): Boolean = fixedMatches(word) && encodingConstraints.filter { xlen in it.xlens }.all {
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
data class InstructionResults(val hits: List<InstructionHit>, val error: String? = null, val diagnostic: InstructionDiagnostic? = null)
object InstructionSearch {
    fun query(catalog: InstructionCatalog, input: String, filter: CatalogFilter, compiler: CompilerIsaOptions? = null): InstructionResults {
        val query = input.trim().lowercase()
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        // Mnemonics support fragments (ret -> mret); English prose matches whole words.
        val descriptionPatterns = tokens.associateWith { token ->
            if (token.matches(Regex("[a-z0-9_.]+"))) Regex("(?<![a-z0-9_])${Regex.escape(token)}(?![a-z0-9_])") else null
        }
        val parsed = InstructionInput.parse(input)
        if (parsed.error != null) return InstructionResults(emptyList(), parsed.error, InstructionDiagnostic(InstructionDiagnosticKind.INPUT, parsed.error))
        val word = parsed.word
        val selectedExtensions = compiler?.associatedTags(filter.extensions) ?: filter.extensions.map { it.lowercase() }.toSet()
        val effectiveFilter = filter.copy(extensions = selectedExtensions)
        val parentTags = compiler?.tokens.orEmpty().filter { it in setOf("m", "a", "b", "c", "v") }.associateWith { compiler!!.associatedTags(setOf(it)) - setOf("i", "e", "f", "d", "zicsr") }
        val available = mutableListOf<Pair<InstructionHit, Set<String>>>()
        var fixedCandidate = false
        val hits = catalog.instructions.flatMap { instruction -> instruction.encodings.mapIndexedNotNull { index, encoding ->
            val tags = encoding.tags + parentTags.filterValues { tags -> tags.any { it in encoding.tags } }.keys
            val nameMatch = tokens.all { it in instruction.name.lowercase() }
            val description = "${instruction.summary} ${instruction.summaryEn} ${encoding.summary.orEmpty()} ${encoding.summaryEn.orEmpty()}".lowercase()
            val textMatch = tokens.all { token -> token in instruction.name.lowercase() ||
                (descriptionPatterns[token]?.containsMatchIn(description) ?: (token in description)) }
            if (word != null && encoding.fixedMatches(word)) fixedCandidate = true
            val category = when {
                word != null -> InstructionMatchCategory.ENCODING
                nameMatch -> InstructionMatchCategory.NAME
                else -> InstructionMatchCategory.DESCRIPTION
            }
            val hit = InstructionHit(instruction, encoding, index, word, category)
            val anyWidthMatch = if (word == null) textMatch else encoding.xlens.any { encoding.matches(word, it) }
            if (anyWidthMatch) available += hit to tags
            if (!anyWidthMatch || !effectiveFilter.accepts(encoding.xlens, encoding.privilege, instruction.namespace, tags) ||
                !encoding.supportsExtensions(selectedExtensions) ||
                (word != null && !encoding.matches(word, filter.xlen))) null else hit
        } }.sortedWith(compareBy<InstructionHit> { it.category.ordinal }
            .thenBy { privilegeRank(it.encoding.privilege) }
            .thenBy { if (it.instruction.name == query) 0 else if (it.instruction.name.startsWith(query)) 1 else 2 }
            .thenBy { if (word != null) -java.lang.Long.bitCount(it.encoding.mask.hex()) else 0 }
            .thenBy { it.instruction.name }.thenBy { it.index })
        if (hits.isNotEmpty()) return InstructionResults(hits)
        if (available.isNotEmpty()) {
            val candidates = available.map { (hit, tags) ->
                val widths = hit.encoding.xlens.filter { word == null || hit.encoding.matches(word, it) }
                val blocked = buildSet {
                    if (filter.xlen !in widths) add(InstructionFilterDimension.WIDTH)
                    if (filter.privilege != null && filter.privilege != normalizePrivilege(hit.encoding.privilege)) add(InstructionFilterDimension.PRIVILEGE)
                    if (filter.namespace != null && filter.namespace != hit.instruction.namespace) add(InstructionFilterDimension.SOURCE)
                    if (selectedExtensions.isNotEmpty() && (selectedExtensions.none { it in tags } || !hit.encoding.supportsExtensions(selectedExtensions))) add(InstructionFilterDimension.EXTENSION)
                }
                blocked to widths
            }
            val (blocked, widths) = candidates.minWith(compareBy<Pair<Set<InstructionFilterDimension>, List<Int>>> { it.first.size }
                .thenBy { InstructionFilterDimension.WIDTH in it.first })
            return InstructionResults(emptyList(), diagnostic = InstructionDiagnostic(InstructionDiagnosticKind.FILTERED,
                "已收录的匹配项被${blocked.joinToString("、") { it.label }}筛选排除。", blocked,
                if (filter.xlen !in widths) widths.first() else null))
        }
        return InstructionResults(emptyList(), diagnostic = if (word != null && fixedCandidate)
            InstructionDiagnostic(InstructionDiagnosticKind.RESERVED, "匹配到编码模板，但操作数字段违反已收录的静态限制，属于该模板的保留编码。")
        else InstructionDiagnostic(InstructionDiagnosticKind.UNKNOWN, if (word == null) "当前数据库未找到对应名称或描述。" else "当前数据库未收录匹配编码，无法据此判断该编码是否合法。"))
    }
}
