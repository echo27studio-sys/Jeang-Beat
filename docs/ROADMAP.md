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
| 2.x | Rhythm Library + Learn mode (Khmer rhythms after transcription with musicians), Quick Start loops, video share | Next |
| M5 | First public APK: polish from phone testing, signed release | |
| M6+ | Vocal recording, sample chopper, automation, sidechain, chord generator, MIDI controllers, stems, tutorial | |

## Khmer identity
- Bilingual interface (English / Khmer). **Khmer strings are a draft and need review by a native speaker** (`core/ui/src/main/res/values-km/strings.xml`).
- Khmer sound kits: Skor Thom/Arak, Sampho, Chhing, Roneat Ek, Kong Vong Thom, Tro Sor/Tro Ou, Khim, Chapei Dong Veng, Pei Pok/Sralai.
  Today only *chhing* and *skor* exist, as synthesised placeholders.
  **Needed:** real recordings we are allowed to use (record local musicians, or CC0/royalty-free packs).
- "Khmer Fusion" Quick Start styles (M5).
- Traditional tuning presets (later, to be verified with Khmer musicians).

## Known limitations (2.0)
- The 2.0 UI has never run on a device or emulator yet: it needs testing on real phones and tablets.
- MP3 export is not offered (Android has no built-in MP3 encoder); M4A (AAC) and WAV are.
- Tempo cannot be automated; audio clips do not time-stretch when the tempo changes.
- Audio stops when the app goes to the background (needs a foreground service for background playback).
- Live recording was part of the removed Pads tab and is not in 2.0 yet.
