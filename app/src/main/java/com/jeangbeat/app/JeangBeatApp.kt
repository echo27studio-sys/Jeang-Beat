package com.jeangbeat.app

import android.app.Application
import com.jeangbeat.core.audio.AudioEngine
import com.jeangbeat.core.data.ProjectStore
import java.io.File

/** Holds app-wide singletons: the audio engine and the project store. */
class JeangBeatApp : Application() {
    lateinit var engine: AudioEngine
        private set
    lateinit var store: ProjectStore
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        engine = AudioEngine()
        store = ProjectStore(File(filesDir, "projects"))
        settings = Settings(this)
    }
}
