# Jeang Beat 3.0

3.0 answers the owner's feedback on 2.0 ([full prompt](../../prompts/JEANG_BEAT_3.0_PROMPT.md)).
Playlist stays as approved; everything else below is new.

## 1. Save and export you can find
- Transport: **Save** (gold dot while there are unsaved edits) and **Export** icons next to undo/redo.
- **File** menu (labelled button): New, Open…, Save, Save as…, Save a copy to *Documents/Jeang Beat/Projects*,
  Recent projects, Export audio, Export / import project (`.jbz`), Import audio, Language, Open-source licences.
- **Export dialog**: Song · Current pattern · Loop region; **MP3** 128/192/320 kbps (default 320) · WAV 16 / 24 /
  32-bit float · M4A · Stems; 44.1 or 48 kHz; Normalize (−0.3 dBFS). Destination: *Music/Jeang Beat*
  (no file picker, Android 10+) or "Choose location…". A snackbar then offers **Share**.
- MP3 uses LAME 3.100 (LGPL) as its own `libmp3lame.so`, encoding while rendering (no whole song in memory).

## 2. Channels and patterns
- Every channel row has a **⋮** menu: rename, colour, replace sound, clone (with notes in every pattern),
  delete (snackbar with Undo), clear (this pattern / all), move up/down, fill each 2/4/8, shift left/right,
  randomize velocity, open piano roll, settings.
- Pattern menu: rename, colour, clone, delete, **Length** (Auto, 1–32 bars, custom up to 512), meter,
  **Split by channel** (one pattern per channel; its Playlist clips are replaced on consecutive tracks).

## 3. Long patterns (FL Studio-style)
- `Pattern.lengthMode`: **AUTO** (default; the end of the last note rounded up to a bar, at least 1 bar)
  or **FIXED**. Old projects are migrated to FIXED so they sound the same (project format 3).
- The piano roll shows two empty bars after the end and scrolls on; the **ruler** moves the playhead
  (tap) and sets a loop region (long-press and drag; ⟲✕ clears it).
- Playlist clips of an auto-length pattern follow its length unless they were resized by hand.

## 4. Browser (sound packs from folders)
- Open it with the 🎵 button at the left of the transport. Phone: a drawer that slides away as soon as a
  drag starts. Landscape / tablet: docked on the left.
- **Add folder** opens Android's folder picker; access is kept across restarts. Anything copied there
  (USB from a PC, Drive / Syncthing sync, downloads) appears after **Refresh** or when the app comes back.
  A folder whose access was lost is greyed out with **Reconnect**.
- The index is a JSON file in app storage. Rescans only re-read files whose size or date changed.
  BPM, key and kind are read from file and folder names ("Dark Piano Loop 90 Am.wav").
- Search, filters (★, Drums, Loops, One-shots, Instruments, Vocals, FX), sub-folder tree, recent files,
  project samples and the built-in sounds. Tap to preview (auto-preview can be turned off).
- **Drag and drop** (long-press a row): Channel Rack empty area → new channel · channel row → replace its
  sound · Playlist lane → audio clip at the snapped position (below the last track adds a track) ·
  Mixer strip → new channel routed there · Piano Roll → replace the open channel's sound.
  Audio files dragged from another app (split screen) work the same way. The ⋮ menu does the same without dragging.
- Used files are copied into the app's sample store named by content hash, so a file is stored once and
  projects keep working when the original is moved.

## 5. Playing and recording
- **Background playback**: while playing, a media notification with **Stop** keeps the beat going with
  the screen off or in another app.
- **Metronome** button (⏱) and **Record** (●). Record switches to PAT, counts in (long-press ● for off / 1 / 2 bars),
  and records the piano-roll keyboard and the **Perform pads** (button at the bottom right of the Pattern tab)
  into the current pattern. Hits are moved back by the output latency and quantized to the piano-roll snap.
  Tap ● again to stop recording; the whole take is one undo step.

## Tests added
`ChannelOpsTest`, `SplitByChannelTest`, `AutoLengthTest`, `DropOpsTest`, `RecordOpsTest` (Kotlin model),
`BrowserTest` (parser, scanner, index, search), engine tests for the pattern loop, metronome and count-in,
and an MP3 encoder test (`core/audio/native-tests`).
