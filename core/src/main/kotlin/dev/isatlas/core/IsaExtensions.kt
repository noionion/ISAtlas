package dev.isatlas.core

import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A compiler vocabulary snapshot, independent of the temporary CSR dataset. */
@Serializable
data class CompilerIsaOptions(
    val sourceUrl: String,
    val retrieved: String,
    val compiler: String,
    val tokens: List<String>,
    val dependencies: Map<String, List<String>> = emptyMap(),
    val dependencySource: String = "",
    val modes: List<String> = listOf("s", "u"),
) {
    val selectable get() = tokens
    /** Only offer extensions explicitly associated with the installed CSR catalog. */
    fun catalogOptions(pack: DataPack): List<String> {
        val tags = pack.registers.flatMap { IsaExtensionTags.associated(it) }.toSet()
        return selectable.filter { it in tags }
    }
    // Mode prerequisites are resolved internally, without becoming filter options.
    fun selectionClosure(selected: Set<String>): Set<String> = associatedTags(selected).intersect((tokens + modes).toSet())

    fun associatedTags(selected: Set<String>): Set<String> {
        val result = mutableSetOf<String>()
        fun visit(token: String) {
            val normalized = token.lowercase(Locale.ROOT)
            if (result.add(normalized)) dependencies[normalized].orEmpty().forEach(::visit)
        }
        selected.forEach(::visit)
        return result
    }

    companion object {
        fun decode(text: String): CompilerIsaOptions = Json.decodeFromString<CompilerIsaOptions>(text).also {
            require(it.sourceUrl.startsWith("https://gcc.gnu.org/"))
            require(it.tokens.isNotEmpty() && it.tokens.distinct().size == it.tokens.size)
            require(it.tokens.all { token -> token.matches(Regex("[a-z][a-z0-9]*")) })
            require(it.tokens.none { token -> token in setOf("sm", "s", "u") })
        }
    }
}

object IsaExtensionTags {
    private val words = Regex("[A-Za-z][A-Za-z0-9]*")
    private fun conditionTags(text: String) = words.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toSet()
    fun registerTags(reg: RegisterDefinition): Set<String> = reg.extensions.map { it.lowercase(Locale.ROOT) }.toSet() + conditionTags(reg.condition)
    fun fieldTags(field: FieldDefinition): Set<String> = conditionTags(field.condition)
    fun associated(reg: RegisterDefinition): Set<String> = registerTags(reg) + reg.fields.flatMap { fieldTags(it) }
}
