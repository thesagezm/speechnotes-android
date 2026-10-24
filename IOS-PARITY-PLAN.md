# Speechnotes-Android — iOS Parity Integration Plan

**Date:** 2026-10-13
**Source of truth:** `speechnotes-ios` @ `f650f79` (v1.7.2/40, 127 Swift files ~34.4k LOC)
**Target:** `speechnotes-android` (v1.1.4/6, 2026-10-15)
**Verification:** Waydroid x86_64 (Android 13) via adb — `adb connect <waydroid-ip>:5555`

## Batch status overview (2026-10-15)

- **Batch A — SHIPPED 2026-10-14** (audio quality parity; details below).
- **Batch B — ALREADY IN TREE (verified 2026-10-15, no changes needed):** B1 flow, B2 font,
  B3 spacing sliders, B4 respect-styles, B5 auto-scroll+speed, B6 tap/swipe/invert toggles,
  B7 trueBlack enum+mapping, B8 chapter prev/next chrome buttons, B9 spineCount (written at
  import, read by shelf card + reader), B10 importError on shelf card, B11 books recycle bin
  (`BooksRecycleBinScreen` + `deletedAt` + 30-day prune) — all present.
- **Batch C — SHIPPED 2026-10-15:**
  - **C1** `PdfPageSource.lines()` now records REAL per-line geometry (x/baseline/height/width
    via a `PDFTextStripper.writeString` override that merges word fragments into rows).
    The fabricated `y=height-(i+1)*12, x=0, height=12` geometry is gone — the two-column
    gutter detector and the heading-height tier are live code again.
  - **C2** `PdfText.chapterText` routes through the gutter-aware `pageText(source, page)`
    instead of the platform's raw page string.
  - **C3** PDF reader: pinch-zoom + pan (`TransformableState`, 1×–4×, double-tap resets,
    1× snaps the pan home).
  - **C4** PDF outline rows carry depth; chevron-fold via `mutableStateMapOf`, current
    entry highlighted, indent by depth.
  - **C5** OCR render scale 1.6 → 2.0 (iOS Vision `.accurate` parity).
- **Batch D — SHIPPED 2026-10-15** (`AudiobookChapters.kt` rewritten to the iOS v1.7.x
  parser):
  - **D1/D2/D3** `chpl` parses BOTH candidate layouts (Nero 4-byte count, ffmpeg 1-byte
    count, each × {100-ns, ms} divisors) and picks by VALIDATION + closest-to-duration
    ranking: monotonic starts, in-duration, and a SPAN check (a collapsed reading — e.g.
    an ms-list read as 100-ns — puts the last start inside the first 2–10% of the book
    and is rejected). Movie timescale from `mvhd` anchors the chapter-track math.
  - **D4** `stsc` walks ALL runs (first-run-only dropped); `stco`+`stsc`+`stsz` map every
    sample to (offset,size); titles read as QuickTime text samples (16-bit BE length,
    `encd`-atom tolerant, UTF-8→UTF-16 lenient).
  - **D5** head slice 8 MB → 64 MB (chunk offsets pointing past 8 MB clipped real tracks).
  - **D7** MediaPlayer decode probe at import when the retriever reports duration 0;
    failure sets `importError` (shelf card shows it), success backfills the real duration.
  - **D8** no fake "Full audiobook" placeholder — empty chapters render an honest
    "No chapter metadata in this file" state.
  - 5 JVM tests: Nero layout, ffmpeg layout, ms-vs-100ns selection, honest-empty, ID3 CHAP.
