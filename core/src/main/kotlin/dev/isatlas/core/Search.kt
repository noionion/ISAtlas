package dev.isatlas.core

import java.util.Locale

enum class SearchCategory(val label: String) { CSR("CSR"), FIELD("字段"), DESCRIPTION("描述相关") }

data class SearchHit(
    val register: RegisterDefinition,
    val field: FieldDefinition? = null,
    val score: Int = 0,
    val category: SearchCategory = if (field == null) SearchCategory.CSR else SearchCategory.FIELD,
) {
    val key get() = register.id + (field?.let { ".${it.name}" } ?: "")
}

object Search {
    private val privilegeOrder = listOf("M", "S", "H", "VS", "U", "D")

    fun query(pack: DataPack, input: String, xlen: Int, privilege: String? = null, namespace: String? = null,
              extensions: Set<String> = emptySet()): List<SearchHit> {
        val query = input.trim().lowercase(Locale.ROOT)
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        val numeric = query.startsWith("0x") || (query.isNotEmpty() && query.all { it in '0'..'9' })
        val address = if (query.startsWith("0x")) query.drop(2).toIntOrNull(16) else query.toIntOrNull()
        if (numeric && address == null) return emptyList()
        val extensionTags = extensions.map { it.lowercase(Locale.ROOT) }.toSet()
        return buildList {
            pack.registers.filter { xlen in it.xlens && (privilege == null || privilege == it.privilege) &&
                (namespace == null || namespace == it.namespace.ifBlank { pack.namespace }) &&
                (extensionTags.isEmpty() || IsaExtensionTags.associated(it).any { tag -> tag in extensionTags }) }.forEach { reg ->
                val name = reg.name.lowercase(Locale.ROOT)
                val namespaced = "${reg.namespace.ifBlank { pack.namespace }}.$name".lowercase(Locale.ROOT)
                val identifiers = "$name $namespaced"
                val regText = "$identifiers ${reg.title} ${reg.summary} ${reg.condition} ${reg.extensions.joinToString(" ")}".lowercase(Locale.ROOT)
                val addressMatch = (reg.locator as RegisterLocator.Csr).address == address
                val nameMatch = !numeric && tokens.all { token -> token in name || ('.' in token && token in namespaced) }
                if (query.isEmpty() || addressMatch || nameMatch) {
                    add(SearchHit(reg, score = when {
                        query.isEmpty() -> 0
                        query == name || query == namespaced || addressMatch -> 100
                        name.startsWith(query) -> 80
                        else -> 60
                    }))
                } else if (!numeric && tokens.all { it in regText }) {
                    add(SearchHit(reg, score = 30, category = SearchCategory.DESCRIPTION))
                }
                // A parent-only query must not enumerate all of its child fields.
                if (query.isNotEmpty() && !numeric && query != name && query != namespaced) {
                    reg.fields.filter { it.layout(xlen) != null && (extensionTags.isEmpty() ||
                        (IsaExtensionTags.registerTags(reg) + IsaExtensionTags.fieldTags(it)).any { tag -> tag in extensionTags }) }.forEach { field ->
                        val fieldName = field.name.lowercase(Locale.ROOT)
                        val names = listOf(fieldName) + field.aliases.map { it.lowercase(Locale.ROOT) }
                        val qualified = names.flatMap { listOf("$name.$it", "$namespaced.$it") }
                        val fieldIdentifiers = (names + qualified).joinToString(" ")
                        val allIdentifiers = "$identifiers $fieldIdentifiers"
                        val fieldText = "${field.summary} ${field.condition}".lowercase(Locale.ROOT)
                        val fieldSpecific = tokens.any { token -> names.any { token in it } || qualified.any { token in it && '.' in token } }
                        val identityMatch = tokens.all { token -> token in name || names.any { token in it } || qualified.any { '.' in token && token in it } }
                        if (identityMatch && fieldSpecific) {
                            add(SearchHit(reg, field, when {
                                query in qualified -> 110
                                query in names -> 95
                                names.any { it.startsWith(query) } -> 75
                                else -> 60
                            }))
                        } else if (tokens.all { it in "$allIdentifiers $fieldText $regText" } && tokens.any { it in fieldText }) {
                            add(SearchHit(reg, field, 25, SearchCategory.DESCRIPTION))
                        }
                    }
                }
            }
        }.sortedWith(compareBy<SearchHit> { it.category.ordinal }
            .thenBy { privilegeOrder.indexOf(it.register.privilege).let { rank -> if (rank < 0) Int.MAX_VALUE else rank } }
            .thenBy { (it.register.locator as RegisterLocator.Csr).address }
            .thenByDescending { it.score }
            .thenBy { it.register.name }
            .thenBy { it.register.namespace }
            .thenBy { it.field?.name ?: "" }
            .thenBy { it.key })
    }
}
