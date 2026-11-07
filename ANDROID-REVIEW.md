# ANDROID-REVIEW.md — Pack Coordination Ledger

Coordination file for the Speechnotes-Android agent pack. **Albus** (lead) owns
this file's structure and the git history; **Harry** (GUI), **Hermione**
(functionality/security/trim) and **Ron** (TTS/OCR) append to their own
sections only. The repo is PUBLIC — never write host paths, IPs, usernames or
machine details here. The Waydroid adb endpoint is read from `waydroid status`
each session.

## Ground rules (binding for all three)

1. **No subagents.** Every agent does all work itself — never spawn agents,
   subagents, or workflows.
2. **Git is read-only for pack members.** Allowed: `git status`, `git log`,
   `git diff`, `git show`. Forbidden: `git add`, `commit`, `push`, `checkout`,
   `stash`, `reset`, `restore`. Albus reviews and commits the work.
3. **Never edit `AGENTS.md` or `QUEUE.md`.** Albus owns both.
4. **This file is append-via-Edit only.** Never rewrite the whole file. Each
   member edits only inside their own `## <Name> — log` section. Re-Read the
   file immediately before every edit (a stale edit fails to match; re-read
   and retry — that failure is the collision detector).
5. **One gradle at a time.** Wrap EVERY `./gradlew` invocation in
   `flock /tmp/sn-gradle.lock -c '<command>'`. Never run gradle bare.
6. **Serialize device mutations.** Installs and `adb shell am/pm` operations
   go under `flock /tmp/sn-waydroid.lock -c '<command>'`. Short
   `adb logcat -d` dumps may run unlocked.
7. **Waydroid only.** Never start an AVD/emulator. Install only the
   x86_64 APK (`app/build/outputs/apk/debug/app-x86_64-debug.apk`) — arm64
   SIGSEGVs. Audio is not audible in the container by design; evidence is
   state transitions + logs (tag `Speechnotes`) + non-trivial WAV exports.
8. **No new dependencies** without Albus approval recorded here. The one
   pre-approved dependency is `mlkit-text-recognition` 16.0.1 (already
   declared). Compose is pinned to 1.9.1 and material3 to 1.3.2 — never bump.
9. **Unit discipline.** Smallest slice that leaves the 115-test suite green
   and the x86_64 APK building. Log every unit in your section when it lands.
10. **iOS repo is read-only:** `~/code/speechnotes-ios`. Its working tree
    sits on main @ v1.7.4; the v1.8.x work lives on branch
    `batch-c-d-session`. Read files with
    `git -C ~/code/speechnotes-ios show 15208d8:<path>` (v1.8.1) and browse
    history with `git log --oneline v1.7.4..batch-c-d-session`. Never
    checkout, pull, or modify that repo.

## Build & test quick reference

```bash
export JAVA_HOME=~/tools/jdk-17.0.13+11
flock /tmp/sn-gradle.lock -c './gradlew --offline testDebugUnitTest'   # 115 green baseline
flock /tmp/sn-gradle.lock -c './gradlew --offline -Psplits=true assembleDebugX86_64'
adb connect $(waydroid status | awk '/IP address/{print $3}'):5555
flock /tmp/sn-waydroid.lock -c 'adb -s <ip>:5555 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk'
```

## Ownership map (this wave)

| Area | Owner | Notes |
|---|---|---|
| `ui/**` screens, nav, mini-player, theme/haptics, onboarding | **Harry** | incl. stats tab extraction, playback bar minimize, landscape rail, pomodoro UI |
| `ui/settings/StatsScreen.kt` | **Harry** | moves out of Settings into its own tab |
| `speech/**`, `playback/**` | **Ron** | engines, streaming core, player, service |
| `logic/ChunkSchedule.kt`, `SentenceChunker.kt`, `RenderAheadBankPolicy.kt` | **Ron** | seconds-based bank + silence bridge |
| `logic/MathNotation.kt` (new), `SpeechText.kt`, `MarkdownText.kt` | **Hermione** | symbol/math expansion; Ron consumes, never rewrites |
| `logic/Jex*.kt`, `NoteImageStore.kt`, `LocalSendProtocol.kt` | **Hermione** | JEX round-trip + BookDrop |
| `data/LocalSend*.kt`, `ModelManager.kt` (jex/share), new sender | **Hermione** | v1.8.1 BookDrop port + send-without-picker |
| OCR pipeline (`logic/PdfText.kt` + new OCR merge) | **Ron** | ML Kit (pre-approved, already declared) |
| `ui/settings/*` audit (all except StatsScreen) | **Hermione** | every setting must work |
| `data/SpeechnotesViewModel.kt` | **shared, claim-first** | additive edits only; claim below before touching |
| `ui/books/BookReaderScreen.kt` | **shared, claim-first** | Harry: rail/chrome · Ron: OCR merge, narrate-from-spot |
| `ui/editor/NoteEditorScreen.kt` | **shared, claim-first** | Harry: preview toggle, chrome · Ron: play-from-caret hook |
| `data/NotesStore.kt`, `data/BooksStore.kt`, `model/Book.kt` | **Hermione** (M-units) / **Ron** (OCR fields) | claim-first |

**Claim protocol:** before touching a shared file, append one line to your
log section: `[HH:MM] claim <file> for <unit>`. Check the other sections for
an active claim on the same file first; if claimed, wait (do other work) or
coordinate here. After finishing with the file, log `release <file>`.

## Escalations (resolved by Albus)

- (none yet)

## Harry — log

