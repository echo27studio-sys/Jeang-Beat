package com.jeangbeat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jeangbeat.app.AppLanguage
import com.jeangbeat.app.StudioState
import com.jeangbeat.app.StudioViewModel
import com.jeangbeat.core.model.Timing
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.R
import kotlin.math.roundToInt

@Composable
fun TransportBar(
    state: StudioState,
    viewModel: StudioViewModel,
    onOpenProjects: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    var showTempo by remember { mutableStateOf(false) }
    var renamePatternId by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth().background(JbColors.Surface).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Play / stop: the most important button, so it is the biggest.
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (state.isPlaying) JbColors.Text else JbColors.Accent)
                    .clickable { viewModel.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (state.isPlaying) R.string.stop else R.string.play),
                    tint = Color.Black,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = viewModel::toggleRecord) {
                Icon(
                    Icons.Default.FiberManualRecord,
                    contentDescription = stringResource(R.string.record),
                    tint = if (state.recording) JbColors.Record else JbColors.TextDim,
                )
            }
            Chip(onClick = { showTempo = true }) {
                Text(
                    stringResource(R.string.bpm_value, state.project.tempo.roundToInt()),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            PatternMenu(state, viewModel, onRename = { renamePatternId = it })
            IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo))
            }
            IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                state.project.name,
                style = MaterialTheme.typography.labelLarge,
                color = JbColors.TextDim,
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onOpenProjects),
            )
            OverflowMenu(onOpenProjects, onLanguageChange)
        }
    }

    if (showTempo) {
        TempoDialog(
            tempo = state.project.tempo,
            swing = state.project.swing,
            onTempo = viewModel::setTempo,
            onSwing = viewModel::setSwing,
            onDismiss = {
                viewModel.commitGesture()
                showTempo = false
            },
        )
    }
    renamePatternId?.let { id ->
        val current = state.project.patterns.firstOrNull { it.id == id }?.name.orEmpty()
        TextInputDialog(
            title = stringResource(R.string.rename),
            initial = current,
            onConfirm = {
                viewModel.renamePattern(id, it)
                renamePatternId = null
            },
            onDismiss = { renamePatternId = null },
        )
    }
}

@Composable
private fun Chip(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(JbColors.SurfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PatternMenu(state: StudioState, viewModel: StudioViewModel, onRename: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val project = state.project
    val pattern = project.currentPattern
    val nextName = stringResource(R.string.pattern_default_name, project.patterns.size + 1)
    Box {
        Chip(onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pattern.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.pattern))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            project.patterns.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Text(p.name, fontWeight = if (p.id == pattern.id) FontWeight.Bold else FontWeight.Normal)
                    },
                    onClick = {
                        viewModel.selectPattern(p.id)
                        open = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.new_pattern)) }, onClick = {
                viewModel.addPattern(nextName, copyCurrent = false)
                open = false
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.clone_pattern)) }, onClick = {
                viewModel.addPattern(nextName, copyCurrent = true)
                open = false
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = {
                onRename(pattern.id)
                open = false
            })
            if (project.patterns.size > 1) {
                DropdownMenuItem(text = { Text(stringResource(R.string.delete_pattern)) }, onClick = {
                    viewModel.deletePattern(pattern.id)
                    open = false
                })
            }
            HorizontalDivider()
            Timing.PATTERN_LENGTHS.forEach { steps ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.pattern_length) + ": " + stringResource(R.string.steps_count, steps),
                            fontWeight = if (steps == pattern.lengthSteps) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        viewModel.setPatternLength(steps)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(onOpenProjects: () -> Unit, onLanguageChange: (AppLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.projects)) }, onClick = {
                open = false
                onOpenProjects()
            })
            HorizontalDivider()
            Text(
                stringResource(R.string.language),
                style = MaterialTheme.typography.labelMedium,
                color = JbColors.TextDim,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            listOf(
                AppLanguage.SYSTEM to R.string.language_system,
                AppLanguage.ENGLISH to R.string.language_english,
                AppLanguage.KHMER to R.string.language_khmer,
            ).forEach { (language, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                    open = false
                    onLanguageChange(language)
                })
            }
        }
    }
}
