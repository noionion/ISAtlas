package dev.isatlas.core

import kotlinx.serialization.Serializable

@Serializable data class InstructionPseudoStep(val name: String, val assembly: String)
@Serializable data class InstructionExpansion(val syntax: String, val condition: String, val conditionEn: String,
    val steps: List<InstructionPseudoStep>, val clobbers: List<String> = emptyList())
@Serializable data class InstructionPseudo(val schemaVersion: Int, val id: String, val namespace: String,
    val name: String, val kind: String, val summary: String, val summaryEn: String,
    val xlens: List<Int>, val privilege: String, val requires: List<List<String>>,
    val sourceRevision: String, val license: String, val semanticSources: List<InstructionSource>,
    val notes: String, val notesEn: String, val expansions: List<InstructionExpansion>, val referenceStatus: String = "defined") {
    val tags get() = requires.flatten().map { it.lowercase() }.toSet()
    val requirements get() = requires.joinToString(" / ") { it.joinToString(" + ") }.ifBlank { "扩展要求未确定" }
    val referenceOnly get() = referenceStatus != "defined"
    val encodingLabel get() = when (referenceStatus) {
        "historical" -> "历史名称参考 · 不推定当前编码"
        "example" -> "仅名称参考 · 手册未给出完整展开"
        else -> if (expansions.any { it.steps.size > 1 }) "伪指令 · 无单条固定编码" else "伪指令 · 编码见展开"
    }
    fun supportsExtensions(capabilities: Set<String>) = capabilities.isEmpty() || requires.any { group -> group.all { it.lowercase() in capabilities } }
}
data class InstructionPseudoHit(val instruction: InstructionPseudo, val category: InstructionMatchCategory) {
    val key get() = instruction.id
}
