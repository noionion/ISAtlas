package dev.isatlas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.isatlas.core.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogSearchScreen(state: AtlasState, model: AppViewModel, csrGrid: LazyGridState, instGrid: LazyGridState,
    showSearch: Boolean, expanded: Map<SearchCategory, Boolean>, onToggle: (SearchCategory) -> Unit, onOpen: (SearchHit) -> Unit) {
    val pager = rememberPagerState(initialPage = state.instructionPage) { 2 }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(pager.settledPage) { model.page(pager.settledPage) }
    Column(Modifier.fillMaxSize()) {
        if (showSearch) SearchInput(state.query, model::query, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        SecondaryTabRow(selectedTabIndex = pager.currentPage) {
            listOf("CSR", "INST").forEachIndexed { index, title ->
                Tab(selected = pager.currentPage == index, onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    modifier = Modifier.height(42.dp).testTag("catalog-tab-$title"), text = { Text(title) })
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).testTag("catalog-pager")) { page ->
            if (page == 0) SearchScreen(state, model, csrGrid, false, expanded, onToggle, onOpen)
            else InstructionResultsScreen(state, instGrid, model::relaxInstructionFilters,
                onPseudo = { keyboard?.hide(); model.openPseudo(it) }) { keyboard?.hide(); model.openInstruction(it) }
        }
    }
}

@Composable
internal fun InstructionResultsScreen(state: AtlasState, grid: LazyGridState, onRelax: (InstructionDiagnostic) -> Unit,
    onPseudo: (InstructionPseudoHit) -> Unit, onOpen: (InstructionHit) -> Unit) {
    val result = remember(state.instructions, state.query, state.xlen, state.privilege, state.namespace, state.extensions, state.compilerIsa) { state.instructionResults }
    val groups = remember(result) { result.groups }
    val explanations = remember(result) { InstructionCandidates.explanations(result.hits) }
    val queryPattern = result.pattern
    var namesExpanded by rememberSaveable { mutableStateOf(true) }
    var descriptionsExpanded by rememberSaveable { mutableStateOf(true) }
    var encodingsExpanded by rememberSaveable { mutableStateOf(true) }
    var expandedVariants by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${groups.size} 个结果" + (if (result.hits.isNotEmpty()) " · ${result.hits.size} 个变体" else "") +
                (if (result.pseudoHits.isEmpty()) "" else " · ${result.pseudoHits.size} 个伪指令"),
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("RV${state.xlen} · ${state.privilege ?: "全部权限"}" + if (state.extensions.isEmpty()) "" else " · 扩展 ${state.extensions.size}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(300.dp), state = grid, modifier = Modifier.fillMaxSize().testTag("instruction-results"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (queryPattern != null) item(key = "partial-pattern", span = { GridItemSpan(maxLineSpan) }) {
                Text("部分编码 · ${queryPattern.notation} · ${queryPattern.knownBits}/${queryPattern.length} 位已知；? 为未知位，仅展示可补全的候选。",
                    modifier = Modifier.testTag("instruction-partial-notice"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (explanations.isNotEmpty()) item(key = "candidates", span = { GridItemSpan(maxLineSpan) }) {
                var expanded by rememberSaveable(state.query) { mutableStateOf(false) }
                Column {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("instruction-candidate-explanation")) {
                        Text(if (expanded) "收起多候选说明" else "为什么有多个候选？")
                    }
                    if (expanded) explanations.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp)) }
                }
            }
            if (groups.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前数据范围内没有匹配项", fontWeight = FontWeight.Bold)
                    Text(result.diagnostic?.message ?: result.error ?: "请调整关键词或筛选条件。", style = MaterialTheme.typography.bodyMedium)
                    result.diagnostic?.takeIf { it.clear.isNotEmpty() }?.let { diagnostic ->
                        TextButton(onClick = { onRelax(diagnostic) }, modifier = Modifier.testTag("relax-instruction-filters")) {
                            Text(diagnostic.suggestedXlen?.let { "切换 RV$it 并清除相关筛选" } ?: "清除相关筛选")
                        }
                    }
                }
            }
            InstructionMatchCategory.entries.forEach { category ->
                val matchingGroups = groups.filter { it.category == category }
                if (matchingGroups.isNotEmpty()) {
                    val expanded = when (category) {
                        InstructionMatchCategory.NAME -> namesExpanded
                        InstructionMatchCategory.DESCRIPTION -> descriptionsExpanded
                        InstructionMatchCategory.ENCODING -> encodingsExpanded
                    }
                    item(key = "category-${category.name}", span = { GridItemSpan(maxLineSpan) }) {
                        Surface(onClick = { when (category) {
                            InstructionMatchCategory.NAME -> namesExpanded = !namesExpanded
                            InstructionMatchCategory.DESCRIPTION -> descriptionsExpanded = !descriptionsExpanded
                            InstructionMatchCategory.ENCODING -> encodingsExpanded = !encodingsExpanded
                        } }, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().testTag("instruction-category-${category.name}").semantics {
                                stateDescription = if (expanded) "已展开" else "已收起"
                            }) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(category.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(matchingGroups.size.toString(), style = MaterialTheme.typography.labelMedium)
                                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(24.dp))
                            }
                        }
                    }
                    if (expanded) items(matchingGroups, key = { it.key }) { group ->
                        if (group.size == 1) {
                            if (group.hits.isNotEmpty()) InstructionResultCard(state, group.hits.single(), onOpen)
                            else PseudoInstructionCard(group.pseudoHits.single(), onPseudo)
                        }
                        else {
                            val showVariants = group.key in expandedVariants
                            ElevatedCard(Modifier.testTag("inst-group-${group.key}")) {
                                Surface(onClick = { expandedVariants = if (showVariants) expandedVariants - group.key else expandedVariants + group.key },
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier.fillMaxWidth().testTag("inst-variants-${group.key}").semantics {
                                        stateDescription = if (showVariants) "已展开" else "已收起"
                                    }) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(group.name, Modifier.weight(1f), fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            Icon(if (showVariants) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                                        }
                                        Text("${group.size} 个候选 · ${(group.hits.map { it.instruction.namespace } + group.pseudoHits.map { it.instruction.namespace }).distinct().size} 个来源",
                                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Text(if (showVariants) "点击变体查看详情" else "展开查看各变体的扩展、编码与语义", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (showVariants) group.hits.forEach { hit ->
                                    HorizontalDivider()
                                    InstructionResultCard(state, hit, onOpen)
                                }
                                if (showVariants) group.pseudoHits.forEach { hit ->
                                    HorizontalDivider()
                                    PseudoInstructionCard(hit, onPseudo)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstructionResultCard(state: AtlasState, hit: InstructionHit, onOpen: (InstructionHit) -> Unit) {
    ElevatedCard(onClick = { onOpen(hit) }, modifier = Modifier.fillMaxWidth().testTag("inst-${hit.key}")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(hit.instruction.name, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${normalizePrivilege(hit.encoding.privilege)} · ${hit.encoding.length} bit", style = MaterialTheme.typography.labelSmall)
            }
            Text(hit.encoding.summary ?: hit.instruction.summary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (hit.category == InstructionMatchCategory.DESCRIPTION) Text("描述匹配", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hit.encoding.requirements, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            Text(hit.instruction.namespace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hit.word != null) {
                val address = InstructionCsr.address(hit.encoding, hit.word!!)
                val csrs = address?.let { state.pack?.let { pack -> InstructionCsr.resolve(pack, it, state.xlen, state.namespace) } }.orEmpty()
                InstructionAssembly.render(hit, state.abiRegisters, csrs.singleOrNull()?.name, state.xlen)?.let { assembly ->
                    Text(assembly.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    if (!assembly.complete) Text("操作数字段（汇编格式待补充）", style = MaterialTheme.typography.labelSmall)
                }
            }
            else if (hit.pattern != null) Text("部分编码匹配 · 操作数可能尚未确定", modifier = Modifier.testTag("partial-${hit.key}"),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            else Text("${hit.encoding.match} / ${hit.encoding.mask}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            if (hit.instruction.kind != "instruction") Text(if (hit.instruction.kind == "alias") "别名 → ${hit.instruction.aliasOf}" else "HINT", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun InstructionDetail(state: AtlasState, hit: InstructionHit, onWidth: (Int) -> Unit, onAbi: (Boolean) -> Unit, onCsr: (String) -> Unit,
    onRelated: (InstructionHit) -> Unit = {}) {
    val instruction = hit.instruction
    val variants = instruction.encodings.withIndex().filter { state.xlen in it.value.xlens }
    var variantIndex by rememberSaveable(hit.key) { mutableIntStateOf(hit.index) }
    val variant = variants.find { it.index == variantIndex } ?: variants.firstOrNull()
    val encoding = variant?.value
    val related = remember(state.instructions, hit.encodingQuery, state.xlen, state.privilege, state.namespace, state.extensions, state.compilerIsa) {
        hit.encodingQuery?.let { query -> state.instructions?.let { catalog -> InstructionSearch.query(catalog, query,
            CatalogFilter(state.xlen, state.privilege, state.namespace, state.extensions), state.compilerIsa).hits } }.orEmpty()
    }
    val bases = remember(state.instructions, instruction.id, hit.encodingQuery, state.xlen, state.privilege, state.extensions, state.compilerIsa) {
        if (hit.encodingQuery != null || instruction.aliasOf == null) emptyList() else state.instructions?.let { catalog ->
            InstructionSearch.query(catalog, instruction.aliasOf.orEmpty(),
                CatalogFilter(state.xlen, state.privilege, instruction.namespace, state.extensions), state.compilerIsa).hits.filter { it.instruction.name == instruction.aliasOf }
        }.orEmpty()
    }
    var selectedField by rememberSaveable(hit.key, variant?.index) { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("instruction-detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${encoding?.length ?: "—"} bit | ${encoding?.privilege?.let(::normalizePrivilege) ?: "—"}", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                WidthPicker(state.xlen, onWidth)
            }
            Text(encoding?.summary ?: instruction.summary, style = MaterialTheme.typography.bodyMedium)
            hit.pattern?.let { pattern ->
                Text("部分编码 · ${pattern.query}", modifier = Modifier.testTag("instruction-partial-query"),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("? 为未知位，待补全后才能确定完整机器码与汇编。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (instruction.kind == "alias") item {
            Text("别名 → ${instruction.aliasOf}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            bases.forEach { base ->
                TextButton(onClick = { onRelated(base) }, modifier = Modifier.testTag("instruction-base-${base.key}")) {
                    Text("查看 ${base.instruction.name} · ${base.encoding.requirements}")
                }
            }
        }
        if (related.size > 1) item {
            var expanded by rememberSaveable(hit.key) { mutableStateOf(false) }
            Column {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("instruction-related-toggle")) {
                    Text(if (expanded) "收起候选" else "${if (hit.pattern != null) "部分编码候选" else "同编码候选"} · ${related.size}")
                }
                if (expanded) {
                    InstructionCandidates.explanations(related).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp)) }
                    related.forEach { candidate ->
                        TextButton(onClick = {
                            if (candidate.instruction.id == instruction.id) variantIndex = candidate.index else onRelated(candidate)
                        }, enabled = candidate.key != "${instruction.id}:${variant?.index}",
                            modifier = Modifier.testTag("instruction-related-${candidate.key}")) {
                            Text("${candidate.instruction.name} · ${candidate.encoding.requirements} · ${candidate.instruction.namespace}")
                        }
                    }
                }
            }
        }
        if (encoding == null) item { Text("此指令不适用于 RV${state.xlen}。") }
        else {
            if (variants.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    variants.forEach { variant -> FilterChip(variant.index == (variants.find { it.index == variantIndex } ?: variants.first()).index,
                        { variantIndex = variant.index }, label = { Text("${variant.index + 1} · ${variant.value.requirements}") }) }
                }
            }
            item {
                Text(encoding.requirements, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                val fields = encoding.fields.map { field -> FieldDefinition(
                    name = if (encoding.fields.count { it.name == field.name } > 1) "${field.name} [${field.msb}:${field.lsb}]" else field.name, summary = field.operand ?: "固定编码 ${field.value}", access = "编码",
                    layouts = listOf(BitLayout(encoding.length, field.msb, field.lsb)), values = field.values) }
                RegisterDiagram(fields, encoding.length, selectedField) { selectedField = it }
                fields.find { it.name == selectedField }?.let { Text("${it.name.substringBefore(" #")} ${it.layout(encoding.length)!!.label} · ${it.summary}") }
            }
            item {
                SelectionContainer { Text("match: ${encoding.match}\nmask:  ${encoding.mask}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                hit.pattern?.let { pattern ->
                    if (pattern.matches(encoding, state.xlen)) {
                        Text("已知操作数与未确定项", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(pattern.operands(encoding).entries.joinToString("\n") { "${it.key} = ${it.value ?: "未确定"}" },
                            modifier = Modifier.testTag("instruction-partial-operands"), style = MaterialTheme.typography.bodySmall) }
                    } else Text("原部分编码不匹配当前位宽或编码变体。", style = MaterialTheme.typography.bodySmall)
                }
                val matched = hit.word?.takeIf { encoding.matches(it, state.xlen) }
                if (matched != null) {
                    val address = InstructionCsr.address(encoding, matched)
                    val csrs = address?.let { state.pack?.let { pack -> InstructionCsr.resolve(pack, it, state.xlen, state.namespace) } }.orEmpty()
                    val current = hit.copy(encoding = encoding, index = variant.index, word = matched)
                    InstructionAssembly.render(current, state.abiRegisters, csrs.singleOrNull()?.name, state.xlen)?.let { assembly ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(state.abiRegisters, { onAbi(true) }, label = { Text("ABI") }, modifier = Modifier.testTag("register-names-abi"))
                            FilterChip(!state.abiRegisters, { onAbi(false) }, label = { Text("x / f 编号") }, modifier = Modifier.testTag("register-names-number"))
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { clipboard.setText(AnnotatedString(assembly.text)) }) { Text(if (assembly.complete) "复制汇编" else "复制字段") }
                        }
                        if (!assembly.complete) Text("操作数字段（汇编格式待补充）", style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(assembly.text, modifier = Modifier.testTag("instruction-assembly"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                    }
                    SelectionContainer { Text(encoding.operands(matched).entries.joinToString("\n") { "${it.key} = ${it.value}" }, style = MaterialTheme.typography.bodySmall) }
                    if (address != null) {
                        Text("CSR · 0x${address.toString(16).padStart(3, '0')}", style = MaterialTheme.typography.labelMedium)
                        if (csrs.isEmpty()) Text("当前位宽和来源范围内未收录此 CSR。", style = MaterialTheme.typography.bodySmall)
                        else {
                            if (csrs.size > 1) Text("存在多个来源候选，请选择：", style = MaterialTheme.typography.bodySmall)
                            csrs.forEach { csr -> TextButton(onClick = { onCsr(csr.id) }, modifier = Modifier.testTag("instruction-csr-${csr.id}")) {
                                Text("${csr.name} · ${csr.namespace.ifBlank { state.pack!!.namespace }} ↗")
                            } }
                        }
                    }
                } else if (hit.word != null) Text("原机器码不匹配当前位宽或编码变体。", style = MaterialTheme.typography.bodySmall)

            }
            val example = InstructionExamples.create(instruction, encoding, state.xlen, state.abiRegisters)
            if (example != null) item {
                InstructionExampleCard(example, independent = hit.encodingQuery != null)
            }
            if (encoding.contextRules.isNotEmpty() || encoding.constraints.isNotEmpty()) item {
                var expanded by rememberSaveable(hit.key, variant?.index) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起执行条件" else "执行条件与限制") }
                if (expanded) {
                    Text("编码匹配后，执行仍需满足扩展与运行状态条件。", style = MaterialTheme.typography.bodySmall)
                    encoding.contextRules.distinctBy { it.summary }.forEach { Text("• ${it.summary}", style = MaterialTheme.typography.bodySmall) }
                    encoding.constraints.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            items(encoding.fields.filter { it.values.isNotEmpty() }) { field ->
                Text(field.name, fontWeight = FontWeight.Bold)
                field.values.forEach { (value, description) -> Text("$value → $description", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            HorizontalDivider()
            Text("${instruction.namespace} · ${instruction.license} · ${instruction.sourceRevision.take(8)}", style = MaterialTheme.typography.labelSmall)
            val source = encoding?.sources?.firstOrNull()?.url ?: instruction.semanticSources.firstOrNull()?.url
            if (source != null) TextButton(onClick = { runCatching { uri.openUri(source) }.onFailure { linkError = true } }) { Text("打开规范原文 ↗") }
            if (linkError) Text("无法打开浏览器", style = MaterialTheme.typography.bodySmall)
        }
    }
}
