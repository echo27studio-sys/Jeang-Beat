package com.jeangbeat.feature.pianoroll

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.jeangbeat.core.ui.sampleDropTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeangbeat.core.model.Channel
import com.jeangbeat.core.model.ChordType
import com.jeangbeat.core.model.NOTE_NAMES
import com.jeangbeat.core.model.Note
import com.jeangbeat.core.model.NoteKey
import com.jeangbeat.core.model.LengthMode
import com.jeangbeat.core.model.Pattern
import com.jeangbeat.core.model.Scale
import com.jeangbeat.core.model.ScaleSetting
import com.jeangbeat.core.model.Snap
import com.jeangbeat.core.model.Timing
import com.jeangbeat.core.model.chordNotes
import com.jeangbeat.core.model.defaultPitch
import com.jeangbeat.core.model.duplicated
import com.jeangbeat.core.model.flipped
import com.jeangbeat.core.model.humanized
import com.jeangbeat.core.model.isBlackKey
import com.jeangbeat.core.model.key
import com.jeangbeat.core.model.legato
import com.jeangbeat.core.model.pitchName
import com.jeangbeat.core.model.quantized
import com.jeangbeat.core.model.sliced
import com.jeangbeat.core.model.transposed
import com.jeangbeat.core.ui.DropdownChip
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.R
import com.jeangbeat.core.ui.ToolButton
import com.jeangbeat.core.ui.displayName
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

interface PianoRollActions {
    /** Edits this channel's notes in the current pattern. [final] = false while dragging (no undo step yet). */
    fun editNotes(final: Boolean, transform: (List<Note>) -> List<Note>)
    fun previewNote(pitch: Int)
    fun setSnap(snap: Snap)
    fun setScale(scale: ScaleSetting)
    fun copy(notes: List<Note>)
    fun clipboard(): List<Note>
    fun togglePlay()
    fun close()

    /** Ruler: where PAT playback starts, and the piano-roll loop region. */
    fun setPosition(tick: Int)
    fun setLoop(startTick: Int, endTick: Int)
    fun clearLoop()

    /** Browser drop: replaces the open channel's sound. */
    fun drop(item: com.jeangbeat.core.ui.Dropped): Boolean = false
}

enum class RollTool { DRAW, PAINT, SELECT, SLICE, DELETE, MUTE, SLIDE }

enum class Lane { VELOCITY, PAN, FINE, RELEASE }

private const val LOWEST_PITCH = 12 // C0
private const val HIGHEST_PITCH = 108 // C8
private val KEYBOARD_WIDTH = 46.dp
private val LANE_HEIGHT = 70.dp

