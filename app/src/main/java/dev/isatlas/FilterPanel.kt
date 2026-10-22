package dev.isatlas

import android.content.res.Configuration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Material's start drawer is mirrored; the panel and app keep their normal text direction. */
@Composable
fun RightFilterDrawer(state: DrawerState, enabled: Boolean, panel: @Composable () -> Unit, content: @Composable () -> Unit) {
    val contentDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(drawerState = state, gesturesEnabled = enabled,
            drawerContent = { CompositionLocalProvider(LocalLayoutDirection provides contentDirection) { panel() } }) {
            CompositionLocalProvider(LocalLayoutDirection provides contentDirection) { content() }
        }
    }
}

@Composable
fun FilterPanel(state: AtlasState, model: AppViewModel) {
    var extensionQuery by rememberSaveable { mutableStateOf("") }
    val extensionGrid = rememberLazyGridState()
    LaunchedEffect(extensionQuery) { extensionGrid.scrollToItem(0) }
    val catalogOptions = remember(state.compilerIsa, state.pack) {
        state.pack?.let { state.compilerIsa?.catalogOptions(it) }.orEmpty()
    }
    val options = remember(catalogOptions, extensionQuery) {
        val query = extensionQuery.trim()
        catalogOptions.filter { it.contains(query, ignoreCase = true) }
            .sortedWith(compareBy<String> { when { it.length == 1 -> 0; it.startsWith("s") -> 1; it.startsWith("z") -> 2; else -> 3 } }
                .thenBy { it })
    }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val panelWidth = (configuration.screenWidthDp * 0.9f).dp.coerceAtMost(if (landscape) 800.dp else 380.dp)
    val reset = { model.xlen(64); model.privilege(null); model.clearExtensions(); model.namespace(null); extensionQuery = "" }
    val basicFilters: @Composable ColumnScope.() -> Unit = {
            Text("架构位宽", style = MaterialTheme.typography.labelLarge)
            SlidingSegments(listOf("RV32", "RV64"), if (state.xlen == 32) 0 else 1, "filter-width") { model.xlen(if (it == 0) 32 else 64) }
            HorizontalDivider()
            Text("访问权限", style = MaterialTheme.typography.labelLarge)
            val privileges = listOf("all", "M", "S", "H", "VS", "U", "D")
            SlidingSegments(privileges, privileges.indexOf(state.privilege ?: "all"), "filter-permission") {
                model.privilege(privileges[it].takeUnless { value -> value == "all" })
            }
    }
    val extensionFilters: @Composable ColumnScope.() -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("扩展", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { model.clearExtensions(); extensionQuery = "" }) { Text("全部扩展") }
            }
            Text(if (state.extensions.isEmpty()) "-march 扩展；多选时匹配任一扩展" else "已选：${state.extensions.sorted().joinToString(" · ")}",
                maxLines = if (landscape) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("S / U 为模式能力；勾选时自动选中前置项。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.extensionMessage != null) Text(state.extensionMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            OutlinedTextField(extensionQuery, { extensionQuery = it }, Modifier.fillMaxWidth().testTag("extension-search"),
                placeholder = { Text("搜索细分扩展，如 Zicfiss", style = MaterialTheme.typography.bodySmall) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall, shape = RoundedCornerShape(10.dp))
            if (options.isEmpty()) Text("没有收录 CSR 的匹配扩展", style = MaterialTheme.typography.bodySmall)
            else LazyVerticalGrid(columns = GridCells.Fixed(2), state = extensionGrid, modifier = Modifier.fillMaxWidth().height(if (landscape) (configuration.screenHeightDp - 250).dp.coerceIn(96.dp, 240.dp) else 240.dp).testTag("extension-options"),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(options, key = { it }) { extension ->
                    FilterChip(state.extensions.contains(extension), { model.extension(extension) },
                        label = { Text(if (extension.length == 1) extension.uppercase() else extension.replaceFirstChar { it.uppercase() }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("extension-$extension"))
                }
            }
    }
    val sources: @Composable ColumnScope.() -> Unit = {
            Text("数据来源", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                val namespaces = state.pack?.registers.orEmpty().map { it.namespace.ifBlank { state.pack!!.namespace } }.distinct().sorted()
                (listOf<Pair<String?, String>>(null to "全部") + namespaces.map { value ->
                    value to when (value) { "manual" -> "ISA 手册"; "thead" -> "玄铁"; else -> value }
                }).forEach { (value, label) ->
                    FilterChip(selected = state.namespace == value, onClick = { model.namespace(value) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.testTag("source-${value ?: "all"}"))
                }
            }
    }
    ModalDrawerSheet(Modifier.width(panelWidth).testTag("filter-panel"), drawerShape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)) {
        if (landscape) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("筛选", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = reset) { Text("重置筛选") }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(0.43f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        basicFilters()
                        HorizontalDivider()
                        sources()
                    }
                    VerticalDivider()
                    Column(Modifier.weight(0.57f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        extensionFilters()
                    }
                }
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("筛选", style = MaterialTheme.typography.titleLarge)
                basicFilters()
                HorizontalDivider()
                extensionFilters()
                HorizontalDivider()
                sources()
                TextButton(onClick = reset) { Text("重置筛选") }
            }
        }
    }
}

/** A single track supports both direct option taps and scrubbing across the thumb. */
@Composable
private fun SlidingSegments(labels: List<String>, selected: Int, tag: String, onSelect: (Int) -> Unit) {
    val select by rememberUpdatedState(onSelect)
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp).clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .padding(3.dp).testTag(tag).selectableGroup().pointerInput(labels.size) {
            fun choose(x: Float) { select((x / size.width * labels.size).toInt().coerceIn(labels.indices)) }
            detectHorizontalDragGestures(onDragStart = { choose(it.x) }, onHorizontalDrag = { change, _ -> change.consume(); choose(change.position.x) })
        }) {
        val cell = maxWidth / labels.size
        val offset by animateDpAsState(cell * selected.coerceIn(labels.indices), label = "filter-thumb")
        Box(Modifier.offset(x = offset).width(cell).fillMaxHeight().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp)))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                Box(Modifier.weight(1f).fillMaxHeight().testTag("$tag-$label")
                    .selectable(selected == index, role = Role.RadioButton, onClick = { onSelect(index) }), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected == index) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
