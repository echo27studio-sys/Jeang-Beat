package com.jeangbeat.feature.playlist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.jeangbeat.core.ui.sampleDropTarget
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeangbeat.core.model.Clip
import com.jeangbeat.core.model.PlaylistTrack
import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.Snap
import com.jeangbeat.core.model.Timing
import com.jeangbeat.core.ui.DropdownChip
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.MuteLed
import com.jeangbeat.core.ui.R
import com.jeangbeat.core.ui.ToolButton
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

interface PlaylistActions {
    fun addPatternClip(trackId: Int, startTick: Int, patternId: Int, final: Boolean)
    fun moveClip(clipId: Int, startTick: Int, trackId: Int, final: Boolean)
    fun resizeClip(clipId: Int, lengthTicks: Int, final: Boolean)
    fun deleteClip(clipId: Int, final: Boolean)
    fun toggleClipMute(clipId: Int)
    fun sliceClip(clipId: Int, atTick: Int)
    fun duplicateClip(clipId: Int)
    fun makeUnique(clipId: Int)
    fun openClip(clip: Clip)
    fun setPosition(tick: Int)
    fun setLoop(startTick: Int, endTick: Int)
    fun clearLoop()
    fun selectPaintPattern(patternId: Int)
    fun newPattern()
    fun addTrack()
    fun toggleTrackMute(trackId: Int)
    fun toggleTrackSolo(trackId: Int)
    fun renameTrack(trackId: Int)
    fun removeTrack(trackId: Int)
    fun importAudio()
    fun addAutomation()
    fun setClipInsert(clipId: Int, insert: Int)
    fun setSnap(snap: Snap)

    /** Browser drop on a lane; [trackId] -1 means below the last track (a new track). [tick] is snapped. */
    fun drop(item: com.jeangbeat.core.ui.Dropped, trackId: Int, tick: Int): Boolean = false
}

enum class PlaylistTool { DRAW, PAINT, SELECT, SLICE, DELETE, MUTE }

private val HEADER_WIDTH = 78.dp
private val TRACK_HEIGHT = 46.dp
private val RULER_HEIGHT = 26.dp
private val SNAPS = listOf(Snap.BAR, Snap.BEAT, Snap.STEP)