@Composable
fun PianoRoll(
    channel: Channel,
    pattern: Pattern,
    ghostNotes: List<Note>,
    scale: ScaleSetting,
    snap: Snap,
    playheadTick: Float?,
    isPlaying: Boolean,
    actions: PianoRollActions,
    modifier: Modifier = Modifier,
    loop: IntRange? = null,
    startTick: Int = 0,
) {
    var tool by rememberSaveable { mutableStateOf(RollTool.DRAW) }
    var chord by rememberSaveable { mutableStateOf(ChordType.NONE) }
    var lane by rememberSaveable { mutableStateOf(Lane.VELOCITY) }
    var selection by remember(channel.id, pattern.id) { mutableStateOf(emptySet<NoteKey>()) }
    var stepWidthDp by rememberSaveable { mutableFloatStateOf(34f) }
    var rowHeightDp by rememberSaveable { mutableFloatStateOf(20f) }
    var newLength by remember(channel.id) { mutableStateOf(pattern.stepTicks * 2) }

    val notes = pattern.notesFor(channel.id)
    // Drop selected keys that no longer exist (after undo, edits elsewhere).
    val liveKeys = notes.map { it.key }.toSet()
    if (!liveKeys.containsAll(selection)) selection = selection intersect liveKeys

    val rows = remember(scale, notes) { visibleRows(scale, notes) }
    val rowOf = remember(rows) { rows.withIndex().associate { (i, p) -> p to i } }
    val density = LocalDensity.current
    val tickPx = with(density) { stepWidthDp.dp.toPx() } / Timing.TICKS_PER_STEP
    val rowPx = with(density) { rowHeightDp.dp.toPx() }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val measurer = rememberTextMeasurer()

    LaunchedEffect(channel.id, rows.size) {
        val target = notes.map { it.pitch }.maxOrNull() ?: (channel.instrument.defaultPitch + 12)
        val row = rows.indexOfFirst { it <= target }.coerceAtLeast(0)
        vScroll.scrollTo((row * rowPx - rowPx * 2).roundToInt().coerceAtLeast(0))
    }

    val sel = selection
    fun applyToSelection(transform: (List<Note>, Set<NoteKey>) -> List<Note>) = actions.editNotes(true) { transform(it, sel) }

    Column(modifier.fillMaxSize().sampleDropTarget { item, _ -> actions.drop(item) }) {
        // ---- title bar ----
        Row(
            Modifier.fillMaxWidth().background(JbColors.Surface).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.tab_piano_roll) + " · " + channel.name,
                    fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = JbColors.channel(channel.color), maxLines = 1,
                )
                Text(
                    "${pattern.name} · ${NOTE_NAMES[scale.root]} ${scale.scale.displayName()} · " +
                        (if (selection.isEmpty()) "" else stringResource(R.string.selected_count, selection.size)),
                    fontSize = 10.sp, color = JbColors.TextDim, maxLines = 1,
                )
            }
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (isPlaying) JbColors.Text else JbColors.Accent).clickable(onClick = actions::togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, stringResource(if (isPlaying) R.string.stop else R.string.play), tint = Color.Black)
            }
            Spacer(Modifier.width(6.dp))
        }

        // ---- tools ----
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF13161C)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolButton(Icons.Default.Edit, stringResource(R.string.tool_draw), tool == RollTool.DRAW, { tool = RollTool.DRAW })
            ToolButton(Icons.Default.FormatPaint, stringResource(R.string.tool_paint), tool == RollTool.PAINT, { tool = RollTool.PAINT })
            ToolButton(Icons.Default.HighlightAlt, stringResource(R.string.tool_select), tool == RollTool.SELECT, { tool = RollTool.SELECT })
            ToolButton(Icons.Default.ContentCut, stringResource(R.string.tool_slice), tool == RollTool.SLICE, { tool = RollTool.SLICE })
            ToolButton(Icons.Default.Delete, stringResource(R.string.tool_delete), tool == RollTool.DELETE, { tool = RollTool.DELETE })
            ToolButton(Icons.AutoMirrored.Filled.VolumeOff, stringResource(R.string.tool_mute), tool == RollTool.MUTE, { tool = RollTool.MUTE })
            ToolButton(Icons.Default.TrendingUp, stringResource(R.string.tool_slide), tool == RollTool.SLIDE, { tool = RollTool.SLIDE })
            Spacer(Modifier.width(6.dp))
            DropdownChip("⊞ ${snap.label}", Snap.entries, { it.label }, actions::setSnap)
            DropdownChip("♫ ${if (chord == ChordType.NONE) stringResource(R.string.chord) else chord.label}", ChordType.entries, { it.label }, { chord = it }, highlight = chord != ChordType.NONE)
            ScaleMenu(scale, actions::setScale)
            IconButton(onClick = { stepWidthDp = (stepWidthDp / 1.25f).coerceIn(10f, 120f) }) { Icon(Icons.Default.ZoomOut, stringResource(R.string.zoom_out)) }
            IconButton(onClick = { stepWidthDp = (stepWidthDp * 1.25f).coerceIn(10f, 120f) }) { Icon(Icons.Default.ZoomIn, stringResource(R.string.zoom_in)) }
            ActionChip("⇕−") { rowHeightDp = (rowHeightDp / 1.2f).coerceIn(12f, 44f) }
            ActionChip("⇕+") { rowHeightDp = (rowHeightDp * 1.2f).coerceIn(12f, 44f) }
        }

        // ---- edit actions ----
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ActionChip(stringResource(R.string.quantize)) { applyToSelection { n, s -> n.quantized(snap.ticks, s) } }
            ActionChip("−1") { applyToSelection { n, s -> n.transposed(-1, s) }.also { selection = selection.map { NoteKey(it.startTick, it.pitch - 1) }.toSet() } }
            ActionChip("+1") { applyToSelection { n, s -> n.transposed(1, s) }.also { selection = selection.map { NoteKey(it.startTick, it.pitch + 1) }.toSet() } }
            ActionChip("−12") { applyToSelection { n, s -> n.transposed(-12, s) }.also { selection = selection.map { NoteKey(it.startTick, it.pitch - 12) }.toSet() } }
            ActionChip("+12") { applyToSelection { n, s -> n.transposed(12, s) }.also { selection = selection.map { NoteKey(it.startTick, it.pitch + 12) }.toSet() } }
            ActionChip("⧉ " + stringResource(R.string.duplicate)) {
                var copies = emptySet<NoteKey>()
                actions.editNotes(true) { list -> list.duplicated(sel, snap.ticks.coerceAtLeast(pattern.stepTicks), pattern.maxTicks).also { copies = it.second }.first }
                if (copies.isNotEmpty()) selection = copies
            }
            ActionChip(stringResource(R.string.copy)) { actions.copy(if (sel.isEmpty()) notes else notes.filter { it.key in sel }) }
            ActionChip(stringResource(R.string.paste)) {
                val clip = actions.clipboard()
                if (clip.isNotEmpty()) {
                    actions.editNotes(true) { it + clip }
                    selection = clip.map { it.key }.toSet()
                }
            }
            ActionChip(stringResource(if (selection.isEmpty()) R.string.select_all else R.string.deselect)) {
                selection = if (selection.isEmpty()) notes.map { it.key }.toSet() else emptySet()
            }
            ActionChip(stringResource(R.string.legato)) { applyToSelection { n, s -> n.legato(s, pattern.lengthTicks) } }
            ActionChip(stringResource(R.string.flip)) { applyToSelection { n, s -> n.flipped(s) }; selection = emptySet() }
            ActionChip(stringResource(R.string.humanize)) { applyToSelection { n, s -> n.humanized(0.12f, n.size + pattern.id, s) } }
            if (selection.isNotEmpty()) {
                ActionChip(stringResource(R.string.delete_selected)) { actions.editNotes(true) { list -> list.filterNot { it.key in sel } }; selection = emptySet() }
            }
        }

        // ---- bar ruler (tap = start position, long-press + drag = loop) ----
        Row(Modifier.fillMaxWidth().height(24.dp).background(Color(0xFF13161C))) {
            Box(Modifier.width(KEYBOARD_WIDTH).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (loop != null) {
                    Text("⟲✕", fontSize = 11.sp, color = JbColors.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = actions::clearLoop).padding(4.dp))
                }
            }
            RollRuler(pattern, tickPx, hScroll, loop, startTick, playheadTick, snap, measurer, actions, Modifier.weight(1f).fillMaxHeight())
        }

        // ---- keyboard + grid ----
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.width(KEYBOARD_WIDTH).fillMaxHeight().verticalScroll(vScroll)) {
                val keyboardHeight = with(density) { (rows.size * rowPx).toDp() }
                Canvas(
                    Modifier.size(KEYBOARD_WIDTH, keyboardHeight).pointerInput(rows, rowPx, actions) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            rows.getOrNull((down.position.y / rowPx).toInt())?.let(actions::previewNote)
                        }
                    },
                ) { drawKeyboard(rows, rowPx, measurer) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val gridWidth = with(density) { (pattern.viewTicks * tickPx).toDp() }.coerceAtLeast(maxWidth)
                val gridHeight = with(density) { (rows.size * rowPx).toDp() }.coerceAtLeast(maxHeight)
                RollGrid(
                    channel, pattern, notes, ghostNotes, rows, rowOf, scale, snap, tool, chord, selection,
                    newLength, tickPx, rowPx, playheadTick, hScroll, vScroll, measurer, actions,
                    onSelection = { selection = it },
                    onNewLength = { newLength = it },
                    onZoom = { factor -> stepWidthDp = (stepWidthDp * factor).coerceIn(10f, 120f) },
                    modifier = Modifier.fillMaxSize(),
                    contentWidth = gridWidth, contentHeight = gridHeight,
                )
            }
        }

        // ---- note property lane ----
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF13161C)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Lane.entries.forEach { l ->
                val label = stringResource(
                    when (l) {
                        Lane.VELOCITY -> R.string.velocity
                        Lane.PAN -> R.string.pan
                        Lane.FINE -> R.string.fine_pitch
                        Lane.RELEASE -> R.string.release
                    },
                )
                Text(
                    label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (lane == l) JbColors.Accent else JbColors.SurfaceRaised)
                        .clickable { lane = l }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = if (lane == l) Color.Black else JbColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(LANE_HEIGHT).background(Color(0xFF101318))) {
            Spacer(Modifier.width(KEYBOARD_WIDTH))
            PropertyLane(channel, pattern, notes, selection, lane, tickPx, hScroll, actions, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(JbColors.SurfaceRaised).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 6.dp),
        fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
    )
}

