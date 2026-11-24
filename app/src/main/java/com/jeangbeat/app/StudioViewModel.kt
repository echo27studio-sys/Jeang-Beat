package com.jeangbeat.app

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jeangbeat.app.io.ExportFormat
import com.jeangbeat.app.io.Exporter
import com.jeangbeat.app.io.SampleLibrary
import com.jeangbeat.core.audio.ENGINE_MIXER_TRACKS
import com.jeangbeat.core.audio.PlayMode
import com.jeangbeat.core.data.ProjectBundle
import com.jeangbeat.core.data.ProjectInfo
import com.jeangbeat.core.model.AutoPoint
import com.jeangbeat.core.model.AutomationTarget
import com.jeangbeat.core.model.Channel
import com.jeangbeat.core.model.Clip
import com.jeangbeat.core.model.DefaultProjects
import com.jeangbeat.core.model.EffectType
import com.jeangbeat.core.model.Instrument
import com.jeangbeat.core.model.Meter
import com.jeangbeat.core.model.Note
import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.ScaleSetting
import com.jeangbeat.core.model.Snap
import com.jeangbeat.core.model.Timing
import com.jeangbeat.core.model.UndoHistory
import com.jeangbeat.core.model.addAudioClip
import com.jeangbeat.core.model.addAutomationClip
import com.jeangbeat.core.model.addChannel
import com.jeangbeat.core.model.addEffect
import com.jeangbeat.core.model.addPattern
import com.jeangbeat.core.model.addPatternClip
import com.jeangbeat.core.model.addPlaylistTrack
import com.jeangbeat.core.model.addSample
import com.jeangbeat.core.model.clearChannel
import com.jeangbeat.core.model.clearLoop
import com.jeangbeat.core.model.defaultPitch
import com.jeangbeat.core.model.deleteClip
import com.jeangbeat.core.model.deletePattern
import com.jeangbeat.core.model.duplicateClip
import com.jeangbeat.core.model.editNotes
import com.jeangbeat.core.model.makeClipUnique
import com.jeangbeat.core.model.moveClip
import com.jeangbeat.core.model.moveEffect
import com.jeangbeat.core.model.removeChannel
import com.jeangbeat.core.model.removeEffect
import com.jeangbeat.core.model.removePlaylistTrack
import com.jeangbeat.core.model.renamePattern
import com.jeangbeat.core.model.resizeClip
import com.jeangbeat.core.model.rotateChannel
import com.jeangbeat.core.model.selectPattern
import com.jeangbeat.core.model.setAutomationPoints
import com.jeangbeat.core.model.setChannelMixerTrack
import com.jeangbeat.core.model.setChannelSteps
import com.jeangbeat.core.model.setEffectParam
import com.jeangbeat.core.model.setLoop
import com.jeangbeat.core.model.setPatternBars
import com.jeangbeat.core.model.setPatternMeter
import com.jeangbeat.core.model.setSend
import com.jeangbeat.core.model.setSidechain
import com.jeangbeat.core.model.sliceClip
import com.jeangbeat.core.model.toggleClipMute
import com.jeangbeat.core.model.toggleStep
import com.jeangbeat.core.model.updateChannel
import com.jeangbeat.core.model.updateClip
import com.jeangbeat.core.model.updateEffect
import com.jeangbeat.core.model.updateMixerTrack
import com.jeangbeat.core.model.updatePlaylistTrack
import com.jeangbeat.core.model.updateStepNote
import com.jeangbeat.core.model.withSwing
import com.jeangbeat.core.model.withTempo
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
import java.util.concurrent.atomic.AtomicBoolean
import com.jeangbeat.core.ui.R as UiR

enum class StudioTab { PLAYLIST, PATTERN, MIXER }

/** Where imported audio goes. */
enum class AudioTarget { PLAYLIST, CHANNEL }

