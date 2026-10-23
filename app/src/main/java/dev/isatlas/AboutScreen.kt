package dev.isatlas

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.isatlas.core.DataPack

private const val PROJECT_URL = "https://github.com/noionion/ISAtlas"

@Composable
fun AboutScreen(pack: DataPack?, onShowSources: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty().ifBlank { "未知" }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    val openLink: (String) -> Unit = { url -> linkError = runCatching { uriHandler.openUri(url) }.isFailure }

    Box(Modifier.fillMaxSize().testTag("about-screen"), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp).testTag("about-content")) {
            if (landscape) {
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(64.dp))
                    AboutIdentity()
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(80.dp))
                    AboutIdentity()
                }
            }
            AboutRow("软件版本", version, tag = "about-app-version")

            // Database management belongs here; local import can extend this section later.
            AboutHeading("数据库")
            AboutRow("数据库版本", pack?.version ?: "尚未加载", tag = "about-database-version")
            AboutRow("数据源与许可", tag = "about-sources", onClick = onShowSources)

            AboutHeading("项目与支持")
            AboutRow("GitHub", subtitle = "noionion/ISAtlas", tag = "about-github", onClick = { openLink(PROJECT_URL) })
            AboutRow("问题反馈", tag = "about-feedback", onClick = { openLink("$PROJECT_URL/issues") })
            if (linkError) Text("无法打开链接，请检查是否有可用浏览器。", Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AboutIdentity() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("ISAtlas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("指令、寄存器与位字段，一搜即得", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AboutHeading(title: String) {
    Text(title, Modifier.padding(top = 28.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun AboutRow(label: String, value: String? = null, subtitle: String? = null, tag: String? = null, onClick: (() -> Unit)? = null) {
    Column {
        val action = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
        Row(Modifier.fillMaxWidth().then(if (value == null && tag != null) Modifier.testTag(tag) else Modifier)
            .then(action).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (value != null) Text(value, if (tag != null) Modifier.testTag(tag) else Modifier,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