@Composable
private fun ScaleMenu(scale: ScaleSetting, onChange: (ScaleSetting) -> Unit) {
    DropdownChip(
        text = "♪ ${NOTE_NAMES[scale.root]}${if (scale.lock) " 🔒" else ""}",
        options = (0..11).toList(),
        optionLabel = { (if (it == scale.root) "✓ " else "   ") + NOTE_NAMES[it] },
        onSelect = { onChange(scale.copy(root = it)) },
        highlight = true,
        extra = { close ->
            HorizontalDivider()
            Scale.entries.forEach { s ->
                DropdownMenuItem(text = { Text((if (s == scale.scale) "✓ " else "   ") + s.displayName()) }, onClick = { close(); onChange(scale.copy(scale = s)) })
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text((if (scale.lock) "✓ " else "   ") + stringResource(R.string.scale_lock)) },
                onClick = { close(); onChange(scale.copy(lock = !scale.lock)) },
            )
        },
    )
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

/** What a single-finger gesture is doing. */
private sealed interface Gesture {
    data object None : Gesture
    data object Pan : Gesture
    /** [base] = all notes when the gesture began; every drag frame is computed from it, never chained. */
    data class Move(
        val originals: List<Note>,
        val anchorTick: Int,
        val anchorRow: Int,
        val base: List<Note>,
        val rows: List<Int>,
        val rowOf: Map<Int, Int>,
        val wasSelected: Boolean,
    ) : Gesture
    data class Resize(val originals: List<Note>, val base: List<Note>, val wasSelected: Boolean) : Gesture
    data class Paint(val pitch: Int, val painted: MutableSet<Int>) : Gesture
    data class Marquee(val start: Offset) : Gesture
    data object Erase : Gesture
}

