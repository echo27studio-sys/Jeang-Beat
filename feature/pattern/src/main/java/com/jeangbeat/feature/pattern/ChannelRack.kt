package com.jeangbeat.feature.pattern

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeangbeat.core.model.Channel
import com.jeangbeat.core.model.MIXER_INSERTS
import com.jeangbeat.core.model.Note
import com.jeangbeat.core.model.Pattern
import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.defaultPitch
import com.jeangbeat.core.model.isStepChannel
import com.jeangbeat.core.model.stepNote
import com.jeangbeat.core.ui.DropdownChip
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.Knob
import com.jeangbeat.core.ui.MuteLed
import com.jeangbeat.core.ui.R
import com.jeangbeat.core.ui.SegmentedChoice
import kotlin.math.roundToInt

private val STEP_W = 24.dp
private val STEP_GAP = 2.dp
private val ROW_H = 40.dp

enum class GraphMode { VELOCITY, PAN, PITCH }

/** FL Studio channel rack: LED, pan, volume, mixer box, name, then steps or a mini piano roll. */
@Composable
internal fun ChannelRack(project: Project, playheadStep: Int?, actions: PatternActions, modifier: Modifier) {
    val pattern = project.currentPattern
    val scroll = rememberScrollState()
    var graphChannelId by rememberSaveable { mutableStateOf<Int?>(null) }
    val stepChannels = project.channels.filter { pattern.isStepChannel(it.id, it.instrument) }
    val graphChannel = stepChannels.firstOrNull { it.id == graphChannelId } ?: stepChannels.firstOrNull()

    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(project.channels, key = { it.id }) { channel ->
                RackRow(project, pattern, channel, playheadStep, scroll, actions, selected = channel.id == graphChannel?.id) {
                    graphChannelId = channel.id
                }
            }
            item {
                Text(
                    stringResource(R.string.rack_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = JbColors.TextDim,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (graphChannel != null) {
            GraphEditor(pattern, graphChannel, stepChannels, scroll, actions) { graphChannelId = it }
        }
    }
}

@Composable
private fun RackRow(
    project: Project,
    pattern: Pattern,
    channel: Channel,
    playheadStep: Int?,
    scroll: ScrollState,
    actions: PatternActions,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val color = JbColors.channel(channel.color)
    Row(
        Modifier.fillMaxWidth().height(ROW_H).padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MuteLed(
            on = !channel.muted,
            solo = channel.solo,
            onToggle = { actions.toggleMute(channel.id) },
            onLongPress = { actions.toggleSolo(channel.id) },
            contentDescription = stringResource(R.string.mute) + " " + channel.name,
        )
        Knob(
            value = (channel.pan + 1f) / 2f,
            onChange = { v, f -> actions.setChannelPan(channel.id, v * 2f - 1f, f) },
            size = 24.dp, bipolar = true,
            contentDescription = stringResource(R.string.pan) + " " + channel.name,
            valueText = panText(channel.pan),
        )
        Spacer(Modifier.width(3.dp))
        Knob(
            value = channel.volume,
            onChange = { v, f -> actions.setChannelVolume(channel.id, v, f) },
            size = 24.dp, default = 0.8f,
            contentDescription = stringResource(R.string.volume) + " " + channel.name,
            valueText = "${(channel.volume * 100).roundToInt()}%",
        )
        Spacer(Modifier.width(3.dp))
        MixerBox(project, channel, actions)
        Spacer(Modifier.width(3.dp))
        Box(
            Modifier
                .width(68.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) Color(0xFF3A4152) else Color(0xFF2B303C))
                .border(width = 0.dp, color = Color.Transparent)
                .pointerInput(channel.id) {
                    detectTapGestures(
                        onTap = { onSelect(); actions.openPianoRoll(channel.id) },
                        onLongPress = { actions.openChannelSettings(channel.id) },
                    )
                }
                .semantics { role = Role.Button; contentDescription = channel.name },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(color))
            Text(
                channel.name,
                modifier = Modifier.padding(start = 7.dp, end = 3.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (channel.muted) JbColors.TextDim else JbColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(5.dp))
        Box(Modifier.weight(1f).horizontalScroll(scroll)) {
            if (pattern.isStepChannel(channel.id, channel.instrument)) {
                StepRow(pattern, channel, color, playheadStep, actions, onSelect)
            } else {
                MiniRoll(pattern, channel, color, playheadStep) { actions.openPianoRoll(channel.id) }
            }
        }
    }
}

@Composable
private fun MixerBox(project: Project, channel: Channel, actions: PatternActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(width = 24.dp, height = 22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF0B0D11))
                .border(1.dp, JbColors.Outline, RoundedCornerShape(4.dp))
                .clickable { open = true }
                .semantics { contentDescription = "Mixer track ${channel.mixerTrack}" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (channel.mixerTrack == 0) "M" else channel.mixerTrack.toString(),
                fontSize = 10.sp, fontWeight = FontWeight.Bold, color = JbColors.Accent, fontFamily = FontFamily.Monospace,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0..MIXER_INSERTS).forEach { i ->
                val name = project.mixer.track(i)?.name ?: ""
                DropdownMenuItem(
                    text = { Text((if (i == channel.mixerTrack) "✓ " else "   ") + (if (i == 0) "M" else "$i") + "  " + name) },
                    onClick = { open = false; actions.setChannelInsert(channel.id, i) },
                )
            }
        }
    }
}

