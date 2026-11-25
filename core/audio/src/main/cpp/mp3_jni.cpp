// JNI wrapper around LAME (libmp3lame.so, LGPL) for MP3 export.
// Kotlin side: com.jeangbeat.core.audio.Mp3Writer
#include <jni.h>

#include <vector>

#include "lame.h"

namespace {
struct Encoder {
    lame_global_flags* lame = nullptr;
    std::vector<unsigned char> out;
};
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_jeangbeat_core_audio_Mp3Writer_nativeOpen(JNIEnv*, jclass, jint sampleRate, jint channels, jint kbps) {
    lame_global_flags* g = lame_init();
    if (g == nullptr) return 0;
    lame_set_in_samplerate(g, sampleRate);
    lame_set_out_samplerate(g, sampleRate);
    lame_set_num_channels(g, channels);
    lame_set_mode(g, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_VBR(g, vbr_off);
    lame_set_brate(g, kbps);
    lame_set_quality(g, 2);  // high quality
    if (lame_init_params(g) < 0) {
        lame_close(g);
        return 0;
    }
    auto* e = new Encoder();
    e->lame = g;
    return reinterpret_cast<jlong>(e);
}

// Encodes `frames` interleaved stereo float frames (-1..1). Returns the MP3 bytes produced.
JNIEXPORT jbyteArray JNICALL Java_com_jeangbeat_core_audio_Mp3Writer_nativeEncode(JNIEnv* env, jclass, jlong handle, jfloatArray pcm, jint frames) {
    auto* e = reinterpret_cast<Encoder*>(handle);
    if (e == nullptr || frames <= 0) return env->NewByteArray(0);
    const size_t size = static_cast<size_t>(1.25 * frames) + 7200;
    if (e->out.size() < size) e->out.resize(size);
    jfloat* data = env->GetFloatArrayElements(pcm, nullptr);
    const int n = lame_encode_buffer_interleaved_ieee_float(e->lame, data, frames, e->out.data(), static_cast<int>(size));
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    const jsize len = n > 0 ? n : 0;
    jbyteArray result = env->NewByteArray(len);
    if (len > 0) env->SetByteArrayRegion(result, 0, len, reinterpret_cast<const jbyte*>(e->out.data()));
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_com_jeangbeat_core_audio_Mp3Writer_nativeFlush(JNIEnv* env, jclass, jlong handle) {
    auto* e = reinterpret_cast<Encoder*>(handle);
    if (e == nullptr) return env->NewByteArray(0);
    if (e->out.size() < 7200) e->out.resize(7200);
    const int n = lame_encode_flush(e->lame, e->out.data(), static_cast<int>(e->out.size()));
    const jsize len = n > 0 ? n : 0;
    jbyteArray result = env->NewByteArray(len);
    if (len > 0) env->SetByteArrayRegion(result, 0, len, reinterpret_cast<const jbyte*>(e->out.data()));
    return result;
}

JNIEXPORT void JNICALL Java_com_jeangbeat_core_audio_Mp3Writer_nativeClose(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Encoder*>(handle);
    if (e == nullptr) return;
    lame_close(e->lame);
    delete e;
}

}  // extern "C"
