package dev.isatlas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.isatlas.core.InstructionUsageExample

@Composable
internal fun InstructionExampleCard(example: InstructionUsageExample, independent: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    OutlinedCard(Modifier.fillMaxWidth().testTag("instruction-example")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("使用示例", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { clipboard.setText(AnnotatedString(example.copyText)) },
                    modifier = Modifier.testTag("instruction-example-copy")) { Text("复制") }
            }
            SelectionContainer {
                Text(example.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("instruction-example-code"))
            }
            Text(example.explanation, style = MaterialTheme.typography.bodySmall)
            if (independent) Text("独立示例，与上方查询编码无关。", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