- **Batch E — 2026-10-16:**
  - **E1a+b (stats) SHIPPED:** `StatsStore.kt` (per-local-day/per-subject/per-kind JSON
    folds, streak/activeDays/subjectTotals; 5 JVM tests), `StatsCenter.kt` (listening
    derived from both players' flows, 15 s folds with caps; `ReadingRecorder` for the
    epub/PDF readers, 30 s folds), Settings "Stats" card (today/streak/week headline,
    30-day read-vs-listened line, 18-week heatmap).
  - **E1c (goals) SHIPPED:** `ReadingGoal.kt` (goal model + `GoalMath` derived progress
    from the book's live position: chapter+fraction over spineCount / pages / audio
    chapters), `GoalStore.kt` (JSON, add/remove/update-in-place/re-arm + completion
    sweep), Settings Goals section (progress bar, status pill, create/edit sheet with
    book picker and date sliders).
  - **E5 SHIPPED:** JEX export materializes `speechnotes://note-image/…` references into
    Joplin `resources/` entries (32-hex resource ids, mime map). Note titles now written
    into the note metadata block too.
  - **E6 SHIPPED:** JEX import restores explicit title + created/updated timestamps
    (`NotesStore.importNote` gained optional metadata params).
  - **E3 (office docs as books) SHIPPED 2026-10-16:** the normalize-to-EPUB pipeline
    ported from DocumentEpub.swift — `DocumentBook.kt` (types + dispatch),
    `DocumentParsers.kt` (DOCX + ODT SAX delegates, shared `chapterize`, XXE-off
    factory), `DocumentSlides.kt` (PPTX rels-ordered slides, ODP title-frame pages),
    `ZipWriter.kt` (store-only zip + CRC-32 + header pixel-size sniff) and
    `DocumentEpub.kt` (the EPUB 3 converter: cover spine page, pool-href images,
    bordered tables, colspan). `importDocument` parses → emits a real EPUB → re-enters
    `importEpub`, so the reader/TOC/TTS pipeline never learns the difference. Picker
    mimes + manifest VIEW filter extended. 8 JVM tests (`DocumentBookTest`) build
    synthetic docx/odt/pptx/odp ZIPs in memory and round-trip them through the writer.
  - **E4 (office docs as notes) SHIPPED 2026-10-16:** `ModelManager.importTextFrom`
    routes office extensions through the same parsers, reduced to text (tables
    comma-joined) — ImportService.officeDocumentText parity.
  - **E2 (BookDrop/LocalSend) SHIPPED 2026-10-16:** three layers —
    `LocalSendProtocol.kt` (`BookDropCore`: pure protocol state machine — register/
    prepare/upload/cancel, sessions + per-file tokens, sha256 verify, 5-min sweep,
    `safeFileName`, iOS's 29-extension allowlist; 20 JVM tests assert every status
    code, no sockets), `LocalSendHTTPServer.kt` (HTTP/1.1 on ServerSocket, 8 MB spill,
    431 head cap, loopback `verifyAlive`), `LocalSendReceiver.kt` (routing, identity
    fingerprint, `StateFlow`s, prefs) + `BookDropRegistry` (PlaybackRegistry-pattern
    bridge: Application creates the receiver, the ViewModel registers the import
    route). MainActivity `onResume` runs the Readest probe-and-heal (stop FIRST, then
    start). Settings "BookDrop" card: enable toggle + live port, auto-accept, recent
    transfers with outcome icons. Bodies land in `AppFiles.bookDropIncomingDir`.
  - **E9 (landscape rail), E10–E14 — still open.**
- **Batch F — 2026-10-16:** share-from-list names the file after the note; dead
  `editorBarMinimized` pref removed; About pane shows the version. **PDF share-as
  styling SHIPPED 2026-10-16** — `NotePdfLayout.kt` (pure block-aware layout:
  heading sizes, list indents/markers, quote indent, monospace code, table rows,
  page splitting; 8 JVM tests) + a Paint-backed adapter in `renderNotePdf`; the
  export is named after the note. This deliberately EXCEEDS iOS parity (iOS's
  `htmlBody` also flattens to plain text before `UIMarkupTextPrintFormatter`).
  Remaining F: adaptive grid columns, grouped settings panes.
