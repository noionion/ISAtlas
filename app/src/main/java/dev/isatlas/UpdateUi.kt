package dev.isatlas

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

@Composable
internal fun UpdateBadge(tag: String) {
    Badge(Modifier.testTag(tag).semantics { contentDescription = "有可用更新" }, containerColor = MaterialTheme.colorScheme.error)
}

@Composable
internal fun UpdateDialogs(repository: UpdateRepository, state: UpdateState) {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) (context as? Activity)?.let(repository::install)
        else repository.permissionDenied()
    }
    UpdatePromptDialog(state, onDismiss = repository::dismiss, onSkip = repository::skip,
        onDownload = {
            // Start the system transfer immediately; notification permission never gates the download.
            repository.download()
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }, onInstall = {
            if (context.packageManager.canRequestPackageInstalls()) (context as? Activity)?.let(repository::install)
            else runCatching {
                installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
            }.onFailure { repository.permissionDenied() }
        })
}

@Composable
internal fun UpdatePromptDialog(
    state: UpdateState,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val release = state.release ?: return
    if (state.prompt == null) return
    val ready = state.stage == UpdateStage.READY
    AlertDialog(
        modifier = Modifier.testTag("update-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(if (ready) "${release.version} 已准备好安装" else "发现新版本 ${release.version}") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("update-notes"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (ready) "安装包已完成校验，安装时需要系统确认。" else
                    "下载在后台进行，可继续查询。${if (release.size > 0) "安装包约 ${"%.1f".format(release.size / 1048576.0)} MB。" else ""}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val notes = release.notes.ifBlank { "此版本未提供更新日志。" }
                    .replace(Regex("(?m)^#{1,6}\\s+"), "").replace("**", "").replace("`", "")
                Text(notes, style = MaterialTheme.typography.bodyMedium)
                if (state.busy || state.stage == UpdateStage.FAILED || state.message != null)
                    Text(state.summary, style = MaterialTheme.typography.bodySmall,
                        color = if (state.stage == UpdateStage.FAILED || state.message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            when {
                ready -> TextButton(onClick = onInstall, modifier = Modifier.testTag("update-install")) { Text("立即安装") }
                state.busy -> TextButton(onClick = onDismiss) { Text("继续查询") }
                else -> TextButton(onClick = onDownload, modifier = Modifier.testTag("update-download")) {
                    Text(if (state.stage == UpdateStage.FAILED) "重新下载" else "下载更新")
                }
            }
        },
        dismissButton = {
            Row {
                if (!ready && !state.busy) TextButton(onClick = onSkip, modifier = Modifier.testTag("update-skip")) { Text("跳过此版本") }
                TextButton(onClick = onDismiss) { Text(if (ready) "稍后安装" else "稍后") }
            }
        },
    )
}
