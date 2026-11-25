package com.jeangbeat.feature.pattern

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeangbeat.core.model.NOTE_NAMES
import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.channelSteps
import com.jeangbeat.core.model.describePattern
import com.jeangbeat.core.model.euclid
import com.jeangbeat.core.model.isMelodic
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.R
import com.jeangbeat.core.ui.displayName
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MAX_RINGS = 3
private val RING_RADII = floatArrayOf(1f, 0.66f, 0.36f)

/**
 * Rhythm circle: each visible channel is a shape on its own ring, a hand
 * sweeps round while playing. Tap a slot to toggle a step, drag around the
 * selected ring to rotate that channel's pattern, Euclid for even spreads.
 */
@Composable
internal fun RingView(project: Project, playheadStep: Int?, actions: PatternActions, modifier: Modifier) {
    val pattern = project.currentPattern
    val drumChannels = project.channels.filter { !it.instrument.isMelodic }
    var visibleIds by rememberSaveable(project.currentPatternId) {
        mutableStateOf(
            drumChannels.sortedByDescending { pattern.notesFor(it.id).size.coerceAtMost(8) }
                .filter { pattern.notesFor(it.id).isNotEmpty() }.take(MAX_RINGS).map { it.id }
                .ifEmpty { drumChannels.take(2).map { it.id } },
        )
    }
    val visible = visibleIds.mapNotNull { project.channel(it) }.take(MAX_RINGS)
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    val selected = visible.firstOrNull { it.id == selectedId } ?: visible.firstOrNull()
    var showEuclid by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Header, like the reference "drum view": badge, name, meta, auto description
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(project.describePattern().uppercase(), fontSize = 10.sp, color = JbColors.TextDim, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(JbColors.channel(pattern.color)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${project.patterns.indexOf(pattern) + 1}", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(pattern.name.uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
            Text(
                "${NOTE_NAMES[project.scale.root]} ${project.scale.scale.displayName().uppercase()} · ${project.tempo.roundToInt()} BPM · " +
                    "${pattern.stepCount} STEPS · ${pattern.meter.label}",
                fontSize = 11.sp, color = JbColors.TextDim, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
            )
        }

        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, 420.dp)
            RingCanvas(project, visible.map { it.id }, selected?.id, playheadStep, actions, Modifier.widthIn(max = side).fillMaxWidth().aspectRatio(1f)) {
                selectedId = it
            }
        }

        // Legend: tap to show/hide (max 3 rings); the gold outline marks the ring you edit.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            drumChannels.forEach { ch ->
                val isVisible = ch.id in visibleIds
                val isSelected = ch.id == selected?.id
                Row(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isVisible) JbColors.SurfaceRaised else JbColors.Surface)
                        .then(if (isSelected) Modifier.border(1.5.dp, JbColors.Accent, RoundedCornerShape(20.dp)) else Modifier)
                        .clickable {
                            visibleIds = when {
                                isVisible && !isSelected -> { selectedId = ch.id; visibleIds }
                                isVisible -> visibleIds - ch.id
                                else -> { selectedId = ch.id; (listOf(ch.id) + visibleIds).take(MAX_RINGS) }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(JbColors.channel(ch.color)))
                    Spacer(Modifier.width(5.dp))
                    Text(ch.name, fontSize = 11.sp, color = if (isVisible) JbColors.Text else JbColors.TextDim, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Mini grid for the visible rings
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            visible.forEach { ch ->
                val steps = pattern.channelSteps(ch.id).toSet()
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.name.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = JbColors.channel(ch.color), modifier = Modifier.width(64.dp), maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        for (s in 0 until pattern.stepCount) {
                            val alt = (s / pattern.meter.stepsPerBeat) % 2 == 1
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (s in steps) JbColors.channel(ch.color) else if (alt) Color(0xFF232836) else Color(0xFF1C2029))
                                    .then(if (s == playheadStep) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(3.dp)) else Modifier)
                                    .clickable { actions.toggleStep(ch.id, s) },
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.step_counter, (playheadStep ?: 0) + 1, pattern.stepCount),
                    fontSize = 10.sp, color = JbColors.TextDim, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                if (selected != null) {
                    FilledTonalButton(onClick = { showEuclid = true }) { Text("${stringResource(R.string.euclid)} · ${selected.name}") }
                }
            }
            Text(stringResource(R.string.ring_hint), fontSize = 10.sp, color = JbColors.TextDim, modifier = Modifier.padding(top = 4.dp))
        }
    }

    if (showEuclid && selected != null) {
        EuclidSheet(
            steps = pattern.stepCount,
            stepsPerBar = pattern.meter.stepsPerBar,
            current = pattern.channelSteps(selected.id),
            channelName = selected.name,
            onApply = { actions.setChannelSteps(selected.id, it); showEuclid = false },
            onClear = { actions.setChannelSteps(selected.id, emptyList()); showEuclid = false },
            onDismiss = { showEuclid = false },
        )
    }
}

