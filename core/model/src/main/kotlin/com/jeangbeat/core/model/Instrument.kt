package com.jeangbeat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Built-in drum sounds. The order must match DrumSoundId in
 * core/audio/src/main/cpp/engine/DrumKit.h — append new sounds at the end only.
 */
enum class DrumSound(val defaultChokeGroup: Int = 0, val isKhmer: Boolean = false) {
    KICK,
    SNARE,
    CLAP,
    HAT_CLOSED(defaultChokeGroup = 1),
    HAT_OPEN(defaultChokeGroup = 1),
    RIM,
    TOM,
    CRASH,
    PERC,
    SHAKER,
    CHHING(isKhmer = true),
    SKOR(isKhmer = true),
}

enum class Waveform { SAW, SQUARE, TRIANGLE, SINE }

/** What a channel plays. Serialized with a "type" discriminator. */
@Serializable
sealed interface Instrument {
    /** One-shot drum sound. Pitch 60 plays the sound at its original pitch. */
    @Serializable
    @SerialName("drum")
    data class Drum(
        val sound: DrumSound,
        /** Semitones, -24..24. */
        val tune: Float = 0f,
        /** 0.05..1, where 1 is the natural length of the sound. */
        val decay: Float = 1f,
        /** Sounds in the same non-zero group cut each other off (e.g. hats). */
        val chokeGroup: Int = sound.defaultChokeGroup,
    ) : Instrument

    /** Sub-bass 808 with pitch punch, drive and legato glide between overlapping notes. */
    @Serializable
    @SerialName("bass808")
    data class Bass808(
        /** Decay time constant in seconds, 0.1..4. */
        val decay: Float = 0.9f,
        /** Glide time in seconds, 0..0.5. 0 disables glide. */
        val glide: Float = 0.08f,
        /** Saturation, 0..1. */
        val drive: Float = 0.3f,
        /** Pitch-envelope "knock" at the start of each note, 0..1. */
        val punch: Float = 0.4f,
        /** Semitones, -12..12. */
        val tune: Float = 0f,
    ) : Instrument

    /** Two-oscillator subtractive synth with a resonant low-pass filter. */
    @Serializable
    @SerialName("synth")
    data class Synth(
        val waveform: Waveform = Waveform.SAW,
        /** Filter cutoff 0..1. */
        val cutoff: Float = 0.55f,
        /** Filter resonance 0..1. */
        val resonance: Float = 0.25f,
        val attack: Float = 0.01f,
        val decay: Float = 0.35f,
        val sustain: Float = 0.6f,
        val release: Float = 0.25f,
        /** Oscillator 2 detune in cents, 0..50. */
        val detune: Float = 8f,
        /** Filter envelope amount 0..1. */
        val envAmount: Float = 0.35f,
    ) : Instrument
}

/** The pitch a step-sequencer step plays for this instrument. */
val Instrument.defaultPitch: Int
    get() = when (this) {
        is Instrument.Drum -> ROOT_PITCH
        is Instrument.Bass808 -> 36 // C2
        is Instrument.Synth -> 60 // C4
    }

/** True for instruments that are played melodically (piano roll first). */
val Instrument.isMelodic: Boolean get() = this !is Instrument.Drum

const val ROOT_PITCH = 60
