package com.jeangbeat.feature.pattern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.jeangbeat.core.ui.sampleDropTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeangbeat.core.model.LengthMode
import com.jeangbeat.core.model.Meter
import com.jeangbeat.core.model.Note
import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.Timing
import com.jeangbeat.core.ui.DropdownChip
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.Knob
import com.jeangbeat.core.ui.R
import com.jeangbeat.core.ui.SegmentedChoice

/** Everything the Pattern tab can ask the app to do. */
interface PatternActions {
    fun toggleStep(channelId: Int, step: Int)

    /** Graph editor / ring edits on one step's note. [final] = finger lifted (one undo step). */
    fun updateStepNote(channelId: Int, step: Int, final: Boolean, transform: (Note) -> Note)
    fun toggleMute(channelId: Int)
    fun toggleSolo(channelId: Int)
    fun setChannelVolume(channelId: Int, volume: Float, final: Boolean)
    fun setChannelPan(channelId: Int, pan: Float, final: Boolean)
    fun setChannelInsert(channelId: Int, insert: Int)
    fun openPianoRoll(channelId: Int)
    fun openChannelSettings(channelId: Int)
    fun previewChannel(channelId: Int)
    fun addChannel()

    fun selectPattern(patternId: Int)
    fun newPattern(copyCurrent: Boolean)
    fun renamePattern()
    fun deletePattern()
    fun setPatternBars(bars: Int)
    fun setMeter(meter: Meter)
    fun setSwing(amount: Float, final: Boolean)

    fun setChannelSteps(channelId: Int, steps: List<Int>)
    fun rotateChannel(channelId: Int, steps: Int, final: Boolean)

    /** Browser drop: on a channel row ([channelId]) replaces its sound, elsewhere adds a channel. */
    fun drop(item: com.jeangbeat.core.ui.Dropped, channelId: Int?): Boolean = false

    // ---- 3.0 channel menu ----
    fun renameChannel(channelId: Int)
    fun setChannelColor(channelId: Int, color: Int)
    fun cloneChannel(channelId: Int)
    fun deleteChannel(channelId: Int)
    fun clearChannel(channelId: Int, allPatterns: Boolean)
    fun moveChannel(channelId: Int, delta: Int)
    fun fillSteps(channelId: Int, every: Int)
    fun shiftSteps(channelId: Int, delta: Int)
    fun randomizeVelocity(channelId: Int)
    fun replaceInstrument(channelId: Int)

    // ---- 3.0 pattern menu ----
    fun setPatternColor(color: Int)
    fun splitByChannel()
    fun setPatternAuto()
    fun customPatternLength()
}

enum class PatternView { RACK, RING }

/**
 * The Pattern tab: FL Studio-style channel rack, or the Ring (rhythm circle)
 * view of the same pattern. [playheadTick] is the position inside the pattern
 * while it plays in PAT mode, else null.
 */
@Composable
fun PatternScreen(
    project: Project,
    playheadTick: Float?,
    actions: PatternActions,
    modifier: Modifier = Modifier,
) {
    var view by rememberSaveable { mutableStateOfView() }
    val pattern = project.currentPattern
    val playheadStep = playheadTick?.let { (it / pattern.stepTicks).toInt() }

    Column(modifier.fillMaxSize().sampleDropTarget { item, _ -> actions.drop(item, null) }) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PatternMenu(project, actions)
            SegmentedChoice(
                options = PatternView.entries,
                selected = view,
                label = { stringResource(if (it == PatternView.RACK) R.string.rack else R.string.ring) },
                onSelect = { view = it },
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.swing).uppercase(), fontSize = 10.sp, color = JbColors.TextDim)
            Knob(
                value = project.swing,
                onChange = actions::setSwing,
                default = 0f,
                contentDescription = stringResource(R.string.swing),
                valueText = "${(project.swing * 100).toInt()}%",
            )
            IconButton(onClick = actions::addChannel) {
                Icon(Icons.Default.Add, stringResource(R.string.add_channel), tint = JbColors.Accent)
            }
        }
        when (view) {
            PatternView.RACK -> ChannelRack(project, playheadStep, actions, Modifier.weight(1f))
            PatternView.RING -> RingView(project, playheadStep, actions, Modifier.weight(1f))
        }
    }
}

private fun mutableStateOfView() = androidx.compose.runtime.mutableStateOf(PatternView.RACK)

@Composable
private fun PatternMenu(project: Project, actions: PatternActions) {
    val pattern = project.currentPattern
    val bars = (pattern.lengthTicks / Timing.TICKS_PER_BAR).coerceAtLeast(1)
    DropdownChip(
        text = pattern.name,
        options = project.patterns,
        optionLabel = { it.name },
        onSelect = { actions.selectPattern(it.id) },
        highlight = true,
        leading = JbColors.channel(pattern.color),
        extra = { close ->
            HorizontalDivider()
            DropdownMenuItem(text = { Text("＋  " + stringResource(R.string.new_pattern)) }, onClick = { close(); actions.newPattern(false) })
            DropdownMenuItem(text = { Text("⧉  " + stringResource(R.string.clone_pattern)) }, onClick = { close(); actions.newPattern(true) })
            DropdownMenuItem(text = { Text("✎  " + stringResource(R.string.rename)) }, onClick = { close(); actions.renamePattern() })
            DropdownMenuItem(text = { Text("✂  " + stringResource(R.string.split_by_channel)) }, onClick = { close(); actions.splitByChannel() })
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                JbColors.Channels.forEachIndexed { i, c ->
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(c)
                            .then(if (i == pattern.color) Modifier.border(2.dp, Color.White, RoundedCornerShape(50)) else Modifier)
                            .clickable { close(); actions.setPatternColor(i) },
                    )
                }
            }
            if (project.patterns.size > 1) {
                DropdownMenuItem(text = { Text("🗑  " + stringResource(R.string.delete_pattern), color = JbColors.Record) }, onClick = { close(); actions.deletePattern() })
            }
            HorizontalDivider()
            val auto = pattern.lengthMode == LengthMode.AUTO
            DropdownMenuItem(
                text = { Text((if (auto) "✓ " else "   ") + stringResource(R.string.length_auto, bars)) },
                onClick = { close(); actions.setPatternAuto() },
            )
            listOf(1, 2, 4, 8, 16, 32).forEach { b ->
                DropdownMenuItem(
                    text = { Text((if (!auto && b == bars) "✓ " else "   ") + stringResource(R.string.bars_count, b)) },
                    onClick = { close(); actions.setPatternBars(b) },
                )
            }
            DropdownMenuItem(text = { Text("   " + stringResource(R.string.length_custom)) }, onClick = { close(); actions.customPatternLength() })
            HorizontalDivider()
            Meter.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text((if (m == pattern.meter) "✓ " else "   ") + stringResource(R.string.meter) + " " + m.label) },
                    onClick = { close(); actions.setMeter(m) },
                )
            }
        },
    )
}
