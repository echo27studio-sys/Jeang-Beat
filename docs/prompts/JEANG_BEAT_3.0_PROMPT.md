# Jeang Beat 3.0 — Complete Build Prompt

> Paste everything below the line into your AI coding assistant in Android Studio
> (new account / new chat). It is self-contained: it describes the app as it is
> today (2.0), the owner's feedback, and exactly what 3.0 must add or change.

---

## 0. Your role and how to work

You are a senior Android + audio engineer continuing development of **Jeang Beat**, a free
beat-making app for Android phones and tablets, in the style of **FL Studio (PC) and FL Studio
Mobile**, but easier to use, with a Khmer identity (English + Khmer UI, Khmer instruments).

- **Source code:** GitHub repository `echo27studio-sys/Jeang-Beat`, branch
  `claude/github-repo-access-bb97sz` (default branch). Clone it and open it in Android Studio.
  **Read `README.md`, `docs/ROADMAP.md`, `docs/design/v2/README.md` and
  `docs/design/rhythm-circle/README.md` before changing anything.**
- **The owner does not keep code on their PC.** All work must be committed and pushed to GitHub.
  Each push builds a debug APK in GitHub Actions (artifact `jeang-beat-debug-apk`), which the
  owner installs on their phone to test.
- **Work in phases (section 7).** After each phase: build, run all tests, commit, push, and give
  the owner a short summary of what to test on the phone. Do not start the next phase until the
  current one builds and passes tests.
- **Do not break what works.** The Playlist was approved by the owner in 2.0 — change it only
  where this document asks.
- **Ask before** adding a paid service, a library with a restrictive licence, analytics, ads, or
  anything that sends data off the phone. None of these are wanted.
- Write user-facing text in **both** `values/strings.xml` (English) and `values-km/strings.xml`
  (Khmer). Mark new Khmer strings with a `<!-- TODO native review -->` comment.
- Never put secrets or signing keys in the repo.

## 1. Tech stack (keep it)

| Part | Choice |
|---|---|
| Language / UI | Kotlin 2.2, Jetpack Compose (BOM 2025.10.01), Material 3 |
| Build | Gradle 8.14.3 wrapper, AGP 8.13.2, version catalog `gradle/libs.versions.toml`, JDK 17 bytecode |
| SDK | minSdk 26, compileSdk / targetSdk 36 |
| Audio | C++17 engine on **Google Oboe 1.10** (AAudio, low latency), NDK 28.2.13676358, CMake 3.31.6, JNI bridge |
| Data | Immutable Kotlin data classes + kotlinx.serialization JSON (`.jbeat` project files, `.jbz` bundles) |
| Media | Android `MediaExtractor`/`MediaCodec` for decoding and AAC encoding |
| CI | `.github/workflows/android.yml` (tests + debug APK), `release.yml` (signed APK on `v*` tags) |

### Module layout (existing)

```
app/                  StudioViewModel (state, undo/redo, autosave), screens, dialogs, io/ (import/export)
core/model/           Pure Kotlin: Project, Pattern, Note, Channel, Song (Playlist), Mixer, *Ops.kt edits, UndoHistory
core/data/            ProjectStore (.jbeat JSON), ProjectBundle (.jbz zip with samples), WavFile/WavWriter
core/audio/           C++ engine (src/main/cpp/engine): sequencer, voices, mixer buses, effects
                      + Kotlin AudioEngine / OfflineRenderer;  native-tests/ = host C++ tests
core/ui/              Theme (dark + gold), Knob, Fader, ToolButton, DropdownChip, strings (en + km)
feature/pattern/      Channel rack, graph editor, Ring (rhythm circle) view
feature/pianoroll/    Piano roll
feature/playlist/     Playlist + automation editor
feature/mixer/        Mixer strips, effect rack, routing
```

### Architecture rules (keep them)

1. **Every edit is a pure function** `Project -> Project` in `core/model` (`*Ops.kt`), unit-tested.
2. `StudioViewModel.edit {}` = one undo step; `editGesture(final)` for drags (transient frames, one
   undo step when the finger lifts). **Drag frames must be computed from the state at gesture
   start**, never chained on the previous frame (this caused a data-loss bug in 2.0).
3. The audio thread never allocates, locks or frees. The UI sends a whole new snapshot with
   `AudioEngine.load(project, mode)`; effect state is kept per effect-slot id.
4. Compose gesture handlers (`pointerInput`) must be keyed on, or use `rememberUpdatedState` for,
   every value/callback they use.
5. Timing: 96 ticks per beat, 24 ticks per 16th step, 384 ticks per bar (4/4 and 12/8).

## 2. What exists today (2.0) — keep all of it

