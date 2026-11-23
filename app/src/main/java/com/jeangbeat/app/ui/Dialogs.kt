package com.jeangbeat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jeangbeat.app.StudioState
import com.jeangbeat.app.StudioViewModel
import com.jeangbeat.core.model.Channel
import com.jeangbeat.core.model.DrumSound
import com.jeangbeat.core.model.Instrument
import com.jeangbeat.core.model.Timing
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.ParamSlider
import com.jeangbeat.core.ui.R
import com.jeangbeat.core.ui.label
import kotlin.math.roundToInt

/** Horizontal row of channel chips, used above the piano roll. */
@Composable
fun ChannelPicker(channels: List<Channel>, selectedId: Int?, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        channels.forEach { channel ->
            FilterChip(
                selected = channel.id == selectedId,
                onClick = { onSelect(channel.id) },
                label = { Text(channel.name, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = JbColors.channel(channel.color).copy(alpha = 0.35f),
                ),
            )
        }
    }
}

@Composable
fun TempoDialog(
    tempo: Float,
    swing: Float,
    onTempo: (Float, Boolean) -> Unit,
    onSwing: (Float, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val taps = remember { mutableStateListOf<Long>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        title = { Text(stringResource(R.string.tempo)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onTempo(tempo.roundToInt() - 1f, true) }) { Icon(Icons.Default.Remove, null) }
                    Text(
                        stringResource(R.string.bpm_value, tempo.roundToInt()),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = { onTempo(tempo.roundToInt() + 1f, true) }) { Icon(Icons.Default.Add, null) }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = {
                        // Tap tempo: average the last few intervals; restart after a 2 s pause.
                        val now = System.currentTimeMillis()
                        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                        taps.add(now)
                        while (taps.size > 5) taps.removeAt(0)
                        if (taps.size >= 2) {
                            val avg = (taps.last() - taps.first()).toFloat() / (taps.size - 1)
                            onTempo((60000f / avg).coerceIn(Timing.MIN_TEMPO, Timing.MAX_TEMPO).roundToInt().toFloat(), true)
                        }
                    }) { Text(stringResource(R.string.tap_tempo)) }
                }
                ParamSlider(
                    label = R.string.tempo,
                    value = tempo,
                    onValueChange = { onTempo(it.roundToInt().toFloat(), false) },
                    onValueChangeFinished = { onTempo(tempo, true) },
                    valueRange = Timing.MIN_TEMPO..Timing.MAX_TEMPO,
                    format = { it.roundToInt().toString() },
                )
                ParamSlider(
                    label = R.string.swing,
                    value = swing,
                    onValueChange = { onSwing(it, false) },
                    onValueChangeFinished = { onSwing(swing, true) },
                    valueRange = 0f..1f,
                    format = { "${(it * 100).roundToInt()}%" },
                )
            }
        },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                singleLine = true,
                label = { Text(stringResource(R.string.name)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun AddChannelDialog(onAdd: (String, Instrument) -> Unit, onDismiss: () -> Unit) {
    val drumOptions = DrumSound.entries.map { it to stringResource(it.label()) }
    val bassName = stringResource(R.string.instrument_808)
    val synthName = stringResource(R.string.instrument_synth)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_channel)) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { SectionTitle(stringResource(R.string.instrument_808) + " / " + synthName) }
                item { ChoiceRow(bassName) { onAdd("808", Instrument.Bass808()) } }
                item { ChoiceRow(synthName) { onAdd(synthName, Instrument.Synth()) } }
                item { SectionTitle(stringResource(R.string.instrument_drum)) }
                items(drumOptions) { (sound, name) ->
                    ChoiceRow(name) { onAdd(name.substringBefore(" ("), Instrument.Drum(sound)) }
                }
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = JbColors.Accent,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChoiceRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
fun ProjectsDialog(state: StudioState, viewModel: StudioViewModel, onDismiss: () -> Unit) {
    var nameDialog by remember { mutableStateOf<String?>(null) } // "new", "demo" or "saveas"
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val untitled = stringResource(R.string.untitled)
    val patternName = stringResource(R.string.pattern_default_name, 1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.projects)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        text = {
            Column {
                Text(
                    stringResource(R.string.current_project, state.project.name),
                    style = MaterialTheme.typography.labelLarge,
                    color = JbColors.Accent,
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { nameDialog = "new" }) { Text(stringResource(R.string.new_project)) }
                    FilledTonalButton(onClick = { nameDialog = "demo" }) { Text(stringResource(R.string.new_project_demo)) }
                    FilledTonalButton(onClick = { nameDialog = "saveas" }) { Text(stringResource(R.string.save_as)) }
                }
                HorizontalDivider()
                if (state.projects.isEmpty()) {
                    Text(stringResource(R.string.no_projects), color = JbColors.TextDim, modifier = Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(state.projects, key = { it.fileName }) { info ->
                        val isCurrent = info.fileName == state.fileName
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isCurrent) {
                                    viewModel.openProject(info.fileName)
                                    onDismiss()
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                info.name,
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                            )
                            if (!isCurrent) {
                                IconButton(onClick = { confirmDelete = info.fileName }) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = JbColors.TextDim)
                                }
                            } else {
                                Spacer(Modifier.width(48.dp))
                            }
                        }
                    }
                }
            }
        },
    )

    nameDialog?.let { kind ->
        TextInputDialog(
            title = stringResource(if (kind == "saveas") R.string.save_as else R.string.new_project),
            initial = if (kind == "saveas") state.project.name else untitled,
            onConfirm = { name ->
                when (kind) {
                    "saveas" -> viewModel.saveAs(name)
                    else -> viewModel.newProject(name, patternName, withDemo = kind == "demo")
                }
                nameDialog = null
                onDismiss()
            },
            onDismiss = { nameDialog = null },
        )
    }
    confirmDelete?.let { fileName ->
        val name = state.projects.firstOrNull { it.fileName == fileName }?.name ?: fileName
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProject(fileName)
                    confirmDelete = null
                }) { Text(stringResource(R.string.delete), color = JbColors.Record) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
