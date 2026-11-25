package com.jeangbeat.core.data

import com.jeangbeat.core.data.browser.BrowserIndex
import com.jeangbeat.core.data.browser.BrowserQuery
import com.jeangbeat.core.data.browser.DocEntry
import com.jeangbeat.core.data.browser.DocumentTree
import com.jeangbeat.core.data.browser.FolderScanner
import com.jeangbeat.core.data.browser.LinkedFolder
import com.jeangbeat.core.data.browser.SampleCategory
import com.jeangbeat.core.data.browser.SampleNameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTest {

    // ---- file-name parser ----

    @Test
    fun parsesBpmAndKey() {
        val p = SampleNameParser.parse("Kick_808_C#_140bpm.wav")
        assertEquals(140, p.bpm)
        assertEquals("C#", p.key)
    }

    @Test
    fun parsesLoopWithBareTempoAndMinorKey() {
        val p = SampleNameParser.parse("Melodies/Dark Piano Loop 90 Am.wav")
        assertEquals(90, p.bpm)
        assertEquals("Am", p.key)
        assertEquals(SampleCategory.LOOPS, p.category)
    }

    @Test
    fun parsesKeyWrittenAsWords() {
        assertEquals("F#m", SampleNameParser.parse("Pad F# minor.wav").key)
        assertEquals("Bb", SampleNameParser.parse("Bass_Bb_Long.wav").key)
        assertEquals("Ebm", SampleNameParser.parse("Lead Ebmin 128 BPM.wav").key)
    }

    @Test
    fun ignoresWordsThatLookLikeKeys() {
        assertNull(SampleNameParser.parse("A Kick Heavy.wav").key)
        assertNull(SampleNameParser.parse("Clap 03.wav").bpm)
        assertNull(SampleNameParser.parse("Deep Bass.wav").key)
    }

    @Test
    fun categories() {
        assertEquals(SampleCategory.DRUMS, SampleNameParser.parse("Kicks/Hard Kick.wav").category)
        assertEquals(SampleCategory.DRUMS, SampleNameParser.parse("Open HH 2.wav").category)
        assertEquals(SampleCategory.VOCALS, SampleNameParser.parse("Vox Chop 1.wav").category)
        assertEquals(SampleCategory.FX, SampleNameParser.parse("FX/Riser Long.wav").category)
        assertEquals(SampleCategory.INSTRUMENTS, SampleNameParser.parse("808 Glide C.wav").category)
        assertEquals(SampleCategory.ONE_SHOTS, SampleNameParser.parse("Thing.wav").category)
        assertEquals(SampleCategory.LOOPS, SampleNameParser.parse("Drum Loop 95.wav").category)
    }

    // ---- scanner ----

    private class FakeTree(val dirs: Map<String, List<DocEntry>>) : DocumentTree {
        var calls = 0
        override val rootId = "root"
        override fun children(parentId: String): List<DocEntry> { calls++; return dirs[parentId].orEmpty() }
    }

    private val tree = FakeTree(
        mapOf(
            "root" to listOf(
                DocEntry("d1", "Drums", true),
                DocEntry("f1", "Loop 90 Am.wav", false, 1000, 1),
                DocEntry("f2", "readme.txt", false, 10, 1),
                DocEntry("f3", ".hidden.wav", false, 10, 1),
                DocEntry("f4", "Huge.wav", false, FolderScanner.MAX_FILE_BYTES + 1, 1),
            ),
            "d1" to listOf(
                DocEntry("f5", "Kick.WAV", false, 500, 2),
                DocEntry("d2", "Hats", true),
            ),
            "d2" to listOf(DocEntry("f6", "Hat 1.mp3", false, 300, 3)),
        ),
    )

    @Test
    fun scanFindsAudioRecursively() {
        val files = FolderScanner.scan("tree:1", tree)
        assertEquals(listOf("Drums/Hats/Hat 1.mp3", "Drums/Kick.WAV", "Loop 90 Am.wav"), files.map { it.path })
        assertEquals("Hat 1", files[0].name)
        assertEquals("Drums/Hats", files[0].dir)
        assertEquals(SampleCategory.DRUMS, files[1].category)
        assertEquals(90, files[2].bpm)
    }

    @Test
    fun rescanKeepsUnchangedEntries() {
        val first = FolderScanner.scan("tree:1", tree)
        val second = FolderScanner.scan("tree:1", tree, first)
        first.zip(second).forEach { (a, b) -> assertSame(a, b) }
        val changed = FakeTree(tree.dirs + ("d2" to listOf(DocEntry("f6", "Hat 1.mp3", false, 999, 9))))
        val third = FolderScanner.scan("tree:1", changed, first)
        assertEquals(999, third.first { it.documentId == "f6" }.size)
    }

    @Test
    fun cancelledScanKeepsOldList() {
        val first = FolderScanner.scan("tree:1", tree)
        assertEquals(first, FolderScanner.scan("tree:1", tree, first, isCancelled = { true }))
    }

    // ---- index & query ----

    @Test
    fun indexRoundTripAndFolderRemoval() {
        val files = FolderScanner.scan("tree:1", tree)
        var index = BrowserIndex().withFolder(LinkedFolder("tree:1", "Samples")).withScan("tree:1", files)
        index = index.toggleFavourite(files[0].key2).markRecent(files[1].key2).markRecent(files[0].key2)
        val back = BrowserIndex.fromJson(index.toJson())
        assertEquals(index, back)
        assertEquals(listOf(files[0].key2, files[1].key2), back.recent)
        val removed = back.withoutFolder("tree:1")
        assertTrue(removed.files.isEmpty() && removed.favourites.isEmpty() && removed.recent.isEmpty())
        assertEquals(BrowserIndex(), BrowserIndex.fromJson("not json"))
    }

    @Test
    fun queryFilters() {
        val files = FolderScanner.scan("tree:1", tree)
        val index = BrowserIndex().withScan("tree:1", files).toggleFavourite(files[1].key2)
        assertEquals(listOf("Drums/Kick.WAV"), BrowserQuery(text = "kick").apply(index).map { it.path })
        assertEquals(listOf("Drums/Kick.WAV"), BrowserQuery(favouritesOnly = true).apply(index).map { it.path })
        assertEquals(listOf("Loop 90 Am.wav"), BrowserQuery(category = SampleCategory.LOOPS).apply(index).map { it.path })
        assertEquals(listOf("Loop 90 Am.wav"), BrowserQuery(bpmMin = 80, bpmMax = 100).apply(index).map { it.path })
        assertEquals(listOf("Loop 90 Am.wav"), BrowserQuery(key = "am").apply(index).map { it.path })
        assertEquals(2, BrowserQuery(text = "drums").apply(index).size)
    }
}