@Composable
private fun RingCanvas(
    project: Project,
    channelIds: List<Int>,
    selectedId: Int?,
    playheadStep: Int?,
    actions: PatternActions,
    modifier: Modifier,
    onSelect: (Int) -> Unit,
) {
    val pattern = project.currentPattern
    val n = pattern.stepCount
    val measurer = rememberTextMeasurer()
    val currentIds by rememberUpdatedState(channelIds)
    val currentSelected by rememberUpdatedState(selectedId)
    val currentSteps by rememberUpdatedState(n)

    Canvas(
        modifier
            .padding(8.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2f - 34.dp.toPx()
                    fun angleOf(p: Offset): Double = (atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble()) + PI / 2 + 2 * PI) % (2 * PI)
                    val steps = currentSteps
                    val dist = hypot(down.position.x - c.x, down.position.y - c.y) / radius
                    val ringIndex = currentIds.indices.minByOrNull { abs(RING_RADII[it] - dist) }
                    val ringId = ringIndex?.let { currentIds[it] }
                    val startAngle = angleOf(down.position)
                    var rotated = 0
                    var moved = false
                    drag(down.id) { change ->
                        val a = angleOf(change.position)
                        var delta = a - startAngle
                        if (delta > PI) delta -= 2 * PI
                        if (delta < -PI) delta += 2 * PI
                        val target = (delta / (2 * PI) * steps).roundToInt()
                        if (abs(change.position.x - down.position.x) + abs(change.position.y - down.position.y) > 12f) moved = true
                        val id = currentSelected ?: ringId
                        if (moved && id != null && target != rotated) {
                            change.consume()
                            actions.rotateChannel(id, target - rotated, final = false)
                            rotated = target
                        }
                    }
                    val id = currentSelected ?: ringId
                    if (moved && id != null) {
                        actions.rotateChannel(id, 0, final = true)
                    } else if (!moved && ringId != null && dist > 0.15f && dist < 1.25f) {
                        val step = ((startAngle / (2 * PI)) * steps).roundToInt() % steps
                        onSelect(ringId)
                        actions.toggleStep(ringId, step)
                    }
                }
            },
    ) {
        val c = center
        val radius = size.minDimension / 2f - 34.dp.toPx()
        fun pos(step: Float, r: Float): Offset {
            val a = -PI / 2 + step * 2 * PI / n
            return Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
        }
        drawCircle(Color(0xFF2E3342), radius, c, style = Stroke(1.5f))
        drawCircle(Color(0xFF1E222C), radius * 0.62f, c, style = Stroke(1f))

        val mainSteps = channelIds.firstOrNull()?.let { pattern.channelSteps(it).toSet() } ?: emptySet()
        for (s in 0 until n) {
            val beat = s % pattern.meter.stepsPerBeat == 0
            drawCircle(JbColors.Background, 3.5.dp.toPx(), pos(s.toFloat(), radius))
            drawCircle(if (beat) Color(0xFF6B7285) else Color(0xFF3A3F4D), 3.5.dp.toPx(), pos(s.toFloat(), radius), style = Stroke(1.2.dp.toPx()))
            val big = s in mainSteps
            val label = measurer.measure(
                "${s + 1}",
                TextStyle(fontSize = if (big) 14.sp else 10.sp, fontWeight = if (big) FontWeight.ExtraBold else FontWeight.Medium, color = if (big) Color.White else Color(0xFF6B7285)),
            )
            val p = pos(s.toFloat(), radius + 20.dp.toPx())
            drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y - label.size.height / 2f))
        }

        playheadStep?.let {
            val p = pos(it.toFloat(), radius + 2f)
            drawLine(Color.White.copy(alpha = 0.75f), c, p, 1.2.dp.toPx())
            drawCircle(Color.White, 6.dp.toPx(), p, style = Stroke(1.8.dp.toPx()))
        }

        // Inner rings first so the main (outer) shape is drawn on top.
        channelIds.withIndex().reversed().forEach { (i, id) ->
            val ch = project.channel(id) ?: return@forEach
            val color = JbColors.channel(ch.color)
            val r = radius * RING_RADII[i]
            val steps = pattern.channelSteps(id)
            val isSelected = id == selectedId
            if (steps.size > 8) {
                drawCircle(color.copy(alpha = 0.25f), r, c, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))))
                steps.forEach { s ->
                    val on = s == playheadStep
                    drawCircle(color, if (on) 4.5.dp.toPx() else 2.8.dp.toPx(), pos(s.toFloat(), r))
                }
            } else {
                if (steps.size >= 2) {
                    val path = Path().apply {
                        steps.forEachIndexed { k, s -> pos(s.toFloat(), r).let { if (k == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                        close()
                    }
                    drawPath(path, color.copy(alpha = 0.18f))
                    drawPath(path, color, style = Stroke(if (isSelected) 2.6.dp.toPx() else 1.8.dp.toPx()))
                } else if (steps.size == 1) {
                    drawLine(color.copy(alpha = 0.6f), c, pos(steps[0].toFloat(), r), 1.dp.toPx())
                }
                steps.forEach { s ->
                    val p = pos(s.toFloat(), r)
                    val on = s == playheadStep
                    if (on) drawCircle(color.copy(alpha = 0.35f), 13.dp.toPx(), p)
                    drawCircle(color, if (on) 8.dp.toPx() else if (i == 0) 6.5.dp.toPx() else 5.dp.toPx(), p)
                    if (on) drawCircle(Color.White, 8.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                }
            }
            if (isSelected) {
                for (s in 0 until n) {
                    if (s !in steps) {
                        drawCircle(color.copy(alpha = 0.55f), 4.dp.toPx(), pos(s.toFloat(), r), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
                    }
                }
            }
        }
        drawCircle(Color.White, 3.dp.toPx(), c)
    }
}

private data class RingPreset(val name: String, val stepsPerBar: Int, val steps: List<Int>)

private val PRESETS = listOf(
    RingPreset("Four on the floor", 16, listOf(0, 4, 8, 12)),
    RingPreset("Tresillo 3:8", 16, listOf(0, 3, 6, 8, 11, 14)),
    RingPreset("Clave 3-2", 16, listOf(0, 3, 6, 10, 12)),
    RingPreset("Clave 2-3", 16, listOf(2, 4, 8, 11, 14)),
    RingPreset("Bossa", 16, listOf(0, 3, 6, 10, 13)),
    RingPreset("3+3+2", 16, listOf(0, 3, 6, 8, 11, 14)),
    RingPreset("Bell 12/8", 12, listOf(0, 2, 4, 5, 7, 9, 11)),
    RingPreset("Dotted 12/8", 12, listOf(0, 3, 6, 9)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EuclidSheet(
    steps: Int,
    stepsPerBar: Int,
    current: List<Int>,
    channelName: String,
    onApply: (List<Int>) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var hits by remember { mutableIntStateOf(current.size.coerceIn(1, stepsPerBar).let { if (current.isEmpty()) 3 else it }) }
    var rotation by remember { mutableIntStateOf(0) }
    var preset by remember { mutableStateOf<RingPreset?>(null) }
    // Euclid is computed per bar and repeated for longer patterns.
    val bars = (steps / stepsPerBar).coerceAtLeast(1)
    val oneBar = preset?.steps ?: euclid(hits, stepsPerBar, rotation)
    val result = (0 until bars).flatMap { b -> oneBar.map { it + b * stepsPerBar } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = JbColors.Surface) {
        Column(Modifier.padding(horizontal = 18.dp).navigationBarsPadding()) {
            Text("${stringResource(R.string.euclid)} · $channelName", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.hits), modifier = Modifier.width(70.dp), color = JbColors.TextDim)
                Stepper(hits, { hits = it.coerceIn(0, stepsPerBar); preset = null })
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.rotate), modifier = Modifier.width(60.dp), color = JbColors.TextDim)
                Stepper(rotation, { rotation = it; preset = null })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS.filter { it.stepsPerBar == stepsPerBar }.forEach { p ->
                    val on = p == preset
                    Text(
                        p.name,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (on) JbColors.Accent else JbColors.SurfaceRaised)
                            .clickable { preset = p }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        color = if (on) Color.Black else JbColors.Text,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (s in 0 until stepsPerBar) {
                    Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(if (s in oneBar) JbColors.Accent else Color(0xFF2A2F3C)))
                }
            }
            Text(oneBar.joinToString(", ") { (it + 1).toString() }, fontSize = 11.sp, color = JbColors.TextDim, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.clear_ring)) }
                FilledTonalButton(onClick = { onApply(result) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.apply)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(JbColors.SurfaceRaised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onChange(value - 1) }) { Text("−", color = JbColors.Accent, fontSize = 18.sp) }
        Text("$value", fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = { onChange(value + 1) }) { Text("+", color = JbColors.Accent, fontSize = 18.sp) }
    }
}