@Composable
fun Playlist(
    project: Project,
    paintPatternId: Int,
    snap: Snap,
    songTick: Float,
    waveforms: Map<Int, FloatArray>,
    actions: PlaylistActions,
    modifier: Modifier = Modifier,
) {
    var tool by rememberSaveable { mutableStateOf(PlaylistTool.DRAW) }
    var barWidthDp by rememberSaveable { mutableFloatStateOf(44f) }
    var selectedClipId by remember { mutableStateOf<Int?>(null) }
    val song = project.song
    if (selectedClipId != null && song.clip(selectedClipId!!) == null) selectedClipId = null
    val density = LocalDensity.current
    val tickPx = with(density) { barWidthDp.dp.toPx() } / Timing.TICKS_PER_BAR
    val trackPx = with(density) { TRACK_HEIGHT.toPx() }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val paintPattern = project.pattern(paintPatternId) ?: project.currentPattern
    // Show at least 16 bars, and always 8 bars past the end of the song.
    val totalTicks = maxOf(song.lengthTicks + 8 * Timing.TICKS_PER_BAR, 16 * Timing.TICKS_PER_BAR)

    Column(modifier.fillMaxSize()) {
        // ---- toolbar ----
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF13161C)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolButton(Icons.Default.Edit, stringResource(R.string.tool_draw), tool == PlaylistTool.DRAW, { tool = PlaylistTool.DRAW })
            ToolButton(Icons.Default.FormatPaint, stringResource(R.string.tool_paint), tool == PlaylistTool.PAINT, { tool = PlaylistTool.PAINT })
            ToolButton(Icons.Default.HighlightAlt, stringResource(R.string.tool_select), tool == PlaylistTool.SELECT, { tool = PlaylistTool.SELECT })
            ToolButton(Icons.Default.ContentCut, stringResource(R.string.tool_slice), tool == PlaylistTool.SLICE, { tool = PlaylistTool.SLICE })
            ToolButton(Icons.Default.Delete, stringResource(R.string.tool_delete), tool == PlaylistTool.DELETE, { tool = PlaylistTool.DELETE })
            ToolButton(Icons.AutoMirrored.Filled.VolumeOff, stringResource(R.string.tool_mute), tool == PlaylistTool.MUTE, { tool = PlaylistTool.MUTE })
            Spacer(Modifier.width(6.dp))
            DropdownChip("⊞ ${snap.label}", SNAPS, { it.label }, actions::setSnap)
            DropdownChip(
                paintPattern.name, project.patterns, { it.name }, { actions.selectPaintPattern(it.id) },
                highlight = true, leading = JbColors.channel(paintPattern.color),
            )
            AddMenu(actions)
            if (song.hasLoop) {
                Text(
                    "⟲ ${stringResource(R.string.clear_loop)}",
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFF3A2C10)).clickable(onClick = actions::clearLoop).padding(horizontal = 9.dp, vertical = 6.dp),
                    color = JbColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
            }
            IconButton(onClick = { barWidthDp = (barWidthDp / 1.3f).coerceIn(14f, 240f) }) { Icon(Icons.Default.ZoomOut, stringResource(R.string.zoom_out)) }
            IconButton(onClick = { barWidthDp = (barWidthDp * 1.3f).coerceIn(14f, 240f) }) { Icon(Icons.Default.ZoomIn, stringResource(R.string.zoom_in)) }
        }

        // ---- ruler ----
        Row(Modifier.fillMaxWidth().height(RULER_HEIGHT).background(Color(0xFF13161C))) {
            Spacer(Modifier.width(HEADER_WIDTH))
            Ruler(project, totalTicks, tickPx, songTick, hScroll, measurer, actions, snap, Modifier.weight(1f).fillMaxHeight())
        }

        // ---- tracks ----
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(HEADER_WIDTH).fillMaxHeight().verticalScroll(vScroll)) {
                song.tracks.forEach { t -> androidx.compose.runtime.key(t.id) { TrackHeader(t, actions) } }
                Box(
                    Modifier.width(HEADER_WIDTH).height(TRACK_HEIGHT).clickable(onClick = actions::addTrack),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Add, stringResource(R.string.add_track), tint = JbColors.Accent) }
            }
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxHeight().sampleDropTarget { item, at ->
                    val x = at.x + hScroll.value
                    val y = at.y + vScroll.value
                    val track = song.tracks.getOrNull((y / trackPx).toInt())
                    val tick = Timing.floorTo((x / tickPx).toInt().coerceAtLeast(0), snap.ticks)
                    actions.drop(item, track?.id ?: -1, tick)
                },
            ) {
                val contentW = with(density) { (totalTicks * tickPx).toDp() }.coerceAtLeast(maxWidth)
                val contentH = (TRACK_HEIGHT * (song.tracks.size + 1)).coerceAtLeast(maxHeight)
                Lanes(
                    project, tool, snap, paintPattern.id, tickPx, trackPx, totalTicks, songTick, hScroll, vScroll, waveforms, measurer,
                    selectedClipId, { selectedClipId = it }, actions,
                    onZoom = { f -> barWidthDp = (barWidthDp * f).coerceIn(14f, 240f) },
                    contentW = contentW, contentH = contentH, modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ---- bottom: selected clip actions, or the pattern picker ----
        val selected = selectedClipId?.let { song.clip(it) }
        if (selected != null) {
            ClipActions(project, selected, actions) { selectedClipId = null }
        } else {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF13161C)).horizontalScroll(rememberScrollState()).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                project.patterns.forEach { p ->
                    val on = p.id == paintPattern.id
                    Text(
                        p.name,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(JbColors.channel(p.color))
                            .then(if (on) Modifier.padding(0.dp) else Modifier)
                            .clickable { actions.selectPaintPattern(p.id) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        color = Color(0xFF0F1115), fontSize = 11.sp, fontWeight = if (on) FontWeight.Black else FontWeight.Bold,
                        textDecoration = if (on) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                    )
                }
                Text(
                    "＋ " + stringResource(R.string.new_pattern),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(JbColors.SurfaceRaised).clickable(onClick = actions::newPattern).padding(horizontal = 10.dp, vertical = 7.dp),
                    color = JbColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AddMenu(actions: PlaylistActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            "＋",
            modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(JbColors.SurfaceRaised).clickable { open = true }.padding(horizontal = 12.dp, vertical = 5.dp),
            color = JbColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.add_track)) }, onClick = { open = false; actions.addTrack() })
            DropdownMenuItem(text = { Text(stringResource(R.string.import_audio)) }, onClick = { open = false; actions.importAudio() })
            DropdownMenuItem(text = { Text(stringResource(R.string.add_automation)) }, onClick = { open = false; actions.addAutomation() })
        }
    }
}

