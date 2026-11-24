package com.jeangbeat.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.jeangbeat.app.io.ExportFormat
import com.jeangbeat.app.ui.FileActions
import com.jeangbeat.app.ui.StudioScreen
import com.jeangbeat.core.audio.PlayMode
import com.jeangbeat.core.data.ProjectBundle
import com.jeangbeat.core.ui.JeangBeatTheme
import com.jeangbeat.core.ui.R as UiR

class MainActivity : ComponentActivity(), FileActions {
    private val viewModel: StudioViewModel by viewModels()

    // Pending choices while the system file picker is open.
    private var audioTarget = AudioTarget.PLAYLIST
    private var exportMode = PlayMode.SONG
    private var exportFormat = ExportFormat.WAV16

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.importAudio(it, audioTarget) }
    }
    private val createWav = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri -> uri?.let(::startExport) }
    private val createM4a = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri -> uri?.let(::startExport) }
    private val createZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(::startExport) }
    private val createBundle = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri?.let(viewModel::exportProject)
    }
    private val openBundle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::importProject)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Settings.wrap(newBase))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_AUDIO_TARGET, audioTarget.name)
        outState.putString(KEY_EXPORT_MODE, exportMode.name)
        outState.putString(KEY_EXPORT_FORMAT, exportFormat.name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The system file picker can outlive this activity (e.g. rotation): restore what was asked for.
        savedInstanceState?.let { b ->
            b.getString(KEY_AUDIO_TARGET)?.let { audioTarget = AudioTarget.valueOf(it) }
            b.getString(KEY_EXPORT_MODE)?.let { exportMode = PlayMode.valueOf(it) }
            b.getString(KEY_EXPORT_FORMAT)?.let { exportFormat = ExportFormat.valueOf(it) }
        }
        enableEdgeToEdge()
        viewModel.ensureProject(
            defaultName = getString(UiR.string.my_first_beat),
            patternName = getString(UiR.string.pattern_default_name, 1),
        )
        setContent {
            JeangBeatTheme {
                StudioScreen(
                    viewModel = viewModel,
                    files = this,
                    onLanguageChange = { language ->
                        (application as JeangBeatApp).settings.language = language
                        recreate()
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    // ---- FileActions ----

    override fun pickAudio(target: AudioTarget) {
        audioTarget = target
        openAudio.launch(arrayOf("audio/*"))
    }

    override fun exportAudio(mode: PlayMode, format: ExportFormat) {
        exportMode = mode
        exportFormat = format
        val base = fileBase() + if (mode == PlayMode.PATTERN) " (pattern)" else ""
        when (format) {
            ExportFormat.WAV16, ExportFormat.WAV24 -> createWav.launch("$base.wav")
            ExportFormat.M4A -> createM4a.launch("$base.m4a")
            ExportFormat.STEMS -> createZip.launch("$base stems.zip")
        }
    }

    override fun exportProject() = createBundle.launch(fileBase() + ProjectBundle.EXTENSION)

    override fun importProject() = openBundle.launch(arrayOf("*/*"))

    private fun startExport(uri: Uri) = viewModel.exportAudio(uri, exportMode, exportFormat)

    private companion object {
        const val KEY_AUDIO_TARGET = "audio_target"
        const val KEY_EXPORT_MODE = "export_mode"
        const val KEY_EXPORT_FORMAT = "export_format"
    }

    private fun fileBase(): String = viewModel.state.value.project.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Jeang Beat" }
}
