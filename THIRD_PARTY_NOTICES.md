# Third-party notices

Jeang Beat includes the following open-source software.

| Component | Licence | Where |
|---|---|---|
| **LAME 3.100** (MP3 encoder) | GNU LGPL v2 or later | `core/audio/src/main/cpp/third_party/lame` (unmodified upstream source, built as the separate shared library `libmp3lame.so`; see `JEANG_BEAT_README.md` there for the download URL and checksum). Upstream: https://lame.sourceforge.io |
| **Oboe** (Google) | Apache License 2.0 | Maven `com.google.oboe:oboe` |
| AndroidX, Jetpack Compose, Material 3 | Apache License 2.0 | Maven |
| Kotlin, kotlinx.serialization, kotlinx.coroutines | Apache License 2.0 | Maven |

## LGPL note for LAME

LAME is linked dynamically (`libmp3lame.so`), so you may replace it with your own
build of LAME. The full source we use is in this repository, and the licence text
is in `core/audio/src/main/cpp/third_party/lame/COPYING`.