@Composable
private fun TrackHeader(track: PlaylistTrack, actions: PlaylistActions) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.width(HEADER_WIDTH).height(TRACK_HEIGHT).background(JbColors.Surface).padding(end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(TRACK_HEIGHT - 12.dp).background(if (track.color >= 0) JbColors.channel(track.color) else JbColors.Outline))
        MuteLed(!track.muted, track.solo, { actions.toggleTrackMute(track.id) }, { actions.toggleTrackSolo(track.id) }, stringResource(R.string.mute) + " " + track.name)
        Box {
            Text(
                track.name,
                modifier = Modifier.clickable { open = true }.padding(vertical = 8.dp),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                color = if (track.muted) JbColors.TextDim else JbColors.Text,
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { open = false; actions.renameTrack(track.id) })
                DropdownMenuItem(text = { Text(stringResource(R.string.solo)) }, onClick = { open = false; actions.toggleTrackSolo(track.id) })
                DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = { open = false; actions.removeTrack(track.id) })
            }
        }
    }
}

@Composable
private fun Ruler(
    project: Project,
    totalTicks: Int,
    tickPx: Float,
    songTick: Float,
    hScroll: ScrollState,
    measurer: TextMeasurer,
    actions: PlaylistActions,
    snap: Snap,
    modifier: Modifier,
) {
    val song = project.song
    var draft by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val stSnap by rememberUpdatedState(snap)
    BoxWithConstraints(modifier) {
        val w = with(LocalDensity.current) { (totalTicks * tickPx).toDp() }.coerceAtLeast(maxWidth)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(tickPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val grid = maxOf(stSnap.ticks, Timing.TICKS_PER_BEAT)
                        fun tickAt(x: Float) = ((x + hScroll.value) / tickPx).roundToInt().coerceAtLeast(0)
                        val startTick = floor(tickAt(down.position.x).toDouble() / grid).toInt() * grid
                        var released = false
                        var dragged = false
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.changes.none { it.pressed }) { released = true; return@withTimeoutOrNull }
                                if ((e.changes.first().position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    dragged = true
                                    return@withTimeoutOrNull
                                }
                            }
                        }
                        if (released) {
                            actions.setPosition(Timing.quantize(tickAt(down.position.x), grid).coerceAtLeast(0))
                            return@awaitEachGesture
                        }
                        if (dragged) {
                            // Quick drag: scrub the song position.
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                c.consume()
                                actions.setPosition(Timing.floorTo(tickAt(c.position.x), stSnap.ticks).coerceAtLeast(0))
                            }
                            return@awaitEachGesture
                        }
                        // Long-press, then drag: set the loop region.
                        var end = startTick + grid
                        draft = startTick to end
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            end = maxOf(startTick + grid, Timing.ceilTo(tickAt(c.position.x), grid))
                            draft = startTick to end
                        }
                        draft = null
                        actions.setLoop(startTick, end)
                    }
                }
                .horizontalScroll(hScroll, enabled = false),
        ) {
            Canvas(Modifier.size(w, RULER_HEIGHT)) {
                val bars = totalTicks / Timing.TICKS_PER_BAR
                val every = if (tickPx * Timing.TICKS_PER_BAR < 30f) 4 else 1
                for (b in 0..bars) {
                    val x = b * Timing.TICKS_PER_BAR * tickPx
                    drawLine(JbColors.Outline, Offset(x, size.height * 0.5f), Offset(x, size.height), 1f)
                    if (b % every == 0) {
                        val t = measurer.measure("${b + 1}", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = JbColors.TextDim))
                        drawText(t, topLeft = Offset(x + 3f, 2f))
                    }
                }
                val loop = draft ?: if (song.hasLoop) song.loopStart to song.loopEnd else null
                loop?.let { (a, b) -> drawRect(JbColors.Accent.copy(alpha = 0.85f), Offset(a * tickPx, 0f), Size((b - a) * tickPx, 5.dp.toPx())) }
                val px = songTick * tickPx
                val path = Path().apply { moveTo(px - 6.dp.toPx(), 0f); lineTo(px + 6.dp.toPx(), 0f); lineTo(px, 9.dp.toPx()); close() }
                drawPath(path, Color.White)
            }
        }
    }
}

