package com.jeangbeat.feature.pads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jeangbeat.core.model.Channel
import com.jeangbeat.core.model.RepeatRate
import com.jeangbeat.core.model.ScaleSetting
import com.jeangbeat.core.model.defaultPitch
import com.jeangbeat.core.model.pitchName
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.R
import com.jeangbeat.core.ui.SegmentedChoice

enum class PadMode { DRUMS, KEYS }

const val PAD_COUNT = 16
const val PAD_VELOCITY = 110

interface PadActions {
    fun padDown(channelId: Int, pitch: Int, velocity: Int)
    fun padUp(channelId: Int, pitch: Int)
    fun setMode(mode: PadMode)
    fun setRepeat(rate: RepeatRate)
    fun toggleRecord()
    fun selectChannel(channelId: Int)
    fun shiftOctave(delta: Int)
}

/** One pad: which channel and pitch it plays. */
data class PadAssignment(val channel: Channel, val pitch: Int, val label: String)

/** Drums: one channel per pad. Keys: one note of the selected channel per pad. */
fun padAssignments(
    mode: PadMode,
    channels: List<Channel>,
    keysChannel: Channel?,
    scale: ScaleSetting,
    octave: Int,
): List<PadAssignment?> = when (mode) {
    PadMode.DRUMS -> List(PAD_COUNT) { i ->
        channels.getOrNull(i)?.let { PadAssignment(it, it.instrument.defaultPitch, it.name) }
    }
    PadMode.KEYS -> {
        if (keysChannel == null) {
            List(PAD_COUNT) { null }
        } else {
            val base = (octave + 1) * 12 + if (scale.lock) scale.root else 0
            val pitches = if (scale.lock) {
                scale.scale.pitchesIn(base, base + 48, scale.root).take(PAD_COUNT)
            } else {
                (base until base + PAD_COUNT).toList()
            }
            pitches.map { PadAssignment(keysChannel, it.coerceIn(0, 127), pitchName(it)) }
        }
    }
}

@Composable
fun PadsScreen(
    channels: List<Channel>,
    mode: PadMode,
    keysChannelId: Int?,
    scale: ScaleSetting,
    octave: Int,
    repeatRate: RepeatRate,
    recording: Boolean,
    actions: PadActions,
    modifier: Modifier = Modifier,
) {
    val keysChannel = channels.firstOrNull { it.id == keysChannelId } ?: channels.firstOrNull()
    val pads = padAssignments(mode, channels, keysChannel, scale, octave)

    Column(modifier.fillMaxSize().padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SegmentedChoice(
                options = PadMode.entries,
                selected = mode,
                label = {
                    stringResource(if (it == PadMode.DRUMS) R.string.pads_drums else R.string.pads_keys)
                },
                onSelect = actions::setMode,
            )
            SegmentedChoice(
                options = RepeatRate.entries,
                selected = repeatRate,
                label = { repeatLabel(it) },
                onSelect = actions::setRepeat,
            )
            IconToggleButton(checked = recording, onCheckedChange = { actions.toggleRecord() }) {
                Icon(
                    Icons.Default.FiberManualRecord,
                    contentDescription = stringResource(R.string.record),
                    tint = if (recording) JbColors.Record else JbColors.TextDim,
                )
            }
        }

        if (mode == PadMode.KEYS) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    channels.forEach { channel ->
                        FilterChip(
                            selected = channel.id == keysChannel?.id,
                            onClick = { actions.selectChannel(channel.id) },
                            label = { Text(channel.name, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = JbColors.channel(channel.color).copy(alpha = 0.35f),
                            ),
                        )
                    }
                }
                IconButton(onClick = { actions.shiftOctave(-1) }) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.octave_down))
                }
                Text("C$octave", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { actions.shiftOctave(1) }) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.octave_up))
                }
            }
        }

        Spacer(Modifier.padding(4.dp))
        PadGrid(pads = pads, mode = mode, actions = actions, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun repeatLabel(rate: RepeatRate): String = when (rate) {
    RepeatRate.OFF -> stringResource(R.string.repeat_off)
    RepeatRate.EIGHTH -> "1/8"
    RepeatRate.STEP -> "1/16"
    RepeatRate.STEP_TRIPLET -> "1/16T"
    RepeatRate.THIRTY_SECOND -> "1/32"
}

@Composable
private fun PadGrid(pads: List<PadAssignment?>, mode: PadMode, actions: PadActions, modifier: Modifier) {
    // Square 4x4 grid that fits the space available. In keys mode the lowest
    // note is bottom-left, like a keyboard turned on its side.
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in 0 until 4) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (col in 0 until 4) {
                        val index = if (mode == PadMode.KEYS) (3 - row) * 4 + col else row * 4 + col
                        Pad(pads.getOrNull(index), actions, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun Pad(assignment: PadAssignment?, actions: PadActions, modifier: Modifier) {
    if (assignment == null) {
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(JbColors.Surface))
        return
    }
    val color = JbColors.channel(assignment.channel.color)
    var pressed by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(assignment)

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) color else color.copy(alpha = 0.28f))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .semantics {
                role = Role.Button
                contentDescription = assignment.label
            }
            .pointerInput(Unit) {
                // React on finger-down (not on release) for the lowest latency.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    val a = current
                    pressed = true
                    actions.padDown(a.channel.id, a.pitch, PAD_VELOCITY)
                    waitForUpOrCancellation()
                    pressed = false
                    actions.padUp(a.channel.id, a.pitch)
                }
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            assignment.label,
            modifier = Modifier.padding(8.dp),
            color = if (pressed) Color.Black else JbColors.Text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
    }
}
