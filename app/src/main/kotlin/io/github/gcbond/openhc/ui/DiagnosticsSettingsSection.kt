package io.github.gcbond.openhc.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.diagnostics.DiagnosticsExport
import io.github.gcbond.openhc.diagnostics.DiagnosticsReport
import io.github.gcbond.openhc.diagnostics.LiveLogBuffer
import io.github.gcbond.openhc.diagnostics.LogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页「诊断」一组：整合后的「实时日志」滚动查看与一键导出完整诊断报告（[LiveLogBuffer] + [DiagnosticsExport]）。
 */
@Composable
internal fun DiagnosticsSettingsSection() {
    SettingsGroup(stringResource(R.string.settings_group_diagnostics)) {
        LiveLogRow()
    }
}

private const val LIVE_THROTTLE_MS = 350L
private val LIVE_SPLIT_TOOLBAR_MIN = 520.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveLogRow() {
    val context = LocalContext.current
    val compact = LocalCompact.current
    val buffer = remember { LiveLogBuffer() }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    var entries by remember { mutableStateOf<List<LiveLogBuffer.Entry>>(emptyList()) }
    var pendingWhilePaused by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var warnOnly by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(true) }
    var jumpRequest by remember { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val listState = rememberLazyListState()

    DisposableEffect(buffer) {
        var detach: (() -> Unit)? = null
        var disposed = false
        Thread({
            val d = LogRecorder.attachLive(buffer) { wake.trySend(Unit) }
            synchronized(buffer) {
                if (disposed) d() else detach = d
            }
        }, "OpenHC-LiveLogInit").apply {
            isDaemon = true
            start()
        }
        onDispose {
            synchronized(buffer) {
                disposed = true
                detach?.invoke()
                detach = null
            }
        }
    }

    LaunchedEffect(buffer, paused) {
        wake.trySend(Unit)
        while (wake.receiveCatching().isSuccess) {
            if (paused) {
                pendingWhilePaused = buffer.pendingCount()
            } else {
                val snap = withContext(Dispatchers.Default) { buffer.drainAndSnapshot() }
                entries = snap
                pendingWhilePaused = 0
                loaded = true
            }
            delay(LIVE_THROTTLE_MS)
        }
    }

    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (scrolling || wasScrolling) {
                    following = !canForward
                }
                wasScrolling = scrolling
            }
    }

    val visibleEntries = remember(entries, warnOnly) {
        if (warnOnly) entries.filter { it.isWarn } else entries
    }
    val lastVisibleId = visibleEntries.lastOrNull()?.id ?: -1L

    LaunchedEffect(jumpRequest) {
        if (jumpRequest > 0 && visibleEntries.isNotEmpty()) {
            following = true
            listState.scrollToItem(visibleEntries.lastIndex)
        }
    }

    LaunchedEffect(lastVisibleId, warnOnly) {
        if (visibleEntries.isNotEmpty() && following && !paused) {
            listState.scrollToItem(visibleEntries.lastIndex)
        }
    }

    val summary = when {
        !loaded -> stringResource(R.string.settings_diag_live_loading)
        entries.isEmpty() && pendingWhilePaused == 0 -> stringResource(R.string.settings_diag_live_empty_summary)
        paused && pendingWhilePaused > 0 ->
            stringResource(R.string.settings_diag_live_paused_pending, pendingWhilePaused, visibleEntries.size)
        paused -> stringResource(R.string.settings_diag_live_paused, visibleEntries.size)
        !following -> stringResource(R.string.settings_diag_live_scrolled_up, visibleEntries.size)
        warnOnly -> stringResource(R.string.settings_diag_live_following_warn, visibleEntries.size, entries.size)
        else -> stringResource(R.string.settings_diag_live_following, entries.size)
    }

    val onExport: () -> Unit = {
        exporting = true
        exportNote = null
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = DiagnosticsExport.export(app)
            main.post {
                exporting = false
                exportNote = when (result) {
                    is DiagnosticsExport.Result.Saved -> app.getString(R.string.settings_diag_saved, result.location) to false
                    is DiagnosticsExport.Result.Failed -> app.getString(R.string.settings_diag_failed, result.reason) to true
                }
            }
        }, "OpenHC-DiagExport").start()
    }

    val viewButtons: @Composable () -> Unit = {
        ActionButton(
            text = stringResource(R.string.settings_diag_live_warn_only),
            kind = if (warnOnly) ActionKind.PRIMARY else ActionKind.NORMAL,
            onClick = {
                warnOnly = !warnOnly
                following = true
            },
        )
        ActionButton(
            text = stringResource(if (paused) R.string.settings_diag_live_resume else R.string.settings_diag_live_pause),
            kind = if (paused) ActionKind.PRIMARY else ActionKind.NORMAL,
            onClick = {
                val resuming = paused
                paused = !paused
                if (resuming) {
                    following = true
                    jumpRequest++
                }
            },
        )
        ActionButton(
            text = stringResource(R.string.settings_diag_live_clear),
            enabled = entries.isNotEmpty() || pendingWhilePaused > 0,
            onClick = {
                buffer.clear()
                entries = emptyList()
                pendingWhilePaused = 0
                paused = false
                following = true
            },
        )
        if (!following && visibleEntries.isNotEmpty()) {
            ActionButton(
                text = stringResource(R.string.settings_diag_live_to_bottom),
                kind = ActionKind.PRIMARY,
                onClick = { jumpRequest++ },
            )
        }
    }

    val exportButton: @Composable () -> Unit = {
        ActionButton(
            text = stringResource(if (exporting) R.string.settings_diag_exporting else R.string.settings_diag_export_button),
            kind = ActionKind.PRIMARY,
            enabled = !exporting,
            onClick = onExport,
        )
    }

    GroupRow(spacing = if (compact) 10.dp else 12.dp) {
        RowHeader(
            stringResource(R.string.settings_diag_live),
            stringResource(R.string.settings_diag_live_hint, LiveLogBuffer.DEFAULT_MAX_LINES),
            summary = summary,
        )

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= LIVE_SPLIT_TOOLBAR_MIN) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    viewButtons()
                    Spacer(Modifier.weight(1f))
                    exportButton()
                }
            } else {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    viewButtons()
                    exportButton()
                }
            }
        }

        exportNote?.let { (text, warn) -> RowNote(text, warn) }

        val scope = rememberCoroutineScope()
        val boxShape = RoundedCornerShape(12.dp)
        val lineStyle = remember(compact) {
            UiType.value(compact).copy(
                fontSize = if (compact) 12.sp else 13.sp,
                lineHeight = 1.4.em,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 250.dp else 380.dp)
                .clip(boxShape)
                .background(Palette.SegmentBackground)
                .border(1.dp, Palette.Divider, boxShape)
                .dpadFocusRing(boxShape)
                .focusable(enabled = visibleEntries.isNotEmpty())
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || visibleEntries.isEmpty()) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            if (!listState.canScrollBackward) return@onPreviewKeyEvent false
                            following = false
                            val target = (listState.firstVisibleItemIndex - 4).coerceAtLeast(0)
                            scope.launch { listState.scrollToItem(target) }
                            true
                        }
                        Key.DirectionDown -> {
                            if (!listState.canScrollForward) {
                                following = true
                                return@onPreviewKeyEvent false
                            }
                            val target = (listState.firstVisibleItemIndex + 4).coerceAtMost(visibleEntries.lastIndex)
                            scope.launch {
                                listState.scrollToItem(target)
                                if (!listState.canScrollForward) following = true
                            }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            if (visibleEntries.isEmpty()) {
                val emptyText = if (entries.isEmpty()) {
                    stringResource(R.string.settings_diag_live_empty)
                } else {
                    stringResource(R.string.settings_diag_live_no_warn, entries.size)
                }
                Text(
                    emptyText,
                    style = UiType.rowSummary(compact),
                    color = Palette.Muted,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 16.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visibleEntries, key = { it.id }) { entry ->
                        val color = when (entry.kind) {
                            DiagnosticsReport.LineKind.WARN -> Palette.Warn
                            DiagnosticsReport.LineKind.MARKER -> Palette.Accent
                            DiagnosticsReport.LineKind.NORMAL -> Palette.Text2
                        }
                        Text(
                            text = entry.text,
                            style = lineStyle,
                            color = color,
                        )
                    }
                }
            }
        }
    }
}
