# Speechnotes-Android — Current State Report

**Purpose:** authoritative inventory of what `speechnotes-android` actually implements today, with
file:line evidence. Written to be pasted into / used as the source for an `AGENTS.md`.

**Audit date:** 2026-10-23
**App version:** `1.1.4` (versionCode `6`) — `app/build.gradle.kts:20-21`
**Package:** `com.speechnotes.app` — `app/build.gradle.kts:12`
**Min SDK 26 / target SDK 36 / compile SDK 36** — `app/build.gradle.kts:13-18`
**Not a git repository** — `git status` returns "not a git repository". There is no commit history
to consult; this document plus `IOS-PARITY-PLAN.md` and `research/*.md` are the only prose record.

## Scale

| Metric | Value |
|---|---|
| Main Kotlin files | 72 |
| Main Kotlin LOC | 21,723 |
| Test Kotlin files | 10 |
| Test Kotlin LOC | 1,455 |
| Unit tests | **78 — all green** (verified 2026-10-23 via `./gradlew --offline testDebugUnitTest --rerun-tasks`) |
| Package distribution | `logic/` 23 · `data/` 15 · `ui/` 15 · `speech/` 11 · `model/` 6 · `playback/` 2 |

Files by package:

```
app/src/main/java/com/speechnotes/app/
├── SpeechnotesApplication.kt   # private-file layout init, haptics pref, BookDrop receiver
├── MainActivity.kt             # edge-to-edge host, intent intake, lifecycle hooks
├── logic/    (23)              # pure-Kotlin ports of the iOS SpeechLogic package
├── speech/   (11)              # engines, streaming core, player
├── data/     (15)              # stores, persistence, ViewModel
├── model/    (6)               # Note, Notebook, Book, ReadingGoal, ReaderAppearance, VoiceCatalog
├── playback/ (2)               # foreground service + registry
└── ui/       (15)              # Compose screens
```

---

## 1. Architecture and conventions

### 1.1 Layering

`MainActivity` → `SpeechnotesApp` (Compose nav host) → `SpeechnotesViewModel` (one observable
surface owning every store and both players) → screens.

`data/SpeechnotesViewModel.kt` is 1,637 lines and is the single place the UI talks to. It exposes
`StateFlow`s for everything the UI observes (`books`, `notes`, `goals`, `readerAppearance`,
`themeState`, `renderMarkdown`, `readAlongEnabled`, `notesSortOrder`, `activeNotebookScope`,
`hasOnboarded`, `miniPlayerCollapsed`, `toast`, `engineKind`, …) and plain functions for mutations
(`createNote`, `importBook`, `deleteBookWithUndo`, `setEngineKind`, `addGoal`, `updateGoal`,
`setReaderSpacing`, …). `data/SpeechnotesViewModel.kt:35` class declaration; public API spans the
whole file.

### 1.2 The `logic/` package — the parity contract

`logic/` is a deliberately Android-free port of the iOS `Packages/SpeechLogic` package. The rule
the repo follows: **the algorithms are ported verbatim, the JSON field names are shared with iOS so
data can move between platforms.** `README.md:70-73` states this explicitly.

| Module | Role |
|---|---|
| `SentenceChunker.kt` | Sentence boundaries, first-chunk cap, batch packing, resume offsets, read-along pieces |
| `SpeechSanitizer.kt` | Strips unspeakable codepoints (zero-width, bidi, C0/C1, variation selectors) |
| `SpeechText.kt` | The single boundary every speak path goes through (sanitized, markdown-aware) |
| `MarkdownText.kt` | One scanner → `plainText()` for speech + `blocks()` for the preview |
| `MarkdownSlashMenu.kt` | 18 slash commands + fuzzy filter, UTF-16 offsets |
| `XhtmlText.kt` | EPUB chapter XHTML → speech text (entities, block splitting, tables, alt text) |
| `EpubInfo.kt` | EPUB metadata / spine / TOC / cover without a WebView |
| `ZipReader.kt` | Minimal read-only ZIP (STORE + DEFLATE, ZIP64) |
| `ZipWriter.kt` | Store-only ZIP writer + CRC-32 + pixel-size sniffing |
| `PdfText.kt` | PDF chapter resolution (outline → heading geometry → page ranges), two-column split |
| `NotePdfLayout.kt` | Pure page layout for the note→PDF export (US Letter, 48 pt margins) |
| `AudiobookChapters.kt` | MP4 `chpl` + Nero chapter tracks + ID3 `CHAP` |
| `DocumentBook.kt` | Normalize-to-EPUB dispatch table + document model types |
| `DocumentParsers.kt` | DOCX + ODT SAX parsers, shared `chapterize()` |
| `DocumentSlides.kt` | PPTX + ODP parsers (one chapter per slide/page) |
| `DocumentEpub.kt` | EPUB 3 converter (mimetype-first, nav + ncx, real `<table>`/`colspan`) |
| `JexExport.kt` / `JexImport.kt` | Joplin `.jex` tar writer / reader |
| `NoteImageStore.kt` | `filesDir/note-images/<noteId>/<hash>.<ext>`, `speechnotes://note-image/…` targets |
| `TextHash.kt` | FNV-1a 64-bit stable hash (bookmarks + image store) |
| `WavWriter.kt` | 16-bit PCM RIFF/WAVE mono encoder, 44-byte header |
| `AppFiles.kt` | The private-file layout + `atomicWrite` |

### 1.3 On-disk layout

Everything lives under `application.filesDir` — **no storage permissions**.

```
filesDir/
├── notes.json  + notes.backup.json
├── notebooks.json + notebooks.backup.json
├── bookmarks.json
├── stats.json
├── reading-goals.json
├── note-images/<noteId>/<hash>.<ext>
├── Books/<bookId>/
│   ├── manifest.json          # one Book JSON per book
│   ├── original.<ext>
│   ├── cover.jpg
│   └── text/%04d.txt + %04d.pages.json   # cached chapter speech text + page offsets
├── Exports/                   # WAV exports, .jex backups, shared logs
├── KokoroOnnx/                # model.onnx / model_uint8.onnx / tokenizer.json / voices.npz
├── Supertonic/                # onnx/… + voice_styles/M1..F5.json
└── BookDrop/incoming/         # received transfers, swept 30 s after routing
```

`logic/AppFiles.kt:19-80` is the authoritative path table.

### 1.4 Build / toolchain

- AGP `8.9.1`, Kotlin `2.4.20`, Compose BOM `2026.09.00` — `gradle/libs.versions.toml`
- **Compose is force-pinned back to 1.9.1 and material3 to 1.3.2** because the M3 Expressive BOM
  resolves Compose 1.12.x, which needs compileSdk 37. The comment block at
  `app/build.gradle.kts:63-101` documents this; `material3` 1.4.0 is pinned to 1.3.2 because the
  BOM's 1.4.0 is compiled against the 1.12 runtime, and that mismatch was the prime suspect for
  on-device interaction-timing bugs.
- ABI splits are opt-in via `-Psplits=true` → `x86_64` + `arm64-v8a`, no universal APK.
  `app/build.gradle.kts:33-42`.
- **JDK 17 is required** (system Java is 11):
  `JAVA_HOME=~/tools/jdk-17.0.13+11 ./gradlew --offline assembleDebugX86_64`
