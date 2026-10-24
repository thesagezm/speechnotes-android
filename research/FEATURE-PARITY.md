# iOS → Android feature-parity research (2026-09-30)

Inline audit (subagents unavailable). Sources: `speechnotes-ios/App/Sources/**` vs
`speechnotes-android/app/src/main/java/com/speechnotes/app/**`.

## Summary counts

- P0 gaps: 0 (all core flows ported)
- P1 gaps: 1 (books recycle bin)
- P2 gaps: 5
- P3 gaps: 4
- Deliberate omissions: 4

Already fixed today (2026-09-30, v1.1.1) — do not re-port: Supertonic tensor dtype,
Kokoro engine→player callback wiring (mini player / play-toggle / state), book cover
backfill + PDF cover fallback, PDF prev/next buttons, editor top-bar play icon state,
editor title field overlap.

## P1 gaps

| Feature | iOS evidence | Android status | Porting notes |
|---|---|---|---|
| Books recycle bin (soft-delete, restore, 30-day purge, un-bin-on-open) | `BooksStore.swift:680,693,712-717,771` (`deletedAt`, purge expired, open un-bins), `BooksRecycleBinView.swift` | MISSING — `BooksStore.delete` does `deleteRecursively()` immediately (`BooksStore.kt:46-49`) | Add `deletedAt: Long?` to `Book`, trash/restore/purge like notes; add a Books bin entry point (Notes bin already exists: `RecycleBinScreen.kt`) |

## P2 gaps

| Feature | iOS evidence | Android status | Porting notes |
|---|---|---|---|
| PDF pinch-zoom + per-page fit | `BookPDFReaderView.swift:692` (pinch-zoom mentioned in fit-width rework) | MISSING — page is a fixed `Image` (`BookReaderScreen.kt:417-425`); this build adds fit + prev/next buttons but not gestures | `pointerInput` transformable (scale+pan) around the page bitmap; clamp 1×–4×, double-tap reset |
| PDF outline tree with collapsible subtrees (chevron fold) | `BookPDFReaderView.swift:33-45,345-442` (children fold, current highlighted) | PARTIAL — Android walks the outline but renders a FLAT list (`BookReaderScreen.kt:386-412`) | Carry `(depth)` per row; `MutableStateMapOf` of collapsed ids; indent by depth |
| Books sort menu (title/author/recent) | `BooksView.swift:55-69` (toolbar sort Menu) | MISSING — only search (`BooksScreen.kt:122-131`); store sorts by title only (`BooksStore.kt:26`) | Sort chip or MoreVert menu; persist choice in Prefs |
| Audiobook bookmark/resume clock estimate for engines without char events | `SpeechPlayer.swift` (wall-clock estimate path) | PRESENT (ported in `SpeechPlayer.kt:521-524`) — verify only | None — listed for completeness after the state-wiring bug |
| Storage screen: per-export playback surface jump from mini player | `SettingsTabView.swift:20-23,70-73` (`.miniPlayerJumpToExports` → push Storage) | PARTIAL — export mini bar exists; tap does nothing meaningful (`onJumpToExports` default `{}` in `MiniPlayerOverlay.kt:70-73`) | Navigate to Settings→Storage and auto-scroll/highlight the playing export |

## P3 gaps

| Feature | iOS evidence | Android status | Porting notes |
|---|---|---|---|
| HangWatchdog (main-thread stall detector/announcer) | `Services/HangWatchdog.swift` | MISSING | ACFR-style watchdog thread; log + surface in Logs screen. Android ANR is system-handled, so value is diagnostic only |
| PlaybackMetrics (time-to-first-audio, per-chunk latency stats) | `Engine/PlaybackMetrics.swift` | MISSING — engines log one-off lines | Accumulate into LogStore-visible rolling stats on the Logs screen |
| ReaderSpacing / PlaybackRail reader layout refinements | `Views/ReaderSpacing.swift`, `Views/PlaybackRail.swift` | PARTIAL (functional but plainer) | Fold into aesthetics pass, not separate porting |
| About screen richness (version, author, MIT) | `AboutView.swift` | PARTIAL — bare ListItem (`SettingsScreen.kt:423-425`) | Minor copy/layout parity |

## Deliberate omissions (do NOT port)

1. **NowPlayingCenter/MPNowPlaying** — Android equivalent already exists (`SpeechPlaybackService.kt` media session + notification).
2. **UIKit/SwiftUI platform bits** (ShareSheet via UIActivityViewController) — Android uses system share intents; already wired.
3. **iOS-specific audio session interrupts** (`AudioSessionSetup.swift`) — Android audio-focus listener covers it (`SpeechPlayer.kt:150-186`).
4. **Widgets/Siri/AppIntents/Spotlight/Watch/CarPlay** — the iOS app has none either (no imports found); not a gap.

## Verified parity (spot-check, no action)

- Note model: `explicitTitle/text/createdAt/updatedAt/deletedAt/notebookId/isPinned/isFavorite`, 30-day recycle retention — identical (`Note.swift` vs `Note.kt`).
- Notebooks, recycle bin (notes), JEX export+import with notebook scoping, storage card, logs, about.
- Speech settings: render-markdown toggle, haptic toggle, image-cache mode, engine picker + per-model download/retry/delete, voice picker scopes per engine.
- Appearance: accent choice, light/dark/system, reader theme (white/sepia/dark), text scale.
- Engines: system TTS, Kokoro (28 voices), Kokoro small, Supertonic (31 langs, 10 styles), Soprano; streaming chunk playback; sleep timer (minutes/end-of-chapter); rate 0.5–2×; audition with restore; bookmarks (note/book-chapter keyed, hash-guarded).
- Books: EPUB (epub.js WebView + asset-loader origin, CFI position, TOC, font size, themes), PDF (chapter resolution from outline/headings/pages, page slider, outline list, per-page listen), audiobooks (chpl/ID3 chapters, ±15s, speed, sleep, app-level player surviving reader).
- Read-along with sentence snapping; WAV export + share; beep fallback; markdown slash menu; note images (insert, cache, tap-to-zoom with pinch — `NoteEditorScreen.kt:956-958`).