@Composable
private fun StepRow(pattern: Pattern, channel: Channel, color: Color, playheadStep: Int?, actions: PatternActions, onSelect: () -> Unit) {
    val group = pattern.meter.stepsPerBeat
    Row(horizontalArrangement = Arrangement.spacedBy(STEP_GAP)) {
        for (step in 0 until pattern.lengthSteps) {
            val note = pattern.stepNote(channel.id, step)
            val alt = (step / group) % 2 == 1
            val base = if (alt) Color(0xFF5B2F33) else Color(0xFF3B4150)
            val onColor = if (alt) Color(0xFFFFB3B5) else Color(0xFFE8EAF0)
            Box(
                Modifier
                    .size(width = STEP_W, height = 30.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (note != null) onColor.copy(alpha = 0.55f + 0.45f * note.velocity / 127f) else base)
                    .then(if (step == playheadStep) Modifier.border(2.dp, JbColors.Accent, RoundedCornerShape(4.dp)) else Modifier)
                    .semantics {
                        role = Role.Checkbox
                        contentDescription = "${channel.name} step ${step + 1}"
                        stateDescription = if (note != null) "on" else "off"
                    }
                    .clickable { onSelect(); actions.toggleStep(channel.id, step) },
            ) {
                if (note != null && note.velocity < 127) {
                    // small velocity notch at the bottom, FL-style
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(color.copy(alpha = 0.8f)))
                }
            }
        }
    }
}

@Composable
private fun MiniRoll(pattern: Pattern, channel: Channel, color: Color, playheadStep: Int?, onOpen: () -> Unit) {
    val notes = pattern.notesFor(channel.id)
    val width = (STEP_W + STEP_GAP) * pattern.lengthSteps - STEP_GAP
    val lo = (notes.minOfOrNull { it.pitch } ?: 60) - 1
    val hi = (notes.maxOfOrNull { it.pitch } ?: 60) + 1
    Canvas(
        Modifier
            .size(width = width, height = 30.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF171A22))
            .border(1.dp, Color(0xFF2B303C), RoundedCornerShape(4.dp))
            .clickable(onClick = onOpen),
    ) {
        val tickPx = size.width / pattern.lengthTicks
        for (beat in 0..pattern.lengthSteps step pattern.meter.stepsPerBeat) {
            val x = beat * pattern.stepTicks * tickPx
            drawLine(Color(0xFF2B303C), Offset(x, 0f), Offset(x, size.height), 1f)
        }
        val span = (hi - lo).coerceAtLeast(1)
        for (n in notes) {
            val y = size.height - 3f - (n.pitch - lo).toFloat() / span * (size.height - 6f)
            drawRect(
                if (n.muted) JbColors.TextDim else color,
                Offset(n.startTick * tickPx, y - 1.5f),
                Size((n.lengthTicks * tickPx - 1f).coerceAtLeast(2f), 3f),
            )
        }
        playheadStep?.let {
            val x = it * pattern.stepTicks * tickPx
            drawLine(JbColors.Accent, Offset(x, 0f), Offset(x, size.height), 2f)
        }
    }
}

