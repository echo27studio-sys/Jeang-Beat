package com.jeangbeat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeangbeat.app.AppLanguage
import com.jeangbeat.app.StudioState
import com.jeangbeat.app.StudioViewModel
import com.jeangbeat.core.model.Note
import com.jeangbeat.core.model.RepeatRate
import com.jeangbeat.core.model.ScaleSetting
import com.jeangbeat.core.model.Snap
import com.jeangbeat.core.model.Timing
import com.jeangbeat.core.model.isMelodic
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.R
import com.jeangbeat.feature.pads.PadActions
import com.jeangbeat.feature.pads.PadMode
import com.jeangbeat.feature.pads.PadsScreen
import com.jeangbeat.feature.pianoroll.PianoRoll
import com.jeangbeat.feature.pianoroll.PianoRollActions
import com.jeangbeat.feature.sequencer.StepSequencer
import com.jeangbeat.feature.sequencer.StepSequencerActions

enum class StudioTab(val label: Int, val icon: ImageVector) {
    PADS(R.string.tab_pads, Icons.Default.ViewModule),
    STEPS(R.string.tab_steps, Icons.Default.GridOn),
    PIANO_ROLL(R.string.tab_piano_roll, Icons.Default.Piano),
}

@Composable
fun StudioScreen(viewModel: StudioViewModel, onLanguageChange: (AppLanguage) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(StudioTab.STEPS) }
    var settingsChannelId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showAddChannel by rememberSaveable { mutableStateOf(false) }
    var showProjects by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(context.getString(it)) }
    }

    // Poll the engine position once per frame while playing, for the playhead.
    var playheadTick by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            withFrameNanos { }
            playheadTick = viewModel.positionTicks()
        }
    }
    val playhead = if (state.isPlaying) playheadTick else null

    val stepActions = remember(viewModel) { sequencerActions(viewModel, { settingsChannelId = it }, { showAddChannel = true }) }
    val padActions = remember(viewModel) { padActions(viewModel) }

    BoxWithConstraints(Modifier.fillMaxSize().background(JbColors.Background)) {
        val wide = maxWidth >= 840.dp && maxHeight >= 480.dp

        Scaffold(
            containerColor = JbColors.Background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TransportBar(
                    state = state,
                    viewModel = viewModel,
                    onOpenProjects = {
                        viewModel.refreshProjects()
                        showProjects = true
                    },
                    onLanguageChange = onLanguageChange,
                )
            },
            bottomBar = {
                if (!wide) {
                    NavigationBar(containerColor = JbColors.Surface) {
                        StudioTab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(stringResource(t.label)) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = JbColors.Accent.copy(alpha = 0.3f)),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val contentModifier = Modifier.padding(padding).fillMaxSize()
            if (wide) {
                // Tablet / landscape: editor on the left, pads always visible on the right.
                Row(contentModifier) {
                    val editorTab = if (tab == StudioTab.PADS) StudioTab.STEPS else tab
                    Column(Modifier.weight(1.5f).fillMaxHeight()) {
                        TabRow(selectedTabIndex = editorTab.ordinal - 1, containerColor = JbColors.Background) {
                            listOf(StudioTab.STEPS, StudioTab.PIANO_ROLL).forEach { t ->
                                Tab(selected = editorTab == t, onClick = { tab = t }, text = { Text(stringResource(t.label)) })
                            }
                        }
                        Editor(editorTab, state, viewModel, stepActions, padActions, playhead, Modifier.weight(1f))
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(JbColors.Outline))
                    Editor(StudioTab.PADS, state, viewModel, stepActions, padActions, playhead, Modifier.weight(1f))
                }
            } else {
                Editor(tab, state, viewModel, stepActions, padActions, playhead, contentModifier)
            }
        }
    }

    settingsChannelId?.let { id ->
        state.project.channel(id)?.let { channel ->
            ChannelSettingsSheet(
                channel = channel,
                viewModel = viewModel,
                onDismiss = { settingsChannelId = null },
            )
        } ?: run { settingsChannelId = null }
    }
    if (showAddChannel) {
        AddChannelDialog(
            onAdd = { name, instrument ->
                viewModel.addChannel(name, instrument)
                showAddChannel = false
            },
            onDismiss = { showAddChannel = false },
        )
    }
    if (showProjects) {
        ProjectsDialog(state = state, viewModel = viewModel, onDismiss = { showProjects = false })
    }
}