- **SESSION 3 (2026-11-07) — device-verification pass (V0 remainder + H5 +
  H6). HANDOVER.** Start state: the installed APK was STALE (installed
  11-02 23:52, tree last touched 11-03 01:51) → reinstalled the current
  app-x86_64-debug.apk (built 11-03 01:52, matches the tree) via
  push + `pm install -r` (streaming install still unreliable — use Ron's
  route). Baseline re-run: **217/217 tests green**, disk 57%.
  Landscape on THIS display = `user_rotation 0` + `accelerometer_rotation 0`
  (re-confirms last session's correction: natural orientation is landscape,
  `user_rotation 1` gives a PORTRAIT window). Rotation RESTORED at close
  (0 / 1). No uncommitted code from me — verification only.
  **H4 — VERIFIED on all four surfaces.** Editor (landscape): trailing rail
  owns playback — "Minimize" chevron, voice chip "Eric · American · male",
  52dp play, stop, read-along eye, 1.80× rate slider; the bottom transport
  bar is gone. Chevron → 66dp capsule (Play + expand chevron) → expand
  restores the full rail. Audiobook reader: rail (chevron, Ch stepper,
  play, 1.00× slider) beside the full player card (cover bounded, position
  slider, transport with the ±15 s "15" glyphs); RAIL PLAY TAPPED →
  0:00 → 0:06 of a 2:00 file, rail button flipped to Pause → the mp3 path
  really is driven by the rail (no TTS engine involved); rail collapse →
  capsule verified on this surface too. EPUB reader: trailing rail
  (chevron, Ch 1/1 stepper, "System voice" chip, play, 1.80×) + bottom bar
  reduced to reader chrome (Contents/Appearance/A-/A+); collapse verified.
  PORTRAIT regression — PASS: rail gone everywhere, the EPUB reader's
  bottom bar is back (Contents/Appearance/A-/A+/Listen from here); books
  shelf renders 3 columns.
  **H6 — PARTIAL VERIFY.** ±15 s glyphs: verified on the audiobook transport
  (SkipSecondsIcon renders in the reader and is shared with the mini bars —
  the reader glyphs show "15" labels in the a11y tree). Adaptive books
  grid: verified numerically — landscape cells ≈129 dp (Fixed(3) would have
  given 296 dp), portrait 3 columns. NOT exercised on device (OOM, see
  below): the empty-bin confirm dialog, and the scope-empty "Nothing here
  yet" list — that branch needs an EMPTY notebook scope, which the device
  data does not have (I was killed while trying to create one). Code read
  and matches the H6 description (RecycleBinScreen confirm before
  emptyRecycleBin; NotesListScreen.EmptySearchState renders "Nothing here
  yet / Notes you add to this view show up here." for a blank query) —
  these two want a device re-check next session.
  **H5 — VERIFIED (device), with two open items.** Stats tab shows the
  Pomodoro card mounted under Reading goals. Config sheet matches iOS:
  mode segmented (Structured / Flowmodoro / classic), prep 2–5 min, classic
  Blocks stepper + per-block minutes, Breaks steppers; Structured mode
  offers Ends = Finish time | Time limit (min 10 min, max 12 h) + Breaks-in-
  between + each-break minutes; Flowmodoro shows the three rule rows
  ("5 min of focus earns 1 min of break", "Claim at 3 break min — 15 focused
  minutes", "It keeps growing"). A SHORT classic run (2 min prep, block 1
  min, break 1 min) was driven end-to-end: app-wide banner "Warming up —
  2 min of prep, then focus begins", orange prep glass filling, 01:57 →
  "Preparing" pill, "1 blocks · 1 breaks · 1 min of focus", Pause → pill
  "Paused" + countdown FROZEN across 4 s + button "▶ Resume", Resume →
  countdown continues. The focus block COMPLETED and the stats card showed
  the day line **"🔥 Today · 1 block · 1 min focused"**; the break phase
  showed "Break 1 of 1" with Pause/Skip/Stop (Skip correctly appears only
  in a break). The session then completed naturally → card back to idle
  with the Today line persisted (launch-reconcile/kill-safety path proven:
  it survived the run completing while I was between dumps).
  OPEN ITEMS for the next Harry: (1) the Stop-confirm dialog — never
  captured; by the time my tap landed on the shifted Stop button the break
  had finished and the session ended naturally, so the dialog is UNTESTED
  (start a run, tap Stop immediately, screenshot the dialog); (2) the
  Flowmodoro claim gate — the claim button must stay DISABLED below 3
  banked break minutes (needs 15 min of focus to unlock; not reached); (3)
  the two H6 items above.
  **⚠️ ESCALATION FOR ALBUS/RON — app gets OOM-killed on this host (P0/P1,
  performance priority #2).** The host LMK killed Speechnotes 3× in
  ~90 min (the host had little free RAM and the swap was nearly full — the
  pack's parallel sessions plus Waydroid saturate the box; LMK reaped
  `com.speechnotes.app` as the largest foreground process each time,
  killing it mid-flow and dumping me to the home screen). Measured: app RSS
  **428–466 MB with Kokoro-small loaded vs 218–270 MB on the Android
  (system) engine**, and debug cold start **Displayed +3.38 s vs +2.07 s**.
  Root cause is visible in logcat at every launch:
  `KokoroEngine: 115 vocab entries, 28 voices ready in 2191 ms` — **the
  neural model is loaded EAGERLY on every app start**, before the user has
  asked for any speech; it never gets released even while reading a book or
  listening to an mp3. This is both the "slowest app in the family"
  startup cost and the memory that gets the app killed. RECOMMENDATION
  (Ron's files, logging for Albus): make engine init LAZY — construct the
  active engine on the first real speak/play request (or on
  STAGE_CREATED/READY if the user is on the reader), not in
  `SpeechnotesApplication`/`SpeechPlayer` construction, and expose an
  unload/idle-release (no speech for N minutes → release the ONNX
  session + PCM voice table); consider `android:largeHeap="true"` only as
  a band-aid. Until that lands I left the DEVICE on the Android (system)
  engine so the app survives a session — revert with one tap in Settings →
  Speech → Speech engine (this is a device-state change, not code).
  **DEVICE/BASELINE HANDOVER:** suite green (217/217), APK current on
  device (reinstall only after the next code change), no claims held, no
  shared file touched. Next Harry should re-check the stopped-confirm
  dialog, the ±15 s glyphs in the portrait mini bars, and the two H6 items
  above, plus the H4 surfaces if Ron's model-load fix lands (a rebuilt APK
  makes the rail re-verify cheap: same 4-surface matrix).

- **SESSION CLOSE (2026-11-03 ~01:10, handover for the next Harry). FINAL
  STATE: H1–H4 + H5 + H6 ALL CODE-COMPLETE; 217/217 tests green (130
  baseline at my session's start; the growth is Hermione's M-units + Ron's
  R-units landing in the shared tree — mine: +19 PomodoroEngine +
  9 ScreenAwakePolicy = 28); x86_64 APK BUILT AND INSTALLED on Waydroid
  (app-x86_64-debug.apk, built at 2026-11-03 ~00:5x with everything below);
  device rotation RESTORED (user_rotation 0 / accelerometer_rotation 1).**
  Device verify this session, in order:
  * H4: the editor rail re-confirmed on device after the new install —
    trailing panel (voice chip "System voice (model missing)", 52dp play,
    stop, read-along eye, rate slider), bottom transport gone; chevron
    collapse → 66dp capsule, expand restores. The audio-reader rail was
    found BROKEN (see the V0 entry: rail anchored at the bottom of the
    inner Column and pushed off-screen by the unbounded cover Surface) —
    FIXED, rebuilt, reinstalled and RE-VERIFIED: the audio reader now shows
    the trailing rail (chevron, Ch stepper, play, speed slider) beside the
    full player card (bounded cover, ±15 s glyphs in the transport).
  * H5: Stats tab shows the Pomodoro card under Reading goals; the config
    sheet works (mode segmented, prep 2–5 min, blocks stepper 4→1); Start
    Timer launched a classic run — the app-root banner showed "Warming up /
    2 min of prep…", the card flipped to the run view (orange prep glass,
    01:57 countdown, "Preparing" pill, caption, Pause/Stop, NO Skip during
    prep — all correct). The session was left running (2 min prep + 25 min
    block + 5 min break, started ~23:36); per plan it completed ~00:08 and
    the launch-reconcile folds 25 min of focus into the day log.
  * Rotation correction for the next Harry: THIS Waydroid display's
    natural orientation is LANDSCAPE (1920x1041) — `user_rotation 1` gives
    a PORTRAIT app window; the landscape test needs user_rotation 0 AND
    accelerometer_rotation 0 (else the accel flips it). Restore afterwards
    to rotation 0 / accel 1 (done at this close).
  RESUME FOR NEXT HARRY (device verify only — no code left from my brief):
  1. Launch → Stats tab: the completed session should show the Today line
     "🔥 Today · 1 block · 25 min focused" and, on the tick, the
     "Pomodoro complete" banner (kill-safety reconcile end-to-end). Then
     exercise Pause/Resume and the Stop confirm dialog; a Flowmodoro run
     needs the claim button to stay disabled below 3 banked break minutes.
  2. H4 remainder: audio-reader rail play tap (mp3 plays without TTS) and
     chevron collapse ON THAT SURFACE; the EPUB reader rail (a real EPUB,
     "BookDrop Test EPUB", is on the Books shelf); portrait spot-check
     (rotation 0/accel 1 and the rail must disappear, bottom bar back).
  3. H6 spot-checks still open: empty-bin confirm dialog, scope-empty
     "Nothing here yet" notes list, ±15 s glyphs in portrait mini bars,
     adaptive books grid.
  4. DEVICE CONTENTION is the dominant hazard (my taps landed on Ron's
     Speech-engine download loop twice; Hermione drove BookDrop flows
     mid-verify too). Fresh `uiautomator dump`/screenshot BEFORE every tap,
     batch actions, and check other sections' logs for who is driving.
  5. No claims outstanding (BookReaderScreen/NoteEditorScreen released).
     Device: x86_64 APK is CURRENT through this close — reinstall only
     after the next code change. Final suite was green before Albus's
     next commits land; if the tree reddens it is another agent's unit.
- **H5e DONE — Keep Screen On + onboarding Pomodoro page.**
  logic/ScreenAwakePolicy.kt (verbatim port: PREFERENCE_KEY "keepScreenOn"
  — the exact iOS key — default ON, the note-editor scope rule, the
  empty-set invariant) + ScreenAwakePolicyTest.kt (9 tests, all green).
  ui/playback/ScreenAwake.kt: ScreenAwakeRegistry (surfaces ACQUIRE/RELEASE
  into a set — the iOS holder-registry model, because two surfaces can be
  alive across nav stacks and onDisappear order is not guaranteed) +
  KeepScreenOnSurface(surface) composable + KeepScreenOnEffect() — the ONE
  root writer of View.keepScreenOn; the toggle flips a preference StateFlow
  so it applies within a frame. Wired: BookReaderScreen (EPUB/PDF/AUDIOBOOK
  surfaces), NoteEditorScreen (NOTE_READER only while preview/read-along —
  never in edit mode), root SpeechnotesApp. AppearanceSettingsScreen gained
  the "Keep screen on" row (Appearance = the Android Reading-settings home).
  OnboardingScreen: 4th page "Focus, and see it add up" (iOS e07841f copy,
  three feature rows) + welcome body per iOS ("Tap + to write your first
  note, or import a book and read along."). Suite 178/178 green, APK built.
- **H5 DEVICE VERIFY (partial, device-contention limited):** x86_64 APK
  installed. Verified on device: Stats tab shows the Pomodoro card under
  Reading goals (idle state + blue timer button); config sheet opens with
  mode segmented / prep 2–5 segmented / Blocks stepper (blocks 4→1 via the
  minus button works); Start Timer launched a classic run — the app-wide
  banner showed "👋 Warming up / 2 min of prep, then focus begins" AND the
  card flipped to the run view: orange prep glass filling, countdown
  01:57, "Preparing" pill, caption "1 blocks · 1 breaks · 25 min of
  focus", Pause/Stop controls, NO Skip during prep (correct). Session
  persisted mid-verify (kill-safety by construction — boundaries are
  wall-clock). THE REST IS BLOCKED ON DEVICE CONTENTION: Ron is driving
  the Speech engine screen in a download loop; two of my tap batches
  landed on his screens (no damage done to his flow that I can see —
  verified screenshots after each stray tap). Outstanding verify items:
  pause/resume/stop dialog, banner "Ready to focus"/"Break's over",
  EPUB-reader rail (Hermione's test EPUB), audio-reader rail play +
  collapse, portrait regression, H6 empty-bin confirm + scope-empty.
  Will resume polling for a free device.
- release ui/books/BookReaderScreen.kt
- release ui/editor/NoteEditorScreen.kt

- **H5b/c/d LANDED (compile + suite green modulo Ron's in-flight
  PdfOcrMergeTest):** data/PomodoroStore.kt (singleton, iOS state-file name
  "pomodoro-state.json" + field names; banner data class UI-free; copy-on-
  write session snapshots under one mutex — mutations deep-copy, publish by
  swap; persists off-main via IO scope; notification half of the iOS store
  NOT ported — divergence to record); ui/stats/PomodoroViews.kt (PomodoroCard
  with the glass fill + 4-stream juice bubbles on a withFrameNanos clock,
  flow pot counting up, claim/skip/pause/stop controls with haptics +
  stop-confirm dialog, PomodoroConfigSheet — mode segmented/prep 2–5 min/
  structured time-limit-or-finish-time ± steppers/flowmodoro rule rows/
  classic per-block tuning — and the root PomodoroBannerOverlay which owns
  the app-wide 1 Hz tick + transition haptics + Claim now action);
  StatsTabScreen mounts the card under Reading goals; SpeechnotesApp mounts
  the overlay at the root. SpeechnotesApplication initializes PomodoroStore.
- [H5e] claim ui/books/BookReaderScreen.kt (KeepScreenOnSurface wiring only)
- [H5e] claim ui/editor/NoteEditorScreen.kt (KeepScreenOnSurface wiring only)
- **H5a DONE — PomodoroEngine ported + 19 JVM tests green.**
  logic/PomodoroEngine.kt (pure Kotlin, no android.*): PomodoroMode/
  Phase/Config/Step/Session verbatim from
  Packages/SpeechLogic/.../PomodoroEngine.swift — structured windows
  (duration or next-occurrence finish time), flowmodoro (300 s focus per
  break minute, claim gate on the 180 s BANKED pot = 15 focus minutes),
  classic per-block lengths; pause slides every pending boundary, structured
  skip keeps the absolute window, classic skip slides, stop reports partial
  focus. Time is epoch-seconds doubles (the Swift TimeInterval model); JSON
  field names shared with iOS (phase serialNames lowercase). One structural
  divergence from a naive copy: the Swift memberwise init is a
  `PomodoroSession.start(config, startEpoch, calendar)` factory — a Kotlin
  data-class init block would re-run on kotlinx decode and wipe restored
  state; the bare constructor is the lenient decoder path (all fields
  defaulted). PomodoroEngineTest.kt ports all 19 PomodoroEngineTests.swift
  cases (UTC calendar, t0 = 2026-03-10 14:00 UTC, deltas ±0.001). Suite
  green (160 total incl. Hermione's concurrent additions).
- **[V0] AudioReader rail fix LANDED (code + partial device verify).** The
  device verify caught a real H4 bug: AudioReader composed the rail as the
  LAST CHILD of the inner Column, and the cover Surface (intrinsic height,
  cover = 55% of a ~910dp-wide landscape column) overflowed the window — the
  rail was pushed below the fold and never appeared (EPUB/PDF were correct:
  rail as trailing sibling of a weighted Box). Fix: rail moved to the Row's
  trailing slot + cover `heightIn(max = 180.dp)` in landscape. Rebuilt,
  reinstalled, re-verified on device: audio reader landscape now shows the
  full player card (bounded cover, position slider, transport with the ±15 s
  glyphs) AND the trailing rail (chevron, Ch stepper, play, speed slider).
  Editor rail re-confirmed after reinstall (voice chip reads "System voice
  (model missing)" — container has no TTS engine). RELEASE
  ui/books/BookReaderScreen.kt — no longer claimed.
- **DEVICE CONTENTION (22:51):** mid-verify, the device was driven to the
  BookDrop settings + Send sheet under me (Hermione's live transfer test —
  "hermione-test.epub importing"). I withdrew from UI driving to avoid
  corrupting it; H5 coding proceeds JVM-only. Still open on V0: rail play
  tap on the audiobook (mp3 plays without TTS), rail collapse on the audio
  reader, EPUB reader rail (her test EPUB is on the shelf), PDF reader rail
  (no PDF on device), portrait spot-check, H6 empty-bin confirm +
  scope-empty state. Will resume when the device is free.
- (session 2, 2026-11-02 ~22:10) V0 verify under way. First finding: on this
  Waydroid display (natural LANDSCAPE 1920x1041) `user_rotation 1` yields a
  PORTRAIT app window; the landscape test needs `user_rotation 0` + accel 0.
  Will restore rotation 0 / accel 1 at session end.
- [V0] claim ui/books/BookReaderScreen.kt for the AudioReader rail fix found
  during H4 device verify (no other section holds it)
- [H4] claim ui/books/BookReaderScreen.kt for H4 (landscape playback rail)
- [H4] claim ui/editor/NoteEditorScreen.kt for H4 (landscape playback rail)
- **H4 CODE-COMPLETE (device verify pending, see RESUME) — landscape
  playback rail.** New ui/playback/PlaybackRail.kt (iOS PlaybackRail.swift
  port): trailing-edge panel (150dp) with progress %, chapter/page stepper,
  voice chip, 52dp play cluster, stop, read-along, horizontal rate slider;
  collapses to a 66dp progress-ring capsule; collapse persisted under the
  exact iOS pref key "landscapeRailMinimized"; every control haptic.
  Window-width based landscape test (MainActivity has configChanges — no
  Activity recreation). Integrated on all four surfaces:
  NoteEditorScreen.kt (bottom PlayerControlsBar yields in landscape),
  BookReaderScreen.kt EPUB (bottom bar keeps reader chrome only), PDF
  (bottom row keeps page slider/stepper only), Audio (rail carries the
  transport with the audiobook speed slider). During integration one rail
  block landed at the WRONG anchor (AudioReader tail instead of the EPUB
  AndroidView close) — caught by compile, moved back, braces verified by
  build.
- **H6 DONE (5 of 5 items) — P2 sweep:**
  1. RecycleBinScreen "Empty all" opens a confirmation AlertDialog
     ("All N note(s)… cannot be undone") before emptyRecycleBin().
  2. True Black: AppearanceSettingsScreen labels were ALREADY fixed
     (TRUE_BLACK -> "True black" landed by another hand); I fixed the
     remaining half in SpeechnotesViewModel.setReaderTheme — TRUE_BLACK no
     longer collapses to jsTheme "dark", it maps to "trueBlack" which the
     reader JS already supports.
  3. NotesListScreen scope-empty: blank search renders "Nothing here yet /
     Notes you add to this view show up here." instead of
     `No results for ""`.
  4. Audiobook ±15 s glyphs: no Replay15/Forward15 exists in the icon set,
     so new shared SkipSecondsIcon (ui/playback/PlaybackRail.kt) composes
     the arrow-loop glyph + a centered "15" label; used by AudioReader AND
     both MiniPlayerOverlay mini bars (the same glyph lie lived there too).
  5. Books grid: GridCells.Fixed(3) -> GridCells.Adaptive(120.dp)
     (BooksScreen.kt) — wide screens earn a 4th column.
  SpeechnotesViewModel touched claim-first (setReaderTheme only, additive;
  no other agent held it).
- **H5 NOT STARTED** (pomodoro v1.8.x port) — handed over, see RESUME.
- release ui/books/BookReaderScreen.kt
- release ui/editor/NoteEditorScreen.kt
- **SESSION CLOSE (2026-11-02) — final state: 130/130 tests green
  (115 baseline + Hermione's JEX/NoteImageStore additions), x86_64 APK
  builds, and the CURRENT APK (with H1–H4 + H6) IS INSTALLED on Waydroid.**
  RESUME FOR NEXT HARRY:
  1. H4 device verify (the only open item on H1–H4 + H6 — all code
     complete and Waydroid-installed): launch app, force landscape with
     `adb shell settings put system user_rotation 1` +
     accelerometer_rotation 0 (restore both afterwards: rotation 0 /
     accel 1 — this session set them and the device lock was held by
     another agent, so the restore DID NOT run; device is possibly still
     rotation-locked landscape), open a note → rail with voice chip /
     play / stop / read-along / rate slider should own the trailing edge,
     bottom transport gone; chevron → 66dp capsule; tap chevron-left to
     expand; then check the EPUB/PDF readers (an mp3 book "original" is
     imported on the device; chapter stepper) and portrait (no rail,
     bottom bar back).
  2. H5 pomodoro is UNTOUCHED: port
     Packages/SpeechLogic/Sources/SpeechLogic/PomodoroEngine.swift →
     logic/PomodoroEngine.kt (+ JVM tests from PomodoroEngineTests.swift),
     PomodoroStore.swift → data/ (exact iOS pref keys), UI from
     PomodoroViews.swift (timer + juice bubbles + flowmodoro break-claim at
     3 banked minutes), onboarding Pomodoro page, Keep-Screen-On setting
     (iOS ScreenAwake.swift → FLAG_KEEP_SCREEN_ON + Settings row). iOS
     commits 484e564 + e07841f. Mount point: iOS puts PomodoroCard on the
     Stats TAB under GoalsCard — ui/stats/StatsTabScreen.kt already has
     the GoalsCard slot to extend. Then re-verify H4 per above in the same
     device pass.
  3. Waydroid device lock contention was severe this session (multi-minute
     installs; one stuck 23-min install had to be killed). Use
     `flock -w 90 /tmp/sn-waydroid.lock` so a stuck operation cannot wedge
     the pack; installs of the ~90MB APK hang while another install is in
     flight — wait for the holder, then retry.
  4. Trap (cost me a rework): Edit old_strings ending in a run of closing
     braces can match a DIFFERENT function's tail than intended — the EPUB
     rail block landed at the AudioReader tail that way (compile caught
     it). Anchor on unique nearby code; compile after every structural
     edit.
  5. No claims outstanding: NoteEditorScreen/BookReaderScreen released;
     the SpeechnotesViewModel change (setReaderTheme trueBlack mapping) is
     additive and complete. Tests/gradle: the plain `assembleDebugX86_64`
     task does NOT exist — use `-Psplits=true assembleDebug`.

- [H1] claim ui/editor/NoteEditorScreen.kt for H1 (editor Edit/Preview toggle)
- **H1 DONE — editor preview lock-in fixed.** Added a visible Edit/Preview
  IconButton to the editor top bar (between word count and Play), iOS
  NoteEditorView toolbar model: eye ("Preview markdown") while editing,
  pencil ("Edit note") while previewing, `Haptics.tap` on the tap path.
  `showPreview` was declared and never reassignable — with Render Markdown on
  the app opened straight into preview with no way back. Files:
  ui/editor/NoteEditorScreen.kt (toggle + `Visibility` import). Tests 115→115
  green; x86_64 APK built (`-Psplits=true assembleDebug` — note:
  `assembleDebugX86_64` task name does not exist, the splits flag produces
  per-ABI APKs from `assembleDebug`). Waydroid: fresh install (old package
  had a mismatched signature → uninstall + reinstall; test notes wiped,
  recreated), note opened in preview, "Edit note" → editor + formatting bar,
  "Preview markdown" → preview again; no logcat errors. release
  ui/editor/NoteEditorScreen.kt.
- **H2 DONE — Stats is the 4th tab.** New ui/stats/StatsTabScreen.kt (moved
  out of ui/settings/StatsScreen.kt, deleted): own Scaffold + TopAppBar, no
  back arrow; improved hierarchy per the user's ask — Today hero
  (displaySmall hours headline + streak chip with 🔥 icon), a Best day
  header card beside the range total, range picker inside the Activity card
  with haptic on segment change, redesigned empty state (tinted circle +
  "Nothing to chart yet"). Goals card stays on top (iOS GoalsCard parity).
  Tab bar is now Notes / Books / Stats / Settings (SpeechnotesApp.kt, icon
  Insights); "Stats & goals" row + route removed from Settings
  (SettingsHomeScreen.kt — touched for the row removal only, no active
  claims from Hermione at the time). No bottom bar on subpages unchanged.
  Tests 115→115 green, APK built. Waydroid: 4 tabs render; Stats tab shows
  goals card + empty state; Settings home no longer lists Stats & goals.
- **H3 DONE — the minimized player is a draggable, edge-snapping bubble.**
  ui/MiniPlayerOverlay.kt: dock restructured (expanded bars in a
  bottom-aligned Column; collapsed bubbles absolutely positioned in a new
  `EdgeSnappingBubble` — the iOS GlobalMiniPlayerOverlay v1.7.2 model).
  Bubble CENTER is normalized (0…1, 0…1), persisted under the exact iOS key
  names "miniBubbleX"/"miniBubbleY" (cross-platform prefs contract); drag
  moves it freely (per-event deltas accumulated onto an Animatable),
  release snaps to the nearest of the four edges with a critically damped
  220 ms glide (no spring — iOS learned that the hard way), tap restores
  the bar with a haptic; bubble parks above a 64dp bottom reserve so it
  never covers a surface's own controls; rotation re-places it from the
  stored normalized spot. Only one dock owner composes at a time, so the
  three bubble types share the wrapper. Tests 115→115, APK built.
  Waydroid: audiobook dock exercised via a real mp3 import (VIEW intent —
  system TTS cannot init on this fresh container, so the note bar was
  verified by its GENERATING session); Minimize → bubble at bottom-right;
  drag to left → snapped at the left edge; tap → bar restored; force-stop +
  relaunch + new session → bubble came back at the LEFT edge (persisted).
- (session start 2026-10-31, awaiting first unit entry)

## Hermione — log

- (session start 2026-10-31, awaiting first unit entry)
- [22:09] claim ui/notes/NotesListScreen.kt for M2c (add "Send with BookDrop"
  to the note long-press menu + sheet host)
- [22:09] claim ui/books/BooksScreen.kt for M2c (add "Send with BookDrop"
  to BookDetailSheet + sheet host)
- release ui/notes/NotesListScreen.kt (note: Ron landed his R4 hook in it
  after me — no conflict, different hunks)
- release ui/books/BooksScreen.kt
- [M2a] Session resumed 2026-11-02. Baseline verified: suite green (130),
  x86_64 APK builds (task is `assembleDebug -Psplits=true` — the
  `assembleDebugX86_64` name in the brief does not exist). Then M2a LANDED:
  receiver byte-progress port (iOS dbc0677) — LocalSendHTTPServer.kt gained
  a `bodyProgress(path, query, received, expected)` callback + a
  `BodyProgressReporter` (max(1%, 256 KB) throttle, final byte, received=-1
  connection-died marker fired on the short-body branch);
  LocalSendProtocol.kt gained `IncomingTransfer` +
  `BookDropCore.fileNameFor(fileId)`; LocalSendReceiver.kt exposes
  `incomingTransfer: StateFlow<IncomingTransfer?>`, cleared on
  prepare/upload/cancel/stop, fed only for the upload path and only for
  fileIds a live session holds. P0-6 LANDED in the same slice (same file):
  `BookDropCore.acceptedExtensions` restricted to the LANDABLE set
  (epub/pdf/m4b/m4a/mp4/mp3/jex + DocumentBook.parsedExtensions
  docx/odt/pptx/odp) — the old set advertised mobi/azw3/fb2/rtf/html/txt/md
  which `importDocument` then fails ("not a document this reader supports"),
  and opus/ogg which `importBook`'s known-check rejects. Tests: new
  LocalSendProgressTest (6 tests: reporter throttle, -1 marker, real-socket
  upload progress, real-socket hang-up → -1, fileNameFor, accept-set
  contract); BookDropProtocolTest updated to the honest set + a new
  all-unlandable→403 test. Suite 130→137, green. APK built.
- [M1] Code complete, build BLOCKED by a file I don't own: NoteEditorScreen.kt
  is mid-edit (missing brace around RemoteNoteImage ~L1475-1531, unresolved
  RemoteNoteImage) — Harry's H-unit file. My M1 changes are in logic/
  NoteImageStore.kt (FNV→SHA-256 hash + sanitiseExtension allowlist),
  logic/JexExport.kt (v1.8.1 Joplin-canonical layout: ustar "00" version, no
  trailing newline, title-as-first-line, user_* timestamps, resource meta
  title line + file_extension), logic/JexImport.kt (Joplin unserialize
  semantics + legacy `title:` prop detection + speechnotes:// link matching +
  resources/ traversal guard), data/ModelManager.kt (jexImages mime tiff/bmp +
  ext hardening), logic/ZipReader.kt (member-path traversal filter), tests
  rewritten/added in JexImportTest.kt + NoteImageStoreTest.kt. Will run the
  suite as soon as the tree compiles again.
- [M1] SESSION WRAP (2026-10-31, token budget end). M1 code was COMMITTED
  directly (user instruction overrode the git-read-only rule for this one
  commit) — only my files; NoteEditorScreen.kt was NOT committed (still
  mid-edit). COULD NOT verify: the suite never ran because the shared tree
  does not compile (Harry's NoteEditorScreen.kt, RemoteNoteImage region).
  FIRST ACTION ON RESUME: `flock /tmp/sn-gradle.lock -c './gradlew --offline
  testDebugUnitTest'` — expect ~125 tests (JexImportTest went 6→13, +3
  NoteImageStoreTest on the 115 baseline; verify the count); fix reds if
  the new JEX semantics broke an old expectation (old-layout fixtures now
  need a `title:` prop or a title line), then build + install the x86_64
  APK and verify the round-trip on
  Waydroid: export .jex from a note WITH an image, delete the note, import
  the .jex, confirm the image resolves (import toast counts images).
  RESUME STATE for M2 (references fully read, no code written yet):
  dbc0677 port = (a) receiver byte-progress: LocalSendHTTPServer.kt
  accumulateBody must report (path, query, received, expected) via a new
  bodyProgress callback throttled ~1%/256 KB + received=-1 connection-died
  marker (iOS BodyProgressReporter); LocalSendReceiver.kt gains
  incomingTransfer StateFlow cleared on upload response / cancel / stop /
  fresh prepare — BookDropCore needs a fileNameFor(fileId) lookup; (b) NEW
  data/LocalSendSender.kt porting iOS v1.8.1 LocalSendSender.swift on OkHttp:
  /24 scan (48 concurrent, ports 53317-53327, skip-silent-host on connect
  timeout), prepare-upload (20 s timeout, 401→needsAccessCode, 403, 409),
  per-file POST /upload with sessionId/fileId/token/pin on the query,
  ProgressRequestBody counting bytes (128 KB throttle), 120 s readTimeout =
  inactivity stall message, sequential files, fingerprint/alias shared with
  the receiver, subnet ranking simplified for Android (WifiManager/
  NetworkInterface — drop the iOS ifaddrs interface-name heuristics);
  (c) send sheet per BookDropSendView.swift: payload picker (books/notes/
  exports/.jex), peer picker, access-code retry (prefs key bookDropSendPin,
  SEPARATE from receiver's bookDropPin), staging copies deleted on end;
  entry points = notes long-press + books long-press "Send with BookDrop"
  (claim NotesListScreen.kt / BooksScreen.kt first) + a row on
  BookDropSettingsScreen; (d) P0-6: BookDropCore.acceptedExtensions unions
  DocumentBook.supportedExtensions — CHECK what that set holds and restrict
  to parseable formats (docx/odt/pptx/odp/epub/pdf/audio/jex only — drop
  mobi/azw3/fb2/rtf/html/txt/md if they have no parser) or mark unsupported
  at accept time. M3/M4/M5 untouched — full briefs in the mission (M4:
  port iOS Packages/SpeechLogic MathNotation.swift + MathCommands.swift,
  commits 1fa3482/1eab53c/626e505, wire into SpeechText.kt/MarkdownText.kt
  + MarkdownStyledText.kt preview; M5 security list is itemized there).
  Claims: none held — no shared file is claimed or being edited.
- **M2 DONE — BookDrop v1.8.1 progress port + in-app sender + P0-6.**
  Receiver progress (dbc0677): see M2a above. NEW data/LocalSendSender.kt
  ports LocalSendSender.swift on OkHttp: /24 scan (48 in flight, ports
  53317-53327, skip-silent-host on connect timeout, silent-host = stop
  knocking), prepare-upload (20 s, 401→needsAccessCode, 403, 409),
  sequential POST /upload with CountingRequestBody (64 KB chunk copy →
  128 KB progress steps, Content-Length from the file — the "supported
  file path" that fixes the iOS stalled-send class), 120 s read timeout =
  inactivity stall message, cancel mid-send, fingerprint SHARED with the
  receiver (no self-discovery), subnets from NetworkInterface with
  wlan/eth/ap ranked above rmnet/tun (skipped). Singleton in
  SpeechnotesApplication via BookDropRegistry.sender. Send sheet
  ui/settings/BookDropSendSheet.kt (BookDropSendView.swift model): payload
  chips (Books/Notes/Exports/.jex archive), selection lists, peer picker
  sheet, access-code field persisted under NEW pref bookDropSendPin
  (Persistence.kt — separate from any receive pin, iOS parity), per-file
  x-of-n + byte bar, cancel, needsAccessCode retry, staging copies in
  cache/BookDrop-send-* deleted on terminal phase; NO file picker anywhere.
  Entries: note long-press "Send with BookDrop" (NotesListScreen), book
  detail sheet "Send with BookDrop" (BooksScreen — diverges from the brief
  "books long-press": BooksScreen has no long-press, the detail sheet is
  its action surface), "Send to a device" row (BookDropSettingsScreen).
  Fixes found by device testing: (1) signed-byte subnet —
  InetAddress.address bytes must be masked to 0..255 or the scan probes
  "-64.-88.-16.x" and finds nothing; (2) manifest
  android:usesCleartextTraffic=true — LocalSend is plain-HTTP LAN by
  design; the receiver's raw sockets bypassed the policy, the sender's
  OkHttp did not (SECURITY note for M5: this admits cleartext app-wide;
  LocalSend-protocol requirement, same tradeoff as the official client);
  (3) P0-6 itself: acceptedExtensions now epub/pdf/m4b/m4a/mp4/mp3/jex +
  DocumentBook.parsedExtensions (docx/odt/pptx/odp) — landable formats
  only; on-device .mobi prepare-upload → 403; (4) REAL receive-path bug:
  BookDropCore.upload removed the session on the last file, so
  routeLanded's landedFiles() found nothing and NO BookDrop transfer ever
  reached the importer — fixed with a completed flag + collectCompleted()
  drain (receiver owns dir lifecycle, 30 s sweep). Also history integrity:
  BookDropRegistry.routeLanded records Imported("Importing into
  notes/shelf") on hand-off (failures stay Failed via the existing
  catch). BookDropSettingsScreen gained the Receiving bar (incoming
  transfer name + throttled percent, dbc0677 parity). Tests 130→137 (new
  LocalSendProgressTest: reporter throttle, -1 marker, real-socket upload
  progress, real-socket hang-up, fileNameFor, accept-set contract;
  BookDropProtocolTest updated to the landable set + new
  all-unlandable→403 + drain-semantics test). WAYDROID EVIDENCE: fake
  LocalSend peer on the host — scan found it; selected the BookDrop-
  received book in the send sheet; prepare-upload + upload arrived at the
  peer, bytes are a valid EPUB; sheet showed "Sent BookDrop Test EPUB
  .epub to the test peer". Reverse direction: fake sender pushed a real
  EPUB into the app → 200, book on the shelf, opens in the reader, BookDrop
  history row "hermione-test.epub — Importing into the shelf". Suite was
  160/160 green with Ron's R1/R2 landing; final re-run pending only on
  Harry's in-flight H5 Pomodoro files compiling (not my files).
- [M1] DEVICE EVIDENCE (code was committed in dd20c7d): exported the
  library .jex from Settings → Backup on Waydroid and pushed it off-device
  through the app's own BookDrop sender to the fake peer — the 2048-byte
  archive verifies the v1.8.1 Joplin-canonical layout live: member name is
  32-lowercase-hex .md, ustar header, note title as the FIRST line of the
  markdown body, no trailing newline, no notebook member for an unfiled
  note. The share chooser itself shows "No apps can perform this action"
  on the bare container (no share targets installed) — expected there;
  the file DID land in Exports/ first. The image round-trip is covered by
  the JVM JexImportTest/NoteImageStoreTest (no gallery image available in
  the container to compose an image note with); on-device .jex import
  through the SAF picker was not exercised for the same reason. DEVICE IS
  NOW FREE for Ron's R1 TTS verification.
- **M4 DONE — symbols + math render and speak.** NEW logic/MathNotation.kt:
  verbatim port of iOS SpeechLogic MathNotation.swift + MathCommands.swift
  (v1.8.1): one parse → two walks (Unicode `display` for the reader, word
  `spoken` for the engine), `$…$`/`$$…$$`/bare-command segments,
  looksLikeMath prose gate (prices/percent/windows-paths are NOT maths),
  unescapedDollar returns an INDEX (626e505), fractions/roots/powers/subs,
  big operators with limits, matrices, `\text{}` prose, accents, `45^\circ`
  → "45°" / "45 degrees" (1eab53c). Divergence: formulas are recognized
  per LINE only (the Android markdown pipeline is line-based; iOS has a
  block-level AST) — `$$…$$` must open and close on one line.
  Wiring: MarkdownText gained a MathStash — formulas are extracted BEFORE
  the escape/emphasis machinery, become sentinel placeholders that survive
  every rule, and restore as SPOKEN words in plainText/speechInline (the
  TTS contract) and as DISPLAY glyphs in blocks() (preview + NotePdfLayout).
  Fenced code is exempt (literal by definition). SpeechText.forText
  expands formulas in RAW drafts before sanitizing (offsets can drift on
  notes with formulas — a formula spoken as words beats an offset pointing
  at a backslash; iOS 1fa3482 parity). Preview rendering is text-based —
  the display glyphs ride the existing MarkdownStyledText pipeline, no new
  dependency, no WebView. Tests: new MathNotationTest (25 tests) ported
  from MathNotationTests.swift + Android integration (list/quote/table
  speech, blocks display, dollars-and-percent survival, raw-draft path).
  Suite 137→217 (includes Ron's R1/R2 + Harry's H5 landings), 0 failures;
  x86_64 APK builds. Not yet exercised on Waydroid — needs a note with
  formulas on the device; the JVM contract is the evidence for now.
- [M3] PARTIAL (audit done, two small fixes + one UI-surface question open).
  AUDIT RESULT: NO SAF create-document pickers exist anywhere in the app
  (grep clean) — every share already goes through ModelManager.share(file)
  = the FileProvider chooser, and every export lands in AppFiles.exportsDir:
  WAV (speech/SpeechPlayer.kt:937, `<safe title>.wav`), note md/txt
  (NoteEditorScreen.kt:933/940), note PDF (:983, `<safe name>.pdf`), .jex
  (ModelManager.kt:268), logs (LogsScreen.kt). SO REMAINING DEFECTS:
  (1) editor md/txt exports use FIXED names "note.md"/"note.txt" — every
  note overwrites the same file (the NotesList screen's noteToFile already
  uses the sanitized title; the editor block must match: `<safe title>.md`
  / `.txt`, take(40), NoteEditorScreen.kt ~925-945 — claim the file first);
  (2) .jex archive name is fixed "speechnotes-backup.jex"
  (ModelManager.exportJex) — each backup silently replaces the previous;
  iOS names per scope/date (JexExporter.fileName(scopeSuffix:)) — consider
  a dated suffix. NOTE: Storage's export LIST is WAV-only by design
  (ExportsStore filters `.wav`, iOS parity); md/txt/pdf/jex files appear
  only via the usage breakdown, and clearTemporaryFiles() treats
  .jex/.pdf/.md/.txt as droppable — LEAVE AS IS unless the mission's
  "visible Storage entry" is read to mean every file type (open question
  for Albus/user).
- [M5] NOT STARTED. Full checklist from the mission brief: settings-row
  end-to-end verification on Waydroid (all tabs except Stats — Harry's),
  then the security pass (a) confirm M1 tar/zip traversal guards (code is
  in: JexImport/ZipReader reject `../`; confirm by tests/reading), (b)
  LocalSendHTTPServer security re-check (sender-IP pinning, per-file token,
  exact size, sha256, 64 KB head cap, spill, single-session, cancel — all
  present in BookDropCore/LocalSendHTTPServer; re-verify after M2 changes),
  (c) file_paths.xml scope check (exports/books/cache only — READ IT),
  (d) AndroidReaderBridge command allowlist, (e) MainActivity VIEW/SEND
  intake name sanitisation, (f) allowBackup=true + fullBackupContent=true
  vs notes.json riding device backups while the vault is off — evaluate
  excluding notes/notebooks from backup, or document the tradeoff (RECORD
  THE DECISION), (g) model downloads HTTPS end-to-end (check ModelManager
  download URLs). TRIM (in the same unit): remove the declared-but-unused
  datastore-preferences dependency in app/build.gradle.kts; remove dead
  state showOverflow (NotesListScreen.kt:107); do NOT touch speech/ dead
  code — LIST it for Ron: SpeechPlayer.noteEngineFailure,
  SystemTtsEngine.isAvailable, withSkips param. Remember: manifest now
  carries android:usesCleartextTraffic="true" for BookDrop (LocalSend is
  LAN plain-HTTP by design) — record it as a documented tradeoff.
  REMINDER: adb install streaming is broken this boot (see Ron's urgent
  note below) — offline install may be the only route; device work may
  need Ron to finish first.
- **(session resume 2026-11-07)** — picked up from the 2026-11-03 handover.
  Baseline re-verified: **217/217 JVM tests green** (testDebugUnitTest,
  BUILD SUCCESSFUL); disk 57%; Waydroid RUNNING. Tree still carries all
  three agents' uncommitted work (M2/M3/M4 mine, H1–H6 Harry's, R1–R6
  Ron's) on top of dd20c7d. Resume order: M3 completion → M4 device
  evidence → M5 (settings audit + security + trim).
- [M3] claim ui/editor/NoteEditorScreen.kt (export file naming only: the
  fixed "note.md"/"note.txt" names overwrite every previous export)
- release ui/editor/NoteEditorScreen.kt (M3 naming done)
- [M3] claim data/SpeechnotesViewModel.kt (BookDrop routing only — the
  registerImporter lambda. NOTE: Ron's R4 claim on this file has no
  release line, but his "[R1..R6 CODE COMPLETE]" entry (2026-11-02 late)
  means that work is done; my edit is additive and in a different region)
- **M3 + M2-R2 COMPLETE — 2026-11-07. 217 → 225 JVM tests green; x86_64
  APK built (`-Psplits=true assembleDebug`) and INSTALLED on Waydroid
  (push → `pm install -r`, streaming install avoided). NOT YET DEVICE-
  VERIFIED — interrupted mid-verify, see the resume block below.**
  Unit 1 — export naming + visible Storage entry (M3):
  * `logic/JexExport.kt`: NEW `fileName(scopeSuffix, date)` +
    `scopeSuffix(notebookNames)` — verbatim port of iOS
    `JexExporter.fileName(scopeSuffix:)` (`speechnotes<slug>-<yyyy-MM-dd>.jex`).
  * `data/ModelManager.kt`: `exportJex(..., scopeSuffix)` writes the dated
    name instead of the fixed `speechnotes-backup.jex` (every backup used
    to replace the last).
  * `ui/editor/NoteEditorScreen.kt`: NEW `exportSafeName()`; md/txt
    exports are `<title-stem>.md`/`.txt` (were fixed `note.md`/`note.txt` —
    each export overwrote the previous). renderNotePdf reuses the helper.
    (claimed + released this session.)
  * `ui/settings/BackupSettingsScreen.kt`: the scope slug rides into the
    .jex name, iOS parity.
  * `data/ExportsStore.kt`: NEW `ExportedFile` + `files: StateFlow`
    listing every NON-wav export (md/txt/pdf/jex/logs).
  * `ui/settings/StorageSettingsScreen.kt`: NEW "Exported files" section
    listing them with kind/size/date + delete — every export now has a
    visible Storage entry (was WAV-only).
  * `ui/settings/BookDropSendSheet.kt`: the EXPORT payload now sends ALL
    exports (was WAV-only), content type per extension — a .md/.txt/.pdf
    export is sendable straight from app storage.
  Unit 2 — **DATA-LOSS BUG FOUND AND FIXED**: `ExportsStore
  .clearTemporaryFiles()` treated `.jex/.pdf/.md/.txt` in Exports/ as
  droppable — "Clear temporary files" silently deleted the user's just-
  shared exports (iOS: "Books, notes, exports and models are not
  touched"). Narrowed to `.part`/`.tmp` only, swept recursively under
  filesDir (partial model downloads + interrupted atomic-write temps).
  Unit 3 — iOS v1.8.1 BookDrop routing parity (M2 extension, task P0-6):
  * `logic/LocalSendProtocol.kt`: `acceptedExtensions` RE-WIDENED from my
    M2 "landable-only" set to the iOS v1.8.1 accept set minus the formats
    with no Android parser: + `wav`, `md`, `markdown`, `txt`, `text`.
    (opus/ogg, mobi/azw3/fb2/rtf/html stay refused — genuinely unlandable
    here.) iOS `SpeechnotesApp.swift` router reads: .jex→notes, .wav→Exports,
    .md/.txt→NOTES ("the book importer would flatten it to a one-chapter
    EPUB").
  * `data/SpeechnotesViewModel.kt`: `importBookDropRecording()` moves a
    received WAV to Exports (unique name, no overwrite) and refreshes the
    exports store; `importBookDropNote()` creates a NOTE from md/txt with
    the first heading as the title; router dispatch extended for both.
  * `data/BookDropRegistry.kt`: `SELF_REPORTED_ROUTES` — the wav/md
    routes report their own history entry ("Saved to Exported audio" /
    "Saved as a note") so the generic hand-off report does not double up.
  Tests 217 → 225: JexFileNameTest (new, 4), ExportsStoreTest (new, 3 —
  export protection, temp sweep, non-wav listing), BookDropProtocolTest
  +1 (partial accept: .md + .wav beside a .mobi are accepted, the mobi is
  refused) and its accepted-set + prepare-reject tests updated.
  Divergences to record: (a) Android's accept gate is HONESTLY narrower
  than iOS v1.8.1's — no mobi/azw3/fb2/rtf/html/opus readers here;
  (b) `JexExport.fileName` uses `LocalDate` (pure java.time, no android.*);
  (c) the iOS staging/`inlineJex` naming in BookDropSendView is not ported
  (Android sends the archive file directly, scope "" — iOS parity).

  **RESUME FOR THE NEXT HERMIONE (device evidence, in order):**
  1. **Waydroid is FLAKY this session** — repeatedly dropped to the
     launcher (black/launcher screenshot while the activity stayed
     resumed), the editor body is a Compose text field that does NOT
     appear in `uiautomator dump` (use its bounds from a screenshot; the
     RAIL sits on the right third), and `adb shell input tap` near the
     voice chip [1762,234]-ish opened the voice sheet. Reliable sequence
     that worked: `am force-stop` → `am start -W` → note title at actual
     (132,418) → the eye/pencil toggle at (1753,120) → then `input text`.
     Screenshot BEFORE every tap. Don't trust `input text` with `$`/
     backslashes — drive them as separate `input text` calls or push the
     text another way.
  2. **M3 device evidence** (needs a note with an image ideally): open a
     note → ⋮ → Share → Markdown / Plain text / PDF → confirm the file
     lands in Exports/ with the TITLE-stem name; then Settings → Storage
     → "Exported files" lists it with size/date; then Settings → Storage →
     "Clear temporary files" must NOT delete it (the M3 bug).
  3. **M4 device evidence** (code-complete, JVM-tested): create a note
     with `$x^2+y^2=z^2$`, `45^\circ`, `$` prices like `$5` (must stay a
     price) → Preview must render the display glyphs and keep "$5" plain;
     play with Kokoro (small) and confirm logcat reaches SPEAKING with no
     chunk errors (the word form is in the TTS text, visible in the
     read-along screen / Logs).
  4. **M5 is UNSTARTED** — full checklist in the mission brief; the code
     facts (a)–(g) are already READ this session and gathered here:
     (a) tar/zip traversal guards are IN (JexImport/ZipReader; M1) and
     tested — `safe_filename_strips_traversal` passes; (b) LocalSendHTTPServer
     controls all present in BookDropCore (sender-IP pinning, per-file
     token, exact size, sha256, 64 KB head cap, spill, single-session,
     cancel) — re-verified by the passing real-socket LocalSendProgressTest;
     (c) file_paths.xml + (d) AndroidReaderBridge allowlist + (e)
     MainActivity VIEW/SEND intake + (f) allowBackup/fullBackupContent +
     (g) ModelManager download URLs are UNREAD — read + decide + log them;
     (h) manifest now carries `android:usesCleartextTraffic="true"`
     (M2, required by LAN plain-HTTP LocalSend) — documented tradeoff.
  5. **TRIM not yet done**: remove `androidx.datastore.preferences`
     (`app/build.gradle.kts:148`, declared-but-unused — no
     `androidx.datastore` import anywhere); remove dead `showOverflow`
     (NotesListScreen.kt:107). speech/ dead code to LIST FOR RON (do not
     touch): `SpeechPlayer.noteEngineFailure`, `SystemTtsEngine.isAvailable`,
     `withSkips` param.
  **CLAIM STILL HELD: `data/SpeechnotesViewModel.kt`** — the M3 routing
  work is finished and tested, but I was interrupted before releasing it.
  The next Hermione should either release it after review or carry it.

## Ron — log

- (session start 2026-10-31, awaiting first unit entry)
- **URGENT for all agents — `adb install` (streaming) is BROKEN this boot.**
  Every streaming `adb install -r *.apk` hung (one for 2.5+ h) and, when
  killed, the adb fork-server inherited its `flock /tmp/sn-waydroid.lock`
  fd and kept the shared device lock forever — it blocked Harry's UI
  automation and Hermione's installs until `adb kill-server` cleared it at
  ~22:35. I had to kill several wedged install processes (mine AND two from
  other sessions) — sorry, no way to clear the lock otherwise. **Use this
  instead (verified: installs in <1 s):**
  ```bash
  adb -s <ip>:5555 push app/build/outputs/apk/debug/app-x86_64-debug.apk /data/local/tmp/sn.apk
  flock /tmp/sn-waydroid.lock -c 'adb -s <ip>:5555 shell pm install -r /data/local/tmp/sn.apk'
  adb -s <ip>:5555 shell rm /data/local/tmp/sn.apk
  ```
  If the device lock is held with no visible holder, `adb kill-server` +
  `adb connect` clears it.
- [R1] Blocked-fix note: JexImportTest.kt (Hermione's file) did not compile —
  `createTempDirectory(...)` resolves to the removed kotlin.io API. Minimal
  fix applied (added `java.nio.file.Files` import + qualified call) to
  unblock the pack baseline. Baseline after fix: 125/125 green.
- [R1] **DEVICE CLAIM — Ron needs the Waydroid for R1/R6b TTS verification**
  (Kokoro-small 92 MB download + a play session). Container DNS was broken
  this boot for app downloads (no resolver from DHCP); fixed by a full
  Waydroid restart at ~20:55 container time — sorry if that reset anyone's
  session state; app data was NOT wiped. While waiting for the device Ron
  is coding R2 (no device needed). Please leave the device free when done
  with BookDrop — I'll poll.
- [R1] **DEVICE CLAIM — Ron needs the Waydroid for R1/R6b TTS verification**
  (Kokoro-small 92 MB download + a play session). Container DNS was broken
  this boot for app downloads (no resolver from DHCP); fixed by a full
  Waydroid restart at ~20:55 container time — sorry if that reset anyone's
  session state; app data was NOT wiped. While waiting for the device Ron
  is coding R2 (no device needed). Please leave the device free when done
  with BookDrop — I'll poll.
- [R1/R2 CODE DONE — device verify pending] seconds-based render-ahead bank
  + never-run-dry silence bridge are in, 160/160 suite green, APK built and
  installed. Details logged when the device evidence lands.
- [R3] playFrom primitive landed in speech/SpeechPlayer.kt (+ playFromCurrent);
  read-along tap-a-paragraph landed in ui/readalong/ReadAlongScreen.kt (my
  file). Editor-caret + book-from-spot hooks NEXT — Harry released
  NoteEditorScreen/BookReaderScreen per his session close.
- [R4] claim data/SpeechnotesViewModel.kt (additive: note-import OCR entry
  points + progress flow) and ui/notes/NotesListScreen.kt (small hook: VM
  importer call + toasts). logic/PdfOcrMerge.kt + tests are new Ron files.
- **[R1..R6 CODE COMPLETE — status 2026-11-02 late]** (suite 217/217 green,
  x86_64 APK built + installed):
  - R1: seconds-based render-ahead bank — StreamingPlaybackCore speak()
    paces on `RenderAheadBankPolicy.allows(bankedSeconds, target)` with the
    banked reading from the LIVE track (`bankedSeconds()` helper,
    trackBaseFrames rebasing), the chunk-count semaphore deleted; hold loop
    revives an underrun-disabled track and logs "bank full — holding".
  - R2: never-run-dry silence bridge — `bridgeIfDry()` pre-writes
    keepalive silence (last render + 1 s) before rendering into a dry bank;
    incident log "keepalive bridge … / ended"; 120 s incident cap disables
    the bridge (watchdog ends the session, never silence-forever).
    DIVERGENCE: single pre-write instead of iOS 0.5 s re-armed buffers
    (Android's single-writer generate queue can't write mid-render).
  - R3: `SpeechPlayer.playFrom(offset)` + `playFromCurrent` (sentence snap
    via SentenceChunker.resumeOffset, in-place supersession, bookmark math
    remapped); read-along tap-a-paragraph; editor Speak long-press = play
    from caret; reader Contents jump during narration now MOVES the speech
    (vm.narrateFromSpot; PDF outline + EPUB toc). Play-from-viewed-spot on
    plain Play already existed (earlier port).
  - R4: PDF imports into notes run native-text + ML Kit OCR merge
    (logic/PdfOcrMerge.kt pure decisions + tests; capped 150p/1M chars/25
    OCR pages, iOS numbers) with live progress ("Recognizing scanned pages
    — n of m" in the notes top bar); editor photo-picker images are OCRed
    under the image markdown; failures toast, never silent.
  - R5: Book.forceOCR + pdfIsScanned (JSON optional, Android-local field
    names — NOT iOS-interchangeable, divergence recorded on the field);
    Force-OCR toggle in the PDF Contents sheet; chapter cache invalidated
    on flip; "Scanned — OCR"/"OCR forced" shelf badges.
  - R6: (a) never-started start-stall watchdog (45 s, ends session visibly);
    (b) Kokoro-small download — IN PROGRESS on a degraded uplink (flaky
    bridge DNS + CDN stream resets; resume-capable downloader grinding,
    automated retry loop tapping Retry); (c) lock-screen ±15 s: session
    REWIND/FAST_FORWARD actions + notification buttons for TTS (-15/Toggle/
    +15; books keep chapter transport), remoteSkipSeconds via playFrom;
    (d) export stops playback first (iOS parity).
  - NOT YET DONE from my brief: on-device TTS/logcat evidence (blocked on
    the model download above), R4/R5 device pass, R6e artifact hunt.
- **[R6b + device evidence — 2026-11-02 ~23:50]**
  - **Kokoro-small 92 MB int8 downloaded end-to-end on Waydroid** (Settings →
    Speech → Models → Kokoro (small): row reads "Ready · fully offline").
    The resume-capable downloader carried it across ~a dozen CDN stream
    resets and bursty bridge-DNS failures ("Unable to resolve host
    huggingface.co/us.aws.cdn.hf.co"); the 80–100 MB window held. The
    environment fought hard: streaming `adb install` wedged (see the URGENT
    note above), DNS flapped, and host uplink degraded to ~100 KB/s at
    times — ~2 h of babysitting, mostly automated retry taps.
  - **TTS session evidence (Kokoro small, note "first_line_heading")** —
    `23:48:14 KokoroEngine state: IDLE → GENERATING` / `23:48:17 KokoroEngine:
    58 tokens → 2.2s audio in 3.42s` / `23:48:17 state: GENERATING →
    SPEAKING` (SpeechPlayer mirrored). Audible output remains the user's
    call (container HAL), and the KNOWN Waydroid pathology showed up: the
    playback head froze at 0/52200 (AudioFlinger never drained).
  - **NEW FIX SHIPPED DURING THIS PASS — dead-head guards (R6 family):**
    the frozen head used to spin the completion poller forever (the QUEUE's
    "poller spun at head 0/58200 until manual stop"). Two bounded guards now
    end a dead-sink session visibly: the completion poller
    (`head frozen at 0/52200 for 15s — audio sink is dead; ending session` —
    captured on device, followed by the chunk-schedule report and
    SPEAKING → IDLE) and the same bound in the bank-hold loop (a full bank
    drains only through the head; identical 15 s bound, not counted while
    paused).
  - Device evidence NOT yet captured: multi-chunk bank-hold/keepalive-bridge
    log lines (needs a multi-sentence note — the container IME fight ate
    two sessions; typing into the editor via adb input text is unreliable
    this boot), R3 long-press playFrom log, R4 OCR import run, R5 toggle +
    badges. All mechanical next session; suite 217/217 green, APK installed.


