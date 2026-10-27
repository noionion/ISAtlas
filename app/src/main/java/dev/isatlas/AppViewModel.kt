package dev.isatlas

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.isatlas.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AtlasState(
    val loading: Boolean = true,
    val error: String? = null,
    val pack: DataPack? = null,
    val instructions: InstructionCatalog? = null,
    val instructionPage: Int = 0,
    val selectedInstructionKey: String? = null,
    val selectedInstructionWord: Long? = null,
    val selectedInstructionPattern: InstructionPattern? = null,
    val selectedPseudoId: String? = null,
    val abiRegisters: Boolean = true,
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
    val showLocalPackages: Boolean = false,
    val localPackages: List<ManagedLocalPackage> = emptyList(),
    val officialPackages: List<OfficialPackageInfo> = emptyList(),
    val enabledOfficialPackages: Set<String> = setOf("aia", "debug", "spmp"),
    val packageBusy: Boolean = false,
    val packageShowProgress: Boolean = false,
    val packageToggleTargets: Map<String, Boolean> = emptyMap(),
    val packagePreview: LocalPackagePreview? = null,
    val packageMessage: String? = null,
) {
    val packageTogglingIds get() = packageToggleTargets.keys
    val instructionResults get() = instructions?.let { InstructionSearch.query(it, query, CatalogFilter(xlen, privilege, namespace, compilerIsa?.associatedTags(extensions) ?: extensions), compilerIsa) } ?: InstructionResults(emptyList())
    val selectedInstruction get() = selectedInstructionKey?.let { key -> instructions?.instructions?.firstNotNullOfOrNull { instruction -> instruction.encodings.mapIndexed { index, encoding -> InstructionHit(instruction, encoding, index, selectedInstructionWord, pattern = selectedInstructionPattern) }.find { it.key == key } } }
    val selectedPseudo get() = instructions?.pseudos?.find { it.id == selectedPseudoId }
    val selected get() = pack?.registers?.find { it.id == selectedId }
    val results get() = pack?.let { Search.query(it, query, xlen, privilege, namespace,
        compilerIsa?.associatedTags(extensions) ?: extensions) }.orEmpty()
}

class AppViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("atlas", 0)
    private val localStore = LocalPackageRepository(application, BuildConfigVersion.code(application))
    private lateinit var officialStore: OfficialPackages
    private lateinit var officialCompiler: CompilerIsaOptions
    private var enabledOfficial = prefs.getStringSet("officialPackages", setOf("aia", "debug", "spmp"))!!.toSet()
    private var pendingPackage: ByteArray? = null
    private val packageOperations = Mutex()
    private val mutable = MutableStateFlow(AtlasState(
        instructionPage = saved["instructionPage"] ?: 0,
        selectedInstructionKey = saved["selectedInstructionKey"],
        selectedInstructionWord = saved["selectedInstructionWord"],
        selectedInstructionPattern = saved.get<String>("selectedInstructionPattern")?.let { InstructionInput.parse(it).pattern },
        selectedPseudoId = saved["selectedPseudoId"],
        abiRegisters = prefs.getBoolean("abiRegisters", true),
        query = saved["query"] ?: "",
        xlen = saved["xlen"] ?: prefs.getInt("xlen", 64),
        selectedId = saved["selectedId"],
        selectedField = saved["selectedField"],
        showAbout = saved["showAbout"] ?: false,
        showSources = saved["showSources"] ?: false,
        showLocalPackages = saved["showLocalPackages"] ?: false,
    ))
    val state = mutable.asStateFlow()
    init { load() }

    fun load() {
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val assets = getApplication<Application>().assets
                    officialStore = OfficialPackages(assets.open("riscv-packages.json").bufferedReader().use { it.readText() }) { path ->
                        assets.open(path).use { it.readBytes() }
                    }
                    officialCompiler = assets.open("march-extensions.json").bufferedReader().use { CompilerIsaOptions.decode(it.readText()) }
                    enabledOfficial = enabledOfficial.intersect(officialStore.packages.map { it.id }.toSet())
                    localStore.load(officialStore.catalogs(enabledOfficial, officialCompiler))
                }
            }
            result.fold(
                onSuccess = { snapshot -> applyPackages(snapshot, initial = true) },
                onFailure = { mutable.update { it.copy(loading = false, error = "内置数据包读取失败，请重试。") } },
            )
        }
    }

    fun previewPackage(uri: Uri) {
        if (mutable.value.packageBusy) return
        mutable.update { it.copy(packageBusy = true, packageShowProgress = true, packageMessage = null) }
        viewModelScope.launch {
            packageOperations.withLock {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { LocalPackages.readBounded(it) }
                            ?: throw IllegalArgumentException("无法读取所选文件")
                        localStore.preview(bytes) to bytes
                    }
                }
                result.fold(onSuccess = { (preview, bytes) ->
                    pendingPackage = bytes
                    mutable.update { it.copy(packageBusy = false, packageShowProgress = false, packagePreview = preview) }
                }, onFailure = { packageFailure(it) })
            }
        }
    }
    fun dismissPackagePreview() {
        if (mutable.value.packageBusy) return
        pendingPackage = null
        mutable.update { it.copy(packagePreview = null) }
    }
    fun confirmPackageImport() {
        val bytes = pendingPackage ?: return
        packageOperation("导入完成") { localStore.install(bytes) }
    }
    fun enablePackage(id: String, enabled: Boolean) {
        if (mutable.value.loading || mutable.value.packageBusy) return
        val running = id in mutable.value.packageTogglingIds
        if (!running && packageEnabled(id) == enabled) return
        // The thumb responds to the requested state immediately. A running worker
        // coalesces later taps rather than disabling/recolouring the switch.
        mutable.update { it.copy(packageToggleTargets = it.packageToggleTargets + (id to enabled)) }
        if (running) return
        viewModelScope.launch {
            try {
                packageOperations.withLock {
                    while (true) {
                        val target = mutable.value.packageToggleTargets[id] ?: break
                        if (packageEnabled(id) != target) {
                            val result = withContext(Dispatchers.IO) { runCatching {
                                if (id.startsWith("official:")) setOfficialEnabled(id.removePrefix("official:"), target)
                                else localStore.setEnabled(id, target)
                            } }
                            result.fold(onSuccess = ::applyPackages, onFailure = { error ->
                                val message = (error as? IllegalArgumentException)?.message?.takeIf { it.length <= 240 }
                                    ?: "切换失败，请检查数据包和可用存储空间。"
                                mutable.update { it.copy(packageMessage = message) }
                            })
                            if (result.isFailure) break
                        }
                        if (mutable.value.packageToggleTargets[id] == target) break
                    }
                }
            } finally {
                // A failed operation rolls the thumb back to the committed state.
                mutable.update { it.copy(packageToggleTargets = it.packageToggleTargets - id) }
            }
        }
    }
    private fun packageEnabled(id: String): Boolean = if (id.startsWith("official:"))
        id.removePrefix("official:") in mutable.value.enabledOfficialPackages
    else mutable.value.localPackages.any { it.record.id == id && it.active }
    private fun setOfficialEnabled(id: String, enabled: Boolean): LocalPackageSnapshot {
        require(officialStore.packages.any { it.id == id && !it.required })
        val previous = enabledOfficial
        val candidate = if (enabled) previous + id else previous - id
        val base = officialStore.catalogs(candidate, officialCompiler)
        val result = localStore.rebase(base)
        if (!prefs.edit().putStringSet("officialPackages", candidate).commit()) {
            localStore.rebase(officialStore.catalogs(previous, officialCompiler))
            error("无法保存数据包设置")
        }
        enabledOfficial = candidate
        return result
    }
    fun removePackage(id: String) = packageOperation("已卸载") { localStore.remove(id) }
    fun packageMessage(value: String?) { mutable.update { it.copy(packageMessage = value) } }
    private fun packageOperation(message: String?, action: () -> LocalPackageSnapshot) {
        if (mutable.value.packageBusy) return
        mutable.update { it.copy(packageBusy = true, packageShowProgress = message != null, packageMessage = null) }
        viewModelScope.launch {
            packageOperations.withLock {
                val result = withContext(Dispatchers.IO) { runCatching(action) }
                result.fold(onSuccess = { snapshot ->
                    pendingPackage = null
                    applyPackages(snapshot)
                    mutable.update { it.copy(packageBusy = false, packageShowProgress = false, packagePreview = null, packageMessage = message) }
                }, onFailure = { packageFailure(it) })
            }
        }
    }
    private fun packageFailure(error: Throwable) {
        val message = when (error) {
            is PackageValidationException -> error.message
            is IllegalArgumentException -> error.message?.takeIf { it.length <= 240 }
            else -> null
        } ?: "操作失败；当前数据保持不变，请检查文件和可用存储空间。"
        mutable.update { it.copy(packageBusy = false, packageShowProgress = false, packageMessage = message) }
    }
    private fun applyPackages(snapshot: LocalPackageSnapshot, initial: Boolean = false) {
        val catalogs = snapshot.catalogs
        mutable.update { current ->
            val roots = current.extensionRoots.intersect(catalogs.compiler.selectable.toSet())
            current.copy(loading = false, error = null, pack = catalogs.csr, instructions = catalogs.instructions,
            compilerIsa = catalogs.compiler, localPackages = snapshot.packages, packageMessage = snapshot.warning,
            officialPackages = officialStore.packages, enabledOfficialPackages = enabledOfficial,
            namespace = current.namespace?.takeIf { ns -> catalogs.csr.registers.any { it.namespace == ns || it.sources.any { source -> source.namespace == ns } } || catalogs.instructions.instructions.any { it.namespace == ns } },
            extensions = catalogs.compiler.selectionClosure(roots),
            extensionRoots = roots,
            selectedId = current.selectedId?.takeIf { id -> catalogs.csr.registers.any { it.id == id } },
            selectedInstructionKey = current.selectedInstructionKey?.takeIf { key -> catalogs.instructions.instructions.any { entry -> entry.encodings.indices.any { "${entry.id}:$it" == key } } },
        ) }
        if (!initial) {
            saved["selectedId"] = mutable.value.selectedId
            saved["selectedInstructionKey"] = mutable.value.selectedInstructionKey
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
    fun page(value: Int) { saved["instructionPage"] = value; mutable.update { it.copy(instructionPage = value) } }
    fun openInstruction(hit: InstructionHit) = selectInstruction(hit, keepPseudo = false)
    fun openPseudoBase(hit: InstructionHit) = selectInstruction(hit, keepPseudo = true)
    private fun selectInstruction(hit: InstructionHit, keepPseudo: Boolean) {
        saved["selectedInstructionKey"] = hit.key
        saved["selectedInstructionWord"] = hit.word
        saved["selectedInstructionPattern"] = hit.pattern?.query
        saved["selectedId"] = null
        saved["selectedField"] = null
        if (!keepPseudo) saved["selectedPseudoId"] = null
        mutable.update { it.copy(selectedInstructionKey = hit.key, selectedInstructionWord = hit.word, selectedInstructionPattern = hit.pattern, selectedId = null, selectedField = null,
            selectedPseudoId = if (keepPseudo) it.selectedPseudoId else null) }
    }
    fun openPseudo(hit: InstructionPseudoHit) {
        saved["selectedPseudoId"] = hit.key
        saved["selectedInstructionKey"] = null
        saved["selectedInstructionWord"] = null
        saved["selectedInstructionPattern"] = null
        saved["selectedId"] = null
        saved["selectedField"] = null
        mutable.update { it.copy(selectedPseudoId = hit.key, selectedInstructionKey = null, selectedInstructionWord = null, selectedInstructionPattern = null, selectedId = null, selectedField = null) }
    }
    fun abiRegisters(value: Boolean) {
        prefs.edit().putBoolean("abiRegisters", value).apply()
        mutable.update { it.copy(abiRegisters = value) }
    }
    fun relaxInstructionFilters(diagnostic: InstructionDiagnostic) {
        diagnostic.suggestedXlen?.let(::xlen)
        if (InstructionFilterDimension.PRIVILEGE in diagnostic.clear) privilege(null)
        if (InstructionFilterDimension.SOURCE in diagnostic.clear) namespace(null)
        if (InstructionFilterDimension.EXTENSION in diagnostic.clear) clearExtensions()
    }
    fun close() {
        if (mutable.value.selectedId != null && mutable.value.selectedInstructionKey != null) {
            saved["selectedId"] = null
            saved["selectedField"] = null
            mutable.update { it.copy(selectedId = null, selectedField = null) }
            return
        }
        if (mutable.value.selectedInstructionKey != null && mutable.value.selectedPseudoId != null) {
            saved["selectedInstructionWord"] = null
            saved["selectedInstructionPattern"] = null
            saved["selectedInstructionKey"] = null
            mutable.update { it.copy(selectedInstructionKey = null, selectedInstructionWord = null, selectedInstructionPattern = null) }
            return
        }
        saved["selectedPseudoId"] = null
        saved["selectedInstructionWord"] = null
        saved["selectedInstructionPattern"] = null
        saved["selectedInstructionKey"] = null
        saved["selectedId"] = null
        saved["selectedField"] = null
        mutable.update { it.copy(selectedId = null, selectedField = null, selectedInstructionKey = null, selectedInstructionWord = null, selectedInstructionPattern = null, selectedPseudoId = null) }
    }
    fun showAbout(value: Boolean) {
        saved["showAbout"] = value
        if (!value) saved["showSources"] = false
        if (!value) saved["showLocalPackages"] = false
        mutable.update { it.copy(showAbout = value, showSources = if (value) it.showSources else false, showLocalPackages = if (value) it.showLocalPackages else false) }
    }
    fun showSources(value: Boolean) {
        saved["showSources"] = value
        if (value) saved["showAbout"] = true
        mutable.update { it.copy(showSources = value, showAbout = it.showAbout || value) }
    }
    fun showLocalPackages(value: Boolean) {
        saved["showLocalPackages"] = value
        if (value) saved["showAbout"] = true
        mutable.update { it.copy(showLocalPackages = value, showAbout = it.showAbout || value) }
    }
}

internal object BuildConfigVersion {
    @Suppress("DEPRECATION") fun code(application: Application): Int = application.packageManager.getPackageInfo(application.packageName, 0).versionCode
}