- **Tabs:** Playlist · Pattern · Mixer. Transport: Play/Stop, **PAT/SONG** switch, bar:beat:step
  display, BPM (tap tempo, swing), undo/redo, ⋮ menu. Portrait = bottom bar, landscape = side rail.
- **Pattern:** FL-style channel rack (mute LED, long-press = solo, pan/volume knobs, mixer-track box,
  steps or mini piano roll), graph editor (velocity/pan/pitch), patterns of 1/2/4 bars in 4/4 or
  12/8, **Ring view** (rhythm circle, tap/rotate/Euclid). Tapping a channel name opens the Piano Roll;
  long-pressing it opens instrument settings.
- **Piano Roll:** draw / paint / select / slice / delete / mute / slide tools, quantize, transpose,
  duplicate, copy/paste, legato, flip, humanize, chord stamps, scale lock, ghost notes, velocity /
  pan / fine pitch / release lanes, two-finger scroll + pinch zoom.
- **Playlist (approved):** tracks with pattern, audio and automation clips, FL tools, song marker,
  loop region, make unique, automation editor.
- **Mixer:** Master + 24 inserts, faders, meters, pan, mute/solo, 10 effect slots (EQ, compressor
  with sidechain, limiter, reverb, delay, saturator, filter), sends, long-press to automate.
- **Instruments:** 12 synthesised drums (incl. Khmer chhing/skor placeholders), 808 with glide,
  2-osc synth, Sampler (imported audio).
- **Files:** autosave, Projects dialog (new/open/save as/delete), audio import, export WAV 16/24 /
  M4A / stems zip, `.jbz` project export/import — **but these are hidden in the ⋮ menu** (see 3.3).
- **Tests:** 43 C++ engine tests (`./core/audio/native-tests/run.sh`), 70 Kotlin tests.

## 3. Owner feedback on 2.0 → required changes in 3.0

### 3.1 Channel management in Pattern (owner: "I cannot find rename / delete / split channel")

The 2.0 functions exist only behind a long-press, which the owner did not discover. Make them obvious:

1. **Channel menu button (⋮) on every rack row** (and the same menu on long-press of the name):
   - Rename · Change colour · Change instrument / replace sample
   - **Clone channel** (same instrument + settings, notes copied in every pattern)
   - **Delete channel** (with confirm + undo snackbar)
   - Clear notes (this pattern) · Clear notes (all patterns)
   - Move up / Move down (and drag-to-reorder via a handle)
   - Route to mixer track… · Open Piano Roll · Instrument settings
   - **Fill each 2 / 4 / 8 steps** (FL "Fill"), **Shift steps left/right**, Randomize velocity
2. **Pattern menu** (pattern name chip) gets visible items and icons:
   Rename · Colour · Clone · Delete · Length (see 3.2) · Meter 4/4 or 12/8 ·
   **Split by channel** (FL Studio feature: turns the current pattern into one pattern per channel
   that has notes, named "<Pattern> – <Channel>", and, if the pattern is used in the Playlist,
   replaces each of its clips with the per-channel clips on consecutive tracks).
3. Multi-select in the rack (tap the colour bars to select several channels) → bulk Delete,
   Route to mixer track, Clone.
4. Unit tests for clone, delete, reorder, split-by-channel (including Playlist clip replacement and undo).

### 3.2 Piano Roll length (owner: "stuck at a limited bar, I cannot extend bars like FL Studio")

2.0 patterns are fixed to 1, 2 or 4 bars and the piano roll stops at the pattern end. 3.0 must
work like FL Studio:

1. **Auto length (default):** a pattern's length is the end of its last note rounded up to a
   whole bar (minimum 1 bar). Drawing, pasting or dragging notes past the end **extends the pattern
   automatically**. Deleting notes shrinks it again.
2. **Fixed length (optional):** Pattern menu → Length → 1/2/4/8/16/32 bars or a custom number,
   or "Auto". Up to **512 bars**.
3. The piano roll grid always shows **at least 2 extra empty bars** after the last note and scrolls
   freely to the right; a **bar ruler** (bar numbers, beat ticks) at the top; tap the ruler to move
   the playhead; long-press-drag on the ruler sets a **loop region** for the piano roll.
4. The channel rack shows as many steps as the pattern is long (scroll horizontally; the step grid
   for long patterns can collapse into the mini-roll view).
5. Playlist clips of an auto-length pattern grow/shrink with the pattern unless they were resized
   by hand (store a `followsPattern` flag on the clip).
6. Model change: `Pattern.lengthMode = AUTO | FIXED` (+ migration: 2.0 patterns become FIXED with
   their current length so old songs sound the same). Engine needs no change (it uses ticks).
7. Tests: auto-extend on add/move/paste, shrink on delete, fixed length clamps, migration.

