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
| M4 | Song and mix: song arrangement timeline, mixer, core effects (EQ, compressor, reverb, delay, filter, distortion, limiter) | Next |
| M5 | Easy mode and export: Quick Start loops (incl. Khmer Fusion), WAV/MP3 export, sharing → **first public APK (v0.1)** | |
| M6+ | Vocal recording, sample chopper, automation, sidechain, chord generator, MIDI controllers, stems, tutorial | |

## Khmer identity
- Bilingual interface (English / Khmer). **Khmer strings are a draft and need review by a native speaker** (`core/ui/src/main/res/values-km/strings.xml`).
- Khmer sound kits: Skor Thom/Arak, Sampho, Chhing, Roneat Ek, Kong Vong Thom, Tro Sor/Tro Ou, Khim, Chapei Dong Veng, Pei Pok/Sralai.
  Today only *chhing* and *skor* exist, as synthesised placeholders.
  **Needed:** real recordings we are allowed to use (record local musicians, or CC0/royalty-free packs).
- "Khmer Fusion" Quick Start styles (M5).
- Traditional tuning presets (later, to be verified with Khmer musicians).

## Known limitations (M0–M3)
- Song mode is not there yet: the current pattern loops.
- No export yet (M5).
- Audio stops when the app goes to the background (needs a foreground service for background playback).
- Pad velocity is fixed (110); velocity by touch position is planned.
- The UI has only been checked on the JVM (Robolectric renders) and needs testing on real phones and tablets.