- Dependencies of note: `onnxruntime-android` 1.30.0, `pdfbox-android` 2.0.27.0,
  `mlkit-text-recognition` 16.0.1, `androidx.webkit` 1.12.1, `coil-compose` 3.1.0,
  `okhttp` 5.3.2, `androidx.media` 1.7.0 (MediaSessionCompat only — **no Media3/ExoPlayer**),
  `kotlinx.serialization` 1.11.0, `datastore-preferences` 1.2.1 (declared, largely unused —
  preferences actually go through SharedPreferences).

---

## 2. Speech stack

### 2.1 Engine contract — `speech/SpeechEngine.kt`

- `SpeechState` = `IDLE | GENERATING | SPEAKING | PAUSED` — `SpeechEngine.kt:7-8`
- `SpeechEngine` interface: `onStateChanged`, `onProgress` (chunk-granular 0…1),
  `onFinished` (**natural end only**), `onPlayedChars` (UTF-16 count of the text actually
  sounding), `speed` (live, applies from the next chunk), `speak/pause/resume/stop`,
  `prewarm()` — `SpeechEngine.kt:18-49`
- `EngineKind` enum, 4 values, picker order worst → best — `SpeechEngine.kt:51-64`:
  `SYSTEM("system")`, `KOKORO_SMALL("kokoroSmall")`, `KOKORO("kokoroOnnx")`, `SUPERTONIC("supertonic")`.
  `isNeural = this != SYSTEM`.
- `ExportState` = `Idle | Running(progress) | Failed(message)` — `SpeechEngine.kt:67-71`

### 2.2 Engine matrix

| | System TTS | Kokoro fp32 | Kokoro uint8 | Supertonic |
|---|---|---|---|---|
| Model dir | — | `KokoroOnnx` | `KokoroOnnx` | `Supertonic` |
| Model file | — | `model.onnx` | `model_uint8.onnx` | 4 × `.onnx` + `tts.json` + indexer + 10 styles |
| Enforced min bytes | — | 300,000,000 | 120,000,000 | 3M + 30M + 200M + 90M + styles |
| Voices | all system | 28 | 28 | 10 styles × 32 langs |
| Sample rate | platform | 24,000 | 24,000 | 44,100 (published by `tts.json`) |
| `chunkMaxChars` | — | 160 | 160 | 200 |
| `firstChunkMaxChars` | — | **100** | **100** | **60** |
| Export inter-chunk silence | — | 0.05 s | 0.05 s | 0.05 s |
| Step count | — | n/a | n/a | 8 (4 only at CRITICAL thermal) |
| Idle unload | — | 5 min | 5 min | 5 min |

Evidence: `speech/SystemTtsEngine.kt`, `speech/KokoroEngine.kt:68-82`, `speech/SupertonicEngine.kt:76-108`,
`speech/ModelFiles.kt:31-96`.

### 2.3 System TTS — `speech/SystemTtsEngine.kt`

- `android.speech.tts.TextToSpeech`, locale hardcoded `Locale.US` — `:146`, `:177`
- **Whole-utterance, not chunked** — this is a deliberate divergence from iOS, which chunks
  (`SystemEngine.swift:342-347`, firstMaxChars 120 / batchMaxChars 320). Android's system TTS is
  a service, so chunking is not needed to avoid the iOS whole-utterance stall.
- Pause is implemented by **replaying the remainder**: `lastRangeEnd` is remembered, the epoch is
  bumped first so stale callbacks cannot clobber `PAUSED`, `tts.stop()`, then `PAUSED`;
  `resume()` re-speaks from the offset — `:180-231`
- Epoch guard on utterance ids (`"$epoch:${UUID}"`) — `:134-140`
- Progress coalesced to every 300 ms — `:98-99`

### 2.4 Kokoro — `speech/KokoroEngine.kt`

- ONNX Runtime, `input_ids` int64 `[1,N]`, `style` float32 `[1,256]`, `speed` float32 `[1]` →
  `waveform` float32 @ 24 kHz — `KokoroEngine.kt:19-24`
- Style slice = `clamp(N-2, 0, rows-1) * 256` — `:222-227`
- `MAX_TOKENS = 510`; over-length chunk → error + diagnostic log — `:213-218`
- 4 intra-op threads, `OptLevel.ALL_OPT` — `:157-160`
- Model validity is TTL-cached 30 s, success latched, failure never latched — mirroring iOS
- `firstChunkMaxChars = 100` with the measured-RTF rationale comment — `:71-79`
- G2P by `MisakiG2P.kt` (dictionary-backed, `assets/g2p/{us,gb}_{gold,silver}.json.gz`,
  numbers→words, letter-by-letter fallback) — `speech/MisakiG2P.kt:18-155`
- Voices: 28, listed in `model/VoiceCatalog.kt:26-33`; default `am_eric`

### 2.5 Supertonic — `speech/SupertonicEngine.kt`

- 4 ONNX sessions (duration predictor, text encoder, vector estimator, vocoder) + unicode indexer
  + 10 style files — `ModelFiles.kt:75-96`
- 10 styles `M1…M5`, `F1…F5`; **32 languages** (`en, ko, ja, ar, bg, cs, da, de, el, es, et, fi,
  fr, hi, hr, hu, id, it, lt, lv, nl, pl, pt, ro, ru, sk, sl, sv, tr, uk, vi, na`) —
  `SupertonicEngine.kt:427-430`; default `M1` / `en`
- Sample rate starts at a provisional 24,000 and is **replaced from `tts.json`
  `ae.sample_rate`** (shipped graphs are 44,100); `core.setSampleRate()` propagates it —
  `SupertonicEngine.kt:188-200`
- `firstChunkMaxChars = 60` with the device-log rationale (155 chars → 15.3 s render; 11 chars →
  1.7 s) — `:76-80`
- Thermal is read **per chunk** via `PowerManager.currentThermalStatus` —
  `SupertonicEngine.kt:62-73`
- Unknown scalar codepoints map to the **space id**, not `-1` (the "Supertonic is silent" fix) —
  `:270-285`
- Vocoder output trimmed to `min(predicted duration, wav.size)` — `:357-363`

### 2.6 Render-ahead bank — `speech/RenderAheadBankPolicy.kt`

A pure, unit-tested policy object (14 tests) that **was ported with full fidelity from iOS**:

- Byte cap 32 MB — `:59`
- Target seconds: `NOMINAL`/`FAIR` 30 s, `SERIOUS` 15 s, `CRITICAL` 8 s — `:79-83`
- Pressure factor: 1.0 / 1.0 / 0.5 / 0.25 — `:111-115`
- Effective target = `min(targetSeconds, byteCap / bytesPerSecond)` — `:127-132`
- **Step ladder** — `:95-98`:
  ```kotlin
  fun totalStep(thermal: ThermalPressure): Int = when (thermal) {
      ThermalPressure.CRITICAL -> 4
      else -> FULL_QUALITY_TOTAL_STEP   // 8
  }
  ```
  i.e. NOMINAL 8, FAIR 8, SERIOUS 8, CRITICAL 4. The comment at `:17-23` records the iOS device
  verdict verbatim: shedding at *serious* rendered ~90 % of a book at 4 steps with audible
  artifacts, so quality outranks speed — 8 steps everywhere except critical.
- `allows(bankedSeconds, targetSeconds)` = `bankedSeconds < targetSeconds` — `:154-155`
- Exhaustion threshold 0.5 s — `:163-166`

### 2.7 Streaming core — `speech/StreamingPlaybackCore.kt` (489 lines)

- **Threading contract:** the main thread owns pipeline state, the `AudioTrack`, state transitions
  and every callback; a single-thread `generateQueue` executor owns model state and synthesis —
  `:24-28`, `:67-69`