@Composable
private fun RollGrid(
    channel: Channel,
    pattern: Pattern,
    notes: List<Note>,
    ghostNotes: List<Note>,
    rows: List<Int>,
    rowOf: Map<Int, Int>,
    scale: ScaleSetting,
    snap: Snap,
    tool: RollTool,
    chord: ChordType,
    selection: Set<NoteKey>,
    newLength: Int,
    tickPx: Float,
    rowPx: Float,
    playheadTick: Float?,
    hScroll: ScrollState,
    vScroll: ScrollState,
    measurer: TextMeasurer,
    actions: PianoRollActions,
    onSelection: (Set<NoteKey>) -> Unit,
    onNewLength: (Int) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier,
    contentWidth: androidx.compose.ui.unit.Dp,
    contentHeight: androidx.compose.ui.unit.Dp,
) {
    val st = rememberUpdatedState(
        RollState(pattern, notes, rows, rowOf, snap, tool, chord, selection, newLength, tickPx, rowPx),
    )
    var marquee by remember { mutableStateOf<Rect?>(null) }
    val color = JbColors.channel(channel.color)

    Box(
        modifier
            .pointerInput(channel.id, pattern.id, actions) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val s = st.value
                    fun content(p: Offset) = Offset(p.x + hScroll.value, p.y + vScroll.value)
                    val start = content(down.position)
                    val hit = s.hitTest(start)
                    val tickAt = { p: Offset -> (p.x / s.tickPx).toInt().coerceIn(0, s.pattern.maxTicks - 1) }
                    val snapFloor = { t: Int -> (floor(t.toDouble() / s.snap.ticks).toInt() * s.snap.ticks).coerceIn(0, s.pattern.maxTicks - 1) }

                    var gesture: Gesture = when {
                        hit != null && (s.tool == RollTool.DRAW || s.tool == RollTool.SLIDE || s.tool == RollTool.SELECT) -> {
                            val (note, edge) = hit
                            val group = if (note.key in s.selection) s.notes.filter { it.key in s.selection } else listOf(note)
                            val wasSelected = note.key in s.selection
                            if (edge && s.tool != RollTool.SELECT) {
                                Gesture.Resize(group, s.notes, wasSelected)
                            } else {
                                Gesture.Move(group, tickAt(start), s.rowIndex(start.y), s.notes, s.rows, s.rowOf, wasSelected)
                            }
                        }
                        s.tool == RollTool.PAINT && hit == null -> s.rows.getOrNull(s.rowIndex(start.y))?.let { Gesture.Paint(it, mutableSetOf()) } ?: Gesture.None
                        s.tool == RollTool.SELECT -> Gesture.Marquee(start)
                        s.tool == RollTool.DELETE -> Gesture.Erase
                        else -> Gesture.None
                    }
                    var moved = false
                    var lastMoved: List<Note> = emptyList()
                    var twoFinger = false
                    var lastSpread = 0f
                    var lastCentroid = Offset.Zero
                    val slop = viewConfiguration.touchSlop

                    fun applyErase(p: Offset) {
                        st.value.hitTest(p)?.first?.let { n -> actions.editNotes(false) { list -> list - n } }
                    }
                    if (gesture is Gesture.Erase) applyErase(start)

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2 && !moved) {
                            // Two fingers: scroll and pinch-zoom; cancel any single-finger action.
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val centroid = (a + b) / 2f
                            val spread = abs(a.x - b.x)
                            if (!twoFinger) {
                                twoFinger = true
                                if (gesture is Gesture.Erase || gesture is Gesture.Paint) actions.editNotes(true) { it }
                                gesture = Gesture.None
                            } else {
                                hScroll.dispatchRawDelta(lastCentroid.x - centroid.x)
                                vScroll.dispatchRawDelta(lastCentroid.y - centroid.y)
                                if (lastSpread > 40f && spread > 40f) {
                                    val f = spread / lastSpread
                                    if (abs(f - 1f) > 0.01f) onZoom(f)
                                }
                            }
                            lastCentroid = centroid
                            lastSpread = spread
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (twoFinger) continue
                        val change: PointerInputChange = event.changes.firstOrNull { it.id == down.id } ?: break
                        val p = content(change.position)
                        if (!moved && (p - start).getDistance() > slop) moved = true
                        if (!moved) continue
                        change.consume()
                        val cur = st.value
                        when (val g = gesture) {
                            is Gesture.Move -> {
                                val dt = Timing.quantize(tickAt(p) - g.anchorTick, cur.snap.ticks)
                                // Rows are taken from the gesture start so folded (scale-lock) rows don't shift mid-drag.
                                val dr = ((p.y / cur.rowPx).toInt().coerceIn(0, g.rows.lastIndex.coerceAtLeast(0))) - g.anchorRow
                                val moved2 = g.originals.map { n ->
                                    val row = (g.rowOf[n.pitch] ?: g.anchorRow) + dr
                                    val pitch = g.rows.getOrNull(row.coerceIn(0, g.rows.lastIndex)) ?: n.pitch
                                    val maxStart = (cur.pattern.maxTicks - n.lengthTicks).coerceAtLeast(0)
                                    n.copy(startTick = (n.startTick + dt).coerceIn(0, maxStart), pitch = pitch)
                                }
                                actions.editNotes(false) { _ -> g.base.filterNot { it in g.originals } + moved2 }
                                lastMoved = moved2
                            }
                            is Gesture.Resize -> {
                                val first = g.originals.first()
                                val end = Timing.quantize(tickAt(p) + 1, cur.snap.ticks)
                                val delta = end - first.endTick
                                val resized = g.originals.map { n ->
                                    val maxLen = (cur.pattern.maxTicks - n.startTick).coerceAtLeast(1)
                                    val minLen = minOf(cur.snap.ticks.coerceAtMost(cur.pattern.stepTicks), maxLen)
                                    n.copy(lengthTicks = (n.lengthTicks + delta).coerceIn(minLen, maxLen))
                                }
                                actions.editNotes(false) { _ -> g.base.filterNot { it in g.originals } + resized }
                                lastMoved = resized
                            }
                            is Gesture.Paint -> {
                                val t = snapFloor(tickAt(p))
                                if (g.painted.add(t)) {
                                    actions.editNotes(false) { list -> list + Note(t, cur.newLength.coerceAtMost(cur.pattern.maxTicks - t), g.pitch) }
                                }
                            }
                            is Gesture.Marquee -> marquee = Rect(g.start, p)
                            Gesture.Erase -> applyErase(p)
                            Gesture.Pan, Gesture.None -> {
                                gesture = Gesture.Pan
                                hScroll.dispatchRawDelta(-(change.position.x - change.previousPosition.x))
                                vScroll.dispatchRawDelta(-(change.position.y - change.previousPosition.y))
                            }
                        }
                    }
                    if (twoFinger) return@awaitEachGesture

                    val s2 = st.value
                    when (val g = gesture) {
                        is Gesture.Move -> if (moved) {
                            actions.editNotes(true) { it }
                            // A moved selection stays selected so it can be moved again.
                            if (g.wasSelected) onSelection(lastMoved.map { it.key }.toSet())
                        }
                        is Gesture.Resize -> if (moved) {
                            actions.editNotes(true) { it }
                            lastMoved.firstOrNull()?.let { onNewLength(it.lengthTicks) }
                            if (g.wasSelected) onSelection(lastMoved.map { it.key }.toSet())
                        }
                        is Gesture.Paint -> if (moved) actions.editNotes(true) { it } else {
                            val t = snapFloor(tickAt(start))
                            actions.editNotes(true) { list -> list + Note(t, s2.newLength.coerceAtMost(s2.pattern.maxTicks - t), g.pitch) }
                            actions.previewNote(g.pitch)
                        }
                        is Gesture.Marquee -> {
                            val r = marquee
                            marquee = null
                            if (r != null && moved) {
                                val keys = s2.notes.filter { s2.noteRect(it)?.overlaps(r.normalized()) == true }.map { it.key }.toSet()
                                onSelection(keys)
                            } else if (hit != null) {
                                val k = hit.first.key
                                onSelection(if (k in s2.selection) s2.selection - k else s2.selection + k)
                            } else {
                                onSelection(emptySet())
                            }
                        }
                        Gesture.Erase -> actions.editNotes(true) { it }
                        Gesture.Pan -> Unit
                        Gesture.None -> if (!moved) {
                            // Taps
                            val pitch = s2.rows.getOrNull(s2.rowIndex(start.y))
                            when (s2.tool) {
                                RollTool.DRAW, RollTool.SLIDE, RollTool.PAINT -> if (hit != null) {
                                    // handled above for DRAW/SLIDE (Move); PAINT on a note does nothing
                                } else if (pitch != null) {
                                    val t = snapFloor(tickAt(start))
                                    val len = s2.newLength.coerceAtMost(s2.pattern.maxTicks - t)
                                    val added = chordNotes(pitch, s2.chord, t, len).map { if (s2.tool == RollTool.SLIDE) it.copy(slide = true) else it }
                                    actions.editNotes(true) { it + added }
                                    actions.previewNote(pitch)
                                }
                                RollTool.SLICE -> hit?.first?.let { n ->
                                    val at = Timing.quantize(tickAt(start), s2.snap.ticks)
                                    actions.editNotes(true) { it.sliced(n, at) }
                                }
                                RollTool.MUTE -> hit?.first?.let { n -> actions.editNotes(true) { list -> list.map { if (it == n) it.copy(muted = !it.muted) else it } } }
                                RollTool.SELECT, RollTool.DELETE -> Unit
                            }
                        }
                    }
                    // Tap on a note with Draw = delete it; with Slide = toggle slide.
                    if (!moved && gesture is Gesture.Move && hit != null) {
                        val n = hit.first
                        when (s2.tool) {
                            RollTool.DRAW -> { actions.editNotes(true) { it - n }; onSelection(s2.selection - n.key) }
                            RollTool.SLIDE -> actions.editNotes(true) { list -> list.map { if (it == n) it.copy(slide = !it.slide) else it } }
                            RollTool.SELECT -> {
                                val k = n.key
                                onSelection(if (k in s2.selection) s2.selection - k else s2.selection + k)
                            }
                            else -> Unit
                        }
                    }
                }
            }
            .horizontalScroll(hScroll, enabled = false)
            .verticalScroll(vScroll, enabled = false),
    ) {
        Canvas(Modifier.size(contentWidth, contentHeight)) {
            drawGrid(rows, pattern, tickPx, rowPx, scale)
            ghostNotes.forEach { n -> rowOf[n.pitch]?.let { drawNoteRect(n, it, tickPx, rowPx, JbColors.TextDim.copy(alpha = 0.18f)) } }
            notes.forEach { n ->
                val row = rowOf[n.pitch] ?: return@forEach
                val c = if (n.muted) JbColors.TextDim.copy(alpha = 0.5f) else color.copy(alpha = 0.55f + 0.45f * n.velocity / 127f)
                drawNoteRect(n, row, tickPx, rowPx, c)
                val top = row * rowPx
                val x = n.startTick * tickPx
                if (n.key in selection) {
                    drawRoundRect(Color.White, Offset(x + 1f, top + 1f), Size((n.lengthTicks * tickPx - 2f).coerceAtLeast(2f), rowPx - 2f), CornerRadius(4f), style = Stroke(2f))
                }
                if (n.slide) {
                    val path = Path().apply { moveTo(x + 2f, top + 2f); lineTo(x + rowPx * 0.55f, top + rowPx / 2); lineTo(x + 2f, top + rowPx - 2f); close() }
                    drawPath(path, JbColors.Accent)
                }
                // resize handle
                drawRect(Color.Black.copy(alpha = 0.3f), Offset(x + n.lengthTicks * tickPx - 5f, top + 4f), Size(2.5f, rowPx - 8f))
                if (rowPx >= 16.dp.toPx() && n.lengthTicks * tickPx > 26.dp.toPx()) {
                    val label = measurer.measure(pitchName(n.pitch), TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F1115)))
                    drawText(label, topLeft = Offset(x + (if (n.slide) rowPx * 0.6f else 4f), top + (rowPx - label.size.height) / 2))
                }
            }
            marquee?.let { r ->
                val n = r.normalized()
                drawRect(JbColors.Accent.copy(alpha = 0.08f), n.topLeft, n.size)
                drawRect(JbColors.Accent, n.topLeft, n.size, style = Stroke(1.5f))
            }
            playheadTick?.let { drawLine(Color.White, Offset(it * tickPx, 0f), Offset(it * tickPx, size.height), 1.5.dp.toPx()) }
        }
    }
}

