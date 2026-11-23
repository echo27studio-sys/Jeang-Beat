package com.jeangbeat.feature.pianoroll

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeangbeat.core.model.Channel
import com.jeangbeat.core.model.NOTE_NAMES
import com.jeangbeat.core.model.Note
import com.jeangbeat.core.model.Pattern
import com.jeangbeat.core.model.Scale
import com.jeangbeat.core.model.ScaleSetting
import com.jeangbeat.core.model.Snap
import com.jeangbeat.core.model.Timing
import com.jeangbeat.core.model.defaultPitch
import com.jeangbeat.core.model.isBlackKey
import com.jeangbeat.core.model.pitchName
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.R
import com.jeangbeat.core.ui.displayName
import kotlin.math.floor
import kotlin.math.roundToInt

interface PianoRollActions {
    fun addNote(note: Note)
    fun removeNote(note: Note)
    fun replaceNote(old: Note, new: Note)

    /** Several edits recorded as one undo step. */
    fun replaceNotes(changes: List<Pair<Note, Note>>)
    fun previewNote(pitch: Int)
    fun setSnap(snap: Snap)
    fun setScale(scale: ScaleSetting)
}

private const val LOWEST_PITCH = 24 // C1
private const val HIGHEST_PITCH = 96 // C7
private val KEYBOARD_WIDTH = 52.dp
private val VELOCITY_LANE_HEIGHT = 64.dp

/** A note being dragged: [original] is in the pattern, [draft] is what the user sees. */
private data class Drag(val original: Note, val draft: Note)