- **Bug fixed in passing 2026-10-16:** `AndroidManifest.xml` never declared the
  FileProvider `<provider>` although `ModelManager.share()` calls
  `FileProvider.getUriForFile(... "${packageName}.fileprovider")` and
  `res/xml/file_paths.xml` existed — every share (md/txt/pdf/wav/jex) threw.
  Declared with authority `${applicationId}.fileprovider`.
- **Version:** 1.1.4(6). 78 JVM tests green (20 bookdrop + 8 document + 8 pdf-layout
  + 14 bank policy + 5 stats + 6 JEX import + 6 appearance + 4 chunker + 5 chapters
  + 2 zip). Built, installed on Waydroid (x86_64), launch-verified.

## Batch A status — SHIPPED 2026-10-14 (all A-items implemented, 26 unit tests green, installed on Waydroid)

### A-bonus: the real playback bug (found via the Waydroid no-audio hunt)

`SentenceChunker.firstChunk` computed its limit with
`utf16CappedIndex(text, text.length, window)` — passing the END as the walk
START, so the walk never ran, the "cap" was the whole text, and a long
unterminated opening paragraph came out as ONE giant chunk. On device that
chunk phonemized to 681 tokens, blew Kokoro's `MAX_TOKENS` (510), and the
entire utterance was skipped ("chunk 0 failed: token count 681" → "no audio
produced"). Fixed to `utf16CappedIndex(text, 0, window)` (iOS passes
`text.startIndex`); `batchMaxChars` also corrected from `chunkMaxChars*3` to
`chunkMaxChars` (iOS `StreamingTTSPlaybackCore:693`). Regression tests in
`SentenceChunkerRegressionTest.kt` (cap, batch bound, exact concat, sentences).

### A-item outcomes

- **A4 bank policy** — `RenderAheadBankPolicy.kt` ported with full fidelity
  (30/15/8 s targets, 1/0.5/0.25 pressure factors, byte cap, `allows` as
  `banked < target`, exhaustion 0.5 s) + 14 JVM tests. The core's gate is a
  semaphore sized `bankTargetSeconds/4` chunks (approximation of iOS's
  banked-seconds check; documented).
- **A5 thermal shed** — Supertonic's `totalStep` reads
  `RenderAheadBankPolicy.totalStep(thermalState())` via
  `PowerManager.currentThermalStatus` per chunk; 8 steps everywhere except
  critical (the iOS quality verdict).
- **A-core underrun recovery** — non-blocking `AudioTrack.write` retry loop
  (main thread can no longer deadlock on a disabled track), hard spin cap
  (~2 s) with the remainder requeued onto the generate queue
  (`writeRemainder`), wall-time-EMA prebuffer `max(1.2 s, lastChunkRender+1)`
  before the first `play()`, and a completion poller that revives a frozen
  head with `play()`.
- **A7** — `setSampleRate` tears down the track (Supertonic publishes 44100
  post-load).
- **A6** — 5-min idle unload of resident ONNX sessions (`unloadModel()` on
  both engines, armed from the player's IDLE transition, cancelled on
  activity).
- **A8** — `exportState`/`shareFile` now observed (failure toast, auto-share
  on completion); `onJumpToExports` navigates to Settings.
- **A9** — voice-picker Recents (3 StringSet prefs, capped 5, "Recent"
  section at the top of the sheet).
- **A3** — verified no-change: Android's system TTS is a service (not a
  streamed core), and its pause/resume replay path is already correct.
- **A1/A2** — Kokoro `firstChunkMaxChars = 100` with the measured-RTF
  rationale ported from `OnnxKokoroEngine.swift:120-131`.

### Waydroid audio verdict (user's "can't hear anything")

