package com.jeangbeat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeangbeat.app.AppLanguage
import com.jeangbeat.app.AudioTarget
import com.jeangbeat.app.MessageAction
import com.jeangbeat.app.io.exportBaseName
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import com.jeangbeat.app.StudioState
import com.jeangbeat.app.StudioTab
import com.jeangbeat.app.StudioViewModel
import com.jeangbeat.core.audio.PlayMode
import com.jeangbeat.core.model.AutomationTarget
import com.jeangbeat.core.model.Clip
import com.jeangbeat.core.model.EffectType
import com.jeangbeat.core.model.Meter
import com.jeangbeat.core.model.Note
import com.jeangbeat.core.model.ScaleSetting
import com.jeangbeat.core.model.Snap
import com.jeangbeat.core.model.isMelodic
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.R
import com.jeangbeat.feature.mixer.Mixer
import com.jeangbeat.feature.mixer.MixerActions
import com.jeangbeat.feature.pattern.PatternActions
import com.jeangbeat.feature.pattern.PatternScreen
import com.jeangbeat.feature.pianoroll.PianoRoll
import com.jeangbeat.feature.pianoroll.PianoRollActions
import com.jeangbeat.feature.playlist.AutomationEditorSheet
import com.jeangbeat.feature.playlist.Playlist
import com.jeangbeat.feature.playlist.PlaylistActions

private data class TabInfo(val tab: StudioTab, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    TabInfo(StudioTab.PLAYLIST, R.string.tab_playlist, Icons.Default.ViewTimeline),
    TabInfo(StudioTab.PATTERN, R.string.tab_pattern, Icons.Default.GridOn),
    TabInfo(StudioTab.MIXER, R.string.tab_mixer, Icons.Default.Tune),
)

/** Things that need a name typed in. */
private sealed interface RenameRequest {
    data class Pattern(val id: Int) : RenameRequest
    data class Track(val id: Int) : RenameRequest
    data class Insert(val index: Int) : RenameRequest
    data class MakeUnique(val clipId: Int) : RenameRequest
    data class Channel(val id: Int) : RenameRequest
    data class CloneChannel(val id: Int) : RenameRequest
    data object NewProject : RenameRequest
    data object PatternLength : RenameRequest
}

