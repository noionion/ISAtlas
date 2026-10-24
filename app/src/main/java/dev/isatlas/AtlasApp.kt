package dev.isatlas

import androidx.activity.compose.BackHandler
import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.isatlas.core.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtlasApp(model: AppViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val updates = remember(context) { UpdateRepository.get(context) }
    val update by updates.state.collectAsStateWithLifecycle()
    val selected = state.selected
    val selectedInstruction = state.selectedInstruction
    val detailOpen = selected != null || selectedInstruction != null
    val landscapeHome = !detailOpen && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val screenStates = rememberSaveableStateHolder()
    // Keep the grid alive while its screen is replaced by a register detail.
    val searchGrid = rememberLazyGridState()
    val instructionGrid = rememberLazyGridState()
    var csrExpanded by rememberSaveable { mutableStateOf(true) }
    var fieldsExpanded by rememberSaveable { mutableStateOf(true) }
    var descriptionsExpanded by rememberSaveable { mutableStateOf(true) }
    var previousQuery by rememberSaveable { mutableStateOf(state.query) }
    var previousWidth by rememberSaveable { mutableIntStateOf(state.xlen) }
    var previousPrivilege by rememberSaveable { mutableStateOf(state.privilege) }
    var previousNamespace by rememberSaveable { mutableStateOf(state.namespace) }
    var previousExtensions by rememberSaveable { mutableStateOf(state.extensions.sorted().joinToString()) }
    LaunchedEffect(state.query, state.xlen, state.privilege, state.namespace, state.extensions) {
        if (previousQuery != state.query || previousWidth != state.xlen || previousPrivilege != state.privilege || previousNamespace != state.namespace || previousExtensions != state.extensions.sorted().joinToString()) {
            searchGrid.scrollToItem(0)
            instructionGrid.scrollToItem(0)
            previousQuery = state.query
            previousWidth = state.xlen
            previousPrivilege = state.privilege
            previousNamespace = state.namespace
            previousExtensions = state.extensions.sorted().joinToString()
        }
    }
    BackHandler(enabled = detailOpen) { model.close() }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = state.showAbout) { model.showAbout(false) }
    BackHandler(enabled = state.showSources) { model.showSources(false) }
    RightFilterDrawer(state = drawer, enabled = !detailOpen && !state.showAbout,
        panel = { FilterPanel(state, model) }) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = if (state.showAbout) Modifier.clearAndSetSemantics {} else Modifier,
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    TopAppBar(
                        expandedHeight = if (landscapeHome || detailOpen) 48.dp else 64.dp,
                        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                        title = {
                            Row(Modifier.fillMaxWidth().testTag("home-app-bar"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text(selected?.name ?: selectedInstruction?.instruction?.name ?: "ISAtlas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("app-title"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (landscapeHome) SearchInput(state.query, model::query, Modifier.weight(1f, fill = false).widthIn(max = 480.dp), compact = true)
                            }
                        },
                        navigationIcon = {
                            if (detailOpen) IconButton(onClick = model::close) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, if (selected != null && selectedInstruction != null) "返回指令" else "返回搜索")
                            }
                        },
                        actions = {
                            IconButton(onClick = { keyboard?.hide(); model.showAbout(true) }) {
                                BadgedBox(badge = { if (update.hasUpdate) UpdateBadge("home-update-badge") }) {
                                    Icon(Icons.Default.Info, "关于 ISAtlas")
                                }
                            }
                            if (!detailOpen) IconButton(onClick = { keyboard?.hide(); scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "筛选") }
                        },
                    )
                },
            ) { insets ->
                Box(Modifier.fillMaxSize().padding(insets)) {
                    screenStates.SaveableStateProvider(selected?.id ?: selectedInstruction?.key ?: "search") {
                        when {
                            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            state.error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(state.error!!)
                                Button(onClick = model::load) { Text("重试") }
                            }
                            selected != null -> key(selected.id) { RegisterDetail(state, selected, model::xlen) }
                            selectedInstruction != null -> InstructionDetail(state, selectedInstruction, model::xlen, model::abiRegisters, { model.open(it) })
                            else -> CatalogSearchScreen(state, model, searchGrid, instructionGrid, showSearch = !landscapeHome,
                                expanded = mapOf(SearchCategory.CSR to csrExpanded, SearchCategory.FIELD to fieldsExpanded, SearchCategory.DESCRIPTION to descriptionsExpanded),
                                onToggle = { category -> when (category) {
                                    SearchCategory.CSR -> csrExpanded = !csrExpanded
                                    SearchCategory.FIELD -> fieldsExpanded = !fieldsExpanded
                                    SearchCategory.DESCRIPTION -> descriptionsExpanded = !descriptionsExpanded
                                } }, onOpen = { hit ->
                                keyboard?.hide()
                                model.open(hit.register.id, hit.field?.name)
                            })
                        }
                    }
                }
            }
            RaisedPage(state.showAbout, "关于 ISAtlas", "收起关于", { model.showAbout(false) },
                modifier = if (state.showSources) Modifier.clearAndSetSemantics {} else Modifier) {
                AboutScreen(state.pack, onShowSources = { model.showSources(true) }, update = update,
                    onCheckUpdate = { updates.check(manual = true) })
            }
            RaisedPage(state.showSources, "数据源与许可", "返回关于", { model.showSources(false) }) {
                DataSourcesScreen(state.pack)
            }
        }
    }
    UpdateDialogs(updates, update)
}

