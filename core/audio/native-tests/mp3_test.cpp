// Host test: LAME encodes a 2 s stereo tone at 192 kbps into valid MP3 frames.
#include <cmath>
#include <cstdio>
#include <vector>

#include "lame.h"

int main() {
    const int sr = 44100, kbps = 192, seconds = 2;
    lame_global_flags* g = lame_init();
    lame_set_in_samplerate(g, sr);
    lame_set_out_samplerate(g, sr);
    lame_set_num_channels(g, 2);
    lame_set_mode(g, JOINT_STEREO);
    lame_set_VBR(g, vbr_off);
    lame_set_brate(g, kbps);
    lame_set_quality(g, 2);
    if (lame_init_params(g) < 0) { std::puts("  FAIL lame_init_params"); return 1; }
    std::vector<float> pcm(static_cast<size_t>(sr) * 2);
    std::vector<unsigned char> out, buf(200000);
    for (int s = 0; s < seconds; ++s) {
        for (int i = 0; i < sr; ++i) {
            const float v = 0.5f * std::sin(2.0 * M_PI * 440.0 * (s * sr + i) / sr);
            pcm[static_cast<size_t>(i) * 2] = v;
            pcm[static_cast<size_t>(i) * 2 + 1] = v;
        }
        const int n = lame_encode_buffer_interleaved_ieee_float(g, pcm.data(), sr, buf.data(), static_cast<int>(buf.size()));
        out.insert(out.end(), buf.begin(), buf.begin() + n);
    }
    const int n = lame_encode_flush(g, buf.data(), static_cast<int>(buf.size()));
    out.insert(out.end(), buf.begin(), buf.begin() + n);
    lame_close(g);

    const double expected = kbps * 1000.0 / 8 * seconds;
    const bool sizeOk = out.size() > expected * 0.9 && out.size() < expected * 1.1;
    const bool sync = out.size() > 2 && out[0] == 0xFF && (out[1] & 0xE0) == 0xE0;
    std::printf("  %s MP3 size %zu bytes (expected ~%.0f)\n", sizeOk ? "PASS" : "FAIL", out.size(), expected);
    std::printf("  %s MP3 starts with a frame sync\n", sync ? "PASS" : "FAIL");
    return sizeOk && sync ? 0 : 1;
}
