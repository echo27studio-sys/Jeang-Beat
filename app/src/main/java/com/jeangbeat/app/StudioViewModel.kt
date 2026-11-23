package com.jeangbeat.app

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jeangbeat.core.data.ProjectInfo
import com.jeangbeat.core.model.Channel
import com.jeangbeat.core.model.DefaultProjects
import com.jeangbeat.core.model.Instrument
import com.jeangbeat.core.model.Note
import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.RepeatRate
import com.jeangbeat.core.model.ScaleSetting
import com.jeangbeat.core.model.Snap
import com.jeangbeat.core.model.Timing
import com.jeangbeat.core.model.UndoHistory
import com.jeangbeat.core.model.addChannel
import com.jeangbeat.core.model.addNote
import com.jeangbeat.core.model.addPattern
import com.jeangbeat.core.model.clearChannel
import com.jeangbeat.core.model.defaultPitch
import com.jeangbeat.core.model.deletePattern
import com.jeangbeat.core.model.isMelodic
import com.jeangbeat.core.model.recordHit
import com.jeangbeat.core.model.removeChannel
import com.jeangbeat.core.model.removeNote
import com.jeangbeat.core.model.renamePattern
import com.jeangbeat.core.model.replaceNote
import com.jeangbeat.core.model.selectPattern
import com.jeangbeat.core.model.setPatternLength
import com.jeangbeat.core.model.setStepVelocity
import com.jeangbeat.core.model.toggleStep
import com.jeangbeat.core.model.updateChannel
import com.jeangbeat.core.model.withSwing
import com.jeangbeat.core.model.withTempo
import com.jeangbeat.feature.pads.PadMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jeangbeat.core.ui.R as UiR