data class StudioState(
    val project: Project,
    val fileName: String,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val mode: PlayMode = PlayMode.PATTERN,
    val isPlaying: Boolean = false,
    val tab: StudioTab = StudioTab.PATTERN,
    /** Channel whose Piano Roll is open inside the Pattern tab. */
    val pianoRollChannelId: Int? = null,
    val paintPatternId: Int = 0,
    val playlistSnap: Snap = Snap.BAR,
    val rollSnap: Snap = Snap.STEP,
    val selectedMixerTrack: Int = 0,
    /** Song marker (SONG mode start position). */
    val songPosition: Int = 0,
    val audioOk: Boolean = true,
    val projects: List<ProjectInfo> = emptyList(),
    /** 0..1 while an export runs, else null. */
    val exportProgress: Float? = null,
    val busy: Boolean = false,
    val waveforms: Map<Int, FloatArray> = emptyMap(),
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as JeangBeatApp
    private val engine = app.engine
    private val store = app.store
    private val settings = app.settings
    val samples = SampleLibrary(application)
    private val exporter = Exporter(application, samples)

    private val history = UndoHistory(DefaultProjects.empty("…"))
    private var gestureBase: Project? = null
    private var saveJob: Job? = null
    private var exportCancel = AtomicBoolean(false)
    private var initialised = false
    private var clipboard: List<Note> = emptyList()

    private val _state = MutableStateFlow(StudioState(project = history.present, fileName = ""))
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** One-shot user messages as string resource ids. */
    val messages: SharedFlow<Int> = _messages

    private val project: Project get() = history.present

    // ---- Lifecycle ----------------------------------------------------------

    fun ensureProject(defaultName: String, patternName: String) {
        if (initialised) return
        initialised = true
        val last = settings.lastProjectFile
        val loaded = last?.let { runCatching { store.load(it) }.getOrNull() }
        if (loaded != null) {
            setProject(loaded, last)
        } else {
            setProject(DefaultProjects.demo(defaultName, patternName), store.uniqueFileNameFor(defaultName))
            scheduleSave(immediate = true)
        }
    }

    fun onForeground() {
        val ok = engine.start()
        pushToEngine()
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

    /** In-progress edit (dragging): no undo step until [commitGesture]. */
    fun editTransient(transform: (Project) -> Project) {
        if (gestureBase == null) gestureBase = history.present
        history.replace(transform(history.present))
        publish(save = false)
    }

    /** Transient while [final] is false; the final call turns the whole gesture into one undo step. */
    private fun editGesture(final: Boolean, transform: (Project) -> Project) {
        if (final) {
            editTransient(transform)
            commitGesture()
        } else {
            editTransient(transform)
        }
    }

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

    private fun pushToEngine() = engine.load(history.present, _state.value.mode)

    private fun publish(save: Boolean = true) {
        val p = history.present
        pushToEngine()
        _state.update { s ->
            s.copy(
                project = p,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                pianoRollChannelId = s.pianoRollChannelId?.takeIf { p.channel(it) != null },
                paintPatternId = s.paintPatternId.takeIf { p.pattern(it) != null } ?: p.currentPatternId,
            )
        }
        if (save) scheduleSave()
    }

    private fun setProject(p: Project, fileName: String) {
        stop()
        gestureBase = null
        history.reset(p)
        settings.lastProjectFile = fileName
        _state.update { it.copy(fileName = fileName, pianoRollChannelId = null, paintPatternId = p.currentPatternId, songPosition = 0, waveforms = emptyMap()) }
        // Sample ids are per project: drop the previous project's audio first.
        engine.unloadAllSamples()
        engine.setPosition(0)
        publish(save = false)
        loadSamples(p)
    }

    /** Loads the project's audio files into the engine (off the main thread) and builds waveforms. */
    private fun loadSamples(p: Project) {
        viewModelScope.launch {
            val waves = HashMap<Int, FloatArray>()
            withContext(Dispatchers.IO) {
                for (ref in p.samples) {
                    val data = samples.load(ref) ?: continue
                    engine.loadSample(ref.id, data.samples, data.channels, data.sampleRate)
                    waves[ref.id] = samples.peaks(data)
                }
            }
            _state.update { it.copy(waveforms = it.waveforms + waves) }
            pushToEngine()
        }
    }

    // ---- Navigation -----------------------------------------------------------

    fun setTab(tab: StudioTab) = _state.update { it.copy(tab = tab) }

    fun openPianoRoll(channelId: Int) = _state.update { it.copy(tab = StudioTab.PATTERN, pianoRollChannelId = channelId) }

    fun closePianoRoll() {
        commitGesture()
        _state.update { it.copy(pianoRollChannelId = null) }
    }

    // ---- Transport ------------------------------------------------------------

    fun setMode(mode: PlayMode) {
        _state.update { it.copy(mode = mode) }
        pushToEngine()
    }

    fun togglePlay() = if (_state.value.isPlaying) stop() else play()

    fun play() {
        pushToEngine()
        engine.play()
        _state.update { it.copy(isPlaying = true) }
    }

    fun stop() {
        engine.stop()
        _state.update { it.copy(isPlaying = false) }
    }

    fun positionTicks(): Float = engine.positionTicks

    fun takeMeters(out: FloatArray) = engine.takeMeters(out)

    fun setSongPosition(tick: Int) {
        engine.setPosition(tick)
        _state.update { it.copy(songPosition = tick) }
    }

    fun setTempo(bpm: Float, final: Boolean) = editGesture(final) { it.withTempo(bpm) }

    fun setSwing(amount: Float, final: Boolean) = editGesture(final) { it.withSwing(amount) }

    // ---- Pattern tab (channel rack, ring) ----------------------------------------

    fun previewNote(channelId: Int, pitch: Int) {
        engine.noteOn(channelId, pitch, 100)
        viewModelScope.launch {
            delay(PREVIEW_MILLIS)
            engine.noteOff(channelId, pitch)
        }
    }

    fun previewChannel(channelId: Int) {
        project.channel(channelId)?.let { previewNote(channelId, it.instrument.defaultPitch) }
    }

    fun toggleStep(channelId: Int, step: Int) {
        edit { it.toggleStep(channelId, step) }
        val p = project.currentPattern
        if (!_state.value.isPlaying && p.notesFor(channelId).any { it.startTick == p.stepTick(step) }) previewChannel(channelId)
    }

    fun updateStepNote(channelId: Int, step: Int, final: Boolean, transform: (Note) -> Note) =
        editGesture(final) { it.updateStepNote(channelId, step, transform) }

    fun toggleMute(channelId: Int) = edit { p -> p.updateChannel(channelId) { it.copy(muted = !it.muted) } }

    fun toggleSolo(channelId: Int) = edit { p -> p.updateChannel(channelId) { it.copy(solo = !it.solo) } }

    fun setChannelVolume(channelId: Int, v: Float, final: Boolean) = editGesture(final) { p -> p.updateChannel(channelId) { it.copy(volume = v) } }

    fun setChannelPan(channelId: Int, v: Float, final: Boolean) = editGesture(final) { p -> p.updateChannel(channelId) { it.copy(pan = v) } }

    fun setChannelInsert(channelId: Int, insert: Int) = edit { it.setChannelMixerTrack(channelId, insert) }

    fun updateChannel(channelId: Int, final: Boolean, transform: (Channel) -> Channel) =
        editGesture(final) { it.updateChannel(channelId, transform) }

    fun renameChannel(channelId: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) edit { p -> p.updateChannel(channelId) { it.copy(name = trimmed) } }
    }

    fun addChannel(name: String, instrument: Instrument) = edit { it.addChannel(name, instrument) }

    fun removeChannel(channelId: Int) = edit { it.removeChannel(channelId) }

    fun clearChannel(channelId: Int) = edit { it.clearChannel(channelId) }

    fun selectPattern(patternId: Int) = edit { it.selectPattern(patternId) }

    fun addPattern(name: String, copyCurrent: Boolean) = edit { it.addPattern(name, copyCurrent) }

    fun deletePattern() = edit { it.deletePattern(it.currentPatternId) }

    fun renamePattern(patternId: Int, name: String) = edit { it.renamePattern(patternId, name) }

    fun setPatternBars(bars: Int) = edit { it.setPatternBars(bars) }

    fun setMeter(meter: Meter) = edit { it.setPatternMeter(meter) }

    fun setChannelSteps(channelId: Int, steps: List<Int>) = edit { it.setChannelSteps(channelId, steps) }

    fun rotateChannel(channelId: Int, steps: Int, final: Boolean) =
        editGesture(final) { if (steps == 0) it else it.rotateChannel(channelId, steps) }

    // ---- Piano roll -----------------------------------------------------------------

    fun editNotes(channelId: Int, final: Boolean, transform: (List<Note>) -> List<Note>) =
        editGesture(final) { it.editNotes(channelId, transform) }

    fun setRollSnap(snap: Snap) = _state.update { it.copy(rollSnap = snap) }

    fun setScale(scale: ScaleSetting) = edit { it.copy(scale = scale) }

    fun copyNotes(notes: List<Note>) {
        clipboard = notes
    }

    fun clipboard(): List<Note> = clipboard

    // ---- Playlist -------------------------------------------------------------------

    fun setPaintPattern(patternId: Int) = _state.update { it.copy(paintPatternId = patternId) }

    fun setPlaylistSnap(snap: Snap) = _state.update { it.copy(playlistSnap = snap) }

    fun addPatternClip(trackId: Int, startTick: Int, patternId: Int, final: Boolean) {
        if (startTick < 0) {
            commitGesture()
            return
        }
        editGesture(final) { it.addPatternClip(trackId, startTick, patternId) }
    }

    fun moveClip(clipId: Int, start: Int, trackId: Int, final: Boolean) = editGesture(final) { it.moveClip(clipId, start, trackId) }

    fun resizeClip(clipId: Int, length: Int, final: Boolean) = editGesture(final) { it.resizeClip(clipId, length) }

    fun deleteClip(clipId: Int, final: Boolean) {
        if (clipId < 0) {
            commitGesture()
            return
        }
        editGesture(final) { it.deleteClip(clipId) }
    }

    fun toggleClipMute(clipId: Int) = edit { it.toggleClipMute(clipId) }

    fun sliceClip(clipId: Int, atTick: Int) = edit { it.sliceClip(clipId, atTick) }

    fun duplicateClip(clipId: Int) = edit { it.duplicateClip(clipId) }

    fun makeUnique(clipId: Int, name: String) = edit { it.makeClipUnique(clipId, name) }

    fun openPatternClip(clip: Clip.PatternClip) {
        edit { it.selectPattern(clip.patternId) }
        _state.update { it.copy(tab = StudioTab.PATTERN, pianoRollChannelId = null) }
    }

    fun setLoop(start: Int, end: Int) = edit { it.setLoop(start, end) }

    fun clearLoop() = edit { it.clearLoop() }

    fun addTrack() = edit { it.addPlaylistTrack() }

    fun toggleTrackMute(trackId: Int) = edit { p -> p.updatePlaylistTrack(trackId) { it.copy(muted = !it.muted) } }

    fun toggleTrackSolo(trackId: Int) = edit { p -> p.updatePlaylistTrack(trackId) { it.copy(solo = !it.solo) } }

    fun renameTrack(trackId: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) edit { p -> p.updatePlaylistTrack(trackId) { it.copy(name = trimmed) } }
    }

    fun removeTrack(trackId: Int) = edit { it.removePlaylistTrack(trackId) }

    fun setClipInsert(clipId: Int, insert: Int) = edit { p ->
        p.updateClip(clipId) { if (it is Clip.AudioClip) it.copy(mixerTrack = insert) else it }
    }

    fun setAutomationPoints(clipId: Int, points: List<AutoPoint>, final: Boolean) =
        editGesture(final) { it.setAutomationPoints(clipId, points) }

    /** Adds an automation clip (4 bars) at the song marker, on the first free track. */
    fun automate(target: AutomationTarget, value: Float) {
        val start = Timing.floorTo(_state.value.songPosition, Timing.TICKS_PER_BAR)
        val length = 4 * Timing.TICKS_PER_BAR
        edit { p0 ->
            var p = p0
            fun free(trackId: Int) = p.song.clips.none { it.trackId == trackId && it.startTick < start + length && it.endTick > start }
            var track = p.song.tracks.lastOrNull { free(it.id) && p.song.clips.none { c -> c.trackId == it.id } }?.id
            if (track == null) {
                p = p.addPlaylistTrack()
                track = p.song.tracks.last().id
            }
            p.addAutomationClip(track, start, length, target, value.coerceIn(0f, 1f))
        }
        message(UiR.string.automation_created)
    }

    // ---- Mixer -----------------------------------------------------------------------

    fun selectMixerTrack(index: Int) = _state.update { it.copy(selectedMixerTrack = index) }

    fun setMixerVolume(track: Int, v: Float, final: Boolean) = editGesture(final) { p -> p.updateMixerTrack(track) { it.copy(volume = v) } }

    fun setMixerPan(track: Int, v: Float, final: Boolean) = editGesture(final) { p -> p.updateMixerTrack(track) { it.copy(pan = v) } }

    fun toggleMixerMute(track: Int) = edit { p -> p.updateMixerTrack(track) { it.copy(muted = !it.muted) } }

    fun toggleMixerSolo(track: Int) = edit { p -> p.updateMixerTrack(track) { it.copy(solo = !it.solo) } }

    fun renameMixerTrack(track: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) edit { p -> p.updateMixerTrack(track) { it.copy(name = trimmed) } }
    }

    fun addEffect(track: Int, type: EffectType) = edit { it.addEffect(track, type) }

    fun removeEffect(track: Int, slotId: Int) = edit { it.removeEffect(track, slotId) }

    fun moveEffect(track: Int, slotId: Int, to: Int) = edit { it.moveEffect(track, slotId, to) }

    fun setEffectEnabled(track: Int, slotId: Int, enabled: Boolean) = edit { p -> p.updateEffect(track, slotId) { it.copy(enabled = enabled) } }

    fun setEffectMix(track: Int, slotId: Int, mix: Float, final: Boolean) =
        editGesture(final) { p -> p.updateEffect(track, slotId) { it.copy(mix = mix.coerceIn(0f, 1f)) } }

    fun setEffectParam(track: Int, slotId: Int, param: Int, value: Float, final: Boolean) =
        editGesture(final) { it.setEffectParam(track, slotId, param, value) }

    fun setSidechain(track: Int, slotId: Int, source: Int) {
        val before = project
        edit { it.setSidechain(track, slotId, source) }
        if (project == before && source > 0) message(UiR.string.route_loop)
    }

    fun setSend(from: Int, to: Int, level: Float, final: Boolean) = editGesture(final) { it.setSend(from, to, level) }

    fun setToMaster(track: Int, on: Boolean) = edit { p -> p.updateMixerTrack(track) { it.copy(toMaster = on) } }

    // ---- Audio import ------------------------------------------------------------------

    fun importAudio(uri: Uri, target: AudioTarget) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ref = samples.import(uri)
                    val data = samples.load(ref)
                    ref to data
                }
            }
            _state.update { it.copy(busy = false) }
            val (ref, data) = result.getOrElse {
                message(UiR.string.import_failed)
                return@launch
            }
            if (data == null) {
                message(UiR.string.import_failed)
                return@launch
            }
            val id = project.nextId
            engine.loadSample(id, data.samples, data.channels, data.sampleRate)
            _state.update { it.copy(waveforms = it.waveforms + (id to samples.peaks(data))) }
            edit { p0 ->
                val p = p0.addSample(ref)
                when (target) {
                    AudioTarget.CHANNEL -> p.addChannel(ref.name, Instrument.Sampler(id))
                    AudioTarget.PLAYLIST -> {
                        val ticks = (ref.seconds * p.tempo / 60.0 * Timing.TICKS_PER_BEAT).toInt().coerceAtLeast(1)
                        val start = _state.value.songPosition
                        val track = p.song.tracks.firstOrNull { t -> p.song.clips.none { it.trackId == t.id && it.startTick < start + ticks && it.endTick > start } }?.id
                            ?: p.song.tracks.last().id
                        p.addAudioClip(track, start, id, ticks)
                    }
                }
            }
            message(UiR.string.audio_imported)
        }
    }

    // ---- Export / project files ----------------------------------------------------------

    fun exportAudio(uri: Uri, mode: PlayMode, format: ExportFormat) {
        commitGesture()
        val snapshot = project
        exportCancel = AtomicBoolean(false)
        val cancel = exportCancel
        _state.update { it.copy(exportProgress = 0f) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) {
                runCatching {
                    exporter.exportAudio(snapshot, mode, format, uri, { cancel.get() }) { p ->
                        _state.update { it.copy(exportProgress = p) }
                    }
                }.getOrDefault(false)
            }
            _state.update { it.copy(exportProgress = null) }
            if (!cancel.get()) message(if (ok) UiR.string.export_done else UiR.string.export_failed)
        }
    }

    fun cancelExport() = exportCancel.set(true)

    fun exportProject(uri: Uri) {
        saveNow()
        val snapshot = project
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { ProjectBundle.export(snapshot, samples.dir, it) }
                }.isSuccess
            }
            message(if (ok) UiR.string.export_done else UiR.string.export_failed)
        }
    }

    fun importProject(uri: Uri) {
        saveNow()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openInputStream(uri)!!.use { ProjectBundle.import(it, samples.dir) } }.getOrNull()
            }
            _state.update { it.copy(busy = false) }
            if (imported == null) {
                message(UiR.string.import_failed)
                return@launch
            }
            val fileName = store.uniqueFileNameFor(imported.name)
            setProject(imported, fileName)
            scheduleSave(immediate = true)
            message(UiR.string.project_imported)
        }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.list() }
            _state.update { it.copy(projects = list) }
        }
    }

    fun newProject(name: String, patternName: String, withDemo: Boolean) {
        saveNow()
        val p = if (withDemo) DefaultProjects.demo(name, patternName) else DefaultProjects.empty(name, patternName)
        setProject(p, store.uniqueFileNameFor(name))
        scheduleSave(immediate = true)
    }

    fun openProject(fileName: String) {
        saveNow()
        val p = runCatching { store.load(fileName) }.getOrElse {
            message(UiR.string.load_error)
            return
        }
        setProject(p, fileName)
    }

    fun saveAs(name: String) {
        val trimmed = name.trim().ifEmpty { return }
        val fileName = store.uniqueFileNameFor(trimmed)
        history.replace(project.copy(name = trimmed))
        _state.update { it.copy(fileName = fileName) }
        settings.lastProjectFile = fileName
        publish(save = false)
        scheduleSave(immediate = true)
        message(UiR.string.saved)
    }

    fun saveNowWithMessage() {
        saveNow()
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
        val p = project
        val fileName = _state.value.fileName
        saveJob = viewModelScope.launch {
            if (!immediate) delay(AUTOSAVE_DELAY_MILLIS)
            withContext(Dispatchers.IO) { runCatching { store.save(p, fileName) } }
        }
    }

    private fun saveNow() {
        commitGesture()
        saveJob?.cancel()
        val fileName = _state.value.fileName
        if (fileName.isNotEmpty()) runCatching { store.save(project, fileName) }
    }

    private fun message(@StringRes id: Int) {
        _messages.tryEmit(id)
    }

    companion object {
        private const val AUTOSAVE_DELAY_MILLIS = 1500L
        private const val PREVIEW_MILLIS = 250L
        const val METER_SIZE = ENGINE_MIXER_TRACKS * 2
    }
}