private fun Rect.normalized(): Rect = Rect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))

/** Snapshot of everything the gesture code needs (kept fresh with rememberUpdatedState). */
private class RollState(
    val pattern: Pattern,
    val notes: List<Note>,
    val rows: List<Int>,
    val rowOf: Map<Int, Int>,
    val snap: Snap,
    val tool: RollTool,
    val chord: ChordType,
    val selection: Set<NoteKey>,
    val newLength: Int,
    val tickPx: Float,
    val rowPx: Float,
) {
    fun rowIndex(y: Float): Int = (y / rowPx).toInt().coerceIn(0, rows.lastIndex.coerceAtLeast(0))

    fun noteRect(n: Note): Rect? {
        val row = rowOf[n.pitch] ?: return null
        return Rect(n.startTick * tickPx, row * rowPx, n.endTick * tickPx, (row + 1) * rowPx)
    }

    /** Note under [p] and whether the touch is on its right edge (resize). */
    fun hitTest(p: Offset): Pair<Note, Boolean>? {
        for (n in notes.asReversed()) {
            val r = noteRect(n) ?: continue
            if (r.contains(p)) {
                val edge = minOf(24f, r.width / 3f)
                return n to (r.right - p.x <= edge)
            }
        }
        return null
    }
}

private fun DrawScope.drawGrid(rows: List<Int>, pattern: Pattern, tickPx: Float, rowPx: Float, scale: ScaleSetting) {
    rows.forEachIndexed { i, pitch ->
        val c = when {
            Math.floorMod(pitch - scale.root, 12) == 0 -> JbColors.SurfaceRaised
            !scale.scale.contains(pitch, scale.root) -> Color(0xFF0B0D11)
            isBlackKey(pitch) -> Color(0xFF14171D)
            else -> JbColors.Background
        }
        drawRect(c, Offset(0f, i * rowPx), Size(size.width, rowPx))
        drawLine(JbColors.GridLine, Offset(0f, (i + 1) * rowPx), Offset(size.width, (i + 1) * rowPx), 1f)
    }
    var tick = 0
    val beat = pattern.stepTicks * pattern.meter.stepsPerBeat
    val lastTick = (size.width / tickPx).toInt()
    while (tick <= lastTick) {
        val x = tick * tickPx
        val (c, w) = when {
            tick % Timing.TICKS_PER_BAR == 0 -> JbColors.TextDim.copy(alpha = 0.6f) to 2f
            tick % beat == 0 -> JbColors.GridBeat to 1.5f
            else -> JbColors.GridLine to 1f
        }
        drawLine(c, Offset(x, 0f), Offset(x, size.height), w)
        tick += pattern.stepTicks
    }
    // FIXED: shade past the end. AUTO: a gold line marks the current end (drawing past it extends the pattern).
    val end = pattern.lengthTicks * tickPx
    if (pattern.lengthMode == LengthMode.FIXED) {
        if (end < size.width) drawRect(Color.Black.copy(alpha = 0.4f), Offset(end, 0f), Size(size.width - end, size.height))
    } else {
        drawLine(JbColors.Accent.copy(alpha = 0.6f), Offset(end, 0f), Offset(end, size.height), 2f)
    }
}