private sealed interface ClipGesture {
    data object None : ClipGesture
    data object Pan : ClipGesture
    data class Move(val clip: Clip, val anchorTick: Int, val anchorTrack: Int) : ClipGesture
    data class Resize(val clip: Clip) : ClipGesture
    data class Paint(val trackId: Int, val placed: MutableSet<Int>) : ClipGesture
    data object Erase : ClipGesture
}

private class LaneState(
    val project: Project,
    val tool: PlaylistTool,
    val snap: Snap,
    val paintPatternId: Int,
    val tickPx: Float,
    val trackPx: Float,
    val selected: Int?,
) {
    val tracks get() = project.song.tracks

    fun trackIndex(y: Float): Int = (y / trackPx).toInt()

    fun rect(c: Clip): Rect? {
        val row = tracks.indexOfFirst { it.id == c.trackId }
        if (row < 0) return null
        return Rect(c.startTick * tickPx, row * trackPx + 2f, c.endTick * tickPx, (row + 1) * trackPx - 2f)
    }

    fun hit(p: Offset): Pair<Clip, Boolean>? {
        for (c in project.song.clips.asReversed()) {
            val r = rect(c) ?: continue
            if (r.contains(p)) return c to (r.right - p.x <= minOf(28f, r.width / 3f))
        }
        return null
    }
}

