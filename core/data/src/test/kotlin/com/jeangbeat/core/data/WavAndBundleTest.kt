package com.jeangbeat.core.data

import com.jeangbeat.core.model.DefaultProjects
import com.jeangbeat.core.model.SampleRef
import com.jeangbeat.core.model.addAudioClip
import com.jeangbeat.core.model.addSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sin

class WavAndBundleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sine(frames: Int = 4800) = AudioData(
        FloatArray(frames * 2) { i -> 0.5f * sin(i / 2 * 2 * Math.PI * 440 / 48000).toFloat() }, 2, 48000,
    )

    @Test
    fun wav16And24RoundTrip() {
        for (bits in listOf(16, 24)) {
            val data = sine()
            val out = ByteArrayOutputStream()
            WavFile.write(out, data, bits)
            assertEquals(44 + data.samples.size * bits / 8, out.size())
            val back = WavFile.read(ByteArrayInputStream(out.toByteArray()))
            assertEquals(2, back.channels)
            assertEquals(48000, back.sampleRate)
            assertEquals(data.frames, back.frames)
            val tolerance = if (bits == 16) 1e-4f else 1e-6f
            for (i in data.samples.indices step 97) assertEquals(data.samples[i], back.samples[i], tolerance)
        }
    }

    @Test
    fun bundleRoundTripIncludesSamples() {
        val samplesDir = tmp.newFolder("samples")
        WavFile.write(samplesDir.resolve("vox.wav"), sine())
        val project = DefaultProjects.demo("Bundle").addSample(SampleRef(0, "Vox", "vox.wav", 4800)).let {
            it.addAudioClip(5, 0, it.samples.last().id, 384)
        }
        val out = ByteArrayOutputStream()
        ProjectBundle.export(project, samplesDir, out)

        val otherPhone = tmp.newFolder("other")
        val imported = ProjectBundle.import(ByteArrayInputStream(out.toByteArray()), otherPhone)
        assertEquals(project, imported)
        assertTrue(otherPhone.resolve("vox.wav").isFile)

        // Importing again into the same folder must not overwrite the existing sample.
        val again = ProjectBundle.import(ByteArrayInputStream(out.toByteArray()), otherPhone)
        assertNotEquals("vox.wav", again.samples.single().fileName)
        assertTrue(otherPhone.resolve(again.samples.single().fileName).isFile)
    }

    @Test
    fun bundleIgnoresPathTraversal() {
        val buf = ByteArrayOutputStream()
        ZipOutputStream(buf).use { zip ->
            zip.putNextEntry(ZipEntry("project.json")); zip.write(ProjectStore.encode(DefaultProjects.empty("X")).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("samples/../../evil.wav")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
        }
        val dir = tmp.newFolder("safe")
        ProjectBundle.import(ByteArrayInputStream(buf.toByteArray()), dir)
        assertTrue(dir.listFiles().isNullOrEmpty())
        assertTrue(!tmp.root.resolve("evil.wav").exists())
    }
}
