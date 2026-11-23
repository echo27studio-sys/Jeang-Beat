package com.jeangbeat.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jeangbeat.app.StudioViewModel
import com.jeangbeat.core.model.Channel
import com.jeangbeat.core.model.DrumSound
import com.jeangbeat.core.model.Instrument
import com.jeangbeat.core.model.Waveform
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.ParamSlider
import com.jeangbeat.core.ui.R
import com.jeangbeat.core.ui.SegmentedChoice
import com.jeangbeat.core.ui.label
import com.jeangbeat.core.ui.typeLabel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSettingsSheet(channel: Channel, viewModel: StudioViewModel, onDismiss: () -> Unit) {
    var name by remember(channel.id) { mutableStateOf(channel.name) }
    val id = channel.id

    fun update(final: Boolean, transform: (Channel) -> Channel) = viewModel.updateChannel(id, final, transform)
    fun updateInstrument(final: Boolean, transform: (Instrument) -> Instrument) =
        update(final) { it.copy(instrument = transform(it.instrument)) }
    val commit = { viewModel.commitGesture() }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.commitGesture()
            if (name != channel.name) viewModel.renameChannel(id, name)
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = JbColors.Surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                stringResource(R.string.channel_settings) + " · " + stringResource(channel.instrument.typeLabel()),
                style = MaterialTheme.typography.titleMedium,
                color = JbColors.channel(channel.color),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            ParamSlider(R.string.volume, channel.volume, { v -> update(false) { it.copy(volume = v) } }, 0f..1f,
                format = { "${(it * 100).roundToInt()}%" }, onValueChangeFinished = commit)
            ParamSlider(R.string.pan, channel.pan, { v -> update(false) { it.copy(pan = v) } }, -1f..1f,
                format = ::panLabel, onValueChangeFinished = commit)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            when (val inst = channel.instrument) {
                is Instrument.Drum -> DrumParams(inst, ::updateInstrument, commit)
                is Instrument.Bass808 -> BassParams(inst, ::updateInstrument, commit)
                is Instrument.Synth -> SynthParams(inst, ::updateInstrument, commit)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.clearChannel(id) }) { Text(stringResource(R.string.clear_notes)) }
                OutlinedButton(onClick = {
                    viewModel.removeChannel(id)
                    onDismiss()
                }) { Text(stringResource(R.string.delete_channel), color = JbColors.Record) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun panLabel(pan: Float): String = when {
    pan < -0.02f -> "L ${(-pan * 100).roundToInt()}"
    pan > 0.02f -> "R ${(pan * 100).roundToInt()}"
    else -> "C"
}

private typealias InstrumentUpdate = (final: Boolean, transform: (Instrument) -> Instrument) -> Unit

@Composable
private fun DrumParams(inst: Instrument.Drum, update: InstrumentUpdate, commit: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.instrument_drum) + ": " + stringResource(inst.sound.label()))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DrumSound.entries.forEach { sound ->
                DropdownMenuItem(text = { Text(stringResource(sound.label())) }, onClick = {
                    open = false
                    update(true) { (it as Instrument.Drum).copy(sound = sound, chokeGroup = sound.defaultChokeGroup) }
                })
            }
        }
    }
    if (inst.sound.isKhmer) {
        Text(stringResource(R.string.khmer_sounds_note), style = MaterialTheme.typography.labelSmall, color = JbColors.TextDim)
    }
    ParamSlider(R.string.tune, inst.tune, { v -> update(false) { (it as Instrument.Drum).copy(tune = v.roundToInt().toFloat()) } },
        -24f..24f, format = { "%+d st".format(it.roundToInt()) }, onValueChangeFinished = commit)
    ParamSlider(R.string.decay, inst.decay, { v -> update(false) { (it as Instrument.Drum).copy(decay = v) } },
        0.05f..1f, format = { "${(it * 100).roundToInt()}%" }, onValueChangeFinished = commit)
    ParamSlider(R.string.choke_group, inst.chokeGroup.toFloat(),
        { v -> update(false) { (it as Instrument.Drum).copy(chokeGroup = v.roundToInt()) } },
        0f..4f, steps = 3, format = { if (it < 0.5f) "–" else it.roundToInt().toString() }, onValueChangeFinished = commit)
}