@Composable
private fun Lanes(
    project: Project,
    tool: PlaylistTool,
    snap: Snap,
    paintPatternId: Int,
    tickPx: Float,
    trackPx: Float,
    totalTicks: Int,
    songTick: Float,
    hScroll: ScrollState,
    vScroll: ScrollState,
    waveforms: Map<Int, FloatArray>,
    measurer: TextMeasurer,
    selectedClipId: Int?,
    onSelect: (Int?) -> Unit,
    actions: PlaylistActions,
    onZoom: (Float) -> Unit,
    contentW: Dp,
    contentH: Dp,
    modifier: Modifier,
) {
    val st = rememberUpdatedState(LaneState(project, tool, snap, paintPatternId, tickPx, trackPx, selectedClipId))
    var lastTap by remember { mutableStateOf(0L to -1) }

    Box(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val s = st.value
                    fun content(p: Offset) = Offset(p.x + hScroll.value, p.y + vScroll.value)
                    val start = content(down.position)
                    val hit = s.hit(start)
                    fun tickAt(p: Offset) = (p.x / s.tickPx).toInt().coerceAtLeast(0)
                    fun snapFloor(t: Int) = floor(t.toDouble() / s.snap.ticks).toInt() * s.snap.ticks
                    val trackAt = { p: Offset -> s.tracks.getOrNull(s.trackIndex(p.y)) }

                    var gesture: ClipGesture = when {
                        hit != null && (s.tool == PlaylistTool.DRAW || s.tool == PlaylistTool.SELECT || s.tool == PlaylistTool.PAINT) ->
                            if (hit.second) ClipGesture.Resize(hit.first) else ClipGesture.Move(hit.first, tickAt(start), s.trackIndex(start.y))
                        hit == null && s.tool == PlaylistTool.PAINT -> trackAt(start)?.let { ClipGesture.Paint(it.id, mutableSetOf()) } ?: ClipGesture.None
                        s.tool == PlaylistTool.DELETE -> ClipGesture.Erase
                        else -> ClipGesture.None
                    }
                    if (gesture is ClipGesture.Erase) hit?.let { actions.deleteClip(it.first.id, final = false) }
                    var moved = false
                    var twoFinger = false
                    var lastCentroid = Offset.Zero
                    var lastSpread = 0f
                    val slop = viewConfiguration.touchSlop

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2 && !moved) {
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val centroid = (a + b) / 2f
                            val spread = abs(a.x - b.x)
                            if (twoFinger) {
                                hScroll.dispatchRawDelta(lastCentroid.x - centroid.x)
                                vScroll.dispatchRawDelta(lastCentroid.y - centroid.y)
                                if (lastSpread > 40f && spread > 40f) onZoom(spread / lastSpread)
                            }
                            twoFinger = true
                            gesture = ClipGesture.None
                            lastCentroid = centroid
                            lastSpread = spread
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (twoFinger) continue
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val p = content(change.position)
                        if (!moved && (p - start).getDistance() > slop) moved = true
                        if (!moved) continue
                        change.consume()
                        val cur = st.value
                        when (val g = gesture) {
                            is ClipGesture.Move -> {
                                val dt = Timing.quantize(tickAt(p) - g.anchorTick, cur.snap.ticks)
                                val row = (cur.trackIndex(p.y)).coerceIn(0, cur.tracks.lastIndex)
                                actions.moveClip(g.clip.id, (g.clip.startTick + dt).coerceAtLeast(0), cur.tracks[row].id, final = false)
                            }
                            is ClipGesture.Resize -> {
                                val end = Timing.quantize(tickAt(p), cur.snap.ticks)
                                actions.resizeClip(g.clip.id, (end - g.clip.startTick).coerceAtLeast(cur.snap.ticks), final = false)
                            }
                            is ClipGesture.Paint -> {
                                val len = cur.project.pattern(cur.paintPatternId)?.lengthTicks ?: Timing.TICKS_PER_BAR
                                val slot = floor(tickAt(p).toDouble() / len).toInt() * len
                                val row = cur.tracks.indexOfFirst { it.id == g.trackId }
                                val rowY = (row + 0.5f) * cur.trackPx
                                if (g.placed.add(slot) && cur.hit(Offset(slot * cur.tickPx + 2f, rowY)) == null) {
                                    actions.addPatternClip(g.trackId, slot, cur.paintPatternId, final = false)
                                }
                            }
                            ClipGesture.Erase -> cur.hit(p)?.let { actions.deleteClip(it.first.id, final = false) }
                            ClipGesture.Pan, ClipGesture.None -> {
                                gesture = ClipGesture.Pan
                                hScroll.dispatchRawDelta(-(change.position.x - change.previousPosition.x))
                                vScroll.dispatchRawDelta(-(change.position.y - change.previousPosition.y))
                            }
                        }
                    }
                    if (twoFinger) {
                        // Close any edit started before the second finger landed (erase on touch-down, paint).
                        actions.deleteClip(-1, final = true)
                        return@awaitEachGesture
                    }
                    val s2 = st.value
                    when (val g = gesture) {
                        is ClipGesture.Move -> if (moved) actions.moveClip(g.clip.id, s2.project.song.clip(g.clip.id)?.startTick ?: g.clip.startTick, s2.project.song.clip(g.clip.id)?.trackId ?: g.clip.trackId, final = true)
                        is ClipGesture.Resize -> if (moved) actions.resizeClip(g.clip.id, s2.project.song.clip(g.clip.id)?.lengthTicks ?: g.clip.lengthTicks, final = true)
                        is ClipGesture.Paint -> if (moved) {
                            actions.addPatternClip(g.trackId, -1, -1, final = true)
                        } else {
                            actions.addPatternClip(g.trackId, snapFloor(tickAt(start)), s2.paintPatternId, final = true)
                        }
                        ClipGesture.Erase -> actions.deleteClip(-1, final = true)
                        else -> Unit
                    }
                    if (!moved) {
                        when {
                            hit != null && s2.tool == PlaylistTool.SLICE -> actions.sliceClip(hit.first.id, Timing.quantize(tickAt(start), s2.snap.ticks))
                            hit != null && s2.tool == PlaylistTool.MUTE -> actions.toggleClipMute(hit.first.id)
                            hit != null && (s2.tool == PlaylistTool.DRAW || s2.tool == PlaylistTool.SELECT || s2.tool == PlaylistTool.PAINT) -> {
                                val now = System.currentTimeMillis()
                                val (t, id) = lastTap
                                if (id == hit.first.id && now - t < 350) {
                                    actions.openClip(hit.first)
                                    lastTap = 0L to -1
                                } else {
                                    onSelect(if (s2.selected == hit.first.id) null else hit.first.id)
                                    lastTap = now to hit.first.id
                                }
                            }
                            hit == null && s2.tool == PlaylistTool.DRAW -> {
                                val track = trackAt(start)
                                if (s2.selected != null) {
                                    onSelect(null)
                                } else if (track != null) {
                                    actions.addPatternClip(track.id, snapFloor(tickAt(start)), s2.paintPatternId, final = true)
                                }
                            }
                            hit == null -> onSelect(null)
                        }
                    }
                }
            }
            .horizontalScroll(hScroll, enabled = false)
            .verticalScroll(vScroll, enabled = false),
    ) {
        Canvas(Modifier.size(contentW, contentH)) {
            val s = st.value
            // lanes and bar lines
            s.tracks.forEachIndexed { i, _ ->
                drawRect(if (i % 2 == 0) JbColors.Background else Color(0xFF12151A), Offset(0f, i * trackPx), Size(size.width, trackPx))
                drawLine(Color(0xFF1E222B), Offset(0f, (i + 1) * trackPx), Offset(size.width, (i + 1) * trackPx), 1f)
            }
            val bars = totalTicks / Timing.TICKS_PER_BAR
            for (b in 0..bars) {
                val x = b * Timing.TICKS_PER_BAR * tickPx
                drawLine(if (b % 4 == 0) JbColors.GridBeat else Color(0xFF1E222B), Offset(x, 0f), Offset(x, size.height), if (b % 4 == 0) 1.5f else 1f)
            }
            if (project.song.hasLoop) {
                drawRect(JbColors.Accent.copy(alpha = 0.05f), Offset(project.song.loopStart * tickPx, 0f), Size((project.song.loopEnd - project.song.loopStart) * tickPx, size.height))
            }
            project.song.clips.forEach { c -> s.rect(c)?.let { drawClip(project, c, it, c.id == selectedClipId, waveforms, measurer) } }
            val px = songTick * tickPx
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
        }
    }
}

