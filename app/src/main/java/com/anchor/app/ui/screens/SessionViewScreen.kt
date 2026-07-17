package com.anchor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private const val PREFS_NAME = "anchor_prefs"
private const val KEY_FONT_SIZE = "session_font_size"
private const val DEFAULT_FONT_SIZE = 14
private const val MIN_FONT_SIZE = 8
private const val MAX_FONT_SIZE = 28
private const val CONTENT_PADDING_DP = 12

// Sample rendered invisibly to learn the true character cell size; long
// enough to average out per-glyph rounding
private const val RULER_TEXT = "MMMMMMMM"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionViewScreen(
    sessionName: String,
    paneContent: String,
    onSendKeys: (String) -> Unit,
    onResizePane: (cols: Int, rows: Int) -> Unit,
    onNextWindow: () -> Unit,
    onPreviousWindow: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, 0) }
    var fontSize by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)) }
    var input by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    LaunchedEffect(paneContent) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sessionName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (fontSize > MIN_FONT_SIZE) {
                                fontSize -= 2
                                prefs.edit().putInt(KEY_FONT_SIZE, fontSize).apply()
                            }
                        },
                        enabled = fontSize > MIN_FONT_SIZE
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease font size")
                    }
                    Text(
                        text = "${fontSize}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    IconButton(
                        onClick = {
                            if (fontSize < MAX_FONT_SIZE) {
                                fontSize += 2
                                prefs.edit().putInt(KEY_FONT_SIZE, fontSize).apply()
                            }
                        },
                        enabled = fontSize < MAX_FONT_SIZE
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase font size")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Send command...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                onSendKeys(input)
                                input = ""
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val availableWidthPx = with(density) { (maxWidth - (CONTENT_PADDING_DP * 2).dp).toPx() }
            val availableHeightPx = with(density) { (maxHeight - (CONTENT_PADDING_DP * 2).dp).toPx() }
            val style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                lineHeight = (fontSize + 4).sp,
                letterSpacing = 0.sp
            )

            // Character cell size must come from a real rendered Text, not
            // TextMeasurer: measuring disagrees with rendering when a system
            // font scale is set (and Text() merges the theme's letterSpacing),
            // which made cols overshoot what the screen can display.
            var rulerSize by remember { mutableStateOf(IntSize.Zero) }
            Text(
                text = RULER_TEXT,
                style = style,
                maxLines = 1,
                softWrap = false,
                onTextLayout = { rulerSize = it.size },
                modifier = Modifier
                    .wrapContentSize(unbounded = true)
                    .alpha(0f)
            )

            if (rulerSize.width > 0 && rulerSize.height > 0) {
                val charWidth = rulerSize.width.toFloat() / RULER_TEXT.length
                val cols = (availableWidthPx / charWidth).toInt().coerceAtLeast(20)
                val rows = (availableHeightPx / rulerSize.height).toInt().coerceAtLeast(10)
                LaunchedEffect(cols, rows) {
                    onResizePane(cols, rows)
                }
            }

            val swipeThreshold = with(density) { 80.dp.toPx() }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .pointerInput(swipeThreshold) {
                        var swipeDelta = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { swipeDelta = 0f },
                            onDragEnd = {
                                if (abs(swipeDelta) > swipeThreshold) {
                                    if (swipeDelta < 0) onNextWindow() else onPreviousWindow()
                                }
                                swipeDelta = 0f
                            },
                            onDragCancel = { swipeDelta = 0f },
                            onHorizontalDrag = { _, dragAmount -> swipeDelta += dragAmount }
                        )
                    }
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = paneContent.ifBlank { "(empty)" },
                    style = style,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(CONTENT_PADDING_DP.dp)
                )
            }
        }
    }
}
