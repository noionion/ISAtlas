package dev.isatlas.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Locale

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
@Serializable data class OperandRelation(val left: String, val right: String, val operator: String)
@Serializable data class InstructionContext(val summary: String, val summaryEn: String)
@Serializable data class InstructionEncoding(val length: Int, val xlens: List<Int>, val requires: List<List<String>>,
    val mask: String, val match: String, val fields: List<InstructionField>, val privilege: String = "U",
    val summary: String? = null, val summaryEn: String? = null, val constraints: List<String> = emptyList(),
    val encodingConstraints: List<EncodingConstraint> = emptyList(), val contextRules: List<InstructionContext> = emptyList(),
    val sources: List<InstructionSource> = emptyList(), val operandRelations: List<OperandRelation> = emptyList()) {
    val tags get() = requires.flatten().map { it.lowercase() }.toSet()
    val requirements get() = requires.joinToString(" / ") { it.joinToString(" + ") }
    /** Alternative groups are OR; capabilities within one group are AND. */
    fun supportsExtensions(capabilities: Set<String>): Boolean = capabilities.isEmpty() ||
        requires.any { group -> group.all { it.lowercase() in capabilities } }
    fun fixedMatches(word: Long): Boolean = word in 0 until (1L shl length) && word and mask.hex() == match.hex()
    fun matches(word: Long, xlen: Int): Boolean = fixedMatches(word) && encodingConstraints.filter { xlen in it.xlens }.all {
            val value = word and it.mask.hex()
            when (it.operator) { "notIn" -> value !in it.values; "in" -> value in it.values; else -> false }
        } && (operandRelations.isEmpty() || operands(word).let { values -> operandRelations.all {
            it.operator == "eq" && values[it.left] != null && values[it.left] == values[it.right]
        } })
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
    val encodings: List<InstructionEncoding>, val semanticSources: List<InstructionSource> = emptyList(),
    val assemblyOperands: List<String>? = null)