private fun DrawScope.drawClip(project: Project, clip: Clip, r: Rect, selected: Boolean, waveforms: Map<Int, FloatArray>, measurer: TextMeasurer) {
    val (color, label) = when (clip) {
        is Clip.PatternClip -> project.pattern(clip.patternId).let { JbColors.channel(it?.color ?: 0) to (it?.name ?: "?") }
        is Clip.AudioClip -> JbColors.Channels[4] to (project.sample(clip.sampleId)?.name ?: "Audio")
        is Clip.AutomationClip -> Color(0xFF3A4152) to automationLabel(project, clip)
    }
    val fill = if (clip.muted) color.copy(alpha = 0.3f) else color
    drawRoundRect(fill, r.topLeft, r.size, CornerRadius(6f))
    clipRect(r.left, r.top, r.right, r.bottom) {
        when (clip) {
            is Clip.PatternClip -> project.pattern(clip.patternId)?.let { p ->
                // Mini preview of the pattern's notes (all channels), repeated across the clip.
                val all = p.notes.values.flatten()
                if (all.isNotEmpty()) {
                    val lo = all.minOf { it.pitch }
                    val span = (all.maxOf { it.pitch } - lo).coerceAtLeast(12)
                    val tickPx = r.width / clip.lengthTicks
                    var base = -clip.offsetTicks
                    while (base < clip.lengthTicks) {
                        for (n in all) {
                            val x = r.left + (base + n.startTick) * tickPx
                            if (x > r.right) continue
                            val y = r.bottom - 4f - (n.pitch - lo).toFloat() / span * (r.height - 18f)
                            drawRect(Color.Black.copy(alpha = 0.45f), Offset(x, y), Size((n.lengthTicks * tickPx).coerceAtLeast(2f), 2.5f))
                        }
                        base += p.lengthTicks
                    }
                }
            }
            is Clip.AudioClip -> waveforms[clip.sampleId]?.let { peaks ->
                val ref = project.sample(clip.sampleId)
                val ticksPerSecond = project.tempo / 60f * Timing.TICKS_PER_BEAT
                val clipSeconds = clip.lengthTicks / ticksPerSecond
                val offsetSeconds = clip.offsetTicks / ticksPerSecond
                val total = (ref?.seconds ?: 1.0).toFloat().coerceAtLeast(0.001f)
                val mid = r.top + r.height / 2 + 5f
                var x = r.left
                while (x < r.right) {
                    val sec = offsetSeconds + (x - r.left) / r.width * clipSeconds
                    val idx = (sec / total * peaks.size).toInt()
                    val v = peaks.getOrElse(idx) { 0f }
                    val h = v * (r.height - 16f) / 2
                    drawLine(Color.Black.copy(alpha = 0.5f), Offset(x, mid - h), Offset(x, mid + h), 1.5f)
                    x += 2f
                }
            }
            is Clip.AutomationClip -> {
                val path = Path()
                val pts = clip.points
                pts.forEachIndexed { i, p ->
                    val x = r.left + p.tick.toFloat() / clip.lengthTicks * r.width
                    val y = r.bottom - 3f - p.value * (r.height - 16f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, JbColors.Accent, style = Stroke(2f))
            }
        }
        val t = measurer.measure(label, TextStyle(fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = if (clip is Clip.AutomationClip) JbColors.Text else Color(0xFF0F1115)))
        drawText(t, topLeft = Offset(r.left + 4f, r.top + 2f))
    }
    if (selected) drawRoundRect(Color.White, r.topLeft, r.size, CornerRadius(6f), style = Stroke(2.5f))
    drawRect(Color.Black.copy(alpha = 0.25f), Offset(r.right - 5f, r.top + 6f), Size(2.5f, r.height - 12f))
}

/** Human label for an automation clip's target. */
fun automationLabel(project: Project, clip: Clip.AutomationClip): String = when (val t = clip.target) {
    is com.jeangbeat.core.model.AutomationTarget.MixerVolume -> "${project.mixer.track(t.track)?.name ?: t.track} · Vol"
    is com.jeangbeat.core.model.AutomationTarget.MixerPan -> "${project.mixer.track(t.track)?.name ?: t.track} · Pan"
    is com.jeangbeat.core.model.AutomationTarget.ChannelVolume -> "${project.channel(t.channelId)?.name ?: "?"} · Vol"
    is com.jeangbeat.core.model.AutomationTarget.ChannelPan -> "${project.channel(t.channelId)?.name ?: "?"} · Pan"
    is com.jeangbeat.core.model.AutomationTarget.EffectParam -> {
        val slot = project.mixer.track(t.track)?.effects?.firstOrNull { it.id == t.slotId }
        "${project.mixer.track(t.track)?.name ?: t.track} · ${slot?.type?.params?.getOrNull(t.param)?.key ?: "?"}"
    }
}

@Composable
private fun ClipActions(project: Project, clip: Clip, actions: PlaylistActions, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(JbColors.Surface).horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip(stringResource(R.string.duplicate)) { actions.duplicateClip(clip.id) }
        if (clip is Clip.PatternClip) {
            Chip(stringResource(R.string.open)) { actions.openClip(clip) }
            Chip(stringResource(R.string.make_unique)) { actions.makeUnique(clip.id) }
        }
        if (clip is Clip.AutomationClip) Chip(stringResource(R.string.edit_points)) { actions.openClip(clip) }
        if (clip is Clip.AudioClip) {
            DropdownChip(
                stringResource(R.string.mixer_track) + " " + (if (clip.mixerTrack == 0) "M" else clip.mixerTrack.toString()),
                project.mixer.tracks, { (if (it.index == 0) "M" else "${it.index}") + "  " + it.name }, { actions.setClipInsert(clip.id, it.index) },
            )
        }
        Chip(stringResource(if (clip.muted) R.string.enabled else R.string.mute)) { actions.toggleClipMute(clip.id) }
        Chip(stringResource(R.string.delete), JbColors.Record) { actions.deleteClip(clip.id, final = true); onDone() }
        HorizontalDivider(Modifier.width(1.dp))
        Chip("✕") { onDone() }
    }
}

@Composable
private fun Chip(text: String, color: Color = JbColors.Text, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(JbColors.SurfaceRaised).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
    )
}
