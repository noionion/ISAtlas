package dev.isatlas

import android.app.Application
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
) {
    val instructionResults get() = instructions?.let { InstructionSearch.query(it, query, CatalogFilter(xlen, privilege, namespace, compilerIsa?.associatedTags(extensions) ?: extensions), compilerIsa) } ?: InstructionResults(emptyList())
    val selectedInstruction get() = selectedInstructionKey?.let { key -> instructions?.instructions?.firstNotNullOfOrNull { instruction -> instruction.encodings.mapIndexed { index, encoding -> InstructionHit(instruction, encoding, index, selectedInstructionWord, pattern = selectedInstructionPattern) }.find { it.key == key } } }
    val selectedPseudo get() = instructions?.pseudos?.find { it.id == selectedPseudoId }
    val selected get() = pack?.registers?.find { it.id == selectedId }
    val results get() = pack?.let { Search.query(it, query, xlen, privilege, namespace,
        compilerIsa?.associatedTags(extensions) ?: extensions) }.orEmpty()
}

class AppViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("atlas", 0)
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
                    val instructions = InstructionCatalog.decodeFiles(assets.open("riscv-instructions.json").bufferedReader().use { it.readText() }) { name ->
                        assets.open("inst/$name").bufferedReader().use { it.readText() }
                    }
                    Triple(pack, compiler, instructions)
                }
            }
            result.fold(
                onSuccess = { (pack, compiler, instructions) -> mutable.update { it.copy(loading = false, pack = pack, compilerIsa = compiler, instructions = instructions) } },
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
        mutable.update { it.copy(showAbout = value, showSources = if (value) it.showSources else false) }
    }
    fun showSources(value: Boolean) {
        saved["showSources"] = value
        if (value) saved["showAbout"] = true
        mutable.update { it.copy(showSources = value, showAbout = it.showAbout || value) }
    }
}
