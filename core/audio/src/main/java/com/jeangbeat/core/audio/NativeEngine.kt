package com.jeangbeat.core.audio

/** Thin JNI declarations for the C++ engine. Use [AudioEngine] instead of calling these directly. */
internal class NativeEngine {
    external fun nativeCreate(): Long
    external fun nativeDestroy(ptr: Long)
    external fun nativeStartStream(ptr: Long): Boolean
    external fun nativeStopStream(ptr: Long)
    external fun nativeSampleRate(ptr: Long): Int
    external fun nativeLatencyMillis(ptr: Long): Double

    external fun nativePlay(ptr: Long)
    external fun nativeStop(ptr: Long)
    external fun nativeSetTempo(ptr: Long, bpm: Float)
    external fun nativeSetSwing(ptr: Long, swing: Float)
    external fun nativeNoteOn(ptr: Long, channel: Int, pitch: Int, velocity: Int)
    external fun nativeNoteOff(ptr: Long, channel: Int, pitch: Int)
    external fun nativePadHold(ptr: Long, channel: Int, pitch: Int, velocity: Int)
    external fun nativePadRelease(ptr: Long, channel: Int, pitch: Int)
    external fun nativeSetRepeatTicks(ptr: Long, ticks: Int)
    external fun nativeAllNotesOff(ptr: Long)
    external fun nativeSetMasterGain(ptr: Long, gain: Float)

    external fun nativeBeginSnapshot(ptr: Long, lengthTicks: Int)
    external fun nativeAddChannel(
        ptr: Long,
        type: Int,
        sound: Int,
        params: FloatArray,
        volume: Float,
        pan: Float,
        muted: Boolean,
        solo: Boolean,
    ): Int
    external fun nativeSetChannelNotes(ptr: Long, channel: Int, packed: IntArray)
    external fun nativeCommitSnapshot(ptr: Long)

    external fun nativePositionTicks(ptr: Long): Float
    external fun nativeIsPlaying(ptr: Long): Boolean
    external fun nativeTakePeak(ptr: Long): Float

    companion object {
        init {
            System.loadLibrary("jeangbeat_audio")
        }
    }
}
