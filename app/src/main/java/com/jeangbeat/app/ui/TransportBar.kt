package com.jeangbeat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Unarchive
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeangbeat.app.AppLanguage
import com.jeangbeat.app.StudioState
import com.jeangbeat.app.StudioViewModel
import com.jeangbeat.core.audio.PlayMode
import com.jeangbeat.core.model.Timing
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.R
import kotlin.math.roundToInt

/** File actions that need the Android document picker (wired in MainActivity). */
interface FileActions {
    fun pickAudio(target: com.jeangbeat.app.AudioTarget)
    fun exportAudio(options: com.jeangbeat.app.io.ExportOptions)
    fun exportProject()
    fun importProject()
}

@Composable
fun TransportBar(
    state: StudioState,
    positionTick: Float,
    viewModel: StudioViewModel,
    onOpenProjects: () -> Unit,
    onExportAudio: () -> Unit,
    onNewProject: () -> Unit,
    onSaveAs: () -> Unit,
    files: FileActions,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    var showTempo by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(JbColors.Surface).statusBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (state.isPlaying) JbColors.Text else JbColors.Accent).clickable { viewModel.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                stringResource(if (state.isPlaying) R.string.stop else R.string.play),
                tint = Color.Black, modifier = Modifier.size(28.dp),
            )
        }
        // FL Studio PAT / SONG switch
        Row(Modifier.clip(RoundedCornerShape(8.dp))) {
            PlayMode.entries.forEach { m ->
                val on = state.mode == m
                Text(
                    if (m == PlayMode.PATTERN) "PAT" else "SONG",
                    modifier = Modifier.background(if (on) JbColors.Accent else JbColors.SurfaceRaised).clickable { viewModel.setMode(m) }.padding(horizontal = 9.dp, vertical = 8.dp),
                    color = if (on) Color(0xFF1A1400) else JbColors.TextDim, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                )
            }
        }
        Lcd(Timing.position(positionTick.toInt()))
        Box(Modifier.clickable { showTempo = true }) { Lcd("${state.project.tempo.roundToInt()}") }
        // Save: always visible; the gold dot means there are edits not saved yet.
        Box {
            IconButton(onClick = viewModel::saveNowWithMessage) { Icon(Icons.Default.Save, stringResource(R.string.save)) }
            if (state.unsaved) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(8.dp).clip(CircleShape).background(JbColors.Accent))
        }
        IconButton(onClick = onExportAudio) { Icon(Icons.Default.IosShare, stringResource(R.string.export_audio), tint = JbColors.Accent) }
        IconButton(onClick = viewModel::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
        IconButton(onClick = viewModel::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
        Text(
            state.project.name, style = MaterialTheme.typography.labelLarge, color = JbColors.TextDim, maxLines = 1,
            modifier = Modifier.clickable(onClick = onOpenProjects),
        )
        Menu(state, viewModel, onOpenProjects, onExportAudio, onNewProject, onSaveAs, files, onLanguageChange)
    }
    if (showTempo) {
        TempoDialog(
            tempo = state.project.tempo,
            swing = state.project.swing,
            onTempo = viewModel::setTempo,
            onSwing = viewModel::setSwing,
            onDismiss = { viewModel.commitGesture(); showTempo = false },
        )
    }
}

@Composable
private fun Lcd(text: String) {
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFF0B0D11)).border(1.dp, JbColors.Outline, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
        color = JbColors.Accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp,
    )
}

@Composable
private fun Menu(
    state: StudioState,
    viewModel: StudioViewModel,
    onOpenProjects: () -> Unit,
    onExportAudio: () -> Unit,
    onNewProject: () -> Unit,
    onSaveAs: () -> Unit,
    files: FileActions,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) LicensesDialog { showLicenses = false }
    Box {
        // A labelled "File" button instead of a bare ⋮ so save/export are easy to find.
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(JbColors.SurfaceRaised).clickable { open = true; viewModel.refreshProjects() }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Folder, null, tint = JbColors.Accent, modifier = Modifier.size(18.dp))
            Text(" " + stringResource(R.string.file_menu), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun close() { open = false }
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.new_project)) }, onClick = { close(); onNewProject() })
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, text = { Text(stringResource(R.string.open) + "…") }, onClick = { close(); onOpenProjects() })
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Save, null) }, text = { Text(stringResource(R.string.save)) }, onClick = { close(); viewModel.saveNowWithMessage() })
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.SaveAs, null) }, text = { Text(stringResource(R.string.save_as) + "…") }, onClick = { close(); onSaveAs() })
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Description, null) }, text = { Text(stringResource(R.string.save_copy_documents)) }, onClick = {
                close()
                if (!viewModel.saveCopyToDocuments()) files.exportProject()
            })
            val recent = state.projects.filter { it.fileName != state.fileName }.take(5)
            if (recent.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.recent_projects), style = MaterialTheme.typography.labelMedium, color = JbColors.TextDim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                recent.forEach { info ->
                    DropdownMenuItem(text = { Text("   " + info.name, maxLines = 1) }, onClick = { close(); viewModel.openProject(info.fileName) })
                }
            }
            HorizontalDivider()
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.IosShare, null) }, text = { Text(stringResource(R.string.export_audio) + " (MP3 / WAV)…") }, onClick = { close(); onExportAudio() })
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Archive, null) }, text = { Text(stringResource(R.string.export_project)) }, onClick = { close(); files.exportProject() })
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Unarchive, null) }, text = { Text(stringResource(R.string.import_project)) }, onClick = { close(); files.importProject() })
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.LibraryMusic, null) }, text = { Text(stringResource(R.string.import_audio)) }, onClick = { close(); files.pickAudio(com.jeangbeat.app.AudioTarget.PLAYLIST) })
            DropdownMenuItem(text = { Text(stringResource(R.string.licenses)) }, onClick = { close(); showLicenses = true })
            HorizontalDivider()
            Text(stringResource(R.string.language), style = MaterialTheme.typography.labelMedium, color = JbColors.TextDim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            listOf(
                AppLanguage.SYSTEM to R.string.language_system,
                AppLanguage.ENGLISH to R.string.language_english,
                AppLanguage.KHMER to R.string.language_khmer,
            ).forEach { (language, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; onLanguageChange(language) })
            }
        }
    }
}