- Chunking: `SentenceChunker.chunks(clean, firstMaxChars, batchMaxChars)` — `:138-147`. The
  comment records the bug that motivated the port: a ×3 batch (480 chars) blew Kokoro's
  `MAX_TOKENS` and produced "chunk 0 failed: token count 681".
- Pause pauses **generation**, not just playback — `:176-178`
- Render-time EWMA: `last = last * 0.3 + wall * 0.7` — `:197`
- **Pacing:** `bankChunks = maxOf(1, (config.bankTargetSeconds / 4.0).toInt())` permits on a
  `Semaphore` — `:165-166`. This is an approximation of the seconds-based bank.
- **Prebuffer:** `max(1.2 s, lastChunkRenderSeconds + 1.0 s)` — `:271-285`
- `AudioTrack`: `MODE_STREAM`, `PCM_16BIT`, mono, buffer `max(byteSize*2, 1 MB)` capped at 8 MB —
  `:357-394`
- **Underrun recovery:** writes are `WRITE_NON_BLOCKING` (a blocking write on a disabled track
  parks the main thread forever); `fit == 0` → `track.play()` + 20 ms sleep, hard-capped at ~2 s
  after which the unwritten tail is re-routed to `writeRemainder` — `:235-266`, `:335-355`
- **Completion poller:** 100 ms tick on `playbackHeadPosition`; a frozen head triggers `play()` to
  revive an underrun-disabled track — `:302-323`
- `setSampleRate` tears down and rebuilds the track — `:111-123` (the A7 fix; Supertonic
  publishes 44,100 after load, so without this the audio would play at the wrong rate)
- `exportWav` re-chunks with the same caps and writes through `WavWriter` — `:452-481`
- `finish()` sets `IDLE` **before** clearing `running` so book auto-advance wins — `:403-411`

### 2.8 Player — `speech/SpeechPlayer.kt` (845 lines)

- Engine kind / voice / rate prefs; **auto-fallback to system TTS when the selected neural model
  is not downloaded** (`usingSystemFallback`) — `:195-206`, `:753-762`
- Audio focus via `AudioFocusRequest` (`AUDIOFOCUS_GAIN`); LOSS → stop, LOSS_TRANSIENT → pause —
  `:149-185`
- **Idle unload:** 5 minutes → `kokoroEngine.unloadModel()` + `supertonicEngine.unloadModel()` —
  `:293-302`
- **Sleep timer:** `sealed class SleepTimer { Off; Minutes(Int); EndOfChapter }` — `:100-104`;
  `Minutes` delays then stops; `EndOfChapter` is handled in `onFinish()` so it stops instead of
  advancing — `:337-343`, `:680-694`
- **Bookmarks:** position-source priority = engine char high-water → progress fraction →
  wall-clock estimate at 14.5 chars/s — `:635-643`; resume snaps to a sentence boundary via
  `SentenceChunker.resumeOffset` — `:578-586`
- `onBackground()` persists the bookmark; `onForeground()` is a **no-op** — `:619-627`
- **Audition:** fixed sample sentence, temporarily swaps the voice, restores the previous voice
  when the sample ends; a 200 ms poller gives up after 8 s ("engine never started") and hard-caps
  at 120 s — `:698-749`