/** FL graph editor: per-step velocity, pan or pitch for one channel. Drag across the bars to draw. */
@Composable
private fun GraphEditor(
    pattern: Pattern,
    channel: Channel,
    stepChannels: List<Channel>,
    scroll: ScrollState,
    actions: PatternActions,
    onChannel: (Int) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(GraphMode.VELOCITY) }
    val density = LocalDensity.current
    val cellPx = with(density) { (STEP_W + STEP_GAP).toPx() }
    val currentPattern by rememberUpdatedState(pattern)
    val currentChannel by rememberUpdatedState(channel)
    val currentMode by rememberUpdatedState(mode)
    val color = JbColors.channel(channel.color)

    Column(Modifier.fillMaxWidth().background(Color(0xFF13161C)).padding(bottom = 6.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SegmentedChoice(
                options = GraphMode.entries,
                selected = mode,
                label = {
                    stringResource(
                        when (it) {
                            GraphMode.VELOCITY -> R.string.velocity
                            GraphMode.PAN -> R.string.pan
                            GraphMode.PITCH -> R.string.pitch
                        },
                    )
                },
                onSelect = { mode = it },
            )
            Spacer(Modifier.weight(1f))
            DropdownChip(channel.name, stepChannels, { it.name }, { onChannel(it.id) }, leading = color)
        }
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            // Align with the step columns of the rack (LED + knobs + box + name).
            Spacer(Modifier.width(4.dp + 24.dp + 24.dp + 3.dp + 24.dp + 3.dp + 24.dp + 3.dp + 68.dp + 5.dp))
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                Canvas(
                    Modifier
                        .size(width = (STEP_W + STEP_GAP) * pattern.lengthSteps, height = 64.dp)
                        .pointerInput(cellPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                var lastStep = -1
                                fun apply(x: Float, y: Float, final: Boolean) {
                                    val step = (x / cellPx).toInt()
                                    val p = currentPattern
                                    if (step !in 0 until p.lengthSteps || p.stepNote(currentChannel.id, step) == null) return
                                    val frac = (1f - y / size.height).coerceIn(0f, 1f)
                                    val def = currentChannel.instrument.defaultPitch
                                    actions.updateStepNote(currentChannel.id, step, final) { n: Note ->
                                        when (currentMode) {
                                            GraphMode.VELOCITY -> n.copy(velocity = (1 + frac * 126).roundToInt())
                                            GraphMode.PAN -> n.copy(pan = frac * 2f - 1f)
                                            GraphMode.PITCH -> n.copy(pitch = (def + ((frac - 0.5f) * 24f).roundToInt()).coerceIn(0, 127))
                                        }
                                    }
                                    lastStep = step
                                }
                                apply(down.position.x, down.position.y, false)
                                var last = down.position
                                drag(down.id) { c ->
                                    c.consume()
                                    last = c.position
                                    apply(c.position.x, c.position.y, false)
                                }
                                apply(last.x, last.y, true)
                            }
                        },
                ) {
                    val h = size.height
                    if (mode != GraphMode.VELOCITY) drawLine(JbColors.Outline, Offset(0f, h / 2), Offset(size.width, h / 2), 1f)
                    for (step in 0 until pattern.lengthSteps) {
                        val n = pattern.stepNote(channel.id, step) ?: continue
                        val x = step * cellPx + 2f
                        val w = cellPx - 4f - STEP_GAP.toPx()
                        when (mode) {
                            GraphMode.VELOCITY -> {
                                val bh = h * n.velocity / 127f
                                drawRect(color, Offset(x, h - bh), Size(w, bh))
                            }
                            GraphMode.PAN -> {
                                val y = h / 2 - n.pan * h / 2
                                drawRect(color, Offset(x, minOf(y, h / 2)), Size(w, kotlin.math.abs(y - h / 2).coerceAtLeast(2f)))
                            }
                            GraphMode.PITCH -> {
                                val off = (n.pitch - channel.instrument.defaultPitch) / 12f
                                val y = h / 2 - off * h / 2
                                drawRect(color, Offset(x, minOf(y, h / 2)), Size(w, kotlin.math.abs(y - h / 2).coerceAtLeast(2f)))
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun panText(pan: Float): String = when {
    pan < -0.02f -> "L${(-pan * 100).roundToInt()}"
    pan > 0.02f -> "R${(pan * 100).roundToInt()}"
    else -> "C"
}