data class InstructionCatalog(val instructions: List<InstructionDefinition>, val version: String,
    val pseudos: List<InstructionPseudo> = emptyList()) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decodeFiles(manifest: String, read: (String) -> String): InstructionCatalog {
            val root = json.parseToJsonElement(manifest).jsonObject
            val schema = root.getValue("schemaVersion").jsonPrimitive.int
            require(schema in setOf(1, 2))
            val features = root["requiredFeatures"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
            require(features.all { it in setOf("operand-relations-v1", "alias-assembly-v1", "pseudo-references-v1") })
            if (schema == 2) require(features.isNotEmpty())
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
            if (entries.any { it.encodings.any { encoding -> encoding.operandRelations.isNotEmpty() } })
                require(schema == 2 && "operand-relations-v1" in features)
            if (entries.any { it.assemblyOperands != null }) require(schema == 2 && "alias-assembly-v1" in features)
            val pseudoFiles = root["pseudoFiles"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            require(pseudoFiles.distinct().size == pseudoFiles.size && pseudoFiles.none { it in files })
            val pseudos = pseudoFiles.map { file ->
                require(file.matches(Regex("[a-z0-9][a-z0-9._-]*\\.json")))
                val text = read(file)
                val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                require(hash == root.getValue("pseudoFileHashes").jsonObject.getValue(file).jsonPrimitive.content) { "Pseudo hash mismatch: $file" }
                require("encodings" !in json.parseToJsonElement(text).jsonObject)
                json.decodeFromString<InstructionPseudo>(text).also { pseudo ->
                    require(pseudo.referenceStatus in setOf("defined", "example", "historical"))
                    require(pseudo.schemaVersion == 1 && pseudo.kind == "pseudo" && (pseudo.expansions.isNotEmpty() || pseudo.referenceOnly))
                    require(pseudo.xlens.isNotEmpty() && pseudo.xlens.all { it in listOf(32, 64) } && (pseudo.requires.isNotEmpty() || pseudo.referenceStatus == "historical"))
                    require(pseudo.requires.all { it.isNotEmpty() } && pseudo.semanticSources.isNotEmpty())
                    require(pseudo.expansions.all { it.steps.isNotEmpty() && it.steps.all { step -> step.assembly.isNotBlank() && entries.any { entry -> entry.name == step.name && entry.namespace == pseudo.namespace } } })
                }
            }
            require((entries.map { it.id } + pseudos.map { it.id }).distinct().size == entries.size + pseudos.size)
            return InstructionCatalog(entries, root.getValue("version").jsonPrimitive.content, pseudos)
        }
    }
}
private fun String.hex() = removePrefix("0x").toLong(16)
enum class InstructionMatchCategory(val label: String) { NAME("指令名"), DESCRIPTION("描述相关"), ENCODING("编码匹配") }
data class InstructionHit(val instruction: InstructionDefinition, val encoding: InstructionEncoding, val index: Int, val word: Long? = null,
    val category: InstructionMatchCategory = InstructionMatchCategory.NAME, val pattern: InstructionPattern? = null) {
    val key get() = "${instruction.id}:$index"
    val encodingQuery get() = pattern?.query ?: word?.let { "0x${it.toString(16)}" }
}
data class InstructionResults(val hits: List<InstructionHit>, val error: String? = null, val diagnostic: InstructionDiagnostic? = null,
    val pseudoHits: List<InstructionPseudoHit> = emptyList(), val input: String = "", val pattern: InstructionPattern? = null) {
    /** Group only the presentation; retain every encoding and source, in search priority order. */
    val groups: List<InstructionResultGroup> get() {
        val machine = hits.groupBy { it.category to it.instruction.name }.map { (identity, variants) ->
            InstructionResultGroup(identity.first, identity.second, variants)
        }
        if (pseudoHits.isEmpty()) return machine
        val references = pseudoHits.groupBy { it.category to it.instruction.name }.map { (identity, variants) ->
            InstructionResultGroup(identity.first, identity.second, emptyList(), variants)
        }
        return (machine + references).groupBy { it.category to it.name }.map { (identity, groups) ->
            InstructionResultGroup(identity.first, identity.second, groups.flatMap { it.hits }, groups.flatMap { it.pseudoHits })
        }.sortedWith(compareBy<InstructionResultGroup> { it.category.ordinal }
            .thenBy { if (it.name.equals(input.trim(), ignoreCase = true)) 0 else 1 }
            .thenBy { it.privilegeOrder }.thenBy { if (it.name.startsWith(input.trim(), ignoreCase = true)) 0 else 1 }.thenBy { it.name })
    }
}
data class InstructionResultGroup(val category: InstructionMatchCategory, val name: String, val hits: List<InstructionHit>,
    val pseudoHits: List<InstructionPseudoHit> = emptyList()) {
    val key get() = "${category.name}:$name"
    val size get() = hits.size + pseudoHits.size
    val privilegeOrder get() = (hits.map { privilegeRank(it.encoding.privilege) } + pseudoHits.map { privilegeRank(it.instruction.privilege) }).min()
}

