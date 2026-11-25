# Jeang Beat

A free beat-making app for Android phones and tablets. The goal is to be **easier
than FL Studio Mobile and just as professional**, with a Khmer identity: a
bilingual interface (English / ខ្មែរ) and Khmer instruments.

> Status: **3.0 built, needs testing on phones**. See [docs/ROADMAP.md](docs/ROADMAP.md), the [3.0 notes](docs/design/v3/README.md) and the [2.0 design](docs/design/v2/README.md).

## New in 3.0

| Area | What changed |
|---|---|
| **Save & export** | Save and Export icons in the transport (gold dot = unsaved), a labelled **File** menu. Export **MP3** (128/192/320 kbps), WAV 16/24/32-bit float, M4A, stems; song / pattern / loop region; 44.1 or 48 kHz; normalize. Saves straight to `Music/Jeang Beat` and offers **Share** |
| **Channels** | ⋮ menu on every channel: rename, colour, replace sound, clone, delete (undo), clear, move, fill each 2/4/8, shift, randomize velocity. Pattern menu: colour, length, **Split by channel** |
| **Long patterns** | Patterns grow automatically as you draw (up to 512 bars) or have a fixed length. Piano roll: bar ruler, tap to move the playhead, long-press-drag for a loop region |
| **Browser** | Link sample folders (Download, a synced Drive / Syncthing folder, SD card). They stay in sync; search, filters, favourites, preview. **Long-press and drag** a sound onto the Channel Rack, Playlist, Mixer or Piano Roll |
| **Play & record** | Playback continues with the screen off (notification with Stop). Metronome, **Record** with count-in into the pattern from the piano-roll keys or the **Perform pads** |

## What's in 2.0

Three tabs, like FL Studio: **Playlist · Pattern · Mixer**. The transport has FL's **PAT / SONG** switch.

| Area | Features |
|---|---|
| **Pattern** | FL Studio PC-style channel rack: mute LED (long-press = solo), pan/volume knobs, mixer track box, step buttons or a mini piano roll per channel, graph editor (velocity / pan / pitch), swing, 1–4 bar patterns in 4/4 or 12/8. **Ring** view: the pattern as shapes on a rhythm circle; tap to toggle, drag to rotate, Euclid with presets (tresillo, clave, bossa, 12/8 bell…) |
| **Piano Roll** | Opens from a channel name. Tools: draw, paint, select, slice, delete, mute, slide notes. Quantize, transpose, duplicate, copy/paste, legato, flip, humanize, chord stamps, scale highlighting and lock, ghost notes, velocity / pan / fine pitch / release lanes. Two-finger scroll, pinch zoom |
| **Playlist** | Tracks with pattern, audio and automation clips. Tools: draw, paint, select, slice, delete, mute. Song marker, loop region, make unique, automation point editor |
| **Mixer** | Master + 24 inserts: fader, pan, stereo meters, mute/solo. 10 effect slots per track with mix knobs — **EQ** (live curve), **compressor** with **sidechain**, **limiter**, **reverb**, **tempo-synced delay**, **saturator**, **filter**. Sends between tracks (loops prevented). Long-press any fader or knob to automate it |
| **Audio engine** | Native C++ on Google Oboe: sample-accurate song playback, mixer buses processed in routing order, effect tails survive edits, nothing allocated on the audio thread |
| **Files** | Import audio (MP3, WAV, M4A, OGG, FLAC…) as a Playlist clip or a sampler instrument. Export / import whole projects as `.jbz` (with their samples). Autosave and undo/redo |
| **Languages** | English and Khmer |

## Download

Debug APKs are built by GitHub Actions on every push (Actions → *Build & test* →
artifact `jeang-beat-debug-apk`). Signed release APKs will appear on the
Releases page once a release key is set up — see [docs/RELEASING.md](docs/RELEASING.md).

## Project structure

```
app/                 App shell: StudioViewModel (state, undo, autosave), screens, dialogs,
                     io/ (audio import via MediaCodec, MP3/WAV/M4A/stems export, MediaStore),
                     browser/ (linked folders, index, preview), PlaybackService
core/model/          Pure Kotlin: project, patterns, playlist, mixer, editing operations, undo
core/data/           .jbeat project files (JSON), .jbz bundles, WAV read/write,
                     browser/ (folder scanner, file-name BPM/key parser, index)
core/audio/          C++ audio engine (src/main/cpp): sequencer, voices, mixer, effects
                     + Kotlin API (AudioEngine, OfflineRenderer)
  native-tests/      Desktop tests for the C++ engine
core/ui/             Theme, knobs, faders, English + Khmer strings
feature/pattern/     Channel rack, graph editor, Ring view
feature/pianoroll/   Piano roll
feature/playlist/    Playlist (song arrangement) and automation editor
feature/mixer/       Mixer strips, effects, routing
```

How it fits together: the UI edits an immutable `Project`. Every edit is pushed
to the undo history, autosaved, and sent to the engine with `AudioEngine.load()`,
which builds a new playback snapshot and swaps it into the audio thread without
locks or allocations on that thread.

## Building

Requirements: JDK 17+, Android SDK (platform 36, NDK 28.2.13676358, CMake 3.31.6).

```bash
./gradlew :core:model:test :core:data:test :core:audio:testDebugUnitTest   # Kotlin unit tests
./gradlew :app:assembleDebug      # APK in app/build/outputs/apk/debug/
./core/audio/native-tests/run.sh  # C++ engine tests (needs cmake + ninja)
```

## Licences and sounds

MP3 export uses **LAME 3.100** (LGPL), built as a separate shared library; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and *File → Open-source licences* in the app.

All built-in sounds are synthesised in code (`core/audio/src/main/cpp/engine/DrumKit.cpp`),
so the app ships with no third-party samples. The Khmer *chhing* and *skor* sounds
are placeholders until real recordings are made.
