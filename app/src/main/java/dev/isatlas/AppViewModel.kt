package dev.isatlas

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.isatlas.core.Catalog
import dev.isatlas.core.DataPack
import dev.isatlas.core.Search
import dev.isatlas.core.CompilerIsaOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AtlasState(
    val loading: Boolean = true,
    val error: String? = null,
    val pack: DataPack? = null,
    val query: String = "",
    val xlen: Int = 64,
    val privilege: String? = null,
    val namespace: String? = null,
    val extensions: Set<String> = emptySet(),
    val extensionRoots: Set<String> = emptySet(),
    val extensionMessage: String? = null,
    val compilerIsa: CompilerIsaOptions? = null,
    val selectedId: String? = null,
    val selectedField: String? = null,
    val showAbout: Boolean = false,
    val showSources: Boolean = false,
) {
    val selected get() = pack?.registers?.find { it.id == selectedId }
    val results get() = pack?.let { Search.query(it, query, xlen, privilege, namespace,
        compilerIsa?.associatedTags(extensions) ?: extensions) }.orEmpty()
}

class AppViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("atlas", 0)
    private val mutable = MutableStateFlow(AtlasState(
        query = saved["query"] ?: "",
        xlen = saved["xlen"] ?: prefs.getInt("xlen", 64),
        selectedId = saved["selectedId"],
        selectedField = saved["selectedField"],
        showAbout = saved["showAbout"] ?: false,
        showSources = saved["showSources"] ?: false,
    ))
    val state = mutable.asStateFlow()
    init { load() }

    fun load() {
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val assets = getApplication<Application>().assets
                    val manifest = assets.open("riscv-catalog.json").bufferedReader().use { it.readText() }
                    val pack = Catalog.decodeFiles(manifest) { name ->
                        assets.open("csr/$name").bufferedReader().use { it.readText() }
                    }
                    val compiler = assets.open("march-extensions.json").bufferedReader().use { CompilerIsaOptions.decode(it.readText()) }
                    pack to compiler
                }
            }
            result.fold(
                onSuccess = { (pack, compiler) -> mutable.update { it.copy(loading = false, pack = pack, compilerIsa = compiler) } },
                onFailure = { mutable.update { it.copy(loading = false, error = "内置数据包读取失败，请重试。") } },
            )
        }
    }

    fun query(value: String) {
        saved["query"] = value
        mutable.update { it.copy(query = value) }
    }
    fun xlen(value: Int) {
        saved["xlen"] = value
        prefs.edit().putInt("xlen", value).apply()
        mutable.update { it.copy(xlen = value) }
    }
    fun privilege(value: String?) { mutable.update { it.copy(privilege = value) } }
    fun namespace(value: String?) { mutable.update { it.copy(namespace = value) } }
    fun extension(value: String) {
        val compiler = mutable.value.compilerIsa ?: return
        if (value !in compiler.selectable) return
        mutable.update { current ->
            val roots = if (value in current.extensions) current.extensionRoots - value else current.extensionRoots + value
            val selected = compiler.selectionClosure(roots)
            val dependents = roots.filter { value in compiler.associatedTags(setOf(it)) && it != value }
            current.copy(extensionRoots = roots, extensions = selected,
                extensionMessage = if (value in current.extensions && value in selected && dependents.isNotEmpty())
                    "$value 是 ${dependents.sorted().joinToString("、")} 的前置项，请先取消依赖它的扩展。" else null)
        }
    }
    fun clearExtensions() { mutable.update { it.copy(extensions = emptySet(), extensionRoots = emptySet(), extensionMessage = null) } }
    fun open(id: String, field: String? = null) {
        saved["selectedId"] = id
        saved["selectedField"] = field
        mutable.update { it.copy(selectedId = id, selectedField = field) }
    }
    fun close() {
        saved["selectedId"] = null
        saved["selectedField"] = null
        mutable.update { it.copy(selectedId = null, selectedField = null) }
    }
    fun showAbout(value: Boolean) {
        saved["showAbout"] = value
        if (!value) saved["showSources"] = false
        mutable.update { it.copy(showAbout = value, showSources = if (value) it.showSources else false) }
    }
    fun showSources(value: Boolean) {
        saved["showSources"] = value
        if (value) saved["showAbout"] = true
        mutable.update { it.copy(showSources = value, showAbout = it.showAbout || value) }
    }
}