/** Explain available candidates without assuming the user's complete ISA or runtime state. */
object InstructionCandidates {
    fun explanations(hits: List<InstructionHit>): List<String> = buildList {
        if (hits.size < 2 || hits.any { it.encodingQuery == null } || hits.map { it.encodingQuery }.distinct().size != 1) return@buildList
        val partial = hits.first().pattern != null
        if (partial) add("各候选均可满足已知位与静态编码限制；不同候选可能对应不同的完整机器码。")
        hits.filter { it.instruction.kind == "alias" }.forEach { alias ->
            if (hits.any { it.instruction.namespace == alias.instruction.namespace && it.instruction.name == alias.instruction.aliasOf })
                add(if (partial) "${alias.instruction.name} 是 ${alias.instruction.aliasOf} 的别名，仍需满足该别名的固定字段与操作数约束。"
                    else "${alias.instruction.name} 是 ${alias.instruction.aliasOf} 的别名；两者表示同一编码的不同写法。")
        }
        if (hits.map { it.instruction.namespace }.distinct().size > 1)
            add("匹配到多个数据来源；所有来源候选均保留，请分别核对扩展和语义。")
        if (hits.map { it.encoding.requires.map { group -> group.map { it.lowercase(Locale.ROOT) }.sorted() }.sortedBy { it.joinToString() } }.distinct().size > 1)
            add("候选的扩展要求不同，可能复用同一编码空间；请按目标 ISA 选择，当前筛选不代表完整 ISA 配置。")
        if (hits.any { it.instruction.kind == "hint" })
            add("包含 HINT 候选；提示用途及其与其他候选的区别请查看各自说明。")
        add("编码匹配不能唯一确认执行行为，仍需核对各候选的执行条件与运行状态。")
    }.distinct()
}
object InstructionSearch {
    fun query(catalog: InstructionCatalog, input: String, filter: CatalogFilter, compiler: CompilerIsaOptions? = null): InstructionResults {
        val query = input.trim().lowercase(Locale.ROOT)
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        // Mnemonics support fragments (ret -> mret); English prose matches whole words.
        val descriptionPatterns = tokens.associateWith { token ->
            if (token.matches(Regex("[a-z0-9_.]+"))) Regex("(?<![a-z0-9_])${Regex.escape(token)}(?![a-z0-9_])") else null
        }
        val parsed = InstructionInput.parse(input)
        if (parsed.error != null) return InstructionResults(emptyList(), parsed.error, InstructionDiagnostic(InstructionDiagnosticKind.INPUT, parsed.error))
        val word = parsed.word
        val pattern = parsed.pattern
        val encoded = word != null || pattern != null
        if (pattern != null && pattern.knownBits < if (pattern.length == 16) 5 else 7)
            return InstructionResults(emptyList(), pattern = pattern, diagnostic = InstructionDiagnostic(InstructionDiagnosticKind.TOO_BROAD,
                "已知位太少，请至少填写 ${if (pattern.length == 16) 5 else 7} 个已知位，缩小部分编码查询范围。"))
        val selectedExtensions = compiler?.associatedTags(filter.extensions) ?: filter.extensions.map { it.lowercase() }.toSet()
        val effectiveFilter = filter.copy(extensions = selectedExtensions)
        val pseudoAvailable = if (encoded) emptyList() else catalog.pseudos.mapNotNull { pseudo ->
            val name = pseudo.name.lowercase(Locale.ROOT)
            val nameMatch = tokens.all { it in name }
            val description = "${pseudo.summary} ${pseudo.summaryEn}".lowercase(Locale.ROOT)
            if (!tokens.all { token -> token in name || (descriptionPatterns[token]?.containsMatchIn(description) ?: (token in description)) }) null
            else InstructionPseudoHit(pseudo, if (nameMatch) InstructionMatchCategory.NAME else InstructionMatchCategory.DESCRIPTION)
        }
        val pseudoHits = pseudoAvailable.filter { hit -> hit.instruction.let { pseudo ->
            effectiveFilter.accepts(pseudo.xlens, pseudo.privilege, pseudo.namespace, pseudo.tags) && pseudo.supportsExtensions(selectedExtensions)
        } }
        val parentTags = compiler?.tokens.orEmpty().filter { it in setOf("m", "a", "b", "c", "v") }.associateWith { compiler!!.associatedTags(setOf(it)) - setOf("i", "e", "f", "d", "zicsr") }
        val available = mutableListOf<Pair<InstructionHit, Set<String>>>()
        var fixedCandidate = false
        val hits = catalog.instructions.flatMap { instruction -> instruction.encodings.mapIndexedNotNull { index, encoding ->
            val tags = encoding.tags + parentTags.filterValues { tags -> tags.any { it in encoding.tags } }.keys
            val nameMatch = tokens.all { it in instruction.name.lowercase(Locale.ROOT) }
            val description = "${instruction.summary} ${instruction.summaryEn} ${encoding.summary.orEmpty()} ${encoding.summaryEn.orEmpty()}".lowercase()
            val textMatch = tokens.all { token -> token in instruction.name.lowercase(Locale.ROOT) ||
                (descriptionPatterns[token]?.containsMatchIn(description) ?: (token in description)) }
            if (word != null && encoding.fixedMatches(word)) fixedCandidate = true
            if (pattern != null && pattern.fixedMatches(encoding)) fixedCandidate = true
            val category = when {
                encoded -> InstructionMatchCategory.ENCODING
                nameMatch -> InstructionMatchCategory.NAME
                else -> InstructionMatchCategory.DESCRIPTION
            }
            val hit = InstructionHit(instruction, encoding, index, word, category, pattern)
            fun matches(xlen: Int) = when {
                pattern != null -> pattern.matches(encoding, xlen)
                word != null -> encoding.matches(word, xlen)
                else -> textMatch
            }
            val anyWidthMatch = encoding.xlens.any(::matches)
            if (anyWidthMatch) available += hit to tags
            if (!anyWidthMatch || !effectiveFilter.accepts(encoding.xlens, encoding.privilege, instruction.namespace, tags) ||
                !encoding.supportsExtensions(selectedExtensions) ||
                (encoded && !matches(filter.xlen))) null else hit
        } }.sortedWith(compareBy<InstructionHit> { it.category.ordinal }
            .thenBy { if (!encoded && it.instruction.name.lowercase(Locale.ROOT) == query) 0 else 1 }
            .thenBy { privilegeRank(it.encoding.privilege) }
            .thenBy { if (it.instruction.name.startsWith(query)) 0 else 1 }
            .thenBy { if (encoded) -java.lang.Long.bitCount(it.encoding.mask.hex()) else 0 }
            .thenBy { it.instruction.name }.thenBy { it.index })
        if (pattern != null && hits.map { it.instruction.name }.distinct().size > 100)
            return InstructionResults(emptyList(), pattern = pattern, diagnostic = InstructionDiagnostic(InstructionDiagnosticKind.TOO_BROAD,
                "匹配范围过宽，超过 100 个指令名称；请补充已知位或选择扩展、权限等筛选。"))
        if (hits.isNotEmpty() || pseudoHits.isNotEmpty()) return InstructionResults(hits, pseudoHits = pseudoHits, input = query, pattern = pattern)
        if (available.isNotEmpty() || pseudoAvailable.isNotEmpty()) {
            val candidates = available.map { (hit, tags) ->
                val widths = hit.encoding.xlens.filter { when {
                    pattern != null -> pattern.matches(hit.encoding, it)
                    word != null -> hit.encoding.matches(word, it)
                    else -> true
                } }
                val blocked = buildSet {
                    if (filter.xlen !in widths) add(InstructionFilterDimension.WIDTH)
                    if (filter.privilege != null && filter.privilege != normalizePrivilege(hit.encoding.privilege)) add(InstructionFilterDimension.PRIVILEGE)
                    if (filter.namespace != null && filter.namespace != hit.instruction.namespace) add(InstructionFilterDimension.SOURCE)
                    if (selectedExtensions.isNotEmpty() && (selectedExtensions.none { it in tags } || !hit.encoding.supportsExtensions(selectedExtensions))) add(InstructionFilterDimension.EXTENSION)
                }
                blocked to widths
            } + pseudoAvailable.map { hit ->
                val pseudo = hit.instruction
                buildSet {
                    if (filter.xlen !in pseudo.xlens) add(InstructionFilterDimension.WIDTH)
                    if (filter.privilege != null && filter.privilege != normalizePrivilege(pseudo.privilege)) add(InstructionFilterDimension.PRIVILEGE)
                    if (filter.namespace != null && filter.namespace != pseudo.namespace) add(InstructionFilterDimension.SOURCE)
                    if (selectedExtensions.isNotEmpty() && !pseudo.supportsExtensions(selectedExtensions)) add(InstructionFilterDimension.EXTENSION)
                } to pseudo.xlens
            }
            val (blocked, widths) = candidates.minWith(compareBy<Pair<Set<InstructionFilterDimension>, List<Int>>> { it.first.size }
                .thenBy { InstructionFilterDimension.WIDTH in it.first })
            return InstructionResults(emptyList(), pattern = pattern, diagnostic = InstructionDiagnostic(InstructionDiagnosticKind.FILTERED,
                "已收录的匹配项被${blocked.joinToString("、") { it.label }}筛选排除。", blocked,
                if (filter.xlen !in widths) widths.first() else null))
        }
        return InstructionResults(emptyList(), pattern = pattern, diagnostic = if (encoded && fixedCandidate)
            InstructionDiagnostic(InstructionDiagnosticKind.RESERVED, if (pattern != null) "已知位与已收录模板的静态限制冲突，无法补全为这些模板的有效编码。"
                else "匹配到编码模板，但操作数字段违反已收录的静态限制，属于该模板的保留编码。")
        else InstructionDiagnostic(InstructionDiagnosticKind.UNKNOWN, if (!encoded) "当前数据库未找到对应名称或描述。" else "当前数据库未收录匹配编码，无法据此判断该编码是否合法。"))
    }
}
