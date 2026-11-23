#include "OboeHost.h"

#include <android/log.h>

#define LOG_TAG "JeangBeatAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace jb {

OboeHost::OboeHost(Engine& engine) : engine_(engine) {}

OboeHost::~OboeHost() { stop(); }

bool OboeHost::start() {
    std::lock_guard<std::mutex> guard(lock_);
    wantRunning_ = true;
    if (stream_) return true;
    return openAndStartLocked();
}

void OboeHost::stop() {
    std::lock_guard<std::mutex> guard(lock_);
    wantRunning_ = false;
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

int32_t OboeHost::sampleRate() const {
    std::lock_guard<std::mutex> guard(lock_);
    return stream_ ? stream_->getSampleRate() : 0;
}

double OboeHost::outputLatencyMillis() const {
    std::lock_guard<std::mutex> guard(lock_);
    if (!stream_) return -1.0;
    auto result = stream_->calculateLatencyMillis();
    return result ? result.value() : -1.0;
}

bool OboeHost::openAndStartLocked() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Game)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        return false;
    }

    // Two bursts of buffering is the usual sweet spot between latency and glitches.
    stream->setBufferSizeInFrames(stream->getFramesPerBurst() * 2);
    engine_.setSampleRate(stream->getSampleRate());

    result = stream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        stream->close();
        return false;
    }
    LOGI("Audio started: %d Hz, burst %d frames, %s", stream->getSampleRate(), stream->getFramesPerBurst(),
         stream->getSharingMode() == oboe::SharingMode::Exclusive ? "exclusive" : "shared");
    stream_ = stream;
    return true;
}

oboe::DataCallbackResult OboeHost::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData, int32_t numFrames) {
    engine_.render(static_cast<float*>(audioData), numFrames);
    return oboe::DataCallbackResult::Continue;
}

void OboeHost::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    LOGE("Audio stream closed: %s", oboe::convertToText(error));
    std::lock_guard<std::mutex> guard(lock_);
    stream_.reset();
    if (wantRunning_ && error == oboe::Result::ErrorDisconnected) {
        openAndStartLocked();
    }
}

}  // namespace jb
