package dev.isatlas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun DatabaseUpdateDialog(state: DatabaseUpdateState, model: AppViewModel) {
    if (!state.prompt) return
    val repository = model.databaseUpdates
    AlertDialog(onDismissRequest = repository::dismiss,
        title = { Text(if (state.release != null) "数据库 ${state.release.version}" else "数据库更新") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("database-update-dialog"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.summary)
                if (state.busy || state.checking) {
                    if (state.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                state.release?.let {
                    Text("当前：${state.version ?: "内置 Manual"} · 下载：${"%.1f".format(it.size / 1048576.0)} MiB",
                        style = MaterialTheme.typography.bodySmall)
                    if (it.notes.isNotBlank()) Text(it.notes, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            when (state.stage) {
                DatabaseStage.IDLE -> if (state.release != null && !state.checking)
                    TextButton(onClick = repository::download, modifier = Modifier.testTag("database-download")) { Text("下载更新") }
                DatabaseStage.APPLY_FAILED -> TextButton(onClick = repository::retryApply,
                    modifier = Modifier.testTag("database-retry-apply")) { Text("重试更新") }
                else -> TextButton(onClick = repository::dismiss) { Text("继续查询") }
            }
        },
        dismissButton = {
            Row {
                if (state.stage == DatabaseStage.DOWNLOADING || state.stage == DatabaseStage.APPLY_FAILED)
                    TextButton(onClick = repository::cancel, modifier = Modifier.testTag("database-cancel")) { Text("取消下载") }
                else if (state.stage == DatabaseStage.IDLE && state.release != null)
                    TextButton(onClick = repository::skip) { Text("跳过版本") }
                TextButton(onClick = repository::dismiss) { Text("关闭") }
            }
        })
}
