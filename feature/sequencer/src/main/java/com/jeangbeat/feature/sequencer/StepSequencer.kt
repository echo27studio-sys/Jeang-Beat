package com.jeangbeat.feature.sequencer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jeangbeat.core.model.Channel
import com.jeangbeat.core.model.Pattern
import com.jeangbeat.core.model.Timing
import com.jeangbeat.core.model.stepVelocity
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.R
import com.jeangbeat.core.ui.SmallToggle
import kotlin.math.roundToInt

/** Callbacks from the step sequencer. */
interface StepSequencerActions {
    fun toggleStep(channelId: Int, step: Int)

    /** [final] is false while dragging and true when the finger is lifted. */
    fun setStepVelocity(channelId: Int, step: Int, velocity: Int, final: Boolean)
    fun selectChannel(channelId: Int)
    fun toggleMute(channelId: Int)
    fun toggleSolo(channelId: Int)
    fun openChannelSettings(channelId: Int)
    fun addChannel()
}

/**
 * FL-style channel rack: one row per instrument, one cell per 16th-note step.
 * All rows share one horizontal scroll so long patterns stay aligned.
 */
@Composable
fun StepSequencer(
    channels: List<Channel>,
    pattern: Pattern,
    selectedChannelId: Int?,
    playheadStep: Int?,
    actions: StepSequencerActions,
    modifier: Modifier = Modifier,
    stepSize: Dp = 34.dp,
) {
    var velocityMode by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.velocity_mode), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Switch(checked = velocityMode, onCheckedChange = { velocityMode = it })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions::addChannel) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.add_channel))
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(channels, key = { it.id }) { channel ->
                ChannelRow(
                    channel = channel,
                    pattern = pattern,
                    selected = channel.id == selectedChannelId,
                    playheadStep = playheadStep,
                    velocityMode = velocityMode,
                    scroll = scroll,
                    stepSize = stepSize,
                    actions = actions,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    pattern: Pattern,
    selected: Boolean,
    playheadStep: Int?,
    velocityMode: Boolean,
    scroll: ScrollState,
    stepSize: Dp,
    actions: StepSequencerActions,
) {
    val color = JbColors.channel(channel.color)
    val dim = channel.muted
    Row(
        Modifier
            .fillMaxWidth()
            .height(stepSize + 8.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Header: colour bar, name, settings, mute, solo
        Row(
            Modifier
                .width(150.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) JbColors.SurfaceRaised else JbColors.Surface)
                .pointerInput(channel.id) {
                    detectTapGestures(
                        onTap = { actions.selectChannel(channel.id) },
                        onLongPress = { actions.openChannelSettings(channel.id) },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
            Text(
                channel.name,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (dim) JbColors.TextDim else JbColors.Text,
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = { actions.openChannelSettings(channel.id) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = stringResource(R.string.channel_settings),
                    tint = JbColors.TextDim,
                    modifier = Modifier.size(18.dp),
                )
            }
            SmallToggle(
                stringResource(R.string.mute_short),
                active = channel.muted,
                activeColor = JbColors.Record,
                onClick = { actions.toggleMute(channel.id) },
                contentDescription = stringResource(R.string.mute),
            )
            Spacer(Modifier.width(4.dp))
            SmallToggle(
                stringResource(R.string.solo_short),
                active = channel.solo,
                activeColor = JbColors.Accent,
                onClick = { actions.toggleSolo(channel.id) },
                contentDescription = stringResource(R.string.solo),
            )
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier.weight(1f).horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (step in 0 until pattern.lengthSteps) {
                val velocity = pattern.stepVelocity(channel.id, step)
                StepCell(
                    channel = channel,
                    step = step,
                    velocity = velocity,
                    color = color,
                    isPlayhead = step == playheadStep,
                    velocityMode = velocityMode,
                    size = stepSize,
                    actions = actions,
                )
                if (step % Timing.STEPS_PER_BEAT == Timing.STEPS_PER_BEAT - 1) Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun StepCell(
    channel: Channel,
    step: Int,
    velocity: Int?,
    color: Color,
    isPlayhead: Boolean,
    velocityMode: Boolean,
    size: Dp,
    actions: StepSequencerActions,
) {
    val on = velocity != null
    val beatShade = (step / Timing.STEPS_PER_BEAT) % 2 == 1
    val offColor = if (beatShade) JbColors.StepOffAccent else JbColors.StepOff
    val description = stringResource(R.string.step_description, channel.name, step + 1)
    val currentVelocity by rememberUpdatedState(velocity)
    val heightPx = with(androidx.compose.ui.platform.LocalDensity.current) { size.toPx() }

    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(offColor)
            .then(if (isPlayhead) Modifier.border(2.dp, JbColors.Playhead, RoundedCornerShape(6.dp)) else Modifier)
            .semantics {
                role = Role.Checkbox
                contentDescription = description
                stateDescription = if (on) "on, velocity ${velocity ?: 0}" else "off"
            }
            .pointerInput(channel.id, step, velocityMode) {
                if (velocityMode) {
                    var dragVelocity = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            if (currentVelocity == null) actions.toggleStep(channel.id, step)
                            dragVelocity = (currentVelocity ?: 100).toFloat()
                        },
                        onDragEnd = {
                            actions.setStepVelocity(channel.id, step, dragVelocity.roundToInt(), final = true)
                        },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            dragVelocity = (dragVelocity - dy / heightPx * 127f).coerceIn(1f, 127f)
                            actions.setStepVelocity(channel.id, step, dragVelocity.roundToInt(), final = false)
                        },
                    )
                } else {
                    detectTapGestures(onTap = { actions.toggleStep(channel.id, step) })
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (on) {
            val fraction = (velocity ?: 100) / 127f
            if (velocityMode) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(fraction).background(color))
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color.copy(alpha = 0.45f + 0.55f * fraction)),
                )
            }
        }
    }
}
