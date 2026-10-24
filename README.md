# Speechnotes for Android

An offline, TTS-first notes app — the Android port of the local
`speechnotes-ios` project, redesigned in **Material 3 Expressive** (large
rounded shapes, springy motion, vibrant tonal color, button groups, FAB menu,
modal sheets, wavy-friendly progress).

**Status: builds and runs.** `./gradlew assembleDebug` produces a working
debug APK; the app has been driven end-to-end on an Android 36 emulator
(notes, playback, read-along, voice picker, settings, books shelf, recycle
bin, dark mode).

## Features

- **Notes** — create/edit/delete, debounced autosave with flush on exit,
  search, sort (last edited / created / title), date-era sections (Today /
  Yesterday / Previous 7 days / Older), pinned section, favorites, notebook
  scoping chips, undo-delete snackbar, 30-day Recently Deleted bin with
  recover / delete-now / empty-all, clipboard import.
- **Editor** — title field in the top bar, autosave, markdown preview
  (rendered blocks), slash-command menu (18 commands with fuzzy filter),
  share as markdown / plain text / audio, delete confirm.
- **Speech** — three selectable engines, all offline after a one-time model
  download:

  | Engine | Model size | Voices | Notes |
  |---|---|---|---|
  | Android system TTS | 0 | every installed system voice | Instant, no download |
  | Kokoro small (ONNX, CPU) | ~177 MB | 28 (US/UK, m/f) | uint8 tier |
  | Kokoro (ONNX, CPU) | ~341 MB | 28 (US/UK, m/f) | fp32 quality |
  | Supertonic (ONNX, CPU) | ~399 MB | 10 styles × 31 languages | Flow-matching, best quality |

  Sentence-chunked streaming playback with generation-ahead pacing, live
  speed slider (0.5–2.0×, applied per chunk), pause/resume/stop, sleep timer
  (5/15/30/60 min + end of chapter), WAV export + share.
- **Read-along** — dedicated reader highlighting the sounding sentence via
  play-time position tracking, with auto-scroll.
- **Voice picker** — searchable, sectioned by accent/gender, tap-to-audition,
  Supertonic language selector (31 languages).
- **Books** — import EPUB / PDF / M4B / MP3 into a cover grid with search;
  chapter narration with auto-advance on natural completion; per-chapter
  cached speech text. EPUB metadata + TOC parsed natively (ZipReader +
  EpubParser), PDF chapters resolved from outline → heading geometry →
  page ranges (PdfBox-Android), with OCR-ready page extraction.
- **Mini-player** — global bar above the navigation bar, collapsible to a
  progress bubble, jump-to-note/book on tap.
- **Settings** — render-markdown toggle, engine picker, per-engine model
  download/delete with progress, accent (12 choices + dynamic color),
  light/dark/system, reading text scale, Joplin `.jex` backup export,
  storage breakdown, about.
- **Onboarding** — three-page first-launch tour.

## Design

Material 3 Expressive, ported 1:1 from the iOS app's feature set:

- `MaterialTheme` with dynamic color on Android 12+, plus the iOS app's
  12 accent choices as explicit palettes when the user overrides.
- Expressive shapes (8/12/20/28/36 dp corner radii), pill transport buttons,
  `ModalBottomSheet` pickers, `FilterChip` notebook scopes, FAB menu,
  LargeTopAppBar-style scroll behavior.
- No custom fonts — the platform font, like the iOS app.

## Architecture

