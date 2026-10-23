package dev.isatlas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A full page rises above its parent and dismisses back down to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RaisedPage(
    visible: Boolean,
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(180)),
        exit = slideOutVertically(tween(240, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(180)),
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    expandedHeight = 48.dp,
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, backLabel) }
                    },
                )
            },
        ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } }
    }
}