data class StudioState(
    val project: Project,
    val fileName: String,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selectedChannelId: Int? = null,
    val isPlaying: Boolean = false,
    val recording: Boolean = false,
    val repeatRate: RepeatRate = RepeatRate.OFF,
    val padMode: PadMode = PadMode.DRUMS,
    val keysChannelId: Int? = null,
    val octave: Int = 3,
    val snap: Snap = Snap.STEP,
    val audioOk: Boolean = true,
    val projects: List<ProjectInfo> = emptyList(),
) {
    val selectedChannel: Channel? get() = selectedChannelId?.let(project::channel)
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as JeangBeatApp
    private val engine = app.engine
    private val store = app.store
    private val settings = app.settings

    private val history = UndoHistory(DefaultProjects.empty("…"))
    private var gestureBase: Project? = null
    private var saveJob: Job? = null
    private var initialised = false

    private val _state = MutableStateFlow(StudioState(project = history.present, fileName = ""))
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** One-shot user messages as string resource ids. */
    val messages: SharedFlow<Int> = _messages

    // ---- Lifecycle ----------------------------------------------------------

    /** Opens the last project, or creates the demo beat on first launch. */
    fun ensureProject(defaultName: String, patternName: String) {
        if (initialised) return
        initialised = true
        val last = settings.lastProjectFile
        val loaded = last?.let { runCatching { store.load(it) }.getOrNull() }
        if (loaded != null) {
            setProject(loaded, last)
        } else {
            val project = DefaultProjects.demo(defaultName, patternName)
            val file = store.uniqueFileNameFor(defaultName)
            setProject(project, file)
            scheduleSave(immediate = true)
        }
    }

    fun onForeground() {
        val ok = engine.start()
        engine.load(history.present)
        _state.update { it.copy(audioOk = ok) }
        if (!ok) message(UiR.string.audio_error)
    }

    fun onBackground() {
        stop()
        engine.pause()
        saveNow()
    }

    override fun onCleared() {
        saveNow()
        super.onCleared()
    }

    // ---- Editing & undo -----------------------------------------------------

    /** Applies an edit as one undo step. */
    fun edit(transform: (Project) -> Project) {
        commitGesture()
        val next = transform(history.present)
        if (next == history.present) return
        history.push(next)
        publish()
    }

    /**
     * Applies an in-progress edit (e.g. while a slider is dragged) without an
     * undo step. Call [commitGesture] when the gesture ends.
     */
    fun editTransient(transform: (Project) -> Project) {
        if (gestureBase == null) gestureBase = history.present
        history.replace(transform(history.present))
        publish(save = false)
    }

    /** Turns the edits since the gesture started into a single undo step. */
    fun commitGesture() {
        val base = gestureBase ?: return
        gestureBase = null
        val final = history.present
        history.replace(base)
        history.push(final)
        publish()
    }

    fun undo() {
        commitGesture()
        history.undo()
        publish()
    }

    fun redo() {
        commitGesture()
        history.redo()
        publish()
    }

    private fun publish(save: Boolean = true) {
        val project = history.present
        engine.load(project)
        _state.update { s ->
            val selected = s.selectedChannelId?.takeIf { project.channel(it) != null } ?: project.channels.firstOrNull()?.id
            val keys = s.keysChannelId?.takeIf { project.channel(it) != null }
                ?: project.channels.firstOrNull { it.instrument.isMelodic }?.id
            s.copy(
                project = project,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                selectedChannelId = selected,
                keysChannelId = keys,
            )
        }
        if (save) scheduleSave()
    }

    private fun setProject(project: Project, fileName: String) {
        stop()
        gestureBase = null
        history.reset(project)
        settings.lastProjectFile = fileName
        _state.update { it.copy(fileName = fileName, selectedChannelId = null, keysChannelId = null) }
        publish(save = false)
    }

    // ---- Transport ----------------------------------------------------------

    fun togglePlay() = if (engine.isPlaying || _state.value.isPlaying) stop() else play()

    fun play() {
        engine.load(history.present)
        engine.play()
        _state.update { it.copy(isPlaying = true) }
    }

    fun stop() {
        engine.stop()
        _state.update { it.copy(isPlaying = false) }
    }

    fun toggleRecord() = _state.update { it.copy(recording = !it.recording) }

    fun positionTicks(): Float = engine.positionTicks

    fun setTempo(bpm: Float, final: Boolean) = if (final) edit { it.withTempo(bpm) } else editTransient { it.withTempo(bpm) }

    fun setSwing(amount: Float, final: Boolean) =
        if (final) edit { it.withSwing(amount) } else editTransient { it.withSwing(amount) }

    // ---- Pads -----------------------------------------------------------------

    fun padDown(channelId: Int, pitch: Int, velocity: Int) {
        engine.padDown(channelId, pitch, velocity)
        val s = _state.value
        if (s.recording && s.isPlaying) {
            // The player hears audio `latency` late, so their hit belongs slightly earlier.
            val latencyTicks = (engine.latencyMillis ?: 0.0) / 60000.0 * s.project.tempo * Timing.TICKS_PER_BEAT
            val tick = (engine.positionTicks - latencyTicks).toInt()
            val grid = if (s.repeatRate != RepeatRate.OFF) s.repeatRate.ticks else Timing.TICKS_PER_STEP
            edit { it.recordHit(channelId, tick, pitch, velocity, grid) }
        }
    }

    fun padUp(channelId: Int, pitch: Int) = engine.padUp(channelId, pitch)

    fun setPadMode(mode: PadMode) = _state.update { it.copy(padMode = mode) }

    fun setRepeat(rate: RepeatRate) {
        engine.setRepeat(rate)
        _state.update { it.copy(repeatRate = rate) }
    }

    fun setKeysChannel(channelId: Int) = _state.update { it.copy(keysChannelId = channelId) }

    fun shiftOctave(delta: Int) = _state.update { it.copy(octave = (it.octave + delta).coerceIn(0, 7)) }

    // ---- Channels -------------------------------------------------------------

    fun selectChannel(channelId: Int, preview: Boolean = true) {
        _state.update { it.copy(selectedChannelId = channelId) }
        val channel = history.present.channel(channelId) ?: return
        if (preview) previewNote(channelId, channel.instrument.defaultPitch)
    }

    fun previewNote(channelId: Int, pitch: Int) {
        engine.noteOn(channelId, pitch, 100)
        viewModelScope.launch {
            delay(PREVIEW_MILLIS)
            engine.noteOff(channelId, pitch)
        }
    }

    fun addChannel(name: String, instrument: Instrument) {
        edit { it.addChannel(name, instrument) }
        history.present.channels.lastOrNull()?.let { selectChannel(it.id) }
    }

    fun removeChannel(channelId: Int) = edit { it.removeChannel(channelId) }

    fun clearChannel(channelId: Int) = edit { it.clearChannel(channelId) }

    fun toggleMute(channelId: Int) = edit { p -> p.updateChannel(channelId) { it.copy(muted = !it.muted) } }

    fun toggleSolo(channelId: Int) = edit { p -> p.updateChannel(channelId) { it.copy(solo = !it.solo) } }

    fun renameChannel(channelId: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) edit { p -> p.updateChannel(channelId) { it.copy(name = trimmed) } }
    }

    /** Live channel edit from a slider; [final] marks the end of the drag. */
    fun updateChannel(channelId: Int, final: Boolean, transform: (Channel) -> Channel) {
        if (final) {
            edit { it.updateChannel(channelId, transform) }
        } else {
            editTransient { it.updateChannel(channelId, transform) }
        }
    }

    // ---- Steps & notes ----------------------------------------------------------

    fun toggleStep(channelId: Int, step: Int) {
        edit { it.toggleStep(channelId, step) }
        val channel = history.present.channel(channelId) ?: return
        if (!_state.value.isPlaying && history.present.currentPattern.notesFor(channelId).any { it.startTick == step * Timing.TICKS_PER_STEP }) {
            previewNote(channelId, channel.instrument.defaultPitch)
        }
    }

    fun setStepVelocity(channelId: Int, step: Int, velocity: Int, final: Boolean) {
        if (final) {
            editTransient { it.setStepVelocity(channelId, step, velocity) }
            commitGesture()
        } else {
            editTransient { it.setStepVelocity(channelId, step, velocity) }
        }
    }

    fun addNote(channelId: Int, note: Note) = edit { it.addNote(channelId, note) }

    fun removeNote(channelId: Int, note: Note) = edit { it.removeNote(channelId, note) }

    fun replaceNote(channelId: Int, old: Note, new: Note) = edit { it.replaceNote(channelId, old, new) }

    fun replaceNotes(channelId: Int, changes: List<Pair<Note, Note>>) = edit { project ->
        changes.fold(project) { p, (old, new) -> p.replaceNote(channelId, old, new) }
    }

    fun setSnap(snap: Snap) = _state.update { it.copy(snap = snap) }

    fun setScale(scale: ScaleSetting) = edit { it.copy(scale = scale) }

    // ---- Patterns ---------------------------------------------------------------

    fun selectPattern(patternId: Int) = edit { it.selectPattern(patternId) }

    fun addPattern(name: String, copyCurrent: Boolean) = edit { it.addPattern(name, copyCurrent) }

    fun deletePattern(patternId: Int) = edit { it.deletePattern(patternId) }

    fun renamePattern(patternId: Int, name: String) = edit { it.renamePattern(patternId, name) }

    fun setPatternLength(steps: Int) = edit { it.setPatternLength(steps) }

    // ---- Projects -----------------------------------------------------------------

    fun refreshProjects() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.list() }
            _state.update { it.copy(projects = list) }
        }
    }

    fun newProject(name: String, patternName: String, withDemo: Boolean) {
        saveNow()
        val project = if (withDemo) DefaultProjects.demo(name, patternName) else DefaultProjects.empty(name, patternName)
        setProject(project, store.uniqueFileNameFor(name))
        scheduleSave(immediate = true)
    }

    fun openProject(fileName: String) {
        saveNow()
        val project = runCatching { store.load(fileName) }.getOrElse {
            message(UiR.string.load_error)
            return
        }
        setProject(project, fileName)
    }

    fun saveAs(name: String) {
        val trimmed = name.trim().ifEmpty { return }
        val fileName = store.uniqueFileNameFor(trimmed)
        history.replace(history.present.copy(name = trimmed))
        _state.update { it.copy(fileName = fileName) }
        settings.lastProjectFile = fileName
        publish(save = false)
        scheduleSave(immediate = true)
        message(UiR.string.saved)
    }

    fun deleteProject(fileName: String) {
        if (fileName == _state.value.fileName) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(fileName) }
            refreshProjects()
        }
    }

    private fun scheduleSave(immediate: Boolean = false) {
        saveJob?.cancel()
        val project = history.present
        val fileName = _state.value.fileName
        saveJob = viewModelScope.launch {
            if (!immediate) delay(AUTOSAVE_DELAY_MILLIS)
            withContext(Dispatchers.IO) { runCatching { store.save(project, fileName) } }
        }
    }

    private fun saveNow() {
        commitGesture()
        saveJob?.cancel()
        val fileName = _state.value.fileName
        if (fileName.isNotEmpty()) runCatching { store.save(history.present, fileName) }
    }

    private fun message(@StringRes id: Int) {
        _messages.tryEmit(id)
    }

    companion object {
        private const val AUTOSAVE_DELAY_MILLIS = 1500L
        private const val PREVIEW_MILLIS = 250L
    }
}
