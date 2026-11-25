# Jeang Beat roadmap

Free Android beat maker (phones + tablets), distributed as an APK first.
Stack: Kotlin + Jetpack Compose for the UI, C++ + Google Oboe for audio.

## Milestones

| # | Milestone | Status |
|---|---|---|
| M0 | Project setup: Gradle modules, C++/Oboe, Compose app, English/Khmer, GitHub Actions APK build | ✅ Done |
| M1 | Audio engine core: low-latency playback, sampler voices, tempo/swing clock, built-in drum kit | ✅ Done |
| M2 | Beat making: step sequencer, 4×4 pads with note repeat and recording, patterns, save/load, undo | ✅ Done |
| M3 | Melody: piano roll (scale lock, velocity), 808 bass with glide, synth | ✅ Done |
| **2.0** | **Playlist · Pattern (FL rack + Ring) · Mixer, Piano Roll inside Pattern** — [design](design/v2/README.md). Song mode, mixer with 7 effects, sends, sidechain, automation, audio import, WAV/M4A/stems export, project bundles | ✅ Built (needs testing on phones) |
| **3.0** | **Visible save + MP3 export, channel menus, auto-length patterns (512 bars), Browser with linked folders + drag & drop, background playback, metronome, live recording** — [notes](design/v3/README.md) | ✅ Built (needs testing on phones) |
| 3.x | Rhythm Library + Learn mode (Khmer rhythms after transcription with musicians), Quick Start loops, video share | Next |
| M5 | First public APK: polish from phone testing, signed release | |
| M6+ | Vocal recording, sample chopper, automation, sidechain, chord generator, MIDI controllers, stems, tutorial | |

## Khmer identity
- Bilingual interface (English / Khmer). **Khmer strings are a draft and need review by a native speaker** (`core/ui/src/main/res/values-km/strings.xml`).
- Khmer sound kits: Skor Thom/Arak, Sampho, Chhing, Roneat Ek, Kong Vong Thom, Tro Sor/Tro Ou, Khim, Chapei Dong Veng, Pei Pok/Sralai.
  Today only *chhing* and *skor* exist, as synthesised placeholders.
  **Needed:** real recordings we are allowed to use (record local musicians, or CC0/royalty-free packs).
- "Khmer Fusion" Quick Start styles (M5).
- Traditional tuning presets (later, to be verified with Khmer musicians).

## Known limitations (3.0)
- The UI has not run on a device or emulator in development (no emulator in the build machine): it needs testing on real phones and tablets.
- Tempo cannot be automated; audio clips do not time-stretch when the tempo changes.
- Browser preview uses Android's media player (not the engine), so it does not follow the song tempo; no waveform preview yet.
- Built-in packs are the synthesised sounds; there are no WAV packs in the APK yet (waiting for Khmer recordings).
- Recorded notes get a fixed length (the snap or one step); held-note length is not recorded.
- Screenshot tests are not in CI yet (Robolectric/Paparazzi downloads were rate-limited before).
