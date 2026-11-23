package com.jeangbeat.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.jeangbeat.app.ui.ChannelPicker
import com.jeangbeat.core.model.DefaultProjects
import com.jeangbeat.core.model.Note
import com.jeangbeat.core.model.RepeatRate
import com.jeangbeat.core.model.ScaleSetting
import com.jeangbeat.core.model.Snap
import com.jeangbeat.core.ui.JbColors
import com.jeangbeat.core.ui.JeangBeatTheme
import com.jeangbeat.feature.pads.PadActions
import com.jeangbeat.feature.pads.PadMode
import com.jeangbeat.feature.pads.PadsScreen
import com.jeangbeat.feature.pianoroll.PianoRoll
import com.jeangbeat.feature.pianoroll.PianoRollActions
import com.jeangbeat.feature.sequencer.StepSequencer
import com.jeangbeat.feature.sequencer.StepSequencerActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens on the JVM and writes PNGs to app/build/screenshots,
 * so layouts can be checked without a device. Not a pixel-diff test (yet).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val demo = DefaultProjects.demo("Demo")

    private fun shoot(name: String, content: @Composable ColumnScope.() -> Unit) {
        compose.setContent {
            JeangBeatTheme {
                Column(Modifier.fillMaxSize().background(JbColors.Background)) { content() }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun stepSequencerPhone() = shoot("steps_phone") {
        StepSequencer(demo.channels, demo.currentPattern, selectedChannelId = 1, playheadStep = 4, actions = NoSteps)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun padsPhone() = shoot("pads_phone") {
        PadsScreen(demo.channels, PadMode.DRUMS, null, demo.scale, 3, RepeatRate.STEP, recording = true, actions = NoPads)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun padsKeysScaleLock() = shoot("pads_keys_phone") {
        PadsScreen(demo.channels, PadMode.KEYS, 9, ScaleSetting(lock = true), 3, RepeatRate.OFF, recording = false, actions = NoPads)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun pianoRollPhone() = shoot("pianoroll_phone") {
        val keys = demo.channel(9)
        ChannelPicker(demo.channels, 9) {}
        PianoRoll(
            channel = keys,
            pattern = demo.currentPattern,
            ghostNotes = demo.currentPattern.notesFor(8),
            scale = demo.scale,
            snap = Snap.STEP,
            playheadTick = 100f,
            actions = NoRoll,
            modifier = Modifier.weight(1f),
        )
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xxhdpi-land")
    fun stepSequencerLandscape() = shoot("steps_landscape") {
        StepSequencer(demo.channels, demo.currentPattern, selectedChannelId = 8, playheadStep = null, actions = NoSteps)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi-km")
    fun padsKhmer() = shoot("pads_khmer") {
        PadsScreen(demo.channels, PadMode.DRUMS, null, demo.scale, 3, RepeatRate.OFF, recording = false, actions = NoPads)
    }

    private object NoSteps : StepSequencerActions {
        override fun toggleStep(channelId: Int, step: Int) = Unit
        override fun setStepVelocity(channelId: Int, step: Int, velocity: Int, final: Boolean) = Unit
        override fun selectChannel(channelId: Int) = Unit
        override fun toggleMute(channelId: Int) = Unit
        override fun toggleSolo(channelId: Int) = Unit
        override fun openChannelSettings(channelId: Int) = Unit
        override fun addChannel() = Unit
    }

    private object NoPads : PadActions {
        override fun padDown(channelId: Int, pitch: Int, velocity: Int) = Unit
        override fun padUp(channelId: Int, pitch: Int) = Unit
        override fun setMode(mode: PadMode) = Unit
        override fun setRepeat(rate: RepeatRate) = Unit
        override fun toggleRecord() = Unit
        override fun selectChannel(channelId: Int) = Unit
        override fun shiftOctave(delta: Int) = Unit
    }

    private object NoRoll : PianoRollActions {
        override fun addNote(note: Note) = Unit
        override fun removeNote(note: Note) = Unit
        override fun replaceNote(old: Note, new: Note) = Unit
        override fun replaceNotes(changes: List<Pair<Note, Note>>) = Unit
        override fun previewNote(pitch: Int) = Unit
        override fun setSnap(snap: Snap) = Unit
        override fun setScale(scale: ScaleSetting) = Unit
    }
}
