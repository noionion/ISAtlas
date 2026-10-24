package dev.isatlas

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun UpdateBadge(tag: String) {
    Badge(Modifier.testTag(tag).semantics { contentDescription = "有可用更新" }, containerColor = MaterialTheme.colorScheme.error)
}

@Composable
internal fun UpdateDialogs(repository: UpdateRepository, state: UpdateState) {
    val context = LocalContext.current
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) repository.install()
        else repository.permissionDenied()
    }
    val confirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        repository.installConfirmationReturned()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val continueInstallation: () -> Unit = {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            repository.takeInstallConfirmation()?.let { intent ->
                runCatching { confirmation.launch(intent) }.onFailure { repository.installConfirmationUnavailable() }
            }
        }
    }
    LaunchedEffect(state.installation) { continueInstallation() }
    DisposableEffect(lifecycle, repository) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) continueInstallation() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val install: () -> Unit = {
        if (context.packageManager.canRequestPackageInstalls()) repository.install()
        else runCatching {
            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
        }.onFailure { repository.permissionDenied() }
    }
    UpdateReadyNotice(state, repository::dismissReadyNotice, install)
    UpdatePromptDialog(state, onDismiss = repository::dismiss, onSkip = repository::skip,
        onDownload = repository::download, onInstall = install)
}

/** An ordinary in-app surface: no status-bar notification or notification permission. */
@Composable
internal fun UpdateReadyNotice(state: UpdateState, onDismiss: () -> Unit, onInstall: () -> Unit) {
    if (!state.readyNotice || state.stage != UpdateStage.READY || state.prompt != null || state.installation.busy) return
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp),
        contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("update-ready-notice")
            .semantics { liveRegion = LiveRegionMode.Polite },
            shape = MaterialTheme.shapes.medium, shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.installation.message ?: state.message ?: if (state.installation.phase == InstallPhase.CONFIRMATION)
                    "更新需要系统确认" else "${state.release?.version} 更新已下载", Modifier.weight(1f),
                    maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onInstall, modifier = Modifier.testTag("update-notice-install"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)) {
                    Text(if (state.installation.phase == InstallPhase.CONFIRMATION) "继续安装" else "安装")
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Clear, "稍后安装") }
            }
        }
    }
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
                Text(if (ready) "安装包已校验，点击安装后提交更新；系统要求时再确认。" else
                    "下载在后台进行，可继续查询。${if (release.size > 0) "安装包约 ${"%.1f".format(release.size / 1048576.0)} MB。" else ""}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val notes = release.notes.ifBlank { "此版本未提供更新日志。" }
                    .replace(Regex("(?m)^#{1,6}\\s+"), "").replace("**", "").replace("`", "")
                Text(notes, style = MaterialTheme.typography.bodyMedium)
                if (state.busy || state.stage == UpdateStage.FAILED || state.message != null || state.installation.message != null)
                    Text(state.summary, style = MaterialTheme.typography.bodySmall,
                        color = if (state.stage == UpdateStage.FAILED || state.message != null || state.installation.message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            when {
                ready -> TextButton(onClick = onInstall, enabled = !state.installation.busy, modifier = Modifier.testTag("update-install")) {
                    Text(if (state.installation.busy) "正在安装…" else "立即安装")
                }
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