### 3.3 Save and export must be visible (owner: "I don't see project save or MP3/WAV export")

1. **Transport bar:** add a **Save** icon (shows a dot when there are unsaved changes) and an
   **Export** icon (share/arrow icon). Keep the ⋮ menu too.
2. **File menu** (new, clearly labelled): New project · Open… · **Save** · Save as… · Recent projects ·
   **Export audio…** · Export project (.jbz) · Import project · Import audio.
3. **Export dialog:** Song / Current pattern / Loop region; format **MP3** (128/192/320 kbps),
   **WAV** (16-bit, 24-bit, 32-bit float), M4A (AAC 256k), **Stems** (zip of WAV per mixer track);
   sample rate 44.1 / 48 kHz; normalize on/off; tail length.
   - **MP3:** Android has no built-in MP3 encoder. Add **LAME** (LGPL) built with the NDK as a
     **separate shared library** (`libmp3lame.so`, dynamic linking) so the LGPL is respected; add a
     `THIRD_PARTY_NOTICES.md` and an "Open-source licences" screen. Encode in the same streaming
     way as the WAV/AAC writers (no full song in memory). If the owner rejects LGPL, fall back to
     M4A and explain.
   - **Default destination without a file picker:** save to the public `Music/Jeang Beat/` folder via
     `MediaStore` (no permission needed on API 29+; for API 26–28 use the SAF picker), then show a
     snackbar with **Open** and **Share** buttons (Android share sheet → WhatsApp, Telegram, Drive…).
     Keep "Choose location…" (SAF) as an option.
   - Progress dialog with cancel; partial files are deleted on cancel/failure (2.0 already does this).
4. **Projects:** save to app storage (autosave every edit, as in 2.0) **and** optionally
   "Save a copy to Documents/Jeang Beat/Projects" as `.jbz`. Show "Saved ✓" feedback.
5. Tests: MP3 writer (encode a sine, decode with MediaExtractor in an instrumented test or check
   frame headers in a JVM test), export option combinations, MediaStore path helpers.

## 4. New in 3.0 — Browser with sound-pack folders (FL Studio "Browser")

Goal: like FL Studio's Browser, a panel listing sound kits / samples from **folders on the phone
that stay in sync**, with **drag and drop** directly onto the Channel Rack or the Playlist.

### 4.1 Sources

1. **Built-in packs** shipped in the APK (`assets/packs/…`): the existing synthesised kit rendered
   to WAV plus room for future Khmer packs.
2. **Linked folders:** "＋ Add folder" uses `ACTION_OPEN_DOCUMENT_TREE`; call
   `takePersistableUriPermission` so access survives restarts. Users can link several folders
   (e.g. `Download/Samples`, a Google Drive / Syncthing-synced folder, an SD card).
   This is how "sync with a local folder like FL Studio" works on Android: whatever the user puts
   in that folder (from a PC via USB, cloud sync, downloads) appears in the Browser.
3. **Project samples** (audio already used in the current project), **Recent**, **Favorites** (★).

### 4.2 Sync and index

- Recursive scan of linked folders through `DocumentsContract` (SAF), file types: WAV, AIFF, MP3,
  FLAC, OGG, M4A. Skip hidden files and files > 200 MB.
- Store an index in **Room** (`samples` table: tree URI, document id, path, name, size,
  lastModified, duration, channels, sample rate, detected BPM/key from the file name like
  `Kick_808_C#_140bpm.wav`, favourite flag, tags).
- **Re-sync** on app start / resume (compare `lastModified` + size; only changed files are
  re-read), plus a manual **Refresh** button and pull-to-refresh. Run scans in a `WorkManager`
  job / coroutine on IO; never block the UI. Show "Scanning… 1,240 files".
- If a folder's permission is lost (user revoked it, SD card removed), show the folder greyed out
  with "Reconnect".
- Optional `pack.json` inside a folder (name, author, licence, cover image, BPM, key, tags) to
  display packs nicely.

### 4.3 Browser UI

- **Phone:** a fourth bottom-bar item **Browser** *or* a pull-out drawer from the left edge
  available on every tab (preferred: drawer, so you can drag from it onto the screen below; the
  drawer slides out of the way when a drag starts).
- **Tablet/landscape:** Browser docked on the left like FL Studio; resizable.
- Tree view (folders expand/collapse), breadcrumb, **search** (name/tags), filters
  (Drums / Loops / One-shots / Instruments / Vocals / FX, BPM range, key), sort.
- **Preview:** tap a sample to audition it (through the engine on a preview bus, low latency;
  tapping another sample stops the previous one); **auto-preview** toggle; preview volume;
  optional "preview in song tempo" for loops; small waveform under the selected file.