@Composable
private fun BassParams(inst: Instrument.Bass808, update: InstrumentUpdate, commit: () -> Unit) {
    fun set(transform: (Instrument.Bass808) -> Instrument.Bass808) = update(false) { transform(it as Instrument.Bass808) }
    ParamSlider(R.string.decay, inst.decay, { v -> set { it.copy(decay = v) } }, 0.1f..4f,
        format = { "%.2f s".format(it) }, onValueChangeFinished = commit)
    ParamSlider(R.string.glide, inst.glide, { v -> set { it.copy(glide = v) } }, 0f..0.5f,
        format = { "${(it * 1000).roundToInt()} ms" }, onValueChangeFinished = commit)
    Text(stringResource(R.string.glide_hint), style = MaterialTheme.typography.labelSmall, color = JbColors.TextDim)
    ParamSlider(R.string.drive, inst.drive, { v -> set { it.copy(drive = v) } }, 0f..1f,
        format = { "${(it * 100).roundToInt()}%" }, onValueChangeFinished = commit)
    ParamSlider(R.string.punch, inst.punch, { v -> set { it.copy(punch = v) } }, 0f..1f,
        format = { "${(it * 100).roundToInt()}%" }, onValueChangeFinished = commit)
    ParamSlider(R.string.tune, inst.tune, { v -> set { it.copy(tune = v.roundToInt().toFloat()) } }, -12f..12f,
        format = { "%+d st".format(it.roundToInt()) }, onValueChangeFinished = commit)
}

@Composable
private fun SynthParams(inst: Instrument.Synth, update: InstrumentUpdate, commit: () -> Unit) {
    fun set(transform: (Instrument.Synth) -> Instrument.Synth) = update(false) { transform(it as Instrument.Synth) }
    Text(stringResource(R.string.waveform), style = MaterialTheme.typography.labelLarge)
    SegmentedChoice(
        options = Waveform.entries,
        selected = inst.waveform,
        label = { stringResource(it.label()) },
        onSelect = { w -> update(true) { (it as Instrument.Synth).copy(waveform = w) } },
        modifier = Modifier.padding(vertical = 6.dp),
    )
    val pct = { v: Float -> "${(v * 100).roundToInt()}%" }
    val secs = { v: Float -> if (v < 1f) "${(v * 1000).roundToInt()} ms" else "%.2f s".format(v) }
    ParamSlider(R.string.cutoff, inst.cutoff, { v -> set { it.copy(cutoff = v) } }, 0f..1f, format = pct, onValueChangeFinished = commit)
    ParamSlider(R.string.resonance, inst.resonance, { v -> set { it.copy(resonance = v) } }, 0f..1f, format = pct, onValueChangeFinished = commit)
    ParamSlider(R.string.env_amount, inst.envAmount, { v -> set { it.copy(envAmount = v) } }, 0f..1f, format = pct, onValueChangeFinished = commit)
    ParamSlider(R.string.attack, inst.attack, { v -> set { it.copy(attack = v) } }, 0.001f..2f, format = secs, onValueChangeFinished = commit)
    ParamSlider(R.string.decay, inst.decay, { v -> set { it.copy(decay = v) } }, 0.01f..3f, format = secs, onValueChangeFinished = commit)
    ParamSlider(R.string.sustain, inst.sustain, { v -> set { it.copy(sustain = v) } }, 0f..1f, format = pct, onValueChangeFinished = commit)
    ParamSlider(R.string.release, inst.release, { v -> set { it.copy(release = v) } }, 0.01f..4f, format = secs, onValueChangeFinished = commit)
    ParamSlider(R.string.detune, inst.detune, { v -> set { it.copy(detune = v) } }, 0f..50f,
        format = { "${it.roundToInt()} ct" }, onValueChangeFinished = commit)
}
