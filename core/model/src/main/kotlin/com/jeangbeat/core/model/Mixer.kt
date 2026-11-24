package com.jeangbeat.core.model

import kotlinx.serialization.Serializable
import kotlin.math.log10
import kotlin.math.pow

/** Describes one effect parameter. Values are stored in these units. */
data class ParamSpec(
    val key: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    /** Logarithmic knob (frequencies, times). */
    val log: Boolean = false,
    /** Whole-number values only (modes, selectors). */
    val stepped: Boolean = false,
    /** Labels for stepped parameters. */
    val options: List<String> = emptyList(),
) {
    fun clamp(v: Float): Float = v.coerceIn(min, max).let { if (stepped) kotlin.math.round(it) else it }

    /** 0..1 knob position for [v]. */
    fun normalize(v: Float): Float = if (log && min > 0f) {
        (log10(v / min) / log10(max / min)).coerceIn(0f, 1f)
    } else {
        ((v - min) / (max - min)).coerceIn(0f, 1f)
    }

    /** Value for a 0..1 knob position. */
    fun denormalize(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return clamp(if (log && min > 0f) min * (max / min).pow(t) else min + (max - min) * t)
    }
}

/**
 * Built-in effects. The param order is the engine's slot order — append only.
 * Must match core/audio/src/main/cpp/engine/Effects.h.
 */
enum class EffectType(val displayName: String, val defaultMix: Float, val params: List<ParamSpec>) {
    EQ(
        "JB Parametric EQ", 1f,
        listOf(
            ParamSpec("Low freq", 20f, 1000f, 100f, "Hz", log = true),
            ParamSpec("Low gain", -18f, 18f, 0f, "dB"),
            ParamSpec("Mid freq", 200f, 8000f, 1000f, "Hz", log = true),
            ParamSpec("Mid gain", -18f, 18f, 0f, "dB"),
            ParamSpec("Mid Q", 0.3f, 8f, 1f, "", log = true),
            ParamSpec("High freq", 1000f, 20000f, 8000f, "Hz", log = true),
            ParamSpec("High gain", -18f, 18f, 0f, "dB"),
            ParamSpec("Low cut", 20f, 1000f, 20f, "Hz", log = true),
        ),
    ),
    COMPRESSOR(
        "JB Compressor", 1f,
        listOf(
            ParamSpec("Threshold", -60f, 0f, -18f, "dB"),
            ParamSpec("Ratio", 1f, 20f, 4f, ":1", log = true),
            ParamSpec("Attack", 0.1f, 100f, 10f, "ms", log = true),
            ParamSpec("Release", 10f, 1000f, 120f, "ms", log = true),
            ParamSpec("Makeup", 0f, 24f, 0f, "dB"),
            /** -1 = no sidechain, otherwise the mixer insert whose signal drives the compressor. */
            ParamSpec("Sidechain", -1f, MIXER_INSERTS.toFloat(), -1f, "", stepped = true),
        ),
    ),
    LIMITER(
        "JB Limiter", 1f,
        listOf(
            ParamSpec("Gain", 0f, 24f, 0f, "dB"),
            ParamSpec("Ceiling", -12f, 0f, -0.3f, "dB"),
            ParamSpec("Release", 10f, 500f, 60f, "ms", log = true),
        ),
    ),
    REVERB(
        "JB Reverb", 0.3f,
        listOf(
            ParamSpec("Size", 0f, 1f, 0.6f),
            ParamSpec("Damping", 0f, 1f, 0.4f),
            ParamSpec("Width", 0f, 1f, 1f),
            ParamSpec("Pre-delay", 0f, 100f, 10f, "ms"),
            ParamSpec("Low cut", 20f, 1000f, 150f, "Hz", log = true),
        ),
    ),
    DELAY(
        "JB Delay", 0.3f,
        listOf(
            ParamSpec("Time", 0f, 5f, 2f, stepped = true, options = listOf("1/16", "1/8", "1/8 dot", "1/4", "1/4 dot", "1/2")),
            ParamSpec("Feedback", 0f, 0.95f, 0.35f),
            ParamSpec("Ping-pong", 0f, 1f, 1f, stepped = true, options = listOf("Off", "On")),
            ParamSpec("Low cut", 20f, 2000f, 200f, "Hz", log = true),
            ParamSpec("High cut", 1000f, 20000f, 8000f, "Hz", log = true),
        ),
    ),
    SATURATOR(
        "JB Saturator", 1f,
        listOf(
            ParamSpec("Drive", 0f, 24f, 6f, "dB"),
            ParamSpec("Tone", -1f, 1f, 0f),
            ParamSpec("Output", -24f, 6f, 0f, "dB"),
        ),
    ),
    FILTER(
        "JB Filter", 1f,
        listOf(
            ParamSpec("Type", 0f, 2f, 0f, stepped = true, options = listOf("Low-pass", "High-pass", "Band-pass")),
            ParamSpec("Cutoff", 20f, 20000f, 2000f, "Hz", log = true),
            ParamSpec("Resonance", 0f, 1f, 0.2f),
        ),
    ),
    ;

    fun defaults(): List<Float> = params.map { it.default }
}

@Serializable
data class EffectSlot(
    /** Stable id: the engine keeps the effect's state (reverb tail, delay line) per id. */
    val id: Int,
    val type: EffectType,
    val enabled: Boolean = true,
    /** Dry/wet, 0..1. */
    val mix: Float = type.defaultMix,
    val params: List<Float> = type.defaults(),
) {
    fun param(index: Int): Float = params.getOrElse(index) { type.params[index].default }
}

@Serializable
data class Send(val target: Int, val level: Float)

@Serializable
data class MixerTrack(
    /** 0 = Master, 1..MIXER_INSERTS = inserts. */
    val index: Int,
    val name: String,
    /** Palette colour index, or -1 for none. */
    val color: Int = -1,
    /** Fader position 0..1. 0.8 = 0 dB (see [faderGain]). */
    val volume: Float = FADER_UNITY,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val effects: List<EffectSlot> = emptyList(),
    val sends: List<Send> = emptyList(),
    /** Inserts go to Master unless this is turned off (then only sends are heard). */
    val toMaster: Boolean = true,
) {
    val isMaster: Boolean get() = index == 0
}

@Serializable
data class Mixer(val tracks: List<MixerTrack>) {
    val master: MixerTrack get() = tracks.first { it.isMaster }

    fun track(index: Int): MixerTrack? = tracks.firstOrNull { it.index == index }

    companion object {
        fun default(): Mixer = Mixer(
            listOf(MixerTrack(0, "Master")) + (1..MIXER_INSERTS).map { MixerTrack(it, "Insert $it") },
        )
    }
}

const val MIXER_INSERTS = 24
const val EFFECT_SLOTS = 10
const val FADER_UNITY = 0.8f

/** Linear gain for a fader position (0.8 = unity, 1.0 ≈ +3.9 dB). */
fun faderGain(volume: Float): Float = (volume / FADER_UNITY).pow(2)

/** Fader position in dB for display. */
fun faderDb(volume: Float): Float = if (volume <= 0f) Float.NEGATIVE_INFINITY else 40f * log10(volume / FADER_UNITY)