private fun DrawScope.drawNoteRect(n: Note, row: Int, tickPx: Float, rowPx: Float, color: Color) {
    drawRoundRect(
        color,
        topLeft = Offset(n.startTick * tickPx + 1f, row * rowPx + 1.5f),
        size = Size((n.lengthTicks * tickPx - 2f).coerceAtLeast(3f), rowPx - 3f),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
}

private fun DrawScope.drawKeyboard(rows: List<Int>, rowPx: Float, measurer: TextMeasurer) {
    rows.forEachIndexed { i, pitch ->
        val black = isBlackKey(pitch)
        drawRect(if (black) Color(0xFF1C1F26) else Color(0xFFD9DCE3), Offset(0f, i * rowPx), Size(size.width, rowPx - 1f))
        if (Math.floorMod(pitch, 12) == 0 || rowPx >= 20.dp.toPx()) {
            val label = measurer.measure(pitchName(pitch), TextStyle(fontSize = 9.sp, color = if (black) Color.White else Color.Black, fontWeight = FontWeight.Bold))
            drawText(label, topLeft = Offset(4.dp.toPx(), i * rowPx + (rowPx - label.size.height) / 2f))
        }
    }
}

/** Velocity / pan / fine pitch / release bars at each note start. Drag to draw values. */
@Composable
private fun PropertyLane(
    channel: Channel,
    pattern: Pattern,
    notes: List<Note>,
    selection: Set<NoteKey>,
    lane: Lane,
    tickPx: Float,
    hScroll: ScrollState,
    actions: PianoRollActions,
    modifier: Modifier,
) {
    val st = rememberUpdatedState(Triple(notes, selection, lane))
    val color = JbColors.channel(channel.color)
    BoxWithConstraints(modifier) {
        val width = with(LocalDensity.current) { (pattern.viewTicks * tickPx).toDp() }.coerceAtLeast(maxWidth)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(tickPx, channel.id, pattern.id, actions) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun apply(p: Offset) {
                            val (ns, sel, ln) = st.value
                            val tick = (p.x + hScroll.value) / tickPx
                            val v = (1f - p.y / size.height).coerceIn(0f, 1f)
                            val targets = ns.filter { tick >= it.startTick && tick < it.endTick && (sel.isEmpty() || it.key in sel) }
                            if (targets.isEmpty()) return
                            actions.editNotes(false) { list ->
                                list.map { n ->
                                    if (n !in targets) n else when (ln) {
                                        Lane.VELOCITY -> n.copy(velocity = (1 + v * 126).roundToInt())
                                        Lane.PAN -> n.copy(pan = v * 2f - 1f)
                                        Lane.FINE -> n.copy(finePitch = (v * 2f - 1f) * 100f)
                                        Lane.RELEASE -> n.copy(release = v)
                                    }
                                }
                            }
                        }
                        apply(down.position)
                        drag(down.id) { c -> c.consume(); apply(c.position) }
                        actions.editNotes(true) { it }
                    }
                }
                .horizontalScroll(hScroll, enabled = false),
        ) {
            Canvas(Modifier.size(width, LANE_HEIGHT)) {
                val h = size.height
                if (lane != Lane.VELOCITY && lane != Lane.RELEASE) drawLine(JbColors.Outline, Offset(0f, h / 2), Offset(size.width, h / 2), 1f)
                var t = 0
                while (t <= pattern.viewTicks) {
                    drawLine(JbColors.GridLine, Offset(t * tickPx, 0f), Offset(t * tickPx, h), 1f)
                    t += pattern.stepTicks
                }
                notes.forEach { n ->
                    val sel = n.key in selection
                    val c = if (sel) JbColors.Accent else color
                    val x = n.startTick * tickPx
                    val frac = when (lane) {
                        Lane.VELOCITY -> n.velocity / 127f
                        Lane.PAN -> (n.pan + 1f) / 2f
                        Lane.FINE -> (n.finePitch / 100f + 1f) / 2f
                        Lane.RELEASE -> n.release
                    }
                    val y = h - frac * h
                    val base = if (lane == Lane.VELOCITY || lane == Lane.RELEASE) h else h / 2
                    drawLine(c, Offset(x + 2f, base), Offset(x + 2f, y), 3f)
                    drawCircle(c, 4.dp.toPx(), Offset(x + 2f, y))
                }
            }
        }
    }
}