@Composable
private fun Editor(
    tab: StudioTab,
    state: StudioState,
    viewModel: StudioViewModel,
    stepActions: StepSequencerActions,
    padActions: PadActions,
    playheadTick: Float?,
    modifier: Modifier,
) {
    val project = state.project
    val pattern = project.currentPattern
    when (tab) {
        StudioTab.STEPS -> StepSequencer(
            channels = project.channels,
            pattern = pattern,
            selectedChannelId = state.selectedChannelId,
            playheadStep = playheadTick?.let { (it / Timing.TICKS_PER_STEP).toInt() },
            actions = stepActions,
            modifier = modifier,
        )
        StudioTab.PADS -> PadsScreen(
            channels = project.channels,
            mode = state.padMode,
            keysChannelId = state.keysChannelId,
            scale = project.scale,
            octave = state.octave,
            repeatRate = state.repeatRate,
            recording = state.recording,
            actions = padActions,
            modifier = modifier,
        )
        StudioTab.PIANO_ROLL -> {
            // Piano roll edits the selected channel; prefer a melodic one.
            val channel = state.selectedChannel ?: project.channels.firstOrNull { it.instrument.isMelodic }
            val ghosts = project.channels
                .filter { it.id != channel?.id && it.instrument.isMelodic }
                .flatMap { pattern.notesFor(it.id) }
            val channelId = channel?.id
            val actions = remember(viewModel, channelId) { pianoRollActions(viewModel, channelId) }
            Column(modifier) {
                ChannelPicker(project.channels, channelId, onSelect = { viewModel.selectChannel(it, preview = false) })
                PianoRoll(
                    channel = channel,
                    pattern = pattern,
                    ghostNotes = ghosts,
                    scale = project.scale,
                    snap = state.snap,
                    playheadTick = playheadTick,
                    actions = actions,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun sequencerActions(
    vm: StudioViewModel,
    openSettings: (Int) -> Unit,
    addChannel: () -> Unit,
) = object : StepSequencerActions {
    override fun toggleStep(channelId: Int, step: Int) = vm.toggleStep(channelId, step)
    override fun setStepVelocity(channelId: Int, step: Int, velocity: Int, final: Boolean) =
        vm.setStepVelocity(channelId, step, velocity, final)
    override fun selectChannel(channelId: Int) = vm.selectChannel(channelId)
    override fun toggleMute(channelId: Int) = vm.toggleMute(channelId)
    override fun toggleSolo(channelId: Int) = vm.toggleSolo(channelId)
    override fun openChannelSettings(channelId: Int) = openSettings(channelId)
    override fun addChannel() = addChannel()
}

private fun padActions(vm: StudioViewModel) = object : PadActions {
    override fun padDown(channelId: Int, pitch: Int, velocity: Int) = vm.padDown(channelId, pitch, velocity)
    override fun padUp(channelId: Int, pitch: Int) = vm.padUp(channelId, pitch)
    override fun setMode(mode: PadMode) = vm.setPadMode(mode)
    override fun setRepeat(rate: RepeatRate) = vm.setRepeat(rate)
    override fun toggleRecord() = vm.toggleRecord()
    override fun selectChannel(channelId: Int) = vm.setKeysChannel(channelId)
    override fun shiftOctave(delta: Int) = vm.shiftOctave(delta)
}

private fun pianoRollActions(vm: StudioViewModel, channelId: Int?) = object : PianoRollActions {
    override fun addNote(note: Note) { channelId?.let { vm.addNote(it, note) } }
    override fun removeNote(note: Note) { channelId?.let { vm.removeNote(it, note) } }
    override fun replaceNote(old: Note, new: Note) { channelId?.let { vm.replaceNote(it, old, new) } }
    override fun replaceNotes(changes: List<Pair<Note, Note>>) { channelId?.let { vm.replaceNotes(it, changes) } }
    override fun previewNote(pitch: Int) { channelId?.let { vm.previewNote(it, pitch) } }
    override fun setSnap(snap: Snap) = vm.setSnap(snap)
    override fun setScale(scale: ScaleSetting) = vm.setScale(scale)
}