```
app/src/main/java/com/speechnotes/app/
├── SpeechnotesApplication.kt   # initializes the private-file layout
├── MainActivity.kt             # edge-to-edge host for SpeechnotesApp()
├── logic/                      # pure Kotlin ports of the iOS SpeechLogic
│   │                           #   package (no Android deps, unit-testable)
│   ├── SentenceChunker.kt      # sentence boundaries, chunks, resume offsets
│   ├── SpeechSanitizer.kt      # unspeakable-codepoint hygiene
│   ├── MarkdownText.kt         # markdown → speech text + reading blocks
│   ├── MarkdownSlashMenu.kt    # 18 slash commands + fuzzy filter
│   ├── XhtmlText.kt            # EPUB chapter XHTML → speech text
│   ├── EpubInfo.kt             # EPUB metadata/spine/TOC/cover parsing
│   ├── ZipReader.kt            # minimal read-only ZIP (EPUB containers)
│   ├── PdfText.kt              # PDF chapter resolution + normalization
│   ├── AudiobookChapters.kt    # MP4 chpl + ID3 CHAP parsing
│   ├── JexExport.kt            # Joplin .jex (tar) writer
│   ├── WavWriter.kt / TextHash.kt / SpeechText.kt
│   └── NoteImageStore.kt / AppFiles.kt
├── speech/
│   ├── SpeechEngine.kt         # SpeechState, EngineKind, ExportState
│   ├── SystemTtsEngine.kt      # android.speech.tts wrapper
│   ├── StreamingPlaybackCore.kt# chunk pipeline, AudioTrack, pacing, export
│   ├── KokoroEngine.kt         # Kokoro ONNX + NpzReader
│   ├── SupertonicEngine.kt     # Supertonic 4-session flow-matching TTS
│   ├── MisakiG2P.kt            # English G2P (dictionary-backed)
│   ├── ModelFiles.kt           # model URLs + size validation
│   └── SpeechPlayer.kt         # UI-facing player + bookmarks + auditions
├── data/                       # stores, persistence, ViewModel
│   ├── NotesStore.kt / NotebooksStore.kt / BookmarkStore.kt / BooksStore.kt
│   ├── Persistence.kt / LogStore.kt / ModelManager.kt
│   └── SpeechnotesViewModel.kt
├── model/                      # Note, Notebook, Book, VoiceCatalog
└── ui/
    ├── theme/Theme.kt          # M3 Expressive color/shapes/typography
    ├── notes/                  # list, editor, read-along, recycle, notebooks
    ├── editor/                 # markdown preview + player controls
    ├── readalong/              # sentence-highlighted reader
    ├── player/ (MiniPlayerOverlay.kt)
    ├── voice/VoicePickerSheet.kt
    ├── books/BooksScreen.kt
    ├── settings/SettingsScreen.kt
    ├── onboarding/OnboardingScreen.kt
    └── SpeechnotesApp.kt       # navigation host + global mini-player
```

The `logic/` package keeps the iOS app's algorithms verbatim (sentence
boundary rules, the markdown scanner, slash-menu fuzzy scoring, ZIP/npz
parsing, WAV headers, the jex tar writer) and shares its JSON field names
with the iOS app, so data can move between the two.

## Build

Requirements: JDK 17, Android SDK (platform 36, build-tools 36.0.0).

```bash
export ANDROID_HOME=/path/to/android-sdk   # or write local.properties
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

The project is offline-first: the app itself declares **no INTERNET
permission**. The only network use is the one-time model downloader in
Settings, which downloads into the app's private storage and validates file
sizes before an engine is enabled.

### G2P dictionaries

Kokoro needs an English grapheme-to-phoneme dictionary. `MisakiG2P.kt` loads
`assets/g2p/us_gold.json.gz` (and the gb/silver variants) — drop the same
dictionaries the iOS app bundles there. Without them the phonemizer falls
back to letter-by-letter spelling, so speech still works, just rougher.

## Verification

Driven on an emulator (Pixel-class AVD, Android 36, x86_64):

- onboarding → notes list → new note → typed text → autosave survived restart
- play with the system voice: `IDLE → SPEAKING → IDLE` transitions observed in
  logcat (`Speechnotes` tag)
- read-along surface replaced the editor during playback, with pause/exit
  controls and the mini-player bar visible above the keyboard
- voice picker opened from the editor's voice chip, search filtered to
  "bella" (Bella + Isabella rows), row selection persisted
- settings: engine picker with download rows, appearance (dark mode verified
  visually), storage breakdown, `.jex` export button
- books tab empty state + import affordance
- delete from the list moved a note to Recently Deleted (30 days left);
  Recover brought it back

## Known gaps (next milestones)

- The EPUB reader (epub.js in a WebView) and the PDF page-rendering reader
  are not wired into the UI yet — import, chapter resolution and speech-text
  extraction work, but the reading surfaces still open as the shelf entry.
- Audiobook playback (MediaPlayer + chapters) is parsed but not surfaced.
- Neural-engine output has not been exercised on device (model download +
  ONNX inference need a real device or a longer emulator session); the
  system-voice path is fully verified.
- ML Kit OCR for scanned PDFs is a dependency but not yet called from the
  page extractor.

## Credits

Ported from `speechnotes-ios` (this workspace). Kokoro (Apache-2.0 model),
Supertonic (Supertone; vendored MIT runner ported to Kotlin), ONNX Runtime
(MIT), PdfBox-Android (MIT), epub.js (FreeBSD), JSZip (MIT/GPL).