**Environment, not the app.** The container's `audio.primary.waydroid` HAL
never establishes its libpulse connection to the host's PipeWire-pulse —
zero clients ever appear, for ANY app (the system ringtone preview writes
frames into the same void; `AudioFlinger` frames reach the HAL then the
output thread parks in standby). The app's frames flow to the HAL correctly;
the break is at the container boundary. Probing showed the host socket is
reachable from the container (`nc -U` connects), so the failure is inside
the HAL's libpulse init — most plausibly pulse-cookie auth
(`~/.config/pulse/cookie` is not visible inside the container; `HOME=/`).
Fixing needs root in the container (sudo): copy the host cookie to
`/.config/pulse/cookie` (or `/etc/pulse/client.conf` with
`auth-cookie = /run/xdg/pulse/cookie`) via the waydroid overlay, then fully
restart the container (`waydroid container stop && start`). Do NOT
`setprop ctl.restart audioserver` from the shell — it wedged the service in
`stopping` and required a full container restart.

### Device evidence (2026-10-14, Waydroid x86_64)

The 137-word note that previously failed wholesale now generates 6 chunks
(83/141/136/135/139/47 tokens — every one ≤ 510), state reaches
GENERATING → SPEAKING, no ANR, UI responsive throughout; 26 unit tests green
(14 bank policy, 4 chunker regression, 8 pre-existing).

---

## Priority order (user's explicit ranking, applies to every batch)

1. **Quality** — audio fidelity, correctness, no regressions. The user's quality report outranks speed (iOS `RenderAheadBankPolicy.swift:120-131`: the 8/8/4/4 step shed shipped audible artifacts and was reverted).
2. **Speed / performance** — time-to-first-audio, thermal behavior, jank. Only after quality is preserved.
3. **Functionality** — the feature set works end-to-end.
4. **Aesthetics** — layout, spacing, polish. Never at the expense of 1–3.

**Batch rules inherited from the iOS project:** every batch ships with a critic pass at ≥8/10; CI-green ≠ device-true (iOS incidents R1, R12); the device test gate is the user's; commit ≠ installed — always run the installer script.

---

## Batch A — Audio quality parity (the highest-value gap)

**Why first:** measured on Waydroid, the current Android TTS plays but the streaming configs diverge from iOS in ways that directly cost quality. iOS tuned these constants against device logs across 214 commits; Android has the older, untuned values.