@Composable
fun PianoRoll(
    channel: Channel?,
    pattern: Pattern,
    ghostNotes: List<Note>,
    scale: ScaleSetting,
    snap: Snap,
    playheadTick: Float?,
    actions: PianoRollActions,
    modifier: Modifier = Modifier,
) {
    if (channel == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.select_channel), color = JbColors.TextDim)
        }
        return
    }

    var stepWidthDp by remember { mutableStateOf(36f) }
    var rowHeightDp by remember { mutableStateOf(22f) }
    var newNoteLength by remember(channel.id) { mutableStateOf(Timing.TICKS_PER_STEP * 2) }
    var dragState by remember { mutableStateOf<Drag?>(null) }
    var velocityDraft by remember { mutableStateOf<Map<Note, Int>>(emptyMap()) }

    val notes = pattern.notesFor(channel.id)
    val rows = remember(scale, notes) { visibleRows(scale, notes) }
    val rowOf = remember(rows) { rows.withIndex().associate { (i, p) -> p to i } }

    val density = LocalDensity.current
    val tickPx = with(density) { stepWidthDp.dp.toPx() } / Timing.TICKS_PER_STEP
    val rowPx = with(density) { rowHeightDp.dp.toPx() }
    val gridWidth = with(density) { (pattern.lengthTicks * tickPx).toDp() }
    val gridHeight = with(density) { (rows.size * rowPx).toDp() }

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()

    // Start scrolled to the instrument's typical range.
    LaunchedEffect(channel.id, rows.size) {
        val target = notes.map { it.pitch }.maxOrNull() ?: (channel.instrument.defaultPitch + 12)
        val row = rows.indexOfFirst { it <= target }.coerceAtLeast(0)
        vScroll.scrollTo((row * rowPx - rowPx * 2).roundToInt().coerceAtLeast(0))
    }

    val currentActions by rememberUpdatedState(actions)
    val currentNotes by rememberUpdatedState(notes)
    val currentRows by rememberUpdatedState(rows)
    val currentRowOf by rememberUpdatedState(rowOf)
    val currentSnap by rememberUpdatedState(snap)
    val currentLength by rememberUpdatedState(pattern.lengthTicks)
    val currentNewLength by rememberUpdatedState(newNoteLength)

    Column(modifier.fillMaxSize()) {
        Toolbar(
            snap = snap,
            scale = scale,
            actions = actions,
            onZoomX = { stepWidthDp = (stepWidthDp * it).coerceIn(14f, 96f) },
            onZoomY = { rowHeightDp = (rowHeightDp * it).coerceIn(14f, 44f) },
        )
        Text(
            stringResource(R.string.piano_roll_hint),
            style = MaterialTheme.typography.labelSmall,
            color = JbColors.TextDim,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Keyboard
            Box(Modifier.width(KEYBOARD_WIDTH).fillMaxSize().verticalScroll(vScroll)) {
                Canvas(
                    Modifier
                        .size(KEYBOARD_WIDTH, gridHeight)
                        .pointerInput(rows) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val row = (down.position.y / rowPx).toInt()
                                currentRows.getOrNull(row)?.let(currentActions::previewNote)
                                waitForUpOrCancellation()
                            }
                        },
                ) { drawKeyboard(rows, rowPx, textMeasurer) }
            }
            // Note grid
            Box(Modifier.weight(1f).fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)) {
                Canvas(
                    Modifier
                        .size(gridWidth, gridHeight)
                        .pointerInput(tickPx, rowPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val hit = hitTest(down.position, currentNotes, currentRowOf, tickPx, rowPx)
                                if (hit != null) {
                                    down.consume()
                                    val original = hit.first
                                    val resizing = hit.second
                                    val originalRow = currentRowOf.getValue(original.pitch)
                                    var draft = original
                                    dragState = Drag(original, draft)
                                    drag(down.id) { change ->
                                        change.consume()
                                        val dx = (change.position.x - down.position.x) / tickPx
                                        val s = currentSnap.ticks
                                        draft = if (resizing) {
                                            val end = Timing.quantize((original.endTick + dx).roundToInt(), s)
                                            val maxLen = currentLength - original.startTick
                                            original.copy(lengthTicks = (end - original.startTick).coerceIn(s.coerceAtMost(maxLen), maxLen))
                                        } else {
                                            val start = Timing.quantize((original.startTick + dx).roundToInt(), s)
                                                .coerceIn(0, (currentLength - original.lengthTicks).coerceAtLeast(0))
                                            val rowDelta = ((change.position.y - down.position.y) / rowPx).roundToInt()
                                            val row = (originalRow + rowDelta).coerceIn(0, currentRows.lastIndex)
                                            val pitch = currentRows[row]
                                            if (pitch != draft.pitch) currentActions.previewNote(pitch)
                                            original.copy(startTick = start, pitch = pitch)
                                        }
                                        dragState = Drag(original, draft)
                                    }
                                    dragState = null
                                    if (draft == original) {
                                        currentActions.removeNote(original)
                                    } else {
                                        if (resizing) newNoteLength = draft.lengthTicks
                                        currentActions.replaceNote(original, draft)
                                    }
                                } else {
                                    val up = waitForUpOrCancellation()
                                    if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                                        val row = (down.position.y / rowPx).toInt()
                                        val pitch = currentRows.getOrNull(row) ?: return@awaitEachGesture
                                        val s = currentSnap.ticks
                                        val start = (floor(down.position.x / tickPx / s).toInt() * s)
                                            .coerceIn(0, currentLength - 1)
                                        val length = currentNewLength.coerceIn(1, currentLength - start)
                                        currentActions.addNote(Note(start, length, pitch))
                                        currentActions.previewNote(pitch)
                                    }
                                }
                            }
                        },
                ) {
                    drawGrid(rows, pattern.lengthTicks, tickPx, rowPx, scale)
                    ghostNotes.forEach { note ->
                        rowOf[note.pitch]?.let { row -> drawNote(note, row, tickPx, rowPx, JbColors.TextDim.copy(alpha = 0.25f)) }
                    }
                    val color = JbColors.channel(channel.color)
                    val d = dragState
                    notes.forEach { note ->
                        val shown = if (d != null && note == d.original) d.draft else note
                        val row = rowOf[shown.pitch] ?: return@forEach
                        val velocity = velocityDraft[note] ?: shown.velocity
                        drawNote(shown, row, tickPx, rowPx, color.copy(alpha = 0.55f + 0.45f * velocity / 127f))
                    }
                    playheadTick?.let { drawPlayhead(it * tickPx) }
                }
            }
        }
        // Velocity lane
        Row(Modifier.fillMaxWidth().height(VELOCITY_LANE_HEIGHT).background(JbColors.Surface)) {
            Box(Modifier.width(KEYBOARD_WIDTH).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.velocity), style = MaterialTheme.typography.labelSmall, color = JbColors.TextDim)
            }
            Box(Modifier.weight(1f).fillMaxSize().horizontalScroll(hScroll)) {
                Canvas(
                    Modifier
                        .size(gridWidth, VELOCITY_LANE_HEIGHT)
                        .pointerInput(tickPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun apply(position: Offset) {
                                    val tick = position.x / tickPx
                                    val velocity = ((1f - position.y / size.height) * 127f).roundToInt().coerceIn(1, 127)
                                    val hits = currentNotes.filter { tick >= it.startTick && tick < it.endTick }
                                    if (hits.isNotEmpty()) velocityDraft = velocityDraft + hits.associateWith { velocity }
                                }
                                down.consume()
                                apply(down.position)
                                drag(down.id) { change ->
                                    change.consume()
                                    apply(change.position)
                                }
                                val changes = velocityDraft.map { (note, v) -> note to note.copy(velocity = v) }
                                    .filter { it.first != it.second }
                                velocityDraft = emptyMap()
                                if (changes.isNotEmpty()) currentActions.replaceNotes(changes)
                            }
                        },
                ) {
                    drawGrid(emptyList(), pattern.lengthTicks, tickPx, size.height, scale)
                    val color = JbColors.channel(channel.color)
                    notes.forEach { note ->
                        val v = velocityDraft[note] ?: note.velocity
                        val h = size.height * v / 127f
                        val x = note.startTick * tickPx
                        drawRect(color, Offset(x, size.height - h), Size(4.dp.toPx(), h))
                        drawCircle(color, 4.dp.toPx(), Offset(x + 2.dp.toPx(), size.height - h))
                    }
                }
            }
        }
    }
}

