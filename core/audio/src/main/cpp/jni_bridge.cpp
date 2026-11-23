// JNI bridge between Kotlin (com.jeangbeat.core.audio.NativeEngine) and the C++ engine.
#include <jni.h>

#include <memory>

#include "Engine.h"
#include "OboeHost.h"

namespace {

struct Handle {
    jb::Engine engine;
    jb::OboeHost host{engine};
};

Handle* from(jlong ptr) { return reinterpret_cast<Handle*>(ptr); }

}  // namespace

#define JB_FN(name) Java_com_jeangbeat_core_audio_NativeEngine_##name

extern "C" {

JNIEXPORT jlong JNICALL JB_FN(nativeCreate)(JNIEnv*, jobject) { return reinterpret_cast<jlong>(new Handle()); }

JNIEXPORT void JNICALL JB_FN(nativeDestroy)(JNIEnv*, jobject, jlong ptr) {
    Handle* h = from(ptr);
    if (h == nullptr) return;
    h->host.stop();
    delete h;
}

JNIEXPORT jboolean JNICALL JB_FN(nativeStartStream)(JNIEnv*, jobject, jlong ptr) {
    return from(ptr)->host.start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JB_FN(nativeStopStream)(JNIEnv*, jobject, jlong ptr) { from(ptr)->host.stop(); }

JNIEXPORT jint JNICALL JB_FN(nativeSampleRate)(JNIEnv*, jobject, jlong ptr) { return from(ptr)->host.sampleRate(); }

JNIEXPORT jdouble JNICALL JB_FN(nativeLatencyMillis)(JNIEnv*, jobject, jlong ptr) {
    return from(ptr)->host.outputLatencyMillis();
}

JNIEXPORT void JNICALL JB_FN(nativePlay)(JNIEnv*, jobject, jlong ptr) { from(ptr)->engine.play(); }
JNIEXPORT void JNICALL JB_FN(nativeStop)(JNIEnv*, jobject, jlong ptr) { from(ptr)->engine.stop(); }

JNIEXPORT void JNICALL JB_FN(nativeSetTempo)(JNIEnv*, jobject, jlong ptr, jfloat bpm) {
    from(ptr)->engine.setTempo(bpm);
}

JNIEXPORT void JNICALL JB_FN(nativeSetSwing)(JNIEnv*, jobject, jlong ptr, jfloat swing) {
    from(ptr)->engine.setSwing(swing);
}

JNIEXPORT void JNICALL JB_FN(nativeNoteOn)(JNIEnv*, jobject, jlong ptr, jint ch, jint pitch, jint vel) {
    from(ptr)->engine.noteOn(ch, pitch, vel);
}

JNIEXPORT void JNICALL JB_FN(nativeNoteOff)(JNIEnv*, jobject, jlong ptr, jint ch, jint pitch) {
    from(ptr)->engine.noteOff(ch, pitch);
}

JNIEXPORT void JNICALL JB_FN(nativePadHold)(JNIEnv*, jobject, jlong ptr, jint ch, jint pitch, jint vel) {
    from(ptr)->engine.padHold(ch, pitch, vel);
}

JNIEXPORT void JNICALL JB_FN(nativePadRelease)(JNIEnv*, jobject, jlong ptr, jint ch, jint pitch) {
    from(ptr)->engine.padRelease(ch, pitch);
}

JNIEXPORT void JNICALL JB_FN(nativeSetRepeatTicks)(JNIEnv*, jobject, jlong ptr, jint ticks) {
    from(ptr)->engine.setRepeatTicks(ticks);
}

JNIEXPORT void JNICALL JB_FN(nativeAllNotesOff)(JNIEnv*, jobject, jlong ptr) { from(ptr)->engine.allNotesOff(); }

JNIEXPORT void JNICALL JB_FN(nativeSetMasterGain)(JNIEnv*, jobject, jlong ptr, jfloat gain) {
    from(ptr)->engine.setMasterGain(gain);
}

JNIEXPORT void JNICALL JB_FN(nativeBeginSnapshot)(JNIEnv*, jobject, jlong ptr, jint lengthTicks) {
    from(ptr)->engine.beginSnapshot(lengthTicks);
}

JNIEXPORT jint JNICALL JB_FN(nativeAddChannel)(JNIEnv* env, jobject, jlong ptr, jint type, jint sound,
                                               jfloatArray params, jfloat volume, jfloat pan, jboolean muted,
                                               jboolean solo) {
    const jsize n = env->GetArrayLength(params);
    jfloat* p = env->GetFloatArrayElements(params, nullptr);
    const jint index = from(ptr)->engine.addChannel(type, sound, p, n, volume, pan, muted == JNI_TRUE,
                                                    solo == JNI_TRUE);
    env->ReleaseFloatArrayElements(params, p, JNI_ABORT);
    return index;
}

JNIEXPORT void JNICALL JB_FN(nativeSetChannelNotes)(JNIEnv* env, jobject, jlong ptr, jint channel,
                                                    jintArray packed) {
    const jsize n = env->GetArrayLength(packed);
    jint* p = env->GetIntArrayElements(packed, nullptr);
    from(ptr)->engine.setChannelNotes(channel, p, n / 4);
    env->ReleaseIntArrayElements(packed, p, JNI_ABORT);
}

JNIEXPORT void JNICALL JB_FN(nativeCommitSnapshot)(JNIEnv*, jobject, jlong ptr) {
    from(ptr)->engine.commitSnapshot();
}

JNIEXPORT jfloat JNICALL JB_FN(nativePositionTicks)(JNIEnv*, jobject, jlong ptr) {
    return from(ptr)->engine.positionTicks();
}

JNIEXPORT jboolean JNICALL JB_FN(nativeIsPlaying)(JNIEnv*, jobject, jlong ptr) {
    return from(ptr)->engine.isPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL JB_FN(nativeTakePeak)(JNIEnv*, jobject, jlong ptr) { return from(ptr)->engine.takePeak(); }

}  // extern "C"