- **Export:** refuses without a neural engine ("The system voice can't export audio — pick a
  neural engine."); output `Exports/<safeFilename(title)>.wav` — `:790-837`
- Read-along pieces come from `SentenceChunker.sentencePieces` — `:537-538`, `:657-676`
- `remoteToggle()` / `remoteChapterSkip(delta)` for lock-screen control — `:321-328`

### 2.9 Beep fallback — `speech/BeepPlayer.kt`

`ToneGenerator`, `STREAM_MUSIC`, volume 50, `TONE_PROP_BEEP` for 150 ms, released after 400 ms —
`:16-18`. Called by the streaming core on a failed chunk — `StreamingPlaybackCore.kt:198`.

### 2.10 Audiobook player — `speech/AudioBookPlayer.kt` (365 lines)

- **`android.media.MediaPlayer`** — one per book, prepared asynchronously — `:59`, `:247-299`
- **No Media3/ExoPlayer anywhere** (grep for `media3|exoplayer` returns nothing). iOS picks a
  backend per container; Android has one.
- Chapters from `Book.audioChapters`; `stepChapter(delta)` clamps and seeks — `:142-146`
- Auto-advance on `OnCompletionListener` — `:274-285`
- ±15 s via `seekByMs` — `:160-164`
- Speed 0.5–2.0 via `playbackParams.setSpeed` — `:184-187`
- Sleep timer in ms; **no "end of chapter" option for audiobooks** — `:167-182`
- Position persisted every ~10 s (20 ticks × 500 ms) and on every pause — `:302-338`
- Uses the **deprecated** `requestAudioFocus(listener, …)` API, unlike `SpeechPlayer` which uses
  `AudioFocusRequest` — `:67-88`

### 2.11 Foreground service — `playback/SpeechPlaybackService.kt` (347 lines)

- `MediaSessionCompat` ("Speechnotes"), notification id 41, channel "playback"
  (`IMPORTANCE_LOW`), `NotificationCompat.MediaStyle` — `:51-87`, `:278-305`
- `foregroundServiceType="mediaPlayback"`, `START_NOT_STICKY` — `:336`, manifest `:70-73`
- Commands: `onPlay`, `onPause`, `onSkipToNext`, `onSkipToPrevious`, `onStop`, `onSeekTo` —
  `:104-113`; router prefers the audiobook over TTS — `:117-139`
- Notification buttons: Previous / Play-Pause / Stop — `:285-290`, `:308-313`
- **No `ACTION_FAST_FORWARD` / `ACTION_REWIND`** — the ±15 s skip is not on the lock screen
  (iOS sets `setSkipBackForwardEnabled(true, interval: 15)`)
- TTS `GENERATING` maps to `STATE_BUFFERING` — `:209-235`
- Self-teardown when nothing is playing (no ghost notification) — `:165-177`

---

## 3. Data layer

### 3.1 Models — `model/`

`Note.kt` — `id`, `explicitTitle`, `text`, `createdAt`, `updatedAt`, `deletedAt`, `notebookId`,
`isPinned`, `isFavorite`; title derives from the first sentence; 30-day recycle retention;
`speechText(renderMarkdown)`.

`Book.kt` — `id`, `title`, `author`, `format` (**a raw `String`, not an enum**: `"epub"`, `"pdf"`,
`"audio"`), `chapters`, `toc`, `position` (1-based), `positionFraction`, `hasCover`, `importError`,
`pdfChapterSource`, `audioChapters`, `theme`, `fontSize`, `cfi`, `spineCount`, `deletedAt`.

`ReadingGoal.kt` — `id`, `bookId`, `bookTitle`, `createdAt`, `startDate`, `deadline`,
`completedAt`; plus `GoalProgress` (fraction, unitLabel, bookExists, daysLeft,
expectedFraction) and `GoalMath` (fraction / unitLabel / progress per format).

`ReaderAppearance.kt` — 14 reader fields matching iOS `ReaderAppearance.swift`.

`VoiceCatalog.kt` — 28 Kokoro voices + 10 Supertonic styles + 32 languages + the audition text.

### 3.2 Stores — `data/`

| Store | File | Notes |
|---|---|---|
| `NotesStore` | `notes.json` + backup | Debounced 400 ms autosave, single-thread writer, atomic temp+rename, quarantine-on-decode-failure, 30-day recycle |
| `NotebooksStore` | `notebooks.json` + backup | Flat Joplin-style notebooks, name uniqueness, manual order |
| `BooksStore` | `Books/<id>/manifest.json` | Per-book manifest, soft delete + `unbinIfDeleted`, 30-day prune, `importError` |
| `BookmarkStore` | `bookmarks.json` | Slot key `note:<id>` / `book:<id>:<chapter>`, hash-guarded, 30-day TTL, **LRU cap 40** |
| `GoalStore` | `reading-goals.json` | Mutex-guarded list, add / remove / **update in place** / re-arm / completion sweep |
| `StatsStore` | `stats.json` | Per-local-day × kind × subject folds; streak rule = ≥ 60 s combined reading+listening |
| `ExportsStore` | `Exports/` | WAV inventory + temp-file cleanup |
| `LogStore` | memory (ring buffer 400) | Also mirrored to logcat under tag "Speechnotes" |
| `ModelManager` | — | Downloader, `.jex` export/import, `share()` via FileProvider, storage usage |
| `Persistence` | — | JSON codec, atomic save with backup, quarantine on decode failure |

`data/Persistence.kt:55-104` is the preference-key catalogue — keys are **intentionally the same
strings iOS uses in `UserDefaults`**.

### 3.3 Stats — `data/StatsStore.kt`, `data/StatsCenter.kt`

- Entry = `(day, kind, subjectId, seconds)`, day = local `yyyy-MM-dd`
- `StatsCenter` derives listening time from the players' published state with **zero engine
  hooks**: TTS folds every 15 s with a 120 s per-slice cap; audiobook the same with a 300 s cap —
  `StatsCenter.kt:28-109`
- `ReadingRecorder` records reading time for the EPUB/PDF readers: folds every 30 s, 120 s cap,
  subject switch ends the previous run — `StatsCenter.kt:116-151`; wired at
  `ui/books/BookReaderScreen.kt:193-194` (EPUB) and `:855-856` (PDF)

### 3.4 BookDrop / LocalSend — `logic/LocalSendProtocol.kt`, `data/LocalSendReceiver.kt`,
`data/LocalSendHTTPServer.kt`

- Ports 53317–53327, first free wins, binds `0.0.0.0` — `LocalSendHTTPServer.kt:385`, `:112-133`
- Endpoints: `POST/GET /api/localsend/v2/register`, `/info`, `/prepare-upload`, `/upload`,
  `/cancel` — `LocalSendReceiver.kt:147-172`
- Status codes: register/info 200; prepare-upload 403/409/400; upload 400/403/409/422/500;
  cancel 400 — `LocalSendProtocol.kt:81-85`
- Extension allowlist = `epub pdf m4b m4a mp4 mp3 jex opus ogg oga` ∪
  `DocumentBook.supportedExtensions` — `LocalSendProtocol.kt:291-296`
- `autoAccept` default **true**, pref `bookDropAutoAccept`; `bookDropEnabled` default **false**;
  `bookDropFingerprint` = two UUIDs
- One session at a time; sender-IP pinning; per-file token; duplicate fileId → 409; size must
  match exactly; sha256 verified when supplied — `LocalSendProtocol.kt:126-189`
- Session timeout 5 min (lazy sweep); history capped at 20
- HTTP body spills to disk past 8 MB; request head capped at 64 KB → 431; socket timeout 60 s;
  one request per connection, `Connection: close`, no chunked, no TLS —
  `LocalSendHTTPServer.kt:73-100`, `:172-188`
- Zombie-listener healing: `probeAndHeal()` loopback-probes on every foreground return —
  `MainActivity.kt:60-66`
- **Receiver only — there is no LocalSend SENDER** (no peer scan, no send screen). iOS has
  `LocalSendSender.swift` and `BookDropSendView.swift`.

### 3.5 Model download — `data/ModelManager.kt`, `speech/ModelFiles.kt`

- OkHttp, 30 s connect timeout, **no read/write/call timeout** (multi-hundred-MB transfers),
  `retryOnConnectionFailure(true)`, redirects on — `ModelManager.kt:47-55`
- Up to 3 attempts per file with **HTTP Range resume** from the `.part` file, 1.5 s × attempt
  backoff, 206-vs-200 handling — `:149-228`
- `minBytes` validation only (no maximum); a set is valid when every file exists and is
  `>= minBytes` — `ModelFiles.kt:105-111`
- `deleteModel` removes only that tier's files, protecting the shared Kokoro tokenizer/voices —
  `:119-132`
- SharedPreferences file name `"speechnotes"`

---

## 4. UI

### 4.1 Navigation — `ui/SpeechnotesApp.kt`

Three tabs (`ui/SpeechnotesApp.kt:70-74`): **Notes**, **Books**, **Settings**. Start destination
is Notes.

Routes (`:134-182`): `notes`, `books`, `settings`, `logs`, `book/{bookId}`, `note/{noteId}`,
`recycle`, `book-recycle`, `notebooks`.

- The settings tab is a **single flat screen** with 9 cards — no Settings sub-navigation
- The mini-player overlay is composed as a **sibling after the Scaffold** in a
  `Box(…, contentAlignment = BottomCenter)` — `:196-209`. The comment at `:190-195` records the
  bug: the default `TopStart` made the bar measure from the top and hide behind the app bar.
  Bottom padding is 80 dp on tab routes, 12 dp elsewhere, plus `.imePadding()`.
- Onboarding gates the entire nav host — `:60-66`

### 4.2 Notes list — `ui/notes/NotesListScreen.kt` (629 lines)

- Sections: **Pinned**, then **Today / Yesterday / Previous 7 days / Older** (era sections only
  apply when sorting by "edited"; other sorts collapse to one section) — `:207`, `:311-331`
- Search field over title + text — `:146-156`
- Sort via a `ModalBottomSheet` (Last edited / Date created / Title) — `:266-290`; the same sheet
  carries "Import text file" and "Recently deleted"
- Pin + favourite as inline icon buttons **and** in the long-press menu — `:509-537`
- Undo-delete snackbar — `SpeechnotesViewModel.kt:587-591` + the toast→snackbar bridge at
  `NotesListScreen.kt:117-126`
- Notebook chips (`All notes · N`, per-notebook `name · N`, `Unfiled`, `Manage`) — `:384-421`
- **No swipe actions** — the file's own doc comment declares a "swipe-free action sheet
  (long-press menu)" — `:86`
- **No drag-and-drop reorder**
- Two empty states only (no-notes, no-search-results). A scoped-empty case renders as
  `No results for ""` — `:203`, `:596-609`
- FAB menu: "New note from clipboard" and "New note" — `:167-197`

### 4.3 Recycle bin — `ui/notes/RecycleBinScreen.kt` (205 lines)

Recover / delete-now / empty-all + days-remaining. The same file also hosts the **Notebooks
manager** (`NotebooksScreen`, `:120`) with create / rename / delete.

**Empty-all fires immediately — there is no confirmation dialog.**

### 4.4 Note editor — `ui/editor/NoteEditorScreen.kt` (1,540 lines)

- Title field in the top bar with first-sentence fallback + word count + "~N min listen" —
  `:650-734`
- Autosave: 400 ms debounce, flush on exit, abandoned-new-note purge, image pruning —
  `:576-614`
- **Markdown formatting bar: 13 actions** — bold, italic, strikethrough, H1, H2, bullet list,
  numbered list, quote, inline code, code block, horizontal rule, insert link, insert image —
  `:120-221`. Shown only when `renderMarkdown && !showPreview` — `:739-755`
- **Slash menu**: 18 commands with fuzzy filter, triggered by typing `/` — `:972-1012`,
  `logic/MarkdownSlashMenu.kt:37-54`
- **Image insertion: all three sources** — Photos (`PickVisualMedia`), Clipboard, From URL
  (downloaded through OkHttp) — `:402-503`; inserted as
  `![](speechnotes://note-image/<hash>.<ext>)`
- **Share menu: 4 formats** — Markdown, Plain text, PDF (block-aware via `NotePdfLayout`),
  Audio (WAV) — `:831-876`; the PDF adapter is `:884-957`
- Inline player controls: voice chip, progress, "Restart from beginning", play/pause/stop,
  read-along toggle, speed slider 0.5–2.0 — `:1228-1347`
- Immersive chrome: tap toggles `immersiveChromeHidden` — `:768-781`
- Full-screen pinch-to-zoom image viewer — `:1170-1215`

**`showPreview` is declared at `:334` and never reassigned.** With **Render Markdown** enabled,
the editor opens in preview and there is no toggle to leave it — the note becomes read-only with
no editing affordance.

### 4.5 Read-along — `ui/readalong/ReadAlongScreen.kt` (150 lines)

Sentence highlighting from the play-time range, auto-scroll, Pause/Resume / Stop / Exit controls.
Auto-scroll uses a **fixed 28 dp × 2 line estimate**, not a measured anchor — `:75`.

**No tap-to-speak-from-paragraph.** The underlying `SpeechPlayer.playFrom` primitive does not exist
on Android at all.

### 4.6 Mini-player — `ui/MiniPlayerOverlay.kt` (585 lines)

Three variants, one dock owner at a time (export → audiobook → note) — `:107-110`.

- Note bar: progress, transport, title + voice, sleep-timer button, speed chip, minimize — `:306-391`
- Audiobook bar: cover, title, chapter, elapsed/duration, ±15 s, transport, minimize — `:203-277`
- Export bar: progress, transport, name + clock, ±15 s, rate readout, minimize — `:496-567`
- Sleep timer menu: 5 / 15 / 30 / 60 min, "End of chapter", "Cancel sleep timer" — `:427-461`
- **The bubble is fixed-position and not draggable** — no `pointerInput`, no offset, no edge
  snapping, no persisted position. Only `miniPlayerCollapsed: Boolean` is stored.
- Export jump lands on the **Settings tab root**, not on the Storage section — `SpeechnotesApp.kt:204`

### 4.7 Books shelf — `ui/books/BooksScreen.kt` (727 lines)

- Import picker: 11 MIME types (epub, pdf, audio/mp4, audio/x-m4b, audio/mpeg, docx, pptx, odt,
  odp, doc, octet-stream) — `:133-145`. **No** text/plain, text/markdown, text/html, RTF, Kindle
  mimetypes, Ogg/Opus.
- **Grid: `GridCells.Fixed(3)`** — `:191`. Not adaptive; there are no `layout-sw600dp` resources.
- Search over title + author — `:154-163`
- `importError` badge replaces the cover with an error container — `:416-451`
- Cover via Coil with an mtime-keyed cache; gradient fallback with title/author — `:455-505`
- Format chip + chapter-count chip — `:507-540`
- Per-card delete icon (no long-press context menu) — `:543-557`
- Delete confirmation with the 30-day copy — `:244-260`
- Books recycle bin (recover / purge / empty-all, both with confirmations) — `:270-366`
- Card tap opens a detail sheet with Read / Delete / chapter list — `:564-709`

**Absent:** long-press menu (iOS has "Send with BookDrop" / "Add a goal" / "Delete"), pull-to-refresh,
DRM-specific badge, OCR badge, original-document-kind label.

### 4.8 Book reader — `ui/books/BookReaderScreen.kt` (1,264 lines)

Dispatch by format at `:154-158`: `EpubReader`, `PdfReader`, `AudioReader`.

**EPUB** — WebView + epub.js served over `WebViewAssetLoader` on `appassets.androidplatform.net`;
the JS bridge is `AndroidReaderBridge.post(json)` — `:165-168`, `:299-358`.

All 14 appearance parameters are pushed over the bridge (`model/ReaderAppearance.kt:72-92`):
`theme`, `fontSize`, `flow`, `font`, `lineHeight`, `paraSpacing`, `letterSpacing`, `margin`,
`respectStyles`, `autoScroll`, `autoScrollSpeed`, `tapTurn`, `swipeTurn`, `tapInverted`.
The initial load also passes 12 of them as URL query items plus `bookPath`, `chapter`, `cfi` —
`:347-353`.

Bridge commands: `readerTheme`, `readerAutoScroll`, `readerGoChapter`, `readerGoToHref`,
`readerNext`, `readerPrev`. JS→native messages: `relocated`, `toc`, `chromeTap`, `pageTurn`,
`error`.

- TOC sheet: manifest TOC (label + chapterIndex) with an epub.js nav fallback — `:361-402`.
  The fallback is **flat** because `reader.js` drops depth when flattening the nav tree.
- Chapter bar with prev/next and "label · N/M" — `:465-497`
- **CFI position persistence** — `:207-214`, `:343-344`

**PDF** — PdfBox-Android, pages rendered to bitmaps.

- **Pinch-zoom + pan: present** — `TransformableState`, zoom clamped 1×–4×, pan snaps home at
  ≤1.01×, double-tap resets — `:892-951`
- **Outline: nested and collapsible** — depth-first walk, `12 dp × depth` indent, chevron toggles
  in a `mutableStateMapOf`, current entry highlighted — `:863-921`, `:1046-1093`
- Page slider + prev/next + "Page X of Y" + narrate button — `:968-1042`
- `PDDocument.load` runs **on the main thread** inside `remember` — `:808-813`. A large scanned PDF
  will block the UI. (iOS opens it detached.)

**Audio** — bound to the app-level `vm.audioBooks`, not a reader-owned player.

- Scrub slider — `:1150-1158`
- ±15 s buttons — `:1165-1187`. The **icons are `Replay10`/`Forward10` (10 s glyphs) while the
  behaviour and the content descriptions are 15 s.**
- Speed slider 0.5–2.0 — `:1213-1222` (iOS uses a preset menu reaching 2.5×/3.0×)
- Sleep timer dropdown 0/5/15/30/60 min — `:1188-1205`
- Chapter list rendered **inline** in the reader body, not in a modal sheet — `:1227-1254`;
  honest empty state "No chapter metadata in this file"

### 4.9 Settings — `ui/settings/SettingsScreen.kt` (1,284 lines)

Nine cards in one scrolling column:

| # | Card | Contents |
|---|---|---|
| 1 | **Notes** | Render markdown, Read-along, Haptic feedback, Image caching |
| 2 | **Speech engine** | Fallback banner, engine radio picker, 3 model download rows |
| 3 | **Appearance** | Accent, System/Light/Dark, Reader theme, Reading text scale, 3 spacing sliders |
| 4 | **Stats** | today / streak / week headlines, 30-day line, 18-week heatmap, **Goals section** |
| 5 | **BookDrop** | Nearby toggle + live port, auto-accept, last error, last 5 transfers |
| 6 | **Backup** | `.jex` export with a notebook-scope sheet, `.jex` import |
| 7 | **Storage** | usage breakdown, exported-audio list with inline transport, delete all, clear temp |
| 8 | **Diagnostics** | Logs link |
| 9 | **About** | "v1.1.4", "Developer: TheSageZM · MIT" |

**There is no voice picker in Settings** — the picker is reachable only from the note editor
(`ui/editor/NoteEditorScreen.kt:808`).

**There is no Kokoro quality-tier control and no Supertonic quality/step control anywhere in the
UI.** The Supertonic step count is fixed internally.

The **Reader theme** segmented control labels `TRUE_BLACK` as "White" (the `else ->` branch at
`:1247`) and `vm.setReaderTheme` maps both `DARK` and `TRUE_BLACK` to the JS theme `"dark"`
(`SpeechnotesViewModel.kt:534`). True Black is only reachable from the reader's own appearance
sheet.

`setReaderTheme` also rewrites the entire 14-key reader-appearance struct
(`SpeechnotesViewModel.kt:479-494`) — changing the theme silently rewrites 14 unrelated keys.

### 4.10 Voice picker — `ui/voice/VoicePickerSheet.kt` (244 lines)

Searchable, grouped by accent+gender, a "Recent" section (last 5 per engine family, only when the
query is blank), tap-to-audition (disabled until the model is downloaded), and a Supertonic-only
language bar.

**The system engine is not handled**: `VoiceCatalog.descriptors(engine.key)` falls through to the
Kokoro bank, so with the System engine selected the sheet lists the 28 Kokoro voices that the
system engine cannot use. `SpeechPlayer.setSystemVoice()` exists (`:416-423`) and has **no UI
caller**.

### 4.11 Logs — `ui/settings/LogsScreen.kt`

In-memory ring buffer (400 entries) polled every 400 ms, level filter chips, auto-follow tail when
near the bottom, monospace rows with per-level colors, copy + share. Copy uses `LogStore.exportText()`
(the whole buffer, ignoring the active filter) — `:76`, `:88`.

### 4.12 Onboarding — `ui/onboarding/OnboardingScreen.kt`

Three pages (WavingHand / RecordVoiceOver / AutoAwesome), Skip + Next/Get started, pager dots,
`BackHandler` that steps back instead of exiting.

### 4.13 Theme — `ui/theme/Theme.kt`

- `ReaderTheme` enum: `WHITE("White","light")`, `SEPIA`, `DARK`, `TRUE_BLACK("trueBlack")` — `:178-180`
- `AccentChoice`: 12 values (SYSTEM + 11 hues) — `:45-63`
- `AppearanceMode`: SYSTEM / LIGHT / DARK — `:163`
- `ThemeState` carries accent, appearance, `previewTextScale` (1.0), `readerTheme` (WHITE) and
  three reader rhythm multipliers (all 1.0) — `:165-176`
- Hand-written M3 tonal palettes; dynamic color only on Android 12+ when accent == SYSTEM —
  `:215-220`
- Stock M3 typography, no custom fonts — `:196-200`
- M3 Expressive `motionScheme` is not applied (internal in material3 1.4.0) — `:257-261`

---

## 5. Preferences — complete ledger

SharedPreferences file **`"speechnotes"`** (`data/Persistence.kt:56`).

| Key | Type | Default | Written by |
|---|---|---|---|
| `engineKind` | string | `"system"` | `SpeechPlayer.setEngineKind` |
| `voice` | string | `"am_eric"` | `SpeechPlayer.setVoice` |
| `supertonicVoice` | string | `"M1"` | `SpeechPlayer.setSupertonicVoice` |
| `supertonicLang` | string | `"en"` | `SpeechPlayer.setSupertonicLang` |
| `systemVoiceIdentifier` | string | `null` | `SpeechPlayer.setSystemVoice` — **no UI caller** |
| `rateMultiplier` | float | `1.0` | `SpeechPlayer.setRateMultiplier` |
| `recentKokoroVoices` / `recentKokoroSmallVoices` / `recentSupertonicVoices` | StringSet | empty | `SpeechPlayer` (capped at 5) |
| `miniPlayerCollapsed` | bool | `false` | `SpeechnotesViewModel` |
| `renderMarkdown` | bool | `false` | `SpeechnotesViewModel` |
| `readAlongEnabled` | bool | — | `SpeechnotesViewModel` |
| `notesSortOrder` | string | `"edited"` | `SpeechnotesViewModel` |
| `activeNotebookScope` | string | `"all"` | `SpeechnotesViewModel` |
| `hasOnboarded` | bool | `false` | `SpeechnotesViewModel.setOnboarded` |
| `accentColorChoice` | string | system | `SpeechnotesViewModel.setAccent` |
| `appAppearance` | string | system | `SpeechnotesViewModel.setAppearance` |
| `previewTextScale` | float | `1.0` | `SpeechnotesViewModel.setPreviewTextScale` |
| `readerLineSpacing` / `readerBlockSpacing` / `readerTableSpacing` | float | `1.0` | `SpeechnotesViewModel.setReaderSpacing` |
| `readerTheme` | string | white | `SpeechnotesViewModel.setReaderTheme` |
| `hapticsEnabled` | bool | `true` | SettingsScreen `persistBool` |
| `imageCacheAutomatic` | bool | `true` | SettingsScreen `persistBool` |
| `bookDropEnabled` | bool | `false` | SettingsScreen / `LocalSendReceiver` |
| `bookDropAutoAccept` | bool | `true` | SettingsScreen / `LocalSendReceiver` |
| `bookDropFingerprint` | string | 2 UUIDs | `LocalSendReceiver.fingerprint()` |
| `bookReaderTheme`, `bookReaderFontSize`, `bookReaderFlow`, `bookReaderFont`, `bookReaderLineHeight`, `bookReaderParaSpacing`, `bookReaderLetterSpacing`, `bookReaderMargin`, `bookReaderRespectStyles`, `bookReaderAutoScroll`, `bookReaderAutoScrollSpeed`, `bookReaderTapTurn`, `bookReaderSwipeTurn`, `bookReaderTapInverted` | mixed | per iOS | `SpeechnotesViewModel.updateReaderAppearance` |

Not in SharedPreferences (JSON files): `notes.json`, `notebooks.json`, `bookmarks.json`,
`stats.json`, `reading-goals.json`.

---

## 6. Manifest and platform surface

`app/src/main/AndroidManifest.xml`:

- Permissions: `INTERNET` (model downloads only), `WAKE_LOCK`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — `:8-14`
- `allowBackup="true"` + `fullBackupContent="true"` — `:18-19`
- `MainActivity` with `configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode"`
  (the activity is **not** recreated on rotation) and `windowSoftInputMode="adjustResize"` —
  `:27-34`
- Intent filters: launcher; `speechnotes://import` deep link; `VIEW` for 11 MIME types — `:35-64`
- `SpeechPlaybackService` — `:70-73`
- `FileProvider` with authority `${applicationId}.fileprovider` and
  `res/xml/file_paths.xml` (exports, books, cache roots) — `:79-87`

`MainActivity.kt`: `onCreate` (edge-to-edge, notification permission request on API ≥ 33),
`onNewIntent`, `onPause` (persists the playback bookmark), `onResume` (BookDrop probe-and-heal).
Intake: `ACTION_VIEW` with a `speechnotes://` scheme → `ImportText`, otherwise `OpenUri`;
`ACTION_SEND` → `EXTRA_TEXT` or `EXTRA_STREAM`. There is **no `SEND` intent-filter in the
manifest**, so share-into-app only works via an explicit intent.

---

## 7. What works today (shipped, verified)

- Notes CRUD, autosave, search, sort, era sections, pin, favourite, undo-delete, notebooks, 30-day
  recycle bin, clipboard + file + deep-link import
- Editor: title, autosave, 13-action markdown bar, 18-command slash menu, 3-way image insertion,
  markdown preview rendering, share as md/txt/pdf/wav, inline player controls, speed slider,
  read-along toggle
- Read-along: sentence highlight + auto-scroll for notes
- Speech: 4 engines, model download with resume + retry + size validation, 5-minute idle unload,
  live rate, pause/resume/stop, chunk-skip with a beep, WAV export + share
- Books: EPUB import (metadata/TOC/cover), EPUB reader in a WebView with all 14 appearance
  parameters, PDF import with outline/heading/page chapter resolution + pinch-zoom + collapsible
  outline + ML Kit OCR fallback, audiobook import with `chpl`/Nero-track/ID3 chapter detection and
  MediaPlayer playback with auto-advance, cover grid, per-book soft-delete bin
- Stats: today/streak/week, 30-day read-vs-listen, 18-week heatmap
- Goals: per-book reading goals with live derived progress, status pills, **create + edit schedule
  + delete + re-arm** — the same editability contract as iOS
- BookDrop receiver: v2.2 protocol, ports 53317–53327, auto-accept, transfer history
- Backup: `.jex` export with a notebook scope sheet + `.jex` import
- Storage: usage breakdown, exported-audio playback with inline transport
- Logs with level filter, copy and share
- Onboarding, theme (12 accents + dynamic color), settings, About

---

## 8. Gaps against iOS — ranked

### P0 — functional bugs / broken features

| # | Gap | Evidence |
|---|---|---|
| P0-1 | **The note editor can enter preview mode and can never leave it.** `showPreview` is set once at `NoteEditorScreen.kt:334` and never reassigned. With **Render Markdown** on, the note is read-only with no way to edit. | `ui/editor/NoteEditorScreen.kt:334` |
| P0-2 | **No `SpeechPlayer.playFrom` primitive.** iOS ships play-from-caret and tap-a-paragraph-to-speak; both are absent because the underlying offset-start API does not exist on Android. | `speech/SpeechPlayer.kt` (no such method) |
| P0-3 | **`.jex` note images cannot round-trip.** `NoteImageStore` hashes with FNV-1a 64-bit → 8–16 hex chars; `ModelManager.jexImages` does `.take(32)` on that (never 32 chars); `JexImport` requires exactly 32 hex chars. | `logic/NoteImageStore.kt:44`, `data/ModelManager.kt:288`, `logic/JexImport.kt:163,224` |
| P0-4 | **No encryption of any kind.** Notes are plaintext JSON. iOS ships a Joplin-compatible vault (`EncryptionVault`, `JoplinCrypto`, `JoplinVault`, `JoplinNoteCipher`, `EncryptionSettingsView`). Grep for `Cipher|javax.crypto|SecretKey|KeyStore` across `app/src/main` → zero hits. | whole `app/src/main/java` |
| P0-5 | **PDF `PDDocument.load` runs on the main thread.** A large/scanned PDF freezes the UI on open. iOS opens it detached with a spinner. | `ui/books/BookReaderScreen.kt:808-813` |
| P0-6 | **BookDrop accepts formats Android cannot parse.** The allowlist unions in `DocumentBook.supportedExtensions` (mobi/azw/azw3/prc/pdb/fb2/rtf/html/txt/md) but only `docx/odt/pptx/odp` have parsers. Such a transfer is accepted with 200, landed, then fails the import. | `logic/LocalSendProtocol.kt:291-296` vs `logic/DocumentBook.kt:86` |
| P0-7 | **The render-ahead bank's seconds check is dead in production.** `RenderAheadBankPolicy.allows()` and `isExhausted()` have **no production callers** — only the unit test calls them. The core paces on a fixed chunk-count semaphore (`bankTargetSeconds / 4.0`). | `speech/RenderAheadBankPolicy.kt:154-166`; `speech/StreamingPlaybackCore.kt:165-166` |

### P1 — major features missing

| # | Gap | iOS evidence |
|---|---|---|
| P1-1 | **No Supertonic quality dial.** iOS has High (8) / Balanced (6) / Automatic / Fast (4), persisted as `supertonicRenderQuality`, applied from the next chunk, and bypassed for export (export is always full quality). Android has no such control. | `iOS SupertonicEngine.swift:61-95`, `SpeechSettingsView.swift:205-219` |
| P1-2 | **No Kokoro quality tier control.** iOS merges fp32/uint8 into one engine with a High/Compact dial (`kokoroTier`). Android has two separate engines (`KOKORO`, `KOKORO_SMALL`) with no tier picker. | `iOS SpeechSettingsView.swift:25-50` |
| P1-3 | **No LocalSend sender.** iOS scans peers, does prepare-upload + per-file POSTs, and ships a send screen (`BookDropSendView`) with a payload picker. Android is receive-only. | `iOS LocalSendSender.swift`, `BookDropSendView.swift` |
| P1-4 | **No read-along in any book reader.** The read-along surface exists only for notes. iOS swaps the EPUB/PDF surface for `ReadAlongView` while narrating. | `iOS BookReaderView.swift:291-325`, `BookPDFReaderView.swift:337-357` |
| P1-5 | **No landscape PlaybackRail.** Nothing in the Android UI is orientation-aware. iOS moves the controls to a 150 pt trailing rail in landscape across all four reading surfaces. | `iOS PlaybackRail.swift` |
| P1-6 | **No EPUB TOC nesting.** `reader.js` flattens the nav tree and `BookTocEntry` has no `depth` field, so the TOC is a flat list. iOS uses the manifest depth with chevron folding. | `iOS BookReaderView.swift:460-571` |
| P1-7 | **No per-book force-OCR toggle and no OCR/DRM shelf badges.** Android OCRs automatically when a PDF chapter has no text layer; iOS has `Book.forceOCR`, `pdfIsScanned`, and "Scanned — OCR" / "OCR forced" chips. | `iOS Book.swift:78-83`, `BooksView.swift:387-392` |
| P1-8 | **No draggable/edge-snapping mini-player bubble.** Only `miniPlayerCollapsed: Boolean` is stored. iOS persists normalized `miniBubbleX`/`miniBubbleY` and snaps to the nearest edge on release. | `iOS GlobalMiniPlayerOverlay.swift:137-249` |
| P1-9 | **No per-chapter WAV export from any book reader.** Android has the `SpeechPlayer.export` API but no book reader calls it. | `iOS BookReaderView.swift:394-401`, `BookPDFReaderView.swift:396-403` |
| P1-10 | **No note swipe actions.** iOS has trailing-swipe Delete (with Undo) and leading-swipe Pin / Share / Export; Android is swipe-free by design. | `iOS NotesListView.swift:379-414` |
| P1-11 | **No audio rate slider in the EPUB reader**, no read-along toggle, no chapter percentage in the chapter label, no opening veil. | `iOS BookPlayerBar.swift:108-121` |

### P2 — smaller gaps and polish

- True Black is mislabelled "White" in Settings and collapsed to `"dark"` by the ViewModel
  (`SettingsScreen.kt:1247`, `SpeechnotesViewModel.kt:534`).
- `setReaderTheme` rewrites 14 unrelated prefs (`SpeechnotesViewModel.kt:479-494`).
- The books grid is `Fixed(3)`; iOS is adaptive (`min 104 pt`).
- No pull-to-refresh on the books shelf.
- No long-press context menu on book cards (iOS: Send with BookDrop / Add a goal / Delete).
- "Add a goal" is not reachable from a book card.
- Recycle bin "empty all" has no confirmation (iOS has one).
- The notes list has no scope-empty state — it renders `No results for ""`.
- The notes long-press menu lacks Delete and Share (only inline icon buttons have them).
- `showOverflow` in `NotesListScreen.kt:107` is dead state.
- `ReaderAppearance.autoScrollSpeedCommand()` is defined but never called
  (`model/ReaderAppearance.kt:98-99`).
- `SpeechPlayer.noteEngineFailure()` has no callers (`speech/SpeechPlayer.kt:455`).
- `SystemTtsEngine.isAvailable` has no callers — a device with no TTS engine fails silently
  (`speech/SystemTtsEngine.kt:240`).
- `SpeechPlaybackService.showNotification(..., withSkips)` is a dead parameter (`:268`).
- The lock screen has no ±15 s skip actions.
- The audiobook speed slider caps at 2.0×; iOS reaches 2.5×/3.0× via a preset menu.
- The audiobook sleep timer has no "end of chapter", no 10/45/90 presets, and no live countdown.
- The audiobook chapter list is inline, not a modal sheet; there is no "playback unavailable" banner.
- The ±15 s buttons use 10 s glyphs (`Replay10`/`Forward10`) while behaving as 15 s.
- `reader.js`'s `window.readerAppearance` ignores `flow` — a flow change only works because it
  bumps `reloadToken` and rebuilds the WebView (`assets/reader.js:290-307`,
  `BookReaderScreen.kt:421-425`).
- The audio reader's stale header comment claims it owns a MediaPlayer; it binds to `vm.audioBooks`.
- `StatsStore` and `GoalStore` have **no corrupt-file quarantine and no backup** (they silently
  reset to empty), unlike the notes/notebooks/bookmarks stores.
- Recents use `putStringSet`, which does not preserve insertion order, so "newest first" is not
  actually enforced.
- No `ACTION_SEND` intent-filter in the manifest, though the code handles it.
- The picker MIME list omits text/plain, text/markdown, text/html, RTF, Kindle, Ogg/Opus.
- The manifest's VIEW filter omits Ogg/Opus/JEX even though BookDrop accepts them.
- The import picker offers `application/octet-stream` as a catch-all for zip containers.
- `SpeechPlayer.export()` does not stop playback first and runs on the same single-thread
  generate queue as live playback (iOS stops first).
- No auto-resume on foreground (iOS restores the most recent bookmark within 30 minutes).
- No local-send / peer discovery.

---

## 9. Test coverage — 78 JVM tests, 10 files

| File | Covers |
|---|---|
| `logic/RenderAheadBankPolicyTest.kt` | 14 tests — bank targets, byte cap, pressure factor, step ladder, `allows`/`isExhausted` |
| `logic/BookDropProtocolTest.kt` | 20 tests — every LocalSend status code, session lifecycle, allowlist, filename safety (socket-free) |
| `logic/DocumentBookTest.kt` | 8 tests — synthetic DOCX/ODT/PPTX/ODP ZIPs round-trip through the EPUB writer |
| `logic/NotePdfLayoutTest.kt` | 8 tests — headings, lists, quotes, code, tables, page splitting |
| `logic/JexImportTest.kt` | 6 tests — tar variants, metadata, resources |
| `logic/SentenceChunkerRegressionTest.kt` | 4 tests — first-chunk cap, batch bound, exact concat, sentences |
| `logic/AudiobookChaptersTest.kt` | 5 tests — Nero layout, ffmpeg layout, ms-vs-100 ns selection, honest-empty, ID3 CHAP |
| `logic/ZipReaderRegressionTest.kt` | 2 tests — ZIP edge cases |
| `model/ReaderAppearanceTest.kt` | 6 tests — appearance defaults and mapping |
| `data/StatsStoreTest.kt` | 5 tests — folds, streak rule, queries |

**No tests exist for:** `NotesStore`, `NotebooksStore`, `BooksStore`, `BookmarkStore`, `GoalStore`,
`ModelManager`, `SpeechPlayer`, `StreamingPlaybackCore`, `SystemTtsEngine`, `KokoroEngine`,
`SupertonicEngine`, `AudioBookPlayer`, `SpeechPlaybackService`, `NoteImageStore`, `MarkdownText`,
`MarkdownSlashMenu`, `XhtmlText`, `PdfText`, `SpeechSanitizer`, `WavWriter`, and every UI screen.

`androidTest/` contains only the generated `ExampleInstrumentedTest.kt` — **no instrumented tests.**

---

## 10. Build and verification

```bash
cd ~/speechnotes-android
export JAVA_HOME=~/tools/jdk-17.0.13+11

# unit tests (78, green as of 2026-10-23)
./gradlew --offline testDebugUnitTest

# universal debug APK
./gradlew --offline assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# per-ABI split for the Waydroid x86_64 container
./gradlew --offline assembleDebugX86_64
# → app/build/outputs/apk/debug/app-x86_64-debug.apk

# install on Waydroid
adb -s <waydroid-ip>:5555 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
```

**Install the x86_64 split, never the arm64 one** — the arm64 APK SIGSEGVs at startup on Waydroid
(confirmed 2026-10-13, `IOS-PARITY-PLAN.md:310`).

**Environment note:** the workspace root was at 94 % full on 2026-10-23 (28 GB free). Check
`df -h /` before every build. Safe reclaim targets: `~/.gradle/caches/build-cache-1` and
`target/debug/incremental`.

**Waydroid audio caveat:** the container's `audio.primary.waydroid` HAL never establishes its
libpulse connection to the host's PipeWire-pulse — zero clients appear for **any** app. The app's
frames reach the HAL correctly; the break is at the container boundary. A full write-up is in
`IOS-PARITY-PLAN.md:148-164`. Use Waydroid for correctness, never for tuning quality constants.

---

## 11. Cross-platform contract notes

1. **Preference keys are shared with iOS by design.** `data/Persistence.kt:55` says so explicitly:
   "Preference keys the app shares with the iOS version."
2. **`logic/` is a port of the iOS `Packages/SpeechLogic` package**, kept algorithmically
   identical so the two platforms' behaviour matches.
3. **The `Book` manifest is NOT interchangeable with iOS.** Android's `Book` is a flat `String`
   format with 18 fields; iOS uses a `BookFormat` enum and carries ~11 fields Android lacks
   (`originalFileName`, `addedAt`, `lastOpenedAt`, `spine`, `pageCount`, `pdfChapters`,
   `pdfIsScanned`, `forceOCR`, `audioChapterSource`, `audioReaderRev`, `audioDuration`). The
   "field names match the iOS app" comment at `model/Book.kt:7-9` is only true for the fields
   that exist on both sides.
4. **iOS is the reference, not the authority.** The iOS repo is at v1.7.4 (build 43) with an
   in-progress Joplin-compatible encryption stack in its working tree; Android is at v1.1.4 (6)
   and predates that work.
5. **`IOS-PARITY-PLAN.md` (28.9 KB, last updated 2026-10-16) is the working gap tracker.** Its
   batch A–F status table is the authoritative record of what has been shipped and what is open.
   `research/FEATURE-PARITY.md` (2026-09-30) is an older snapshot and is **stale** — it still
   lists the books recycle bin as P1-missing, which shipped on 2026-10-15.
6. **The README is stale in places.** `README.md:155-165` "Known gaps" claims the EPUB and PDF
   readers "are not wired into the UI yet" and that audiobook playback "is parsed but not
   surfaced" — all three shipped since. It also lists "Kokoro small" and "Kokoro" as two
   separate engines, where iOS has since merged them into one engine with a tier dial.
