package com.jeangbeat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerOpsTest {
    private val base = DefaultProjects.empty("Mix")

    @Test
    fun defaultMixerHasMasterAndInserts() {
        assertEquals(MIXER_INSERTS + 1, base.mixer.tracks.size)
        assertEquals("Master", base.mixer.master.name)
        assertEquals(1f, faderGain(FADER_UNITY), 1e-6f)
        assertEquals(0f, faderDb(FADER_UNITY), 1e-4f)
    }

    @Test
    fun effectsAddEditMoveRemove() {
        val p1 = base.addEffect(1, EffectType.EQ).addEffect(1, EffectType.REVERB)
        val slots = p1.mixer.track(1)!!.effects
        assertEquals(listOf(EffectType.EQ, EffectType.REVERB), slots.map { it.type })
        val p2 = p1.setEffectParam(1, slots[0].id, 1, 99f) // clamped to +18 dB
        assertEquals(18f, p2.mixer.track(1)!!.effects[0].param(1), 0f)
        val p3 = p2.moveEffect(1, slots[1].id, 0)
        assertEquals(EffectType.REVERB, p3.mixer.track(1)!!.effects[0].type)
        val p4 = p3.removeEffect(1, slots[0].id)
        assertEquals(listOf(EffectType.REVERB), p4.mixer.track(1)!!.effects.map { it.type })
    }

    @Test
    fun tenSlotsMaximum() {
        var p = base
        repeat(12) { p = p.addEffect(2, EffectType.FILTER) }
        assertEquals(EFFECT_SLOTS, p.mixer.track(2)!!.effects.size)
    }

    @Test
    fun sendsRefuseLoopsAndMaster() {
        val p = base.setSend(1, 2, 0.5f).setSend(2, 3, 0.5f)
        assertSame(p, p.setSend(3, 1, 0.5f)) // 1→2→3→1 would loop
        assertSame(p, p.setSend(1, 1, 0.5f))
        assertSame(p, p.setSend(1, 0, 0.5f))
        assertEquals(0.5f, p.mixer.track(1)!!.sends.single().level)
        assertTrue(p.setSend(1, 2, 0f).mixer.track(1)!!.sends.isEmpty())
    }

    @Test
    fun sidechainCountsAsRouting() {
        val p0 = base.addEffect(4, EffectType.COMPRESSOR)
        val slot = p0.mixer.track(4)!!.effects.single().id
        val p1 = p0.setSidechain(4, slot, 1)
        assertEquals(1, p1.mixer.track(4)!!.effects.single().sidechainSource())
        // Now 1 feeds 4 via sidechain, so sending 4 into 1 would loop.
        assertSame(p1, p1.setSend(4, 1, 0.5f))
        assertSame(p1, p1.setSidechain(4, slot, 4))
    }

    @Test
    fun paramSpecNormalizationRoundTrips() {
        val spec = EffectType.EQ.params[0] // log 20..1000
        assertEquals(100f, spec.denormalize(spec.normalize(100f)), 0.5f)
        assertEquals(0f, spec.normalize(20f), 1e-6f)
        val stepped = EffectType.DELAY.params[0]
        assertEquals(3f, stepped.clamp(2.6f), 0f)
    }

    @Test
    fun newChannelGetsFreeInsertNamedAfterIt() {
        val p = base.addChannel("Shaker", Instrument.Drum(DrumSound.SHAKER))
        val ch = p.channels.last()
        assertTrue(ch.mixerTrack > 0)
        assertEquals("Shaker", p.mixer.track(ch.mixerTrack)!!.name)
    }

    @Test
    fun demoProjectIsMixedAndArranged() {
        val demo = DefaultProjects.demo("Demo")
        assertTrue(demo.song.clips.size >= 3)
        assertEquals(1, demo.mixer.track(4)!!.effects.single().sidechainSource())
        assertTrue(demo.mixer.track(6)!!.sends.any { it.target == 7 })
        val ids = demo.channels.map { it.id }.toSet()
        demo.patterns.forEach { pat -> assertTrue(pat.notes.keys.all { it in ids }) }
    }
}
