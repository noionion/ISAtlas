package dev.isatlas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.isatlas.core.*

@Composable
internal fun PseudoInstructionCard(hit: InstructionPseudoHit, onOpen: (InstructionPseudoHit) -> Unit) {
    val instruction = hit.instruction
    ElevatedCard(onClick = { onOpen(hit) }, modifier = Modifier.fillMaxWidth().testTag("pseudo-${hit.key}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(instruction.name, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                Text(if (instruction.referenceOnly) "名称参考" else "伪指令 · ${normalizePrivilege(instruction.privilege)}", style = MaterialTheme.typography.labelSmall)
            }
            Text(instruction.summary, style = MaterialTheme.typography.bodyMedium)
            Text(instruction.requirements + if (instruction.expansions.isEmpty()) "" else " · ${instruction.expansions.size} 种展开", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            Text("${instruction.encodingLabel} · ${instruction.namespace}", style = MaterialTheme.typography.labelSmall)
            if (hit.category == InstructionMatchCategory.DESCRIPTION) Text("描述匹配", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun PseudoInstructionDetail(state: AtlasState, instruction: InstructionPseudo, onWidth: (Int) -> Unit,
    onBase: (InstructionHit) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    val bases = remember(state.instructions, instruction.id, state.xlen, state.extensions, state.compilerIsa) {
        instruction.expansions.flatMap { it.steps }.map { it.name }.distinct().associateWith { name ->
            state.instructions?.let { catalog -> InstructionSearch.query(catalog, name,
                CatalogFilter(state.xlen, namespace = instruction.namespace, extensions = state.extensions), state.compilerIsa).hits
                .filter { it.instruction.name == name } }.orEmpty()
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("pseudo-instruction-detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(instruction.encodingLabel, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                WidthPicker(state.xlen, onWidth)
            }
            Text(instruction.summary, style = MaterialTheme.typography.bodyMedium)
            Text(instruction.requirements, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
        item { Text(instruction.notes, style = MaterialTheme.typography.bodySmall) }
        if (state.xlen in instruction.xlens) InstructionExamples.create(instruction)?.let { example ->
            item { InstructionExampleCard(example) }
        }
        if (state.xlen !in instruction.xlens) item { Text("此条目不适用于 RV${state.xlen}。") }
        else instruction.expansions.forEachIndexed { index, expansion ->
            item(key = "expansion-$index") {
                OutlinedCard(Modifier.fillMaxWidth().testTag("pseudo-expansion-$index")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(expansion.condition, fontWeight = FontWeight.SemiBold)
                        SelectionContainer { Text(expansion.syntax, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        HorizontalDivider()
                        val sequence = expansion.steps.joinToString("\n") { it.assembly }
                        SelectionContainer { Text(sequence, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("pseudo-sequence-$index"), style = MaterialTheme.typography.bodyMedium) }
                        if (expansion.clobbers.isNotEmpty()) Text("改写：${expansion.clobbers.joinToString()}", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(sequence)) }, modifier = Modifier.testTag("pseudo-copy-$index")) { Text("复制展开") }
                    }
                }
            }
        }
        if (bases.isNotEmpty()) item {
            Text("基础指令", fontWeight = FontWeight.SemiBold)
            bases.forEach { (name, candidates) ->
                if (candidates.isEmpty()) Text("$name · 当前位宽或扩展筛选下无可用条目", style = MaterialTheme.typography.bodySmall)
                candidates.forEach { base -> TextButton(onClick = { onBase(base) }, modifier = Modifier.testTag("pseudo-base-${base.key}")) {
                    Text("$name · ${base.encoding.requirements} ↗")
                } }
            }
        }
        item {
            HorizontalDivider()
            Text("${instruction.namespace} · ${instruction.license} · ${instruction.sourceRevision.take(8)}", style = MaterialTheme.typography.labelSmall)
            instruction.semanticSources.forEach { source -> TextButton(onClick = { runCatching { uri.openUri(source.url) }.onFailure { linkError = true } }) { Text("打开规范原文 ↗") } }
            if (linkError) Text("无法打开浏览器", style = MaterialTheme.typography.bodySmall)
        }
    }
}