/** Rows shown top (high) to bottom (low). With scale lock, out-of-scale rows are folded away. */
private fun visibleRows(scale: ScaleSetting, notes: List<Note>): List<Int> {
    val all = (LOWEST_PITCH..HIGHEST_PITCH)
    val pitches = if (scale.lock) {
        val used = notes.map { it.pitch }.toSet()
        all.filter { scale.scale.contains(it, scale.root) || it in used }
    } else {
        all.toList()
    }
    return pitches.sortedDescending()
}

/** Returns the note under [pos] and whether the touch is on its right edge (resize). */
private fun hitTest(
    pos: Offset,
    notes: List<Note>,
    rowOf: Map<Int, Int>,
    tickPx: Float,
    rowPx: Float,
): Pair<Note, Boolean>? {
    for (note in notes.asReversed()) {
        val row = rowOf[note.pitch] ?: continue
        val left = note.startTick * tickPx
        val right = note.endTick * tickPx
        val top = row * rowPx
        if (pos.x in left..right && pos.y in top..(top + rowPx)) {
            val edge = minOf(24f * (rowPx / 22f), (right - left) / 3f)
            return note to (right - pos.x <= edge)
        }
    }
    return null
}

private fun DrawScope.drawGrid(rows: List<Int>, lengthTicks: Int, tickPx: Float, rowPx: Float, scale: ScaleSetting) {
    // Row backgrounds: black keys darker, out-of-scale rows dimmed, root rows highlighted.
    rows.forEachIndexed { i, pitch ->
        val color = when {
            Math.floorMod(pitch - scale.root, 12) == 0 -> JbColors.SurfaceRaised
            !scale.scale.contains(pitch, scale.root) -> Color(0xFF111319)
            isBlackKey(pitch) -> Color(0xFF14171D)
            else -> JbColors.Background
        }
        drawRect(color, Offset(0f, i * rowPx), Size(size.width, rowPx))
        drawLine(JbColors.GridLine, Offset(0f, (i + 1) * rowPx), Offset(size.width, (i + 1) * rowPx), 1f)
    }
    if (rows.isEmpty()) drawRect(JbColors.Surface)
    // Vertical lines: step, beat, bar.
    var tick = 0
    while (tick <= lengthTicks) {
        val x = tick * tickPx
        val (color, width) = when {
            tick % (Timing.TICKS_PER_BEAT * 4) == 0 -> JbColors.TextDim.copy(alpha = 0.6f) to 2f
            tick % Timing.TICKS_PER_BEAT == 0 -> JbColors.GridBeat to 1.5f
            else -> JbColors.GridLine to 1f
        }
        drawLine(color, Offset(x, 0f), Offset(x, size.height), width)
        tick += Timing.TICKS_PER_STEP
    }
}