| # | Item | iOS evidence | Android now | Fix |
|---|---|---|---|---|
| A1 | Supertonic first-chunk cap | `SupertonicEngine.swift:128` `firstMaxChars: 60` | `SupertonicEngine.kt:59` `FIRST_CHUNK_MAX_CHARS = 60` — **already matches** | verify only |
| A2 | Kokoro first-chunk cap | `OnnxKokoroEngine.swift:131` `firstMaxChars: 100` (with a measured-RTF rationale in the comment at :120-130) | `KokoroEngine.kt:71-77` sets **no** `firstChunkMaxChars` → defaults to `chunkMaxChars` (160) | add `firstChunkMaxChars = 100` + port the rationale comment |
| A3 | System-engine first-chunk cap | `SystemEngine.swift:344` `firstMaxChars: 120` "fast start: the first sentence sounds now" | `SystemTtsEngine.kt` builds no `Config` at all — uses `SpeechEngine` defaults | wire an explicit config; verify the pause-resume replay path (`SystemTtsEngine.kt:180-208`) doesn't clip the first chunk |
| A4 | Render-ahead bank in **seconds**, not chunk count | `RenderAheadBankPolicy` — 30 s nominal/fair, 15 s serious, 8 s critical; byte cap 32 MB; `allows(bankedSeconds:)` deliberately `banked < target` so it can't deadlock | `StreamingPlaybackCore.kt:45` `generationAheadLimit = 2` (a chunk count) | port `RenderAheadBankPolicy` as a pure Kotlin object + unit tests; replace the semaphore permit count with a banked-seconds check |
| A5 | Thermal-pressure step shed | `RenderAheadBankPolicy.totalStep` — 8 steps through `serious`, 4 only at `critical`; `pressureFactor` 1.0/0.5/0.25 | `SupertonicEngine.kt:52` `totalStep = 8` always, no thermal awareness | port `ThermalPressure` (Android: `PowerManager.getCurrentThermalStatus` / `OnThermalStatusChangedListener`), wire `totalStep` + bank multiplier. **Quality guard:** never shed below 8 steps except at critical — the iOS device verdict says the 8/8/4/4 ladder ruined 90% of a book |
| A6 | Idle engine unload | `SpeechPlayer.swift:694,717` — Supertonic + Kokoro sessions dropped after 5 min idle (LiveContainer jetsam) | `KokoroEngine.release()`/`SupertonicEngine.release()` exist but are called only from `SpeechPlayer.shutdown()` (process teardown) | 5-minute idle timer per engine, wired into the player's state transitions |
| A7 | `setSampleRate` track teardown | iOS core recreates its graph when the sample rate is published at load | `StreamingPlaybackCore.kt:89-92` `setSampleRate` only rebinds the field; a track built at the pre-load 24 kHz rate would keep playing Supertonic's 44.1 kHz audio at the wrong rate | tear down `audioTrack`/`trackAttached` when the rate changes (Supertonic publishes 44100 at load — `SupertonicEngine.kt:165`) |
| A8 | WAV export completion UI | iOS export has progress + a share sheet | `SpeechPlayer.exportState` / `shareFile` StateFlows exist (`SpeechPlayer.kt:59-63`) but **no composable observes them** — the editor's "Audio (WAV)" option (`NoteEditorScreen.kt:847`) appears to do nothing | collect both flows in the editor; progress + "Share" on completion + failure toast. Also wire `onJumpToExports` (currently a no-op default) |
| A9 | Voice-picker "recents" | iOS has `recentKokoroVoices`/`recentKokoroSmallVoices`/`recentSupertonicVoices` capped at 5 | `VoicePickerSheet.kt:42` doc comment claims recents; no state exists | port the three prefs + a recents section |

**A10 — thermal verification plan (Waydroid):** Waydroid is x86_64 under a desktop CPU; RTF there is NOT a phone proxy. Use it only for correctness (chunks advance, no deadlock, no crash), never to tune step counts. The constants come from the iOS device log quoted above.

---

## Batch B — EPUB reader parity (largest single functional gap)

The Android WebView reader already carries the full iOS appearance bridge in `assets/reader.js` — **the parameters exist but nothing sends them.** This is the cheapest large win in the project.

| # | Item | iOS evidence | Android now | Fix |
|---|---|---|---|---|
| B1 | Flow choice (scrolled/paginated) | `ReaderAppearance.swift:15` `flow: String` | `BookReaderScreen.kt:283` hardcodes `flow=paginated`; reader.js supports both | add the preference + settings row + pass it |
| B2 | Font family | `ReaderAppearance.swift:18` book/serif/sans/mono | reader.js :19,44-48,86 handles it; no Kotlin call site | port picker + bridge call |
| B3 | Line height / para spacing / letter spacing / margin | `ReaderAppearance.swift:21-25` | reader.js :20-23,87-93,104-110 | port sliders (reuse the note-reader spacing UI already in `SettingsScreen.kt:818-844`) |
| B4 | Respect-book-styles | `ReaderAppearance.swift:26` | reader.js :24,85 | port toggle |
| B5 | Auto-scroll + speed | `ReaderAppearance.swift:30-32` (10–200 px/s) | reader.js :317-330 implements it fully; no UI | port toggle + speed slider |
| B6 | Tap-turn / swipe-turn / tap inversion | `ReaderAppearance.swift:33-35` | reader.js :29-31,363-386 | port toggles |
| B7 | True-black theme | iOS `trueBlack` value | reader.js THEMES has `trueBlack` (:41) but `ReaderTheme` enum (`Theme.kt:178-180`) has no case and `themeMode()` (`BookReaderScreen.kt:340-344`) can't emit it | add the enum case + mapping |
| B8 | Chapter prev/next in reader chrome | `BookReaderView.swift:668` `readerGoChapter(n)` | TOC sheet only | add chrome buttons |
| B9 | `spineCount` in the manifest | `Book.swift:28` | no field; only `chapters.size` | add (needed for progress display parity) |
| B10 | Per-card `importError` | `Book.importError` surfaced on the shelf card (`BookGridCard.metaLine`) | the field exists (`Book.kt:25`) but is **never written or read**; failures exist only as a transient banner/toast | write it at import, render a badge on the card |
| B11 | Books recycle bin | `BooksStore.swift` `deletedAt` + purge + `BooksRecycleBinView` | `BooksStore.kt:43-46` hard-deletes (`deleteRecursively`) | already tracked as P1 in `research/FEATURE-PARITY.md:23` — port `deletedAt` + bin screen + restore |

