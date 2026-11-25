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
import com.jeangbeat.app.io.ExportOptions
import com.jeangbeat.app.io.exportBaseName
import com.jeangbeat.app.ui.FileActions
import com.jeangbeat.app.ui.StudioScreen
import com.jeangbeat.core.data.ProjectBundle
import com.jeangbeat.core.ui.JeangBeatTheme
import com.jeangbeat.core.ui.R as UiR

class MainActivity : ComponentActivity(), FileActions {
    private val viewModel: StudioViewModel by viewModels()

    // Pending choices while the system file picker is open.
    private var audioTarget = AudioTarget.PLAYLIST
    private var exportOptions = ExportOptions()

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.importAudio(it, audioTarget) }
    }
    private val createWav = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri -> uri?.let(::startExport) }
    private val createMp3 = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri -> uri?.let(::startExport) }
    private val createM4a = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri -> uri?.let(::startExport) }
    private val createZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(::startExport) }
    private val createBundle = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri?.let(viewModel::exportProject)
    }
    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel.browser::addFolder)
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
        outState.putSerializable(KEY_EXPORT_OPTIONS, exportOptions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The system file picker can outlive this activity (e.g. rotation): restore what was asked for.
        savedInstanceState?.let { b ->
            b.getString(KEY_AUDIO_TARGET)?.let { audioTarget = AudioTarget.valueOf(it) }
            @Suppress("DEPRECATION")
            (b.getSerializable(KEY_EXPORT_OPTIONS) as? ExportOptions)?.let { exportOptions = it }
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

    override fun exportAudio(options: ExportOptions) {
        exportOptions = options
        val base = exportBaseName(viewModel.state.value.project.name, options)
        when (options.format) {
            ExportFormat.WAV16, ExportFormat.WAV24, ExportFormat.WAV32F -> createWav.launch("$base.wav")
            ExportFormat.MP3 -> createMp3.launch("$base.mp3")
            ExportFormat.M4A -> createM4a.launch("$base.m4a")
            ExportFormat.STEMS -> createZip.launch("$base stems.zip")
        }
    }

    override fun exportProject() = createBundle.launch(fileBase() + ProjectBundle.EXTENSION)

    override fun importProject() = openBundle.launch(arrayOf("*/*"))

    override fun pickFolder() = openFolder.launch(null)

    private fun startExport(uri: Uri) = viewModel.exportAudio(uri, exportOptions)

    private companion object {
        const val KEY_AUDIO_TARGET = "audio_target"
        const val KEY_EXPORT_OPTIONS = "export_options"
    }

    private fun fileBase(): String = viewModel.state.value.project.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Jeang Beat" }
}