private fun DrawScope.drawNote(note: Note, row: Int, tickPx: Float, rowPx: Float, color: Color) {
    val inset = 1.5f
    drawRoundRect(
        color,
        topLeft = Offset(note.startTick * tickPx + inset, row * rowPx + inset),
        size = Size((note.lengthTicks * tickPx - inset * 2).coerceAtLeast(2f), rowPx - inset * 2),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
}

private fun DrawScope.drawPlayhead(x: Float) {
    drawLine(JbColors.Playhead, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
}

private fun DrawScope.drawKeyboard(rows: List<Int>, rowPx: Float, textMeasurer: TextMeasurer) {
    rows.forEachIndexed { i, pitch ->
        val black = isBlackKey(pitch)
        drawRect(
            if (black) Color(0xFF1C1F26) else Color(0xFFD9DCE3),
            Offset(0f, i * rowPx),
            Size(size.width, rowPx - 1f),
        )
        if (Math.floorMod(pitch, 12) == 0 || rowPx >= 20.dp.toPx()) {
            val label = textMeasurer.measure(
                pitchName(pitch),
                TextStyle(fontSize = (rowPx * 0.42f / density).coerceIn(8f, 12f).sp, color = if (black) Color.White else Color.Black),
            )
            drawText(label, topLeft = Offset(4.dp.toPx(), i * rowPx + (rowPx - label.size.height) / 2f))
        }
    }
}

@Composable
private fun Toolbar(
    snap: Snap,
    scale: ScaleSetting,
    actions: PianoRollActions,
    onZoomX: (Float) -> Unit,
    onZoomY: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Dropdown(stringResource(R.string.snap), snapLabel(snap), Snap.entries, ::snapLabel) { actions.setSnap(it) }
        Dropdown(stringResource(R.string.key), NOTE_NAMES[scale.root], (0..11).toList(), { NOTE_NAMES[it] }) {
            actions.setScale(scale.copy(root = it))
        }
        Dropdown(stringResource(R.string.scale), scale.scale.displayName(), Scale.entries, { it.displayName() }) {
            actions.setScale(scale.copy(scale = it))
        }
        Text(stringResource(R.string.scale_lock), style = MaterialTheme.typography.labelMedium)
        Switch(checked = scale.lock, onCheckedChange = { actions.setScale(scale.copy(lock = it)) })
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { onZoomX(1f / 1.25f) }) { Icon(Icons.Default.ZoomOut, stringResource(R.string.zoom_out)) }
        IconButton(onClick = { onZoomX(1.25f) }) { Icon(Icons.Default.ZoomIn, stringResource(R.string.zoom_in)) }
        IconButton(onClick = { onZoomY(1f / 1.2f) }) { Icon(Icons.Default.UnfoldLess, stringResource(R.string.zoom_out)) }
        IconButton(onClick = { onZoomY(1.2f) }) { Icon(Icons.Default.UnfoldMore, stringResource(R.string.zoom_in)) }
    }
}

private fun snapLabel(snap: Snap): String = when (snap) {
    Snap.BAR -> "1 bar"
    Snap.BEAT -> "1/4"
    Snap.EIGHTH -> "1/8"
    Snap.STEP -> "1/16"
    Snap.STEP_TRIPLET -> "1/16T"
    Snap.THIRTY_SECOND -> "1/32"
}

@Composable
private fun <T> Dropdown(label: String, current: String, options: List<T>, name: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("$label: $current", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(name(option)) }, onClick = {
                    open = false
                    onSelect(option)
                })
            }
        }
    }
}