---

## Batch C — PDF quality (two real bugs, not missing features)

| # | Item | Evidence | Fix |
|---|---|---|---|
| C1 | Synthetic line geometry kills two whole detectors | `SpeechnotesViewModel.kt:1268-1277` — every line reports `y = height-(i+1)*12.0, height = 12.0, x = 0.0`. `twoColumnSplit` needs distinct x positions (`PdfText.kt:166-167`) → can never fire; `chaptersFromHeadings` needs `height > modal*1.35` → all heights are 12.0 → always false | extract real line geometry from PdfBox (`PDFTextStripper` regions, or `TextPosition` x/y/height) so the gutter and heading tiers actually run. This is a **correctness bug**, ranked above features |
| C2 | `PdfText.pageText` (two-column-aware) is never called | `PdfText.kt:123` — `chapterText` calls `source.pageText(page)` directly | route chapter extraction through the gutter-aware path |
| C3 | Pinch-zoom + fit-to-width | iOS fit-width rework (`BookPDFReaderView.swift:713`); Android renders `fillMaxSize` at a fixed scale (`BookReaderScreen.kt:494-498`) | `pointerInput` transformable around the page bitmap, clamp 1×–4×, double-tap reset. Already a P2 in the parity doc |
| C4 | Outline tree folding | iOS collapsible subtrees with current highlight | Android walks the outline but renders flat (`BookReaderScreen.kt:386-412`). P2 in the parity doc |
| C5 | OCR path quality | Android uses ML Kit Latin at 1.6f render (`SpeechnotesViewModel.kt:1012-1042`) vs iOS Vision `.accurate`, 2×, language correction off | match the iOS parameters; also gate on `pageHasTextLayer` the same way |

---

## Batch D — Audiobook chapter detection robustness

The Android hand-parsers (`logic/AudiobookChapters.kt`) are the ONLY path — there is no AVFoundation equivalent, so they inherit every heuristic iOS documents as fragile (`AudiobookChapters.swift:510-550`).