@Composable
fun StudioScreen(viewModel: StudioViewModel, files: FileActions, onLanguageChange: (AppLanguage) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var settingsChannelId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showAddChannel by rememberSaveable { mutableStateOf(false) }
    var showProjects by rememberSaveable { mutableStateOf(false) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showAutomationPicker by rememberSaveable { mutableStateOf(false) }
    var automationClipId by rememberSaveable { mutableStateOf<Int?>(null) }
    var rename by remember { mutableStateOf<RenameRequest?>(null) }

    var replaceChannelId by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { m ->
            val text = context.getString(m.text, *m.args.toTypedArray())
            val result = snackbar.showSnackbar(
                text,
                actionLabel = m.actionLabel?.let(context::getString),
                withDismissAction = m.action != null,
                duration = if (m.action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (val a = m.action) {
                    MessageAction.Undo -> viewModel.undo()
                    is MessageAction.Share -> shareFile(context, a.uri, a.mime)
                    null -> Unit
                }
            }
        }
    }

    // Engine position, polled once per frame while playing.
    var position by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            withFrameNanos { }
            position = viewModel.positionTicks()
        }
        position = 0f
    }
    val songTick = if (state.isPlaying && state.mode == PlayMode.SONG) position else state.songPosition.toFloat()
    val patternTick = if (state.isPlaying && state.mode == PlayMode.PATTERN) position else null
    val displayTick = if (state.isPlaying) position else if (state.mode == PlayMode.SONG) state.songPosition.toFloat() else 0f

    // Mixer meters, polled while the Mixer is on screen.
    var meters by remember { mutableStateOf(FloatArray(StudioViewModel.METER_SIZE)) }
    LaunchedEffect(state.tab) {
        if (state.tab != StudioTab.MIXER) return@LaunchedEffect
        val buf = FloatArray(StudioViewModel.METER_SIZE)
        val smooth = FloatArray(StudioViewModel.METER_SIZE)
        while (true) {
            withFrameNanos { }
            viewModel.takeMeters(buf)
            for (i in buf.indices) smooth[i] = maxOf(buf[i], smooth[i] * 0.88f) // gentle fall-off
            meters = smooth.copyOf()
        }
    }

    val patternNameFor = { n: Int -> context.getString(R.string.pattern_default_name, n) }

    BoxWithConstraints(Modifier.fillMaxSize().background(JbColors.Background)) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            TransportBar(
                state, displayTick, viewModel,
                onOpenProjects = { viewModel.refreshProjects(); showProjects = true },
                onExportAudio = { showExport = true },
                onNewProject = { rename = RenameRequest.NewProject },
                onSaveAs = { viewModel.refreshProjects(); showProjects = true },
                files = files,
                onLanguageChange = onLanguageChange,
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (landscape) {
                    NavigationRail(containerColor = JbColors.Surface, modifier = Modifier.fillMaxHeight()) {
                        TABS.forEach { t ->
                            NavigationRailItem(
                                selected = state.tab == t.tab, onClick = { viewModel.setTab(t.tab) },
                                icon = { Icon(t.icon, null) }, label = { Text(stringResource(t.label)) },
                                colors = NavigationRailItemDefaults.colors(indicatorColor = JbColors.Accent.copy(alpha = 0.3f)),
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    TabContent(
                        state, viewModel, songTick, patternTick, meters, patternNameFor,
                        onSettings = { settingsChannelId = it },
                        onAddChannel = { showAddChannel = true },
                        onRename = { rename = it },
                        onReplace = { replaceChannelId = it },
                        onAutomationPicker = { showAutomationPicker = true },
                        onEditAutomation = { automationClipId = it },
                        files = files,
                    )
                }
            }
            if (!landscape) {
                NavigationBar(containerColor = JbColors.Surface) {
                    TABS.forEach { t ->
                        NavigationBarItem(
                            selected = state.tab == t.tab, onClick = { viewModel.setTab(t.tab) },
                            icon = { Icon(t.icon, null) }, label = { Text(stringResource(t.label)) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = JbColors.Accent.copy(alpha = 0.3f)),
                        )
                    }
                }
            } else {
                Box(Modifier.navigationBarsPadding())
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
        if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = JbColors.Accent)
    }

    // ---- dialogs & sheets ----
    settingsChannelId?.let { id ->
        val channel = state.project.channel(id)
        if (channel == null) settingsChannelId = null else ChannelSettingsSheet(channel, viewModel) { settingsChannelId = null }
    }
    if (showAddChannel) {
        AddChannelDialog(
            onAdd = { name, instrument -> viewModel.addChannel(name, instrument); showAddChannel = false },
            onImport = { showAddChannel = false; files.pickAudio(AudioTarget.CHANNEL) },
            onDismiss = { showAddChannel = false },
        )
    }
    if (showProjects) ProjectsDialog(state, viewModel, files) { showProjects = false }
    if (showExport) {
        ExportDialog(
            state.mode,
            hasLoop = state.project.song.hasLoop,
            onExport = { options, toMusic ->
                showExport = false
                if (!toMusic || !viewModel.exportToMediaStore(options, exportBaseName(state.project.name, options))) files.exportAudio(options)
            },
            onDismiss = { showExport = false },
        )
    }
    replaceChannelId?.let { id ->
        AddChannelDialog(
            onAdd = { _, instrument -> viewModel.replaceInstrument(id, instrument); replaceChannelId = null },
            onImport = { replaceChannelId = null; files.pickAudio(AudioTarget.CHANNEL) },
            onDismiss = { replaceChannelId = null },
        )
    }
    state.exportProgress?.let { ExportProgressDialog(it, viewModel::cancelExport) }
    if (showAutomationPicker) {
        AutomationTargetDialog(state.project, onPick = { target, value -> showAutomationPicker = false; viewModel.automate(target, value) }) { showAutomationPicker = false }
    }
    automationClipId?.let { id ->
        val clip = state.project.song.clip(id) as? Clip.AutomationClip
        if (clip == null) {
            automationClipId = null
        } else {
            AutomationEditorSheet(state.project, clip, { points, final -> viewModel.setAutomationPoints(id, points, final) }) {
                viewModel.commitGesture()
                automationClipId = null
            }
        }
    }
    rename?.let { r ->
        val (title, initial) = when (r) {
            is RenameRequest.Pattern -> stringResource(R.string.rename) to (state.project.pattern(r.id)?.name ?: "")
            is RenameRequest.Track -> stringResource(R.string.rename) to (state.project.song.track(r.id)?.name ?: "")
            is RenameRequest.Insert -> stringResource(R.string.rename) to (state.project.mixer.track(r.index)?.name ?: "")
            is RenameRequest.MakeUnique -> stringResource(R.string.make_unique) to
                ((state.project.song.clip(r.clipId) as? Clip.PatternClip)?.let { state.project.pattern(it.patternId)?.name + " 2" } ?: "")
            is RenameRequest.Channel -> stringResource(R.string.rename) to (state.project.channel(r.id)?.name ?: "")
            is RenameRequest.CloneChannel -> stringResource(R.string.clone_channel) to ((state.project.channel(r.id)?.name ?: "") + " 2")
            RenameRequest.NewProject -> stringResource(R.string.new_project) to stringResource(R.string.untitled)
            RenameRequest.PatternLength -> stringResource(R.string.length_bars_prompt) to state.project.currentPattern.bars.toString()
        }
        TextInputDialog(title, initial, onConfirm = { name ->
            when (r) {
                is RenameRequest.Pattern -> viewModel.renamePattern(r.id, name)
                is RenameRequest.Track -> viewModel.renameTrack(r.id, name)
                is RenameRequest.Insert -> viewModel.renameMixerTrack(r.index, name)
                is RenameRequest.MakeUnique -> viewModel.makeUnique(r.clipId, name)
                is RenameRequest.Channel -> viewModel.renameChannel(r.id, name)
                is RenameRequest.CloneChannel -> viewModel.cloneChannel(r.id, name)
                RenameRequest.NewProject -> viewModel.newProject(name, context.getString(R.string.pattern_default_name, 1), withDemo = false)
                RenameRequest.PatternLength -> name.trim().toIntOrNull()?.let { viewModel.setPatternBars(it.coerceIn(1, 512)) }
            }
            rename = null
        }, onDismiss = { rename = null })
    }
}

@Composable
private fun TabContent(
    state: StudioState,
    vm: StudioViewModel,
    songTick: Float,
    patternTick: Float?,
    meters: FloatArray,
    patternNameFor: (Int) -> String,
    onSettings: (Int) -> Unit,
    onAddChannel: () -> Unit,
    onRename: (RenameRequest) -> Unit,
    onReplace: (Int) -> Unit,
    onAutomationPicker: () -> Unit,
    onEditAutomation: (Int) -> Unit,
    files: FileActions,
) {
    val project = state.project
    when (state.tab) {
        StudioTab.PATTERN -> {
            val rollChannel = state.pianoRollChannelId?.let(project::channel)
            if (rollChannel != null) {
                val pattern = project.currentPattern
                val ghosts = project.channels.filter { it.id != rollChannel.id && it.instrument.isMelodic }.flatMap { pattern.notesFor(it.id) }
                val actions = remember(vm, rollChannel.id) { pianoRollActions(vm, rollChannel.id) }
                PianoRoll(
                    rollChannel, pattern, ghosts, project.scale, state.rollSnap, patternTick,
                    state.isPlaying && state.mode == PlayMode.PATTERN, actions,
                    loop = state.rollLoop, startTick = state.rollStart,
                )
            } else {
                val actions = remember(vm) { patternActions(vm, onSettings, onAddChannel, onRename, onReplace, patternNameFor) }
                PatternScreen(project, patternTick, actions)
            }
        }
        StudioTab.PLAYLIST -> {
            val actions = remember(vm) { playlistActions(vm, onRename, onAutomationPicker, onEditAutomation, files, patternNameFor) }
            Playlist(project, state.paintPatternId, state.playlistSnap, songTick, state.waveforms, actions)
        }
        StudioTab.MIXER -> {
            val actions = remember(vm) { mixerActions(vm) { onRename(RenameRequest.Insert(it)) } }
            Mixer(project, meters, state.selectedMixerTrack, vm::selectMixerTrack, actions)
        }
    }
}

private fun patternActions(
    vm: StudioViewModel,
    onSettings: (Int) -> Unit,
    onAddChannel: () -> Unit,
    onRename: (RenameRequest) -> Unit,
    onReplace: (Int) -> Unit,
    patternNameFor: (Int) -> String,
) = object : PatternActions {
    override fun renameChannel(channelId: Int) = onRename(RenameRequest.Channel(channelId))
    override fun setChannelColor(channelId: Int, color: Int) = vm.setChannelColor(channelId, color)
    override fun cloneChannel(channelId: Int) = onRename(RenameRequest.CloneChannel(channelId))
    override fun deleteChannel(channelId: Int) = vm.deleteChannel(channelId)
    override fun clearChannel(channelId: Int, allPatterns: Boolean) = vm.clearChannel(channelId, allPatterns)
    override fun moveChannel(channelId: Int, delta: Int) = vm.moveChannel(channelId, delta)
    override fun fillSteps(channelId: Int, every: Int) = vm.fillSteps(channelId, every)
    override fun shiftSteps(channelId: Int, delta: Int) = vm.shiftSteps(channelId, delta)
    override fun randomizeVelocity(channelId: Int) = vm.randomizeVelocity(channelId)
    override fun replaceInstrument(channelId: Int) = onReplace(channelId)
    override fun setPatternColor(color: Int) = vm.setPatternColor(color)
    override fun splitByChannel() = vm.splitByChannel()
    override fun setPatternAuto() = vm.setPatternAuto()
    override fun customPatternLength() = onRename(RenameRequest.PatternLength)
    override fun toggleStep(channelId: Int, step: Int) = vm.toggleStep(channelId, step)
    override fun updateStepNote(channelId: Int, step: Int, final: Boolean, transform: (Note) -> Note) = vm.updateStepNote(channelId, step, final, transform)
    override fun toggleMute(channelId: Int) = vm.toggleMute(channelId)
    override fun toggleSolo(channelId: Int) = vm.toggleSolo(channelId)
    override fun setChannelVolume(channelId: Int, volume: Float, final: Boolean) = vm.setChannelVolume(channelId, volume, final)
    override fun setChannelPan(channelId: Int, pan: Float, final: Boolean) = vm.setChannelPan(channelId, pan, final)
    override fun setChannelInsert(channelId: Int, insert: Int) = vm.setChannelInsert(channelId, insert)
    override fun openPianoRoll(channelId: Int) = vm.openPianoRoll(channelId)
    override fun openChannelSettings(channelId: Int) = onSettings(channelId)
    override fun previewChannel(channelId: Int) = vm.previewChannel(channelId)
    override fun addChannel() = onAddChannel()
    override fun selectPattern(patternId: Int) = vm.selectPattern(patternId)
    override fun newPattern(copyCurrent: Boolean) = vm.addPattern(patternNameFor(vm.state.value.project.patterns.size + 1), copyCurrent)
    override fun renamePattern() = onRename(RenameRequest.Pattern(vm.state.value.project.currentPatternId))
    override fun deletePattern() = vm.deletePattern()
    override fun setPatternBars(bars: Int) = vm.setPatternBars(bars)
    override fun setMeter(meter: Meter) = vm.setMeter(meter)
    override fun setSwing(amount: Float, final: Boolean) = vm.setSwing(amount, final)
    override fun setChannelSteps(channelId: Int, steps: List<Int>) = vm.setChannelSteps(channelId, steps)
    override fun rotateChannel(channelId: Int, steps: Int, final: Boolean) = vm.rotateChannel(channelId, steps, final)
}

private fun pianoRollActions(vm: StudioViewModel, channelId: Int) = object : PianoRollActions {
    override fun editNotes(final: Boolean, transform: (List<Note>) -> List<Note>) = vm.editNotes(channelId, final, transform)
    override fun previewNote(pitch: Int) = vm.previewNote(channelId, pitch)
    override fun setSnap(snap: Snap) = vm.setRollSnap(snap)
    override fun setScale(scale: ScaleSetting) = vm.setScale(scale)
    override fun copy(notes: List<Note>) = vm.copyNotes(notes)
    override fun clipboard(): List<Note> = vm.clipboard()
    override fun togglePlay() {
        if (vm.state.value.mode != PlayMode.PATTERN) vm.setMode(PlayMode.PATTERN)
        vm.togglePlay()
    }
    override fun close() = vm.closePianoRoll()
    override fun setPosition(tick: Int) = vm.setPatternPosition(tick)
    override fun setLoop(startTick: Int, endTick: Int) = vm.setRollLoop(startTick, endTick)
    override fun clearLoop() = vm.clearRollLoop()
}

private fun playlistActions(
    vm: StudioViewModel,
    onRename: (RenameRequest) -> Unit,
    onAutomationPicker: () -> Unit,
    onEditAutomation: (Int) -> Unit,
    files: FileActions,
    patternNameFor: (Int) -> String,
) = object : PlaylistActions {
    override fun addPatternClip(trackId: Int, startTick: Int, patternId: Int, final: Boolean) = vm.addPatternClip(trackId, startTick, patternId, final)
    override fun moveClip(clipId: Int, startTick: Int, trackId: Int, final: Boolean) = vm.moveClip(clipId, startTick, trackId, final)
    override fun resizeClip(clipId: Int, lengthTicks: Int, final: Boolean) = vm.resizeClip(clipId, lengthTicks, final)
    override fun deleteClip(clipId: Int, final: Boolean) = vm.deleteClip(clipId, final)
    override fun toggleClipMute(clipId: Int) = vm.toggleClipMute(clipId)
    override fun sliceClip(clipId: Int, atTick: Int) = vm.sliceClip(clipId, atTick)
    override fun duplicateClip(clipId: Int) = vm.duplicateClip(clipId)
    override fun makeUnique(clipId: Int) = onRename(RenameRequest.MakeUnique(clipId))
    override fun openClip(clip: Clip) {
        when (clip) {
            is Clip.PatternClip -> vm.openPatternClip(clip)
            is Clip.AutomationClip -> onEditAutomation(clip.id)
            is Clip.AudioClip -> Unit
        }
    }
    override fun setPosition(tick: Int) = vm.setSongPosition(tick)
    override fun setLoop(startTick: Int, endTick: Int) = vm.setLoop(startTick, endTick)
    override fun clearLoop() = vm.clearLoop()
    override fun selectPaintPattern(patternId: Int) = vm.setPaintPattern(patternId)
    override fun newPattern() {
        vm.addPattern(patternNameFor(vm.state.value.project.patterns.size + 1), copyCurrent = false)
        vm.setPaintPattern(vm.state.value.project.currentPatternId)
    }
    override fun addTrack() = vm.addTrack()
    override fun toggleTrackMute(trackId: Int) = vm.toggleTrackMute(trackId)
    override fun toggleTrackSolo(trackId: Int) = vm.toggleTrackSolo(trackId)
    override fun renameTrack(trackId: Int) = onRename(RenameRequest.Track(trackId))
    override fun removeTrack(trackId: Int) = vm.removeTrack(trackId)
    override fun importAudio() = files.pickAudio(AudioTarget.PLAYLIST)
    override fun addAutomation() = onAutomationPicker()
    override fun setClipInsert(clipId: Int, insert: Int) = vm.setClipInsert(clipId, insert)
    override fun setSnap(snap: Snap) = vm.setPlaylistSnap(snap)
}

private fun mixerActions(vm: StudioViewModel, onRename: (Int) -> Unit) = object : MixerActions {
    override fun setVolume(track: Int, volume: Float, final: Boolean) = vm.setMixerVolume(track, volume, final)
    override fun setPan(track: Int, pan: Float, final: Boolean) = vm.setMixerPan(track, pan, final)
    override fun toggleMute(track: Int) = vm.toggleMixerMute(track)
    override fun toggleSolo(track: Int) = vm.toggleMixerSolo(track)
    override fun rename(track: Int) = onRename(track)
    override fun addEffect(track: Int, type: EffectType) = vm.addEffect(track, type)
    override fun removeEffect(track: Int, slotId: Int) = vm.removeEffect(track, slotId)
    override fun moveEffect(track: Int, slotId: Int, toPosition: Int) = vm.moveEffect(track, slotId, toPosition)
    override fun setEffectEnabled(track: Int, slotId: Int, enabled: Boolean) = vm.setEffectEnabled(track, slotId, enabled)
    override fun setEffectMix(track: Int, slotId: Int, mix: Float, final: Boolean) = vm.setEffectMix(track, slotId, mix, final)
    override fun setEffectParam(track: Int, slotId: Int, param: Int, value: Float, final: Boolean) = vm.setEffectParam(track, slotId, param, value, final)
    override fun setSidechain(track: Int, slotId: Int, source: Int) = vm.setSidechain(track, slotId, source)
    override fun setSend(from: Int, to: Int, level: Float, final: Boolean) = vm.setSend(from, to, level, final)
    override fun setToMaster(track: Int, on: Boolean) = vm.setToMaster(track, on)
    override fun automate(target: AutomationTarget, value: Float) = vm.automate(target, value)
}


/** Opens the Android share sheet for an exported file (also lets the user "open with" a player). */
internal fun shareFile(context: android.content.Context, uri: android.net.Uri, mime: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mime
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
