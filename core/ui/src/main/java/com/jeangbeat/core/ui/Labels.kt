package com.jeangbeat.core.ui

import androidx.annotation.StringRes
import com.jeangbeat.core.model.DrumSound
import com.jeangbeat.core.model.Instrument
import com.jeangbeat.core.model.Scale
import com.jeangbeat.core.model.Waveform

@StringRes
fun DrumSound.label(): Int = when (this) {
    DrumSound.KICK -> R.string.drum_kick
    DrumSound.SNARE -> R.string.drum_snare
    DrumSound.CLAP -> R.string.drum_clap
    DrumSound.HAT_CLOSED -> R.string.drum_hat_closed
    DrumSound.HAT_OPEN -> R.string.drum_hat_open
    DrumSound.RIM -> R.string.drum_rim
    DrumSound.TOM -> R.string.drum_tom
    DrumSound.CRASH -> R.string.drum_crash
    DrumSound.PERC -> R.string.drum_perc
    DrumSound.SHAKER -> R.string.drum_shaker
    DrumSound.CHHING -> R.string.drum_chhing
    DrumSound.SKOR -> R.string.drum_skor
}

@StringRes
fun Instrument.typeLabel(): Int = when (this) {
    is Instrument.Drum -> R.string.instrument_drum
    is Instrument.Bass808 -> R.string.instrument_808
    is Instrument.Synth -> R.string.instrument_synth
}

@StringRes
fun Waveform.label(): Int = when (this) {
    Waveform.SAW -> R.string.wave_saw
    Waveform.SQUARE -> R.string.wave_square
    Waveform.TRIANGLE -> R.string.wave_triangle
    Waveform.SINE -> R.string.wave_sine
}

/** Scale names stay in English in every language (standard music terms). */
fun Scale.displayName(): String = when (this) {
    Scale.MAJOR -> "Major"
    Scale.MINOR -> "Minor"
    Scale.HARMONIC_MINOR -> "Harmonic minor"
    Scale.DORIAN -> "Dorian"
    Scale.PHRYGIAN -> "Phrygian"
    Scale.MAJOR_PENTATONIC -> "Major pentatonic"
    Scale.MINOR_PENTATONIC -> "Minor pentatonic"
    Scale.BLUES -> "Blues"
    Scale.CHROMATIC -> "Chromatic"
}
