package dev.isatlas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.isatlas.core.*

@Composable internal fun LocalPackagesScreen(state: AtlasState, model: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::previewPackage) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().testTag("local-packages-screen"), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AboutHeading("内置规范包")
            state.officialPackages.forEach { item ->
                Row(Modifier.fillMaxWidth().testTag("official-package-${item.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(when (item.id) { "manual" -> "ISA Manual"; "aia" -> "AIA"; "debug" -> "Debug / Trace"; else -> "SPMP" },
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${item.version} · ${item.csrCount} CSR" + if (item.supplementCount > 0) " · 补充 ${item.supplementCount} 项" else " · 含指令库",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.required) Text("基础包", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Switch(checked = state.packageToggleTargets["official:${item.id}"] ?: (item.id in state.enabledOfficialPackages),
                        enabled = !state.packageBusy,
                        onCheckedChange = { model.enablePackage("official:${item.id}", it) },
                        modifier = Modifier.testTag("official-package-enabled-${item.id}"))
                }
            }
            if (state.officialPackages.any { !it.required }) Text("扩展包包含新增 CSR 和对基础定义的补充；关闭时撤回对应补充。来源与许可可在数据源页查看。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AboutHeading("本地数据包")
            Text("导入企业或自定义 CSR、指令数据包，离线查询。", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge)
            Text("仅在本机保存，不上传、不加入官方数据库。来源由制作者声明，未经官方验证；卸载应用会删除已导入包。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(enabled = !state.packageBusy && !state.loading, modifier = Modifier.testTag("local-package-import"), onClick = {
                runCatching { picker.launch(arrayOf("*/*")) }.onFailure { model.packageMessage("无法打开系统文件选择器。") }
            }) { Text(if (state.packageShowProgress) "正在处理…" else "选择本地数据包") }
            if (state.packageShowProgress) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("local-package-progress"))
            state.packageMessage?.let { message ->
                Text(message, Modifier.testTag("local-package-message"), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { model.packageMessage(null) }) { Text("关闭提示") }
            }
            AboutHeading("已导入的数据包")
            if (state.localPackages.isEmpty()) Text("暂无本地数据包。支持 CSR 包、指令包或组合 ZIP 包。", style = MaterialTheme.typography.bodyMedium)
            state.localPackages.forEach { item ->
                val record = item.record
                val manifest = item.data?.manifest
                Column(Modifier.fillMaxWidth().testTag("local-package-${record.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(record.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${record.version} · ${manifest?.publisher ?: "本地导入"}", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = state.packageToggleTargets[record.id] ?: item.active, enabled = !state.packageBusy && item.data != null,
                            onCheckedChange = { model.enablePackage(record.id, it) }, modifier = Modifier.testTag("local-package-enabled-${record.id}"))
                    }
                    item.problem?.let { problem ->
                        Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (manifest != null) {
                        Text("RISC-V · ${item.data!!.registers.size} CSR · ${item.data!!.instructions.size} 指令 · ${manifest.namespace}", style = MaterialTheme.typography.bodySmall)
                        if (manifest.description.isNotBlank()) Text(manifest.description, style = MaterialTheme.typography.bodySmall)
                        if (manifest.dependencies.isNotEmpty()) Text("依赖：${manifest.dependencies.joinToString { "${it.id} ${it.version}" }}", style = MaterialTheme.typography.bodySmall)
                        Text("许可：${manifest.license}", style = MaterialTheme.typography.bodySmall)
                        if (manifest.licenseText.isNotBlank()) {
                            var licenseOpen by rememberSaveable(record.id) { mutableStateOf(false) }
                            TextButton(onClick = { licenseOpen = !licenseOpen }) { Text(if (licenseOpen) "收起许可" else "查看许可") }
                            if (licenseOpen) Text(manifest.licenseText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !state.packageBusy, onClick = {
                            runCatching { picker.launch(arrayOf("*/*")) }.onFailure { model.packageMessage("无法打开系统文件选择器。") }
                        }) { Text("选择升级 / 替换包") }
                        TextButton(enabled = !state.packageBusy, onClick = { removing = record.id }, modifier = Modifier.testTag("local-package-remove-${record.id}")) { Text("卸载") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    state.packagePreview?.let { preview ->
        val m = preview.data.manifest
        AlertDialog(onDismissRequest = model::dismissPackagePreview, title = { Text(if (preview.replacing != null) "替换数据包" else "导入数据包") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()).testTag("local-package-preview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium)
                    Text("版本 ${m.version} · RISC-V\n发布者：${m.publisher}\n命名空间：${m.namespace}")
                    Text("${preview.data.registers.size} 个 CSR · ${preview.data.instructions.size} 条指令")
                    if (m.description.isNotBlank()) Text(m.description)
                    Text("许可：${m.license} · 本地来源，未经官方验证")
                    preview.replacing?.let { Text("将替换 ${it.version}；仅替换本地同 ID 的包。") }
                    if (preview.conflicts.any) Text("检测到 ${preview.conflicts.csrAddresses} 个同地址 CSR、${preview.conflicts.instructionEncodings} 个可能重叠的指令编码。导入后保留全部来源，通过来源筛选区分，不覆盖内置数据。", Modifier.testTag("local-package-conflicts"))
                    else Text("与现有数据共存，不覆盖内置数据。")
                    preview.problem?.let { Text("导入后暂不可启用：$it", color = MaterialTheme.colorScheme.error) }
                    Text("确认前，当前查询数据保持不变。", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(enabled = !state.packageBusy, onClick = model::confirmPackageImport,
                modifier = Modifier.testTag("local-package-confirm")) { Text(if (preview.conflicts.any) "确认共存导入" else "确认导入") } },
            dismissButton = { TextButton(enabled = !state.packageBusy, onClick = model::dismissPackagePreview) { Text("取消") } })
    }
    removing?.let { id -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("卸载本地数据包？") },
        text = { Text("删除应用内的包副本，原始文件不变。依赖此包的数据会暂停启用；内置数据不受影响。") },
        confirmButton = { TextButton(onClick = { removing = null; model.removePackage(id) }, modifier = Modifier.testTag("local-package-remove-confirm")) { Text("卸载") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}
