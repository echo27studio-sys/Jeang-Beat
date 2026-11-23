# Jeang Beat

A free beat-making app for Android phones and tablets. The goal is to be **easier
than FL Studio Mobile and just as professional**, with a Khmer identity: a
bilingual interface (English / ខ្មែរ) and Khmer instruments.

> Status: early development (milestones M0–M3 done). See [docs/ROADMAP.md](docs/ROADMAP.md).

## What works today

| Area | Features |
|---|---|
| **Audio engine** | Native C++ engine on Google Oboe (low-latency AAudio), timing accurate to the sample, glitch-free live edits, soft limiter on the master |
| **Step sequencer** | Channel rack with 16/32/64 steps, mute/solo, velocity editing by dragging, swing, playhead |
| **Drum pads** | 4×4 multi-touch pads that fire on finger-down, note repeat (1/8, 1/16, 1/16T, 1/32), record into the pattern with latency compensation, keys mode for melodic playing |
| **Piano roll** | Tap to add, tap to delete, drag to move, drag the end to resize, snap, scale lock (folds away off-scale rows), ghost notes from other instruments, velocity lane, zoom |
| **Instruments** | 12 synthesised drum sounds (including Khmer *chhing* and *skor* placeholders), 808 bass with punch/drive and legato glide, 2-oscillator synth with resonant filter |
| **Projects** | Multiple patterns per project, undo/redo, autosave, save as / open / delete, demo beat on first launch |
| **Languages** | English and Khmer, switchable in the app |
| **Screens** | Phone: bottom tabs (Pads / Steps / Piano Roll). Tablet or landscape: editor and pads side by side |

## Download

Debug APKs are built by GitHub Actions on every push (Actions → *Build & test* →
artifact `jeang-beat-debug-apk`). Signed release APKs will appear on the
Releases page once a release key is set up — see [docs/RELEASING.md](docs/RELEASING.md).

## Project structure

```
app/                 App shell: StudioViewModel (state, undo, autosave), screens, dialogs
core/model/          Pure Kotlin: project data, editing operations, scales, undo history
core/data/           Saving/loading .jbeat project files (JSON)
core/audio/          C++ audio engine (src/main/cpp) + Kotlin API (AudioEngine)
  native-tests/      Desktop tests for the C++ engine
core/ui/             Theme, shared controls, English + Khmer strings
feature/pads/        Drum pads / keys
feature/sequencer/   Step sequencer (channel rack)
feature/pianoroll/   Piano roll
```

How it fits together: the UI edits an immutable `Project`. Every edit is pushed
to the undo history, autosaved, and sent to the engine with `AudioEngine.load()`,
which builds a new playback snapshot and swaps it into the audio thread without
locks or allocations on that thread.

## Building

Requirements: JDK 17+, Android SDK (platform 36, NDK 28.2.13676358, CMake 3.31.6).

```bash
./gradlew test                    # Kotlin unit tests + screen renders (app/build/screenshots)
./gradlew :app:assembleDebug      # APK in app/build/outputs/apk/debug/
./core/audio/native-tests/run.sh  # C++ engine tests (needs cmake + ninja)
```

## Licences and sounds

All built-in sounds are synthesised in code (`core/audio/src/main/cpp/engine/DrumKit.cpp`),
so the app ships with no third-party samples. The Khmer *chhing* and *skor* sounds
are placeholders until real recordings are made.