@Composable
private fun WidthPicker(xlen: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(32, 64).forEach { width ->
            FilterChip(selected = xlen == width, onClick = { onChange(width) }, label = { Text("RV$width") })
        }
    }
}

@Composable
internal fun SearchInput(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (compact) {
        BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            modifier = modifier.height(40.dp).testTag("search-input"),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { input ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("搜索名称、地址或编码", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            input()
                        }
                        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Clear, "清空搜索", Modifier.size(18.dp))
                        }
                    }
                }
            },
        )
        return
    }
    OutlinedTextField(
        value = query, onValueChange = onQuery,
        modifier = modifier.heightIn(min = 48.dp).testTag("search-input"),
        singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text("搜索名称、地址或编码", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, "清空搜索") } },
        shape = RoundedCornerShape(12.dp),
    )
}
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SearchScreen(state: AtlasState, model: AppViewModel, listState: LazyGridState, showSearch: Boolean,
                         expanded: Map<SearchCategory, Boolean>, onToggle: (SearchCategory) -> Unit, onOpen: (SearchHit) -> Unit) {
    val results = remember(state.pack, state.query, state.xlen, state.privilege, state.namespace, state.extensions, state.compilerIsa) { state.results }
    val groups = remember(results) { results.groupBy { it.category } }
    Column(Modifier.fillMaxSize()) {
        if (showSearch) SearchInput(state.query, model::query, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${results.size} 个结果", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("RV${state.xlen} · ${state.privilege ?: "全部权限"}" + (state.namespace?.let { " · $it" } ?: "") + (if (state.extensions.isEmpty()) "" else " · 扩展 ${state.extensions.size}"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(300.dp), state = listState, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f).testTag("search-results")) {
            if (results.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("当前数据范围内没有匹配项", fontWeight = FontWeight.Bold)
                        Text("可以调整关键词、位宽、权限或来源筛选。数据库未收录的项目，仍可能存在于规范或厂商文档中。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            SearchCategory.entries.filter { groups[it].orEmpty().isNotEmpty() }.forEach { category ->
                val hits = groups[category].orEmpty()
                val isExpanded = expanded[category] != false
                item(key = "search-group-${category.name}", span = { GridItemSpan(maxLineSpan) }) {
                    Surface(onClick = { onToggle(category) }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().testTag("search-group-${category.name}").semantics {
                            stateDescription = if (isExpanded) "已展开" else "已收起"
                        }) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(category.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(hits.size.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp))
                        }
                    }
                }
                if (isExpanded) items(hits, key = { it.key }) { hit -> ResultCard(hit, state.pack!!, onOpen) }
            }
        }
    }
}
@Composable
private fun ResultCard(hit: SearchHit, pack: DataPack, onOpen: (SearchHit) -> Unit) {
    val reg = hit.register
    ElevatedCard(onClick = { onOpen(hit) }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reg.locator.label, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("${reg.privilege} · ${if (hit.field == null) "CSR" else "FIELD"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (reg.coverage == "address-only") Text("仅地址", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.weight(1f))
            }
            Text(reg.name + (hit.field?.let { ".${it.name}" } ?: ""), fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(hit.field?.summary ?: reg.summary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text("${reg.namespace.ifBlank { pack.namespace }} · ${reg.sourceRevision.take(8).ifBlank { pack.source.revision }} · ${reg.xlens.joinToString(" / ") { "RV$it" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RegisterDetail(state: AtlasState, reg: RegisterDefinition, onWidthChange: (Int) -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fields = reg.fields.filter { it.layout(state.xlen) != null }.sortedByDescending { it.layout(state.xlen)!!.msb }
    val available = state.xlen in reg.xlens
    var selectedField by rememberSaveable(reg.id, state.selectedField) { mutableStateOf(state.selectedField) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var sourceError by remember { mutableStateOf(false) }
    var lastTarget by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.selectedField, state.xlen) {
        val target = "${state.selectedField}:${state.xlen}"
        if (lastTarget == target) return@LaunchedEffect
        lastTarget = target
        val index = fields.indexOfFirst { it.name == state.selectedField }
        if (available && index >= 0) list.scrollToItem(index + 2)
    }
    val address = (reg.locator as RegisterLocator.Csr).address
    val access = reg.privilege + if ((address shr 10) == 3) "RO" else "RW"
    val description = reg.summary.ifBlank { reg.title }.removeSuffix("。")
    LazyColumn(state = list, modifier = Modifier.testTag("register-detail-list"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${reg.locator.label} | $access", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("desc: $description", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                if (landscape) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("位布局", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    WidthPicker(state.xlen, onWidthChange)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!landscape) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("位布局", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    WidthPicker(state.xlen, onWidthChange)
                }
                if (available && reg.coverage == "address-only") {
                    Text("仅收录地址，字段布局待补充。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                } else if (available) {
                    RegisterDiagram(fields, state.xlen, selectedField, reg.regions) { selectedField = it }
                    fields.firstOrNull { it.name == selectedField }?.let { field ->
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${field.name} ${field.layout(state.xlen)!!.label} · ${field.access}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Text(field.summary, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { scope.launch { list.animateScrollToItem(fields.indexOf(field) + 2) } }) { Text("查看字段说明") }
                            }
                        }
                    }

                    reg.regions.filter { it.layout(state.xlen) != null }.distinctBy { it.label to it.access }.forEach { region ->
                        Text("${region.label} · ${region.access}：${region.summary}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else Text("此 CSR 不适用于 RV${state.xlen}，请选择 ${reg.xlens.joinToString { "RV$it" }}。", color = MaterialTheme.colorScheme.error)
            }
        }
        if (available) items(fields, key = { it.name }) { field ->
            val selected = selectedField == field.name
            Surface(
                modifier = Modifier.fillMaxWidth().border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .clickable { selectedField = field.name },
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
            ) {
                SelectionContainer {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${field.name}  ${field.layout(state.xlen)!!.label}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(field.summary)
                        Text(field.access, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (field.condition.isNotBlank()) Text("条件：${field.condition}", style = MaterialTheme.typography.bodySmall)
                        if (field.typeRule.isNotBlank()) {
                            var expanded by rememberSaveable(field.name) { mutableStateOf(false) }
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起访问规则" else "访问规则（依配置）") }
                            if (expanded) Text(field.typeRule, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        (field.valuesByXlen[state.xlen] ?: field.values).forEach { (value, description) -> Text("$value  →  $description", style = MaterialTheme.typography.bodySmall) }
                        if (field.sourceUrl.isNotBlank()) TextButton(onClick = { runCatching { uriHandler.openUri(field.sourceUrl) }.onFailure { sourceError = true } }) {
                            Text("字段规范原文")
                        }
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("来源与版本", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
            Text("${reg.namespace.ifBlank { state.pack!!.namespace }} · ${reg.license}", style = MaterialTheme.typography.bodySmall)
            Text(reg.sourceRevision.ifBlank { state.pack!!.source.revision }, style = MaterialTheme.typography.bodySmall)
            Text("数据包 v${state.pack!!.version} · ${if (reg.coverage == "address-only") "仅地址索引" else "上游字段目录"}", style = MaterialTheme.typography.bodySmall)
            if (reg.condition.isNotBlank()) Text("适用条件：${reg.condition}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { uriHandler.openUri(reg.sourceUrl) }.onFailure { sourceError = true } }) { Text("打开数据来源 ↗") }
            if (sourceError) SelectionContainer { Text("无法打开浏览器：${reg.sourceUrl}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