/** Ticks the editor shows: the pattern plus two empty bars to draw into (AUTO). */
internal val Pattern.viewTicks: Int
    get() = if (lengthMode == LengthMode.AUTO) lengthTicks + 2 * Timing.TICKS_PER_BAR else lengthTicks

@Composable
private fun RollRuler(
    pattern: Pattern,
    tickPx: Float,
    hScroll: ScrollState,
    loop: IntRange?,
    startTick: Int,
    playheadTick: Float?,
    snap: Snap,
    measurer: TextMeasurer,
    actions: PianoRollActions,
    modifier: Modifier,
) {
    var draft by remember { mutableStateOf<IntRange?>(null) }
    val currentSnap by rememberUpdatedState(snap)
    val currentActions by rememberUpdatedState(actions)
    BoxWithConstraints(modifier) {
        val width = with(LocalDensity.current) { (pattern.viewTicks * tickPx).toDp() }.coerceAtLeast(maxWidth)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(tickPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val grid = maxOf(currentSnap.ticks, Timing.TICKS_PER_BEAT)
                        fun tickAt(x: Float) = ((x + hScroll.value) / tickPx).roundToInt().coerceAtLeast(0)
                        var released = false
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.changes.none { it.pressed }) { released = true; return@withTimeoutOrNull }
                            }
                        }
                        if (released) {
                            currentActions.setPosition(Timing.floorTo(tickAt(down.position.x), grid))
                            return@awaitEachGesture
                        }
                        val start = Timing.floorTo(tickAt(down.position.x), grid)
                        var end = start + grid
                        draft = start until end
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            end = maxOf(start + grid, Timing.ceilTo(tickAt(c.position.x), grid))
                            draft = start until end
                        }
                        draft = null
                        currentActions.setLoop(start, end)
                    }
                }
                .horizontalScroll(hScroll, enabled = false),
        ) {
            Canvas(Modifier.size(width, 24.dp)) {
                val bars = pattern.viewTicks / Timing.TICKS_PER_BAR
                for (b in 0..bars) {
                    val x = b * Timing.TICKS_PER_BAR * tickPx
                    drawLine(JbColors.Outline, Offset(x, size.height * 0.4f), Offset(x, size.height), 1f)
                    val t = measurer.measure("${b + 1}", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (b * Timing.TICKS_PER_BAR < pattern.lengthTicks) JbColors.TextDim else JbColors.TextDim.copy(alpha = 0.4f)))
                    drawText(t, topLeft = Offset(x + 3f, 2f))
                }
                (draft ?: loop)?.let { l ->
                    drawRect(JbColors.Accent.copy(alpha = 0.85f), Offset(l.first * tickPx, size.height - 5.dp.toPx()), Size((l.last + 1 - l.first) * tickPx, 5.dp.toPx()))
                }
                val sx = startTick * tickPx
                val tri = Path().apply { moveTo(sx, size.height - 9.dp.toPx()); lineTo(sx + 8.dp.toPx(), size.height - 9.dp.toPx()); lineTo(sx, size.height); close() }
                drawPath(tri, JbColors.Accent)
                playheadTick?.let { drawLine(Color.White, Offset(it * tickPx, 0f), Offset(it * tickPx, size.height), 1.5f) }
            }
        }
    }
}
