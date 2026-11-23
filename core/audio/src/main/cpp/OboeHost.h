#pragma once

#include <oboe/Oboe.h>

#include <memory>
#include <mutex>

#include "Engine.h"

namespace jb {

// Owns the Oboe output stream and forwards audio callbacks to the Engine.
// Re-opens the stream automatically when the audio device changes
// (e.g. headphones plugged in or Bluetooth connected).
class OboeHost : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    explicit OboeHost(Engine& engine);
    ~OboeHost() override;

    bool start();
    void stop();
    int32_t sampleRate() const;
    double outputLatencyMillis() const;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openAndStartLocked();

    Engine& engine_;
    mutable std::mutex lock_;
    std::shared_ptr<oboe::AudioStream> stream_;
    bool wantRunning_ = false;
};

}  // namespace jb
