package com.jeangbeat.core.data

import com.jeangbeat.core.model.DefaultProjects
import com.jeangbeat.core.model.Instrument
import com.jeangbeat.core.model.Waveform
import com.jeangbeat.core.model.addChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun saveAndLoadRoundTrip() {
        val store = ProjectStore(tmp.newFolder())
        val project = DefaultProjects.demo("My Beat")
            .addChannel("Lead", Instrument.Synth(waveform = Waveform.SQUARE, cutoff = 0.2f))
        val file = store.save(project)
        assertEquals("My Beat.jbeat", file)
        assertEquals(project, store.load(file))
    }

    @Test
    fun listShowsSavedProjects() {
        val store = ProjectStore(tmp.newFolder())
        store.save(DefaultProjects.empty("One"))
        store.save(DefaultProjects.empty("Two"))
        assertEquals(setOf("One", "Two"), store.list().map { it.name }.toSet())
    }

    @Test
    fun corruptFilesAreSkippedInListAndReportedOnLoad() {
        val dir = tmp.newFolder()
        val store = ProjectStore(dir)
        dir.resolve("broken.jbeat").writeText("{not json")
        assertTrue(store.list().isEmpty())
        val error = runCatching { store.load("broken.jbeat") }.exceptionOrNull()
        assertTrue(error is ProjectLoadException)
    }

    @Test
    fun unknownFieldsFromNewerVersionsAreIgnored() {
        val json = ProjectStore.encode(DefaultProjects.empty("Old")).replaceFirst("{", "{\"futureField\":42,")
        assertEquals("Old", ProjectStore.decode(json).name)
    }

    @Test
    fun khmerNamesAreKept() {
        assertEquals("បទថ្មី", ProjectStore.sanitize("បទថ្មី"))
        assertEquals("a_b_c", ProjectStore.sanitize("a/b\\c"))
        assertEquals("Untitled", ProjectStore.sanitize("   "))
    }

    @Test
    fun uniqueFileNamesDoNotOverwrite() {
        val store = ProjectStore(tmp.newFolder())
        store.save(DefaultProjects.empty("Beat"))
        assertEquals("Beat (2).jbeat", store.uniqueFileNameFor("Beat"))
    }

    @Test
    fun deleteRemovesFile() {
        val store = ProjectStore(tmp.newFolder())
        val file = store.save(DefaultProjects.empty("Gone"))
        assertTrue(store.delete(file))
        assertFalse(store.exists(file))
    }
}
