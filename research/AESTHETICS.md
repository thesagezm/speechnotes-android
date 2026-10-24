# Aesthetics audit — Speechnotes Android (2026-09-30)

Inline audit. Baseline: the theme is already solid — M3 dynamic color + 12 seeded
accents (`Theme.kt:45-75`), light/dark/system, reader themes. The gaps are in
motion, depth, and small-screen rhythm, not palette.

## Top 10 quick wins (S = under an hour, M = half a day, L = a day+)

| # | Item | File | Concrete change | Effort |
|---|---|---|---|---|
| 1 | Screen transitions are the hard A→B default | `SpeechnotesApp.kt:132-171` | Give `NavHost` `enterTransition`/`exitTransition`: fade+slight slide (e.g. `fadeIn(tween(220)) + slideInHorizontally { it/14 }`), pop reversed. One parameter block, whole app instantly feels softer | S |
| 2 | Note cards appear/disappear stiffly | `NotesListScreen.kt` (LazyColumn) | `animateItem()` on row modifier (Compose 1.7: `Modifier.animateItem()`), so deletes/reorders glide | S |
| 3 | Book covers sit flat on the shelf | `BooksScreen.kt:255-262` | `CardDefaults.cardElevation(defaultElevation = 2.dp, pressedElevation = 6.dp)` + a subtle shadow shape (large corner); optionally 2dp stroke of `surfaceContainerHighest` for covers with white edges | S |
| 4 | Mini player pops in with no motion cue | `MiniPlayerOverlay.kt:106-130` | `AnimatedVisibility(enter = expandVertically(expandFrom = Bottom) + fadeIn(), exit = shrinkVertically(shrinkTowards = Bottom))`; add `animateContentSize()` on the Column so bar↔bubble morphs instead of jumping | S |
| 5 | Editor top bar is cramped at 6 actions | `NoteEditorScreen.kt:612-661` | Move Share + Delete into one `MoreVert` overflow (matches the Notes list's menu idiom); the play spinner keeps its slot. Title field gets real width back | S |
| 6 | Progress bars are default-thin | `MiniPlayerBar`, `PlayerControlsBar` | `LinearProgressIndicator(strokeThickness = 5.dp, trackStrokeThickness = 5.dp, gapSize = 0)` rounded caps; or a thin `Slider`-style scrubber with a 4dp track for read-along position | S |
| 7 | Search field + chips row reads as two stacked systems | `NotesListScreen.kt:146-165` | One shared surface: wrap search + chip row in `Surface(color = surfaceContainerLow)` with the search field borderless (`colors(focusedContainerColor = Transparent…)`), chips scroll inside the same band | M |
| 8 | Empty states are text-only | `NotesListScreen`, `BooksScreen.kt:214-246` (Books has the good one) | Reuse Books' illustrated empty state pattern (tinted rounded square + icon + two-tone copy) for Notes/RecycleBin/Notebooks | S |
| 9 | Splash → app handoff is abrupt | `SpeechnotesApp.kt:53-66` | `androidx.core.splashscreen` theme with the app's waveform icon on the teal gradient; `setOnExitAnimationListener` fade. Cheap perceived-quality jump | M |
| 10 | FAB menu labels float without scrim | `NotesListScreen.kt:167-196` | Dim background 20% (`Scrim`/overlay Box) while `fabExpanded`, and stagger the two labels with `AnimatedVisibility` + 40ms delay — reads as deliberate, not popped | S |

## Screen-by-screen findings

**Theme.kt** — healthy. Add: `Typography` override so `titleLarge/headlineSmall` use a slightly tighter tracking (letter-spacing −0.01em) for the bold "Speechnotes" headers; consider `MotionScheme` (M3 1.4) once pinned deps allow. Dark scheme relies on dynamic color on A12+; verify seeded accents also re-map `surfaceContainer*` (they do via `TonalPalette`, fine).

**Notes list** — hierarchy is good (title bold → snippet gray → meta row). Improvements: date chips right-aligned in `labelSmall·onSurfaceVariant` already; the pin/star/trash/share icon row could become a single long-press context sheet (iOS contextMenu parity, `NoteRowView.swift`) — reduces per-card visual noise by 3 icons.

**Editor** — top bar fix from today + quick-win #5. The formatting bar: give buttons `FilledTonalIconButton` toggled states (active bold button shows `secondaryContainer`) so mode is visible at a glance. Read-along screen: add `animateColorAsState` crossfade on the sentence highlight (200ms) instead of instant swap.

**Books** — cover grid: aspect 0.68 good; add quick-win #3 elevation + pressed `scale 0.97f` via `graphicsLayer` in `interactionSource` collectIsPressed. Detail sheet: cover thumbnail at the top of `BookDetailSheet` (48dp, rounded) — currently text-only, misses the physical "book" cue.

**PDF reader** — today's buttons land in the right row. Add swipe pagination (`Modifier.pointerInput { detectHorizontalDragGestures }`, threshold 80dp, animate page crossfade with `Crossfade(pageIndex)`) and pinch-zoom (P2 parity item). Page label → `AnimatedContent` slide so flipping pages feels physical.

**Mini player** — bar is information-dense; bubble mode is good. Add quick-win #4 motion; when GENERATING, the spinner+label is right. Consider `graphicsLayer` blur-glass imitation: `surfaceContainerHigh` at 0.92 alpha over content (cheap glass feel without Haze).

**Onboarding** — check: give each step `AnimatedContent` slide between pages and a 3-dot progress indicator; ends with the accent pick so the first impression is already personal.

**System bars** — `enableEdgeToEdge` with `Surface`-colored status bar scrim per screen (editor vs reader currently same); guard against icon-contrast flip in dark mode.

## iOS polish worth porting

- Note row context menu (long-press → pin/favorite/delete) — `NoteRowView.swift:172-176` contextMenu idiom.
- PDF outline chevron-fold (`BookPDFReaderView.swift:386-442`) — both a parity and an aesthetic win.
- `ReaderSpacing` typography rhythm in EPUB reader (measured paragraph spacing).

## Design-language proposal (one paragraph)

Speechnotes Android should read as "calm paper": M3 tonal surfaces with the seeded
accent used only for state (selection, progress, active chips), never decoration;
book covers and note text are the only saturated elements. Motion is small and
asymmetric — 200–250ms fades/scale-presses for controls, 300ms+ shared-axis slides
for navigation, everything interruptible. Shape language: extra-large rounded
search/cards (already there), 12dp for inner chips; elevation reserved for truly
floating things (mini player, FAB, sheets) so books and notes sit flush on the
shelf. Type: one family, weight does the hierarchy (bold titles, medium labels,
regular body), tabular figures in clocks/word counts.
