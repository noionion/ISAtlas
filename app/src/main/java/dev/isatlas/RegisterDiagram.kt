package dev.isatlas

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.isatlas.core.*
import kotlin.math.cos
import kotlin.math.sin

/** A short register strip with boundary bit numbers and slanted field callouts. */
@Composable
fun RegisterDiagram(fields: List<FieldDefinition>, xlen: Int, selected: String?, regions: List<BitRegion> = emptyList(), onSelect: (String) -> Unit) {
    val orientation = LocalConfiguration.current.orientation
    val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
    val colors = MaterialTheme.colorScheme
    val selectField by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val angle = 55f
    val radians = Math.toRadians(angle.toDouble())
    val sine = sin(radians).toFloat()
    val cosine = cos(radians).toFloat()
    val namePaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { 12.sp.toPx() }
            typeface = Typeface.MONOSPACE
        }
    }
    // Reserve the bold text extent too: selection must not clip a callout.
    val labelWidths = fields.associate { field ->
        namePaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        field.name to with(density) { namePaint.measureText(field.displayName).toDp() }
    }
    val fontHeight = with(density) { (namePaint.fontMetrics.descent - namePaint.fontMetrics.ascent).toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("register-diagram")) {
        val viewportWidth = maxWidth
        var laneBits by rememberSaveable(xlen) { mutableIntStateOf(minOf(xlen, if (orientation == Configuration.ORIENTATION_LANDSCAPE) 32 else 16)) }
        var laneOrientation by rememberSaveable { mutableIntStateOf(orientation) }
        LaunchedEffect(orientation) {
            if (laneOrientation != orientation) {
                laneBits = minOf(xlen, if (orientation == Configuration.ORIENTATION_LANDSCAPE) 32 else 16)
                laneOrientation = orientation
            }
        }
        val lanes = remember(fields, regions, xlen, laneBits) { registerDiagramLanes(fields, xlen, laneBits, regions) }
        val gutter = 14.dp
        val availableWidth = viewportWidth - 24.dp - gutter * 2
        val minimumCell = if (laneBits == 16) 14 else 18
        val plotWidth = maxOf(availableWidth, (laneBits * minimumCell * maxOf(1f, density.fontScale)).dp)
        fun needsCallout(segment: DiagramSegment) = segment.field?.let { field ->
            plotWidth / laneBits * segment.width < labelWidths.getValue(field.name) + 12.dp
        } == true
        val rightOverflow = lanes.flatMap { it.segments }.mapNotNull { segment ->
            segment.field?.takeIf { needsCallout(segment) }?.let { field ->
                val laneHigh = (segment.msb / laneBits + 1) * laneBits - 1
                val center = plotWidth / laneBits * (laneHigh - segment.msb + segment.width / 2f)
                (center + labelWidths.getValue(field.name) * cosine - plotWidth).coerceAtLeast(0.dp)
            }
        }.maxOrNull() ?: 0.dp
        val contentWidth = plotWidth + gutter * 2 + rightOverflow
        val scroll = rememberScrollState()
        Surface(color = colors.surfaceContainerLowest, shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, colors.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (landscape) fields.firstOrNull { it.name == selected }?.let { "已选：${it.name} ${it.layout(xlen)!!.label} · ${it.access}" } ?: "拖动选择字段" else "MSB → LSB", Modifier.weight(1f), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                    listOf(16, 32).filter { it <= xlen }.forEach { bits ->
                        FilterChip(selected = laneBits == bits, onClick = { laneBits = bits }, label = { Text("$bits 位/行") })
                    }
                }
                }
                if (!landscape) Text(fields.firstOrNull { it.name == selected }?.let {
                    "已选：${it.name} ${it.layout(xlen)!!.label} · ${it.access}"
                } ?: "在条带上左右拖动选择字段", Modifier.fillMaxWidth().heightIn(min = 20.dp),
                    style = MaterialTheme.typography.labelMedium, color = colors.primary)
                Column(Modifier.horizontalScroll(scroll).testTag("diagram-lanes"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lanes.forEach { lane ->
                        val calloutHeight = lane.segments.mapNotNull { segment ->
                            segment.field?.takeIf { needsCallout(segment) }?.let { labelWidths.getValue(it.name) * sine + fontHeight * cosine }
                        }.maxOrNull() ?: 0.dp
                        val numberBand = maxOf(18.dp, fontHeight + 2.dp)
                        val frameHeight = 22.dp
                        val calloutTop = numberBand + frameHeight + 8.dp
                        val laneHeight = calloutTop + calloutHeight + 8.dp
                        Box(Modifier.width(contentWidth).height(laneHeight)) {
                            Canvas(Modifier.fillMaxSize()) {
                                val left = gutter.toPx()
                                val width = plotWidth.toPx()
                                val cell = width / laneBits
                                val top = numberBand.toPx()
                                val bottom = top + frameHeight.toPx()
                                val labelTop = calloutTop.toPx()
                                val ink = colors.onSurface
                                val canvas = drawContext.canvas.nativeCanvas
                                val numbers = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    textSize = with(density) { 10.sp.toPx() }
                                    typeface = Typeface.MONOSPACE
                                    color = colors.onSurfaceVariant.toArgb()
                                    textAlign = Paint.Align.CENTER
                                }
                                lane.segments.forEach { segment ->
                                    val x = left + (lane.msb - segment.msb) * cell
                                    val segmentWidth = segment.width * cell
                                    val field = segment.field
                                    val active = field != null && field.name == selected
                                    val fill = when {
                                        active -> colors.primaryContainer
                                        field == null -> colors.surfaceContainerLow
                                        else -> colors.surfaceContainerLowest
                                    }
                                    drawRect(fill, Offset(x, top), Size(segmentWidth, bottom - top))
                                    if (field == null && segment.region == null) clipRect(x, top, x + segmentWidth, bottom) {
                                        var hatch = x - (bottom - top)
                                        while (hatch < x + segmentWidth) {
                                            drawLine(colors.outline.copy(alpha = 0.14f), Offset(hatch, bottom),
                                                Offset(hatch + bottom - top, top), 1.dp.toPx())
                                            hatch += 10.dp.toPx()
                                        }
                                    }
                                    drawLine(ink, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                                    // Short ticks distinguish bits without dividing the whole field.
                                    for (bit in 1 until segment.width) {
                                        val tickX = x + bit * cell
                                        drawLine(ink.copy(alpha = 0.65f), Offset(tickX, top), Offset(tickX, top + 2.dp.toPx()), 0.7.dp.toPx())
                                        drawLine(ink.copy(alpha = 0.65f), Offset(tickX, bottom), Offset(tickX, bottom - 2.dp.toPx()), 0.7.dp.toPx())
                                    }
                                    // Each segment gets only its first and last bit number.
                                    canvas.drawText(segment.msb.toString(), x + cell / 2, -numbers.fontMetrics.ascent, numbers)
                                    if (segment.lsb != segment.msb) canvas.drawText(segment.lsb.toString(),
                                        x + segmentWidth - cell / 2, -numbers.fontMetrics.ascent, numbers)
                                    if (field != null) {
                                        val anchor = x + segmentWidth / 2
                                        namePaint.typeface = Typeface.create(Typeface.MONOSPACE, if (active) Typeface.BOLD else Typeface.NORMAL)
                                        namePaint.color = (if (active) colors.primary else ink).toArgb()
                                        if (needsCallout(segment)) {
                                            namePaint.textAlign = Paint.Align.LEFT
                                            drawLine(if (active) colors.primary else colors.outline, Offset(anchor, bottom),
                                                Offset(anchor, labelTop - 3.dp.toPx()), 0.8.dp.toPx())
                                            canvas.save()
                                            canvas.rotate(angle, anchor, labelTop)
                                            canvas.drawText(field.displayName, anchor, labelTop - namePaint.fontMetrics.ascent, namePaint)
                                            canvas.restore()
                                        } else {
                                            namePaint.textAlign = Paint.Align.CENTER
                                            canvas.drawText(field.displayName, anchor,
                                                (top + bottom - namePaint.fontMetrics.ascent - namePaint.fontMetrics.descent) / 2, namePaint)
                                        }
                                        if (active) drawLine(colors.primary, Offset(x, bottom - 2.dp.toPx()),
                                            Offset(x + segmentWidth, bottom - 2.dp.toPx()), 2.dp.toPx())
                                    } else {
                                        val unknown = Paint(numbers)
                                        val label = segment.region?.label ?: "待补充"
                                        val center = x + segmentWidth / 2
                                        val middle = (top + bottom) / 2
                                        if (unknown.measureText(label) + 4.dp.toPx() > segmentWidth) {
                                            unknown.textSize = with(density) { 8.sp.toPx() }
                                            canvas.save()
                                            canvas.rotate(-90f, center, middle)
                                            canvas.drawText(label, center, middle - (unknown.fontMetrics.ascent + unknown.fontMetrics.descent) / 2, unknown)
                                            canvas.restore()
                                        } else canvas.drawText(label, center,
                                            middle - (unknown.fontMetrics.ascent + unknown.fontMetrics.descent) / 2, unknown)
                                    }
                                }
                                drawRect(ink, Offset(left, top), Size(width, bottom - top), style = Stroke(1.dp.toPx()))
                                val rangeBaseline = bottom + 3.dp.toPx() - numbers.fontMetrics.ascent
                                numbers.textAlign = Paint.Align.RIGHT
                                canvas.drawText(lane.msb.toString(), left, rangeBaseline, numbers)
                                numbers.textAlign = Paint.Align.LEFT
                                canvas.drawText(lane.lsb.toString(), left + width, rangeBaseline, numbers)
                            }
                            Row(Modifier.offset(x = gutter, y = numberBand).width(plotWidth).height(frameHeight)) {
                                lane.segments.forEach { segment ->
                                    val field = segment.field
                                    Box(Modifier.weight(segment.width.toFloat()).fillMaxHeight()
                                        .then(if (field != null) Modifier
                                            .testTag("diagram-${field.name}-${segment.msb}-${segment.lsb}")
                                            .selectable(field.name == selected, role = Role.Button, onClick = { onSelect(field.name) })
                                            .semantics { contentDescription = "${field.name} ${segment.range}" }
                                        else Modifier.semantics { contentDescription = "${segment.region?.label ?: "待补充"} ${segment.range} ${segment.region?.access.orEmpty()}" }))
                                }
                            }
                            // A shared hit area avoids expanded, overlapping one-bit touch targets.
                            // Only drags starting on the strip select; the surrounding diagram pans.
                            Box(Modifier.offset(x = gutter, y = numberBand - 10.dp).width(plotWidth).height(frameHeight + 20.dp)
                                .testTag("diagram-strip-${lane.msb}-${lane.lsb}")
                                .pointerInput(lane, plotWidth) {
                                    fun selectAt(x: Float) {
                                        lane.fieldAtFraction(x / size.width)?.let { selectField(it.name) }
                                    }
                                    detectHorizontalDragGestures(
                                        onDragStart = { position -> selectAt(position.x) },
                                        onHorizontalDrag = { change, _ ->
                                            change.consume()
                                            selectAt(change.position.x)
                                        },
                                    )
                                }
                                .pointerInput(lane, plotWidth) {
                                    detectTapGestures { position ->
                                        lane.fieldAtFraction(position.x / size.width)?.let { selectField(it.name) }
                                    }
                                })
                        }
                    }
                }
                if (contentWidth > viewportWidth - 24.dp) Text("条带上拖动选域；条带外左右滑动查看 →", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