- Long-press menu: Add to channel rack · Add to Playlist at song marker · Favourite ·
  Show in folder · Details.

### 4.4 Drag and drop

Use Compose drag-and-drop (`Modifier.dragAndDropSource` / `dragAndDropTarget`) with an in-app
payload (sample id), and show a ghost preview with the file name while dragging.

| Drop on | Result |
|---|---|
| Channel Rack, empty area | New **Sampler** channel named after the file, on a free mixer insert |
| Channel Rack, existing channel row | Replace that channel's sample (confirm if it isn't a sampler) |
| Piano Roll | Replace the sample of the open channel |
| Playlist lane | New **audio clip** at the drop position (snapped), on that track; long loops are sliced to the clip length |
| Mixer insert strip | New sampler channel routed to that insert |

- When a sample is used, **copy it into the app's sample store** (as 2.0 does; de-duplicate by
  content hash) so projects never break if the original file is moved, and `.jbz` export stays
  self-contained.
- Drag-and-drop must also work **from other apps** (e.g. Files app in split-screen) where Android
  allows it (`DragAndDropPermissions`).

### 4.5 Tests

Room DAO tests, scanner tests on a fake document tree, file-name BPM/key parser tests,
drop-target logic tests (pure functions that turn a drop into a `Project -> Project` edit).

## 5. Also fix / improve in 3.0

1. **Background playback:** a media-style foreground service so playback continues with the screen
   off (notification with play/stop).
2. **Live recording** (removed with Pads in 2.0): record from the Piano Roll keyboard and from a
   small "Perform" pad overlay in the Pattern tab, with metronome + count-in, quantize on record,
   latency compensation (2.0's `AudioEngine.latencyMillis`).
3. **Metronome** button in the transport.
4. **Tempo automation** (optional, if time allows; engine must map ticks→frames piecewise).
5. **Screenshot tests** for every screen (Roborazzi or Paparazzi) running in CI, so UI changes can
   be checked without a phone. (2.0's Robolectric test was removed because a 159 MB Maven
   Central download was rate-limited — cache it in CI or use Paparazzi's layoutlib from Google Maven.)
6. Update the GitHub Actions to Node-24 versions of the actions (deprecation warnings in 2.0).

## 6. Quality bar ("done" means)

- Builds with `./gradlew :app:assembleDebug`; all tests pass:
  `./gradlew :core:model:test :core:data:test :core:audio:testDebugUnitTest` and
  `./core/audio/native-tests/run.sh` (with ASan/UBSan).
- No allocations/locks on the audio thread; no `!!` on data from files or the user.
- Every new string in English **and** Khmer; every new control has a `contentDescription`;
  touch targets ≥ 40 dp.
- Old projects (`formatVersion` 1 and 2) open unchanged → bump to `formatVersion = 3` with a tested
  migration.
- Works in portrait and landscape, phone and tablet.
- README, ROADMAP and a `docs/design/v3/README.md` (with mockups) updated.

## 7. Phases (build, test, commit, push after each)

| Phase | Scope | Owner tests on phone |
|---|---|---|
| **3.1** | Visible Save / Export icons + File menu; MediaStore export to Music/Jeang Beat + share; channel ⋮ menu (rename, colour, clone, delete, reorder, fill, clear); pattern menu clearly labelled | Save, rename/delete/clone channels, export WAV and share it |
| **3.2** | Auto-length patterns + fixed lengths up to 512 bars; piano roll ruler, endless scroll, loop region; Split by channel; migration to formatVersion 3 | Draw an 8-bar melody; split a drum pattern by channel |
| **3.3** | MP3 export (LAME via NDK, LGPL notices); export options (sample rate, normalize, loop region) | Export MP3 320 kbps and play it in another app |
| **3.4** | Browser: linked folders (SAF), Room index, sync/refresh, search/filters, preview, favourites | Link a samples folder, add files from a PC, refresh, preview |
| **3.5** | Drag & drop from Browser to Channel Rack / Playlist / Mixer / Piano Roll | Build a beat only by dragging samples |
| **3.6** | Background playback service, metronome, live recording + Perform pads, screenshot tests in CI | Record a melody live; play with screen off |

## 8. Owner preferences to respect

- Free app, distributed as an APK first (Play Store later). No ads, no account, works offline.
- FL Studio PC workflow and names where possible (Channel Rack, Playlist, Piano Roll, Mixer,
  Browser, PAT/SONG), but simpler on a phone.
- Khmer identity: bilingual UI; Khmer rhythms and instrument samples are added only after
  transcription/recording with Khmer musicians (mark anything unverified as DRAFT).
- Dark theme with gold accent (`JbColors` in `core/ui`).
- Short, clear progress reports in plain English after each phase, with a list of what to test.