| # | Item | iOS's documented failure | Android fix |
|---|---|---|---|
| D1 | Nero 4-byte vs ffmpeg 1-byte count | both "succeed" on plausible bytes; a first chapter at 0 falls to variant B and mis-parses | decide by **validated table**, not by which parse returns non-empty: monotonic starts, first ≈ 0, last end ≈ duration; log the atom's byte offset |
| D2 | Unit divisor guessed against duration | 100-ns vs ms both "fit within 2× duration" → closest wins; a 400 s prologue picks wrong | read the movie timescale from `mvhd` and use it (spec-driven, not guessed) |
| D3 | `moov/udta/chpl` signature hunt on the tail | a `chpl` inside `mdat` audio bytes can pass | require monotonic, in-duration validation before accepting |
| D4 | `stsc` first-run only | real chapter tracks have multi-run tables → titles land wrong or drop | walk all runs (iOS admits this is "not worth the complexity" — on Android it's the only path, so it IS worth it) |
| D5 | 8 MB head slice | `chaptersFromTrak` silently returns title-less chapters when `stco` points past it | parse the whole `moov` via the box walker (already present, :72-90) instead of slicing |
| D6 | No AVFoundation-tier divergence to worry about | iOS writes `"avfoundation"` where Android writes `"mp4-full"` | pick one canonical source-string set for both platforms; the iOS doc flags the schema divergence as a porting hazard |
| D7 | Decodability not validated at import | a file MediaPlayer can't decode imports fine (empty chapters, duration 0, synthetic cover) and fails at first play | probe at import; set `importError` so the shelf card shows it (depends on B10) |
| D8 | Fake "Full audiobook" chapter | iOS writes a placeholder then hides it by exact title match (the `07785fb` special case) | don't copy this bug: represent no-chapters as `audioChapters: null` + an honest "no chapter markers" UI state |

---

## Batch E — Functionality parity (whole missing categories)

| # | Category | iOS evidence | Android now | Size |
|---|---|---|---|---|
| E1 | **Stats + reading goals** | `StatsStore`, `StatsCenter`, `StatsRecorder`, `ReadingGoalsView` — Duolingo-style goals with live-derived progress, 18-week heatmap, streaks | **nothing at all** — zero hits for `StatsStore`/`goal`/`streak` | large; split E1a (stats store + recorders + persistence) and E1b (stats tab UI) and E1c (goals) |
| E2 | **BookDrop / LocalSend v2.2 receiver** | `LocalSend/LocalSendReceiver.swift` — register/info/prepare-upload/upload/cancel, sha256 verify, per-file tokens, accept history | **nothing** — no network code beyond OkHttp model downloads | large; port the models + HTTP server + accept-history UI |
| E3 | **Office documents as books** | `DocumentEpub.swift` — docx/odt/pptx/odp/doc → normalize-to-EPUB, real `<table>` HTML with spans, one chapter per slide | **nothing** — `grep -i "docx\|odt\|pptx\|odp"` → zero hits | large; the normalize-to-EPUB pipeline is reusable with the existing ZipReader |
| E4 | **Office documents as notes** | `ImportService.officeDocumentText` (tables → comma-joined lines) | nothing | small once E3's parsers exist |
| E5 | JEX export loses note images | iOS exports `resources/<id>.<ext>` + metadata | `JexExport.kt:25` `NotePayload.images` is always empty — images are silently dropped | small; the importer already handles them |
| E6 | JEX import drops titles/timestamps | `ImportedNote.title` parsed, never used; created/updated ignored | same bug ported | small |
| E7 | Immersive chrome per surface | iOS per-surface keys `.epub/.pdf/.audio/.editor` | Android hides only the top bar in the note editor while reading (`NoteEditorScreen.kt:519-521`); reader chrome toggle exists via JS `chromeTap` | small — add the per-surface preference keys |
| E8 | Favorites filter | iOS: also absent (`Note.swift:26` claims "filterable", nothing filters) | also absent | **decide together**: ship a real favourites scope on both, or delete the flag on both. Don't port a fiction |
| E9 | Landscape rail | iOS `PlaybackRail.swift` — 150 pt trailing panel, voice chip, progress capsule, horizontal rate slider, chapter stepper, minimisable | **nothing orientation-aware** — no size-class or configuration usage anywhere; books grid is `GridCells.Fixed(3)` in both orientations | medium; add `currentWindowAdaptiveInfo` and a rail for expanded widths |
| E10 | Draggable edge-snapping mini-player bubble | iOS normalised coords persisted (`miniBubbleX/Y`) | Android bubble exists but is not draggable (no `pointerInput` in `MiniPlayerOverlay.kt`) | small |
| E11 | Sleep-timer remaining display + persistence | iOS shows live M:SS | Android presets only (`MiniPlayerOverlay.kt:427-461`) | small |
| E12 | Becoming-noisy receiver (headphone unplug) | iOS pauses on route change | no receiver registered | small |
| E13 | About pane: version, licences | `AboutView.swift` | bare ListItem | trivial |
| E14 | Hang watchdog | iOS `HangWatchdog` (diagnostic; Android ANR is system-handled) | missing | optional — P3 in the parity doc |

---

## Batch F — Aesthetics (only after A–E land)

- Books grid: adaptive columns (`GridCells.Adaptive`) instead of `Fixed(3)` — 3 columns is cramped on tablets.
- Settings: one scrolling column of cards → grouped panes matching iOS's Form structure; add the missing version/licence rows.
- Reader spacing: `readerTableSpacing` is written but `MarkdownTable` never reads it; `previewTextScale` doesn't affect the note's own preview (only ReadAlong). Wire both.
- `editorBarMinimized` pref is declared and never used — delete or implement.
- Share-from-list writes a fixed file literally named `SpeechnotesViewModel-note.txt` (`NotesListScreen.kt:298-305`) — name it after the note.
- PDF share-as flattens markdown to plain lines (`NoteEditorScreen.kt:840`), losing heading/list styling iOS preserves via `UIPrintPageRenderer`.

---

## Bugs to fix regardless of batch (live, not theoretical)

1. **`renderMarkdown` derivation mismatch** — `resumeIfBookmarkPending`-equivalent logic and the editor can disagree on which text is spoken, so a stored `textHash` won't match and "resume" silently becomes "start over" while corrupting the slot. Verify the Android equivalent (`SpeechPlayer.kt:571-595` position-source priority vs `NoteEditorScreen.kt:316-318` `computeSpeechText`) and make one derivation the single source of truth.
2. **`sampleRate` teardown** (A7) — wrong-rate playback is silent corruption, not a cosmetic bug.
3. **PDF synthetic geometry** (C1) — two detectors are dead code as written.
4. **WAV export has no UI** (A8) — a shipped feature that looks broken to the user.
5. **`exportState`/`shareFile` dead flows** — delete the flows or observe them; dead observable state is how the original iOS bug class started.

---

## Verification protocol (Waydroid)

```bash
# one-time per session
adb kill-server && adb start-server
waydroid show-full-ui          # detached; takes ~60 s
adb connect <waydroid-ip>:5555   # needs the key already in place

# build (JDK 17 is required — system Java is 11)
JAVA_HOME=~/tools/jdk-17.0.13+11 ./gradlew --offline assembleDebugX86_64
adb -s <waydroid-ip>:5555 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
```

**Install the x86_64 split, never the arm64 one** — the arm64 APK SIGSEGVs at startup on Waydroid (`/memfd:exec (deleted)`, null-deref at load; confirmed 2026-10-13). Baseline screenshots: `~/.zcode/tmp/android-baseline/`.

**Measured baseline (2026-10-13, Waydroid x86_64):** app launches clean on the x86_64 split; Supertonic loads 4 sessions + 10 styles in ~2.9 s; first chunk 29 tokens → 1.7 s of audio in 4.5 s render (RTF ≈ 2.6 on this desktop-CPU container — **not a phone proxy**); state machine IDLE→GENERATING→SPEAKING is correct; mini-player, pause, stop all present. Compile + 8 unit tests green.

**Disk warning:** the workspace root is at 100% (2.8 G free after clearing the Rust incremental cache and the Gradle build cache). Every build must be preceded by a `df -h /` check; `~/.gradle/caches/build-cache-1` and `target/debug/incremental` are the safe reclaim targets.

## Ordering rationale

A first because quality gaps are cheap to close (constants + one policy port) and expensive to leave. B next because the bridge already exists — it's wiring, not invention. C before D because C contains live correctness bugs while D is robustness hardening. E is the big new-functionality block and splits cleanly per category. F last, per the user's ranking.
