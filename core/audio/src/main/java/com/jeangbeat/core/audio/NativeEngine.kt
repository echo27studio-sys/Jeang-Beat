package com.jeangbeat.core.audio

/** Thin JNI declarations for the C++ engine. Use [AudioEngine] instead of calling these directly. */
internal class NativeEngine {
    external fun nativeCreate(): Long
    external fun nativeDestroy(ptr: Long)
    external fun nativeStartStream(ptr: Long): Boolean
    external fun nativeStopStream(ptr: Long)
    external fun nativeSampleRate(ptr: Long): Int
    external fun nativeSetSampleRate(ptr: Long, rate: Int)
    external fun nativeLatencyMillis(ptr: Long): Double

    external fun nativePlay(ptr: Long)
    external fun nativePlayCountIn(ptr: Long, bars: Int)
    external fun nativeSetMetronome(ptr: Long, on: Boolean)
    external fun nativeIsCountingIn(ptr: Long): Boolean
    external fun nativeStop(ptr: Long)
    external fun nativeSetPosition(ptr: Long, tick: Long)
    external fun nativeSetTempo(ptr: Long, bpm: Float)
    external fun nativeSetSwing(ptr: Long, swing: Float)
    external fun nativeNoteOn(ptr: Long, channel: Int, pitch: Int, velocity: Int)
    external fun nativeNoteOff(ptr: Long, channel: Int, pitch: Int)
    external fun nativePadHold(ptr: Long, channel: Int, pitch: Int, velocity: Int)
    external fun nativePadRelease(ptr: Long, channel: Int, pitch: Int)
    external fun nativeSetRepeatTicks(ptr: Long, ticks: Int)
    external fun nativeAllNotesOff(ptr: Long)
    external fun nativeSetMasterGain(ptr: Long, gain: Float)
    external fun nativeSetStemInsert(ptr: Long, insert: Int)

    external fun nativeLoadSample(ptr: Long, id: Int, data: FloatArray, channels: Int, sampleRate: Float)
    external fun nativeUnloadSample(ptr: Long, id: Int)

    external fun nativeBeginSnapshot(
        ptr: Long, mode: Int, currentPattern: Int, songLength: Long, loopStart: Long, loopEnd: Long,
        patternLoopStart: Long, patternLoopEnd: Long,
    )
    external fun nativeSetPatternPosition(ptr: Long, tick: Long)
    external fun nativeAddChannel(
        ptr: Long, type: Int, sound: Int, sampleId: Int, params: FloatArray,
        volume: Float, pan: Float, muted: Boolean, solo: Boolean, insert: Int,
    ): Int
    external fun nativeAddPattern(ptr: Long, lengthTicks: Int, stepTicks: Int): Int
    external fun nativeSetPatternNotes(ptr: Long, pattern: Int, channel: Int, ints: IntArray, floats: FloatArray)
    external fun nativeAddPatternClip(ptr: Long, start: Long, length: Long, offset: Long, pattern: Int)
    external fun nativeAddAudioClip(ptr: Long, start: Long, length: Long, offset: Long, sampleId: Int, gain: Float, insert: Int)
    external fun nativeAddAutomation(
        ptr: Long, target: Int, a: Int, b: Int, c: Int, min: Float, max: Float, log: Boolean,
        start: Long, length: Long, ticks: IntArray, values: FloatArray,
    )
    external fun nativeSetInsert(
        ptr: Long, index: Int, volume: Float, pan: Float, muted: Boolean, solo: Boolean, toMaster: Boolean,
        targets: IntArray, levels: FloatArray,
    )
    external fun nativeAddEffect(ptr: Long, insert: Int, slotId: Int, type: Int, enabled: Boolean, mix: Float, params: FloatArray)
    external fun nativeCommitSnapshot(ptr: Long)

    external fun nativePositionTicks(ptr: Long): Float
    external fun nativeIsPlaying(ptr: Long): Boolean
    external fun nativeTakePeak(ptr: Long): Float
    external fun nativeTakeMeters(ptr: Long, out: FloatArray)
    external fun nativeRender(ptr: Long, out: FloatArray, frames: Int)

    companion object {
        init {
            System.loadLibrary("jeangbeat_audio")
        }
    }
}
