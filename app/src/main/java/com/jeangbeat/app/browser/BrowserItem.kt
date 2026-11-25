package com.jeangbeat.app.browser

import com.jeangbeat.core.data.browser.IndexedSample
import com.jeangbeat.core.model.Instrument
import com.jeangbeat.core.model.SampleRef

/** Something in the Browser that can be previewed, added or dragged. */
sealed interface BrowserItem {
    val label: String

    /** An audio file in a linked folder. */
    data class File(val sample: IndexedSample) : BrowserItem {
        override val label: String get() = sample.name
    }

    /** A built-in synthesised sound (drums, 808, synth). */
    data class Sound(override val label: String, val instrument: Instrument) : BrowserItem

    /** Audio already in the current project. */
    data class ProjectSample(val ref: SampleRef) : BrowserItem {
        override val label: String get() = ref.name
    }
}
