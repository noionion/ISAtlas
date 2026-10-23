package dev.isatlas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.isatlas.core.DataPack

@Composable
fun DataSourcesScreen(pack: DataPack?) {
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().testTag("sources-screen"), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            if (pack == null) {
                Text("数据库尚未加载。", Modifier.padding(vertical = 24.dp))
            } else {
                pack.registers.groupBy { (it.namespace.ifBlank { pack.namespace }) to it.sourceRevision.ifBlank { pack.source.revision } }
                    .toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
                    .forEach { (source, registers) ->
                        val (namespace, revision) = source
                        key(namespace, revision) {
                            val title = if (revision == pack.source.revision) pack.source.title
                                else if (namespace == "thead") "T-head Extension Specification" else namespace
                            AboutHeading(title)
                            if (namespace == "manual") {
                                Text("RISC-V International 及贡献者；字段经结构化整理，并补充中英文摘要。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (namespace == "thead") {
                                Text("T-head Semi；保留其寄存器定义。", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("提交版本", Modifier.padding(top = 12.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            SelectionContainer {
                                Text(revision, Modifier.testTag("source-revision-$namespace"),
                                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                linkError = runCatching { uriHandler.openUri(registers.first().sourceUrl) }.isFailure
                            }, contentPadding = PaddingValues(0.dp)) { Text("查看固定版本原文 ↗") }
                            registers.map { it.license }.filter { it.isNotBlank() }.distinct().forEach { license ->
                                SourceLicense(namespace, license)
                            }
                            HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
            }
            if (linkError) Text("无法打开链接，请检查是否有可用浏览器。", Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SourceLicense(namespace: String, license: String) {
    val context = LocalContext.current
    var expanded by rememberSaveable(namespace, license) { mutableStateOf(false) }
    val text = remember(license) {
        runCatching {
            require(license.matches(Regex("[A-Za-z0-9.+-]+")))
            context.assets.open("licenses/$license.txt").bufferedReader().use { it.readText() }
        }.getOrElse { "当前数据库未附带该许可正文。" }
    }
    Text("许可：$license", style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("source-license-$namespace-$license"),
        contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起许可正文" else "查看许可正文") }
    AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
        SelectionContainer {
            Text(text, Modifier.testTag("source-license-text-$namespace-$license").padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
