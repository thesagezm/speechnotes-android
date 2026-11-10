# QUEUE.md

Working memory for the Speechnotes-Android build pipeline. Owned by the agent.
Updated at the end of every unit (and in the same commit as the work it
describes). NOTE: this repo is PUBLIC — never commit host-specific paths,
IPs, usernames or machine details. Paths are written relative (`~/…`); the
Waydroid adb IP is read from `waydroid status` each session.

## Current Phase

**The Great Rewrite — through v0.1.0 prerelease.** TTS fixed (Unit 1), perf
pass (Unit 2, release cold start ~370 ms), settings subpages (Unit 3),
Kokoro-small quantized (Unit 4), GitHub push (Unit 5), settings-parity audit
(Unit 6), Speech settings parity (Unit 7), Stats parity (Unit 8), Storage
images (Unit 9), and the Encryption vault (Unit 10) — code-complete, 90/90
tests green, Unit 10 verified end-to-end on Waydroid. **2026-10-24: the repo
went PUBLIC and prerelease v0.1.0 was published** (user instruction). The
remaining Waydroid verification items are listed under Pending Queue.

**2026-11-09 — HARRY: the GUI follow-up wave is WOUND UP (H8 + H9 + H10,
all code-complete, 248/248 JVM green, x86_64 APK built + installed).** The
book readers now carry the shared collapsed-transport pill
(`bookBarMinimized`, iOS parity) across EPUB/PDF/audiobook; the last three
`!!` rule violations are gone (plus the others in Harry's own files); and
the markdown formatting bar — which was corrupting text, CRASHING the
list/quote buttons on a selection that reached the end of a note, and had
completely dead H1/H2 buttons — is rewritten around a new pure
`logic/MarkdownFormatActions.kt` with 17 regression tests, and now WRAPS
instead of scrolling sideways so every action is reachable on a 360dp phone.
See Completed Units ("2026-11-09 — Harry H8–H10") for the evidence, and
Pending Queue for the four items that remain unexercised on the device
(mostly because no other session left the device free long enough).

**2026-11-09 — HARRY2: the remaining `!!` sweep unit is DONE (Pending Queue 5
closed)** — every `!!` in shipping paths is gone (BooksScreen ×2,
SpeechSettingsScreen, BookDropSendSheet, AudiobookChapters,
LocalSendHTTPServer ×3, KokoroEngine, SupertonicEngine ×7, ViewModel ×1), so
`grep '!!'` over `app/src/main/java` returns only comments. Suite
**248/248 green**, x86_64 APK (89 MB) rebuilt + installed on Waydroid. H4's
landscape matrix re-verified on the note editor and the PDF reader (rail,
bottom-transport-yields, collapse-to-capsule); the EPUB/audiobook rails and
the portrait regression carry the H4 evidence (rotation contention cut the
pass). The two H6 device items (recycle-bin empty-all confirm, scope-empty
state) remain **open — back with Harry1** (the user stopped this session
before they could be run). Detail in Completed Units ("2026-11-09 — Harry2").

**2026-11-09 — HARRY1 close-out: the PDF reader's pill is DONE (Pending
Queue 4(a)/4b closed).** A PDF was pushed to the device with
`research/localsend-probe.py` and the reader rendered the shared collapsed
transport pill (Play / Previous / Paused / Next / expand chevron) — H8's
PDF branch is device-verified, and it opened already collapsed because
`bookBarMinimized` was still true from the EPUB/audiobook tests. The only
unexercised GUI items left on the project are the **H6 recycle-bin
empty-all confirm dialog** and the **scope-empty "Nothing here yet"
state**. No code landed, no unit, no commit. Device/adb notes in Session
Handover ("HARRY1 CLOSE-OUT").

## User Feedback Pending

0. **2026-11-10 — the ORT/Android acceleration advisory (user-pasted).** An
   external plan written for the iOS→Android port (Core ML/ANE → ORT EPs,
   Waydroid-not-a-benchmark, gap diagnosis, pipeline corrections). Analysis
   against the tree: **most of its Phase 1 is already implemented and
   verified here** — the R-wave root-caused the dead-sink as category (d)
   integration failure (R8: AudioTrack native buffer > 32 KB never drains;
   sine-wave repro with no ONNX; fixed by `MAX_TRACK_BUFFER_BYTES = 32 KB`),
   the seconds-based bank (R1), never-run-dry bridge (R2), watchdogs (R6),
   adaptive opener (0a) and the single-worker/continuous-track/prebuffer
   pipeline checklist are all in `StreamingPlaybackCore.kt`. Waydroid ABI
   translation is moot (x86_64 splits only, never arm64 there). **What
   remains actionable, queued as 0d–0f below:** (0d) per-chunk RTF timeline
   instrumentation — index, chars, audio-s, render wall-s, RTF, banked
   depth before/after (the one diagnostic the advisory wants that we lack;
   also completes Pending Queue 3's device evidence); (0e) XNNPACK EP +
   Supertonic `NO_OPT`→`ALL_OPT` behind a debug flag (no new dependency;
   PCM-parity check vs CPU EP via the debug probe); (0f) physical-phone
   RTF/thermal baseline — Waydroid's measured RTF ≈ 1.9 (Kokoro-small: 2.2 s
   audio in 4.23 s) is > 1, so container gaps are physics, not scheduling;
   only a phone run can answer the speed question. NNAPI recorded as
   experimental-only (deprecated Android 15), no code.
1. **2026-11-08 — BOOKDROP (user's own words, "you are hermione the debugg
   agent"): "the perfect bookdrop is the ios bookdrop".** Three bugs, in
   the user's order:
   1. **Android BookDrop accepts small files but not large files
      (audiobooks).** The other apps send and receive fine.
   2. **The access code is taken into account only when SENDING, never
      when RECEIVING.**
   3. **Tapping the mini-player (not the bubble) in a free space must
      take the user to the book they were reading** — as it does in
      Speechnotes iOS. On Android it does nothing.
   **Status: all three CODE-COMPLETE (uncommitted) + JVM-green
   (231/231) + DEVICE-VERIFIED on Waydroid 2026-11-09 (the second
   Hermione session)** — evidence in "Completed Units". The
   iOS reference for all three is the **v1.8.1 branch
   `batch-c-d-session`** (commit `626e505`, build 47) — NOT
   `sage-upgrades` (which is older and has no sender).
1. **2026-10-31 — the pack dispatch:** the user chartered
   a three-agent pack led by Albus — **Harry** (GUI), **Hermione**
   (functionality/security/trim), **Ron** (TTS/OCR) — coordinating through
   `ANDROID-REVIEW.md`. Feedback being addressed:
   - **Harry:** minimize playback bar (more viewing space in notes/books),
     landscape rail + its minimization, **Stats as its own tab** (not in
     Settings), pomodoro brought to the latest (iOS v1.8.x) standard, stats
     UI improvement, biggest UI bugs.
   - **Hermione:** BookDrop updates/bugs fixed in speechnotes-ios v1.8.1;
     saving within + sending from the app **without the file picker**; JEX
     import/export per the latest working code (fix what prevented it); all
     settings work; symbols + math formulas render in markdown preview AND
     are read correctly by TTS; security loopholes.
   - **Ron:** TTS fast, no artifacts, no skipped/failed words, select
     word/paragraph to start reading from; OCR when importing files into
     notes (not just CamScanner PDFs — see the iOS implementation), TTS
     reads OCR content, native PDF text combined with OCRed text, Google
     bundled ML Kit (pre-approved by the user; already declared 16.0.1).
   Unit mapping is under In Flight. Coordination rules live in
   `ANDROID-REVIEW.md` (build/device locks, claim protocol, read-only git).
   **STATUS 2026-11-07:** all three members' code is IN and verified by
   Albus (225/225 green, APK installed + launched clean, 4 tabs on device).
   Still open: Ron's lazy-engine-init P0 + the missing device evidence,
   Hermione's M5 (settings audit + security pass + trim), Harry's verify
   remainder and three `!!` to clear. See Pending Queue.
   **STATUS 2026-11-09 (Harry):** the GUI follow-up wave is wound up —
   H8 (book-reader collapse pills), H9 (`!!` cleared in Harry's files) and
   H10 (formatting bar rewritten + wrapped) are code-complete, 248/248
   green, built and installed; the leftovers are the four device items in
   Pending Queue 4 (PDF pill, empty-all confirm, scope-empty state) and
   R7's own device evidence, which belongs to Ron.
1. **"start analysing ways to make the tts much much faster… iOS set maximum
   tokens from the previous generation seconds — completely increased the
   speed of generation and prevented stopping of tts"** — ADDRESSED: the
   analysis (2026-10-24) located the iOS seconds-based ChunkSchedule
   (`afdc1d1` + `e680114` + silence bridge `241888e`) and **Unit 0a shipped
   the schedule port** (see Completed Units). Remaining from the plan: 0b
   (live seconds-based bank, fixes P0-7) and 0c (never-run-dry silence
   bridge — the "prevented stopping" half).
2. **"push and handover right now… the repo should be public… as well as the
   prerelease v0.1.0… just remove things that can lead to identification of
   this or my pc"** — DONE 2026-10-24: all four markdown docs (AGENTS.md
   paths/IP scrub only, no rule changes) sanitized in tree AND in git
   history (filter-branch + force-push), repo flipped public, v0.1.0
   prerelease tagged with release APKs attached.
2. **"make sure all the settings (including stats) are on par to speechnotes
   ios or better … then ask me … prerelease"** — the ask was answered by the
   user with the go-ahead above. Settings parity units 6–10 shipped.
3. **"The android app is the slowest"** — ADDRESSED (Unit 2): R8 release
   cold start ~370 ms on Waydroid. User's hands-on verdict pending.
4. **"The tts is also not working"** — RESOLVED (Unit 1); user's own play
   completed naturally on 2026-10-24.
5. **"The buttons dont feel right"** — root cause fixed in Unit 1; instant
   radio/toggle flips verified 2026-10-24.
6. **"switch the small kokoro model just as i did in speechnotes-ios"** —
   DONE (Unit 4, int8 ~92 MB). The 92 MB download itself still needs one
   online run (container was offline): Settings → Speech → Kokoro (small) →
   Download.

## GitHub status

**PUBLIC.** `github.com/thesagezm/speechnotes-android` (main).
History was rewritten twice (2026-10-24, and again at the v0.1.5 release) to
purge host paths, IPs, machine details and personal identifiers —
discipline going forward: every unit lands with QUEUE.md updated in the same
commit, `git push` after verify, and NO machine identifiers in any file.
**Release: v0.1.0 (prerelease)** — tag + notes + x86_64/arm64 release APKs.

## In Flight / Last Push

- **2026-11-10 — Unit 0d: per-chunk RTF timeline (the acceleration
  advisory's diagnostic table) — SHIPPED, observability-only.**
  New `speech/ChunkTimeline.kt` (pure, JVM-tested, Locale.US-pinned) +
  three touches in `StreamingPlaybackCore.kt`: the speak-loop logs one
  line per chunk (`chunk i/N: chars=… audio=…s render=…s rtf=… banked=…→…s
  bridge=on/off track=new/reused`), `exportWav` logs the speed fragment
  per chunk (the clean inference-only RTF), and `bridgeIfDry` now returns
  whether it wrote keepalive silence (the timeline's `bridge=on`) — every
  bare `return` became `return false`, control flow otherwise identical,
  single call site. **Evidence:** constrained gradle (flock
  /tmp/sn-gradle.lock, --no-daemon -Xmx1200m, in-process Kotlin) → BUILD
  SUCCESSFUL 3m53s; test-results XML = **253 tests / 0 failures / 0
  errors** (248 prior + 5 ChunkTimelineTest, the headline pinning the
  real release datapoint 2.2 s audio / 4.23 s render → rtf=1.92); x86_64
  APK 89 MB built. **Device smoke NOT run:** Waydroid session RUNNING but
  `waydroid status` reports `IP address: UNKNOWN` and 192.168.240.2 is
  no-route (the HARRY1 close-out pathology; a container restart clears
  it, app data survives) — and the user closed the device pass for a
  bigger update. Nothing was mutated on the device this session. Next
  session: one neural play + grep `rtf=` in logcat completes 0d; 0e
  (XNNPACK + Supertonic NO_OPT→ALL_OPT behind a debug flag) is next in
  the queue.
- **2026-11-09 — HARRY2: the `!!` sweep unit (Pending Queue 5) — DONE,
  UNCOMMITTED (Albus commits), plus a partial H4 landscape re-verify.**
  Files touched (non-shared, claim-first; the ViewModel hunk is one line):
  `ui/books/BooksScreen.kt`, `ui/settings/SpeechSettingsScreen.kt`,
  `ui/settings/BookDropSendSheet.kt` (Hermione's file),
  `logic/AudiobookChapters.kt`, `data/LocalSendHTTPServer.kt` (Hermione's),
  `speech/KokoroEngine.kt` + `speech/SupertonicEngine.kt` (Ron's),
  `data/SpeechnotesViewModel.kt`. Every `!!` in shipping paths is now a local
  snapshot or a `?: error("…")` guard; `grep '!!'` over `app/src/main/java`
  returns only comments. **Evidence:** `testDebugUnitTest` +
  `-Psplits=true assembleDebug` under the memory-constrained gradle
  invocation → BUILD SUCCESSFUL (1m41s), **248 tests / 0 failures / 0
  errors**, x86_64 APK 89 MB (md5 `4f6d914f7402eb0c76b974febac9dfa2`), pushed
  + `pm install -r` → Success. **Device:** the note-editor landscape rail
  (chevron + voice chip "M1 · male" + Play/Stop/Read-along/1.00×, bottom
  transport gone) collapsed to the 66dp capsule and expanded back; the PDF
  reader rail (page stepper "Page 1/1" + voice chip + Play + rate over the
  reader chrome and the "Expand playback controls" pill) is present. Cut
  short by the user — rotation contention + a Google sign-in dialog ate the
  rest of the matrix; EPUB/audiobook rails and the portrait regression were
  not re-run this session.

- **2026-11-09 — HARRY: H8 + H9 + H10 — WOUND UP, UNCOMMITTED (Albus
  commits).** Session claims held throughout (both shared, claim-first):
  `ui/editor/NoteEditorScreen.kt` and `ui/books/BookReaderScreen.kt`; both
  released at close. Files (Harry's, do not clobber):
  `ui/playback/CompactPlayback.kt` (+ the `rememberBookBarMinimized` /
  `setBookBarMinimized` pref helpers on the iOS-shared `speechnotes`
  SharedPreferences — the same key iOS's `@AppStorage("bookBarMinimized")`
  uses), `ui/books/BookReaderScreen.kt` (all three readers), **new**
  `logic/MarkdownFormatActions.kt`, `ui/editor/NoteEditorScreen.kt`
  (bar rewired to it + the `onEdit` collapsed-selection fix),
  `logic/PomodoroEngine.kt`, `ui/stats/PomodoroViews.kt`, **new**
  `test/.../logic/MarkdownFormatActionsTest.kt` (17 tests).
  **Evidence:** `testDebugUnitTest` → **248/248 green** (231 prior + 17 new);
  `-Psplits=true assembleDebug` → x86_64 APK (89 MB) → `adb push` +
  `pm install -r` → `Success`; cold start **+1.68 s** with 4 tabs and no
  crash lines; the three pill surfaces, the H1 button and the pomodoro
  Stop-confirm dialog all fired on Waydroid (detail in Completed Units).
  **⚠️ Two environment findings for everyone (read before touching the
  device):**
  1. **A second agent session drove the device concurrently all session** —
     it started the debug-only `AudioTrackProbeActivity`, reinstalled its
     own build onto the device mid-pass (logcat `ziparchive: Unable to open
     … base.dm`), which killed my app and produced an Android "Speechnotes
     keeps stopping" dialog, and it edited app state under me (rate
     1.80×→1.00×, text appended to the test note, navigation to Settings →
     Speech). Screenshots must be re-taken immediately before every tap.
  2. **`/tmp/sn-waydroid.lock` is structurally BROKEN.** Whoever starts the
     Waydroid UI runs `flock /tmp/sn-waydroid.lock -c 'waydroid
     show-full-ui'`, and the UI process **inherits that lock fd and holds
     it for the whole session** — so no one can ever take the lock and
     every device command queues forever behind it (three orphaned waiters
     were found). Start the UI OUTSIDE the flock so rule 6 of
     ANDROID-REVIEW.md actually serialises anything.
- **2026-11-09 — HERMIONE: BookDrop unit VERIFIED. (UNCOMMITTED — Albus
  commits.)** All three user-reported bugs exercised on Waydroid with
  the code-complete tree. Device evidence is folded into the existing
  entry in **Completed Units → "2026-11-08 — the BookDrop unit (3 user
  bugs, now device-verified)"**. Environmental notes for the next
  session:
  - **Another agent's `pm install` overwrote my APK mid-round** (its
    `lastUpdateTime` was 9 minutes later than mine). Always md5 the
    installed `base.apk` against your own output before drawing device
    conclusions — that silent overwrite cost one false "the fix is not
    in the APK" detour.
  - **Host RAM is the standing hazard** (the machine is small and the
    swap is often exhausted). Stop the Gradle + Kotlin daemons (`./gradlew --stop`,
    `pkill -f KotlinCompileDaemon`) before a build, and prefer
    `--no-daemon -Dorg.gradle.jvmargs=-Xmx1200m
    -Dkotlin.compiler.execution.strategy=in-process` while the Rust
    sibling project is compiling.
  - **BookDrop transfer history is in-memory only** — it survives the
    session but not an app relaunch, so "Recent transfers" is not
    evidence that a landing failed. Check the Books shelf/notes list.
- **2026-11-08 — HERMIONE: the BookDrop unit (3 user-reported bugs) —
  CODE as landed, see Completed Units for the code facts.** Files
  (mine, do not clobber): `logic/LocalSendProtocol.kt`,
  `data/LocalSendHTTPServer.kt` (the `unauthorized()` helper lives HERE —
  the file is in `data/` but declares
  `package com.speechnotes.app.logic`, which is why it looked "missing"
  for Ron), `data/LocalSendReceiver.kt`, `data/BookDropRegistry.kt`,
  `data/Persistence.kt`, `data/SpeechnotesViewModel.kt` (the BookDrop
  importer block only), `ui/settings/BookDropSettingsScreen.kt`,
  `ui/settings/BookDropSendSheet.kt`, `ui/MiniPlayerOverlay.kt`,
  `ui/SpeechnotesApp.kt` (the mini-player jump only),
  `test/.../logic/BookDropProtocolTest.kt`.
  NOT MINE (concurrent agents): `ui/editor/NoteEditorScreen.kt`,
  `ui/books/BookReaderScreen.kt`, `speech/*` (R7/R8), `app/src/debug/`,
  `ui/playback/CompactPlayback.kt` (Harry H7), `ANDROID-REVIEW.md`
  (Harry/Ron write there too).

- **2026-11-07 — PACK WAVE: CODE COMPLETE, INTEGRATION IN PROGRESS.**
  All three members closed their sessions and handed over (their logs are in
  `ANDROID-REVIEW.md`). **Albus verification:** 225/225 JVM tests green
  (forced `--rerun-tasks`), x86_64 APK built (89 MB), installed on Waydroid
  via the push+`pm install` route, app launches clean (no crash/OOM lines),
  4-tab nav confirmed on device (Notes/Books/Stats/Settings).
  - **Harry (code-complete):** H1 editor Edit/Preview toggle (P0-1 fixed) ·
    H2 Stats as the 4th tab + redesigned stats UI (moved out of Settings) ·
    H3 minimized player = draggable, edge-snapping bubble persisting the
    iOS `miniBubbleX`/`miniBubbleY` keys · H4 landscape PlaybackRail
    (iOS PlaybackRail.swift, 150dp trailing panel, collapse to a 66dp
    capsule, pref `landscapeRailMinimized`) on all four reading surfaces,
    incl. the audio-reader anchoring bug he caught in device verify · H5
    pomodoro at the v1.8.x standard (logic/PomodoroEngine.kt + 19 ported
    tests, data/PomodoroStore.kt, ui/stats/PomodoroViews.kt with juice
    bubbles + flowmodoro claim gate, onboarding page, keep-screen-on via
    logic/ScreenAwakePolicy.kt) · H6 P2 sweep (empty-all confirm, True
    Black label + `trueBlack` mapping, scope-empty state, shared
    SkipSecondsIcon "15" glyphs, adaptive books grid).
  - **Hermione (code-complete except M5):** M1 JEX round-trip (P0-3:
    FNV→SHA-256 hash, v1.8.1 Joplin-canonical tar layout, tar/zip traversal
    guards — committed dd20c7d) · M2 BookDrop v1.8.1 port (receiver byte
    progress + connection-died marker, NEW data/LocalSendSender.kt on
    OkHttp, BookDropSendSheet, note/book "Send with BookDrop", P0-6
    landable-only allowlist) + two real device-found bugs fixed (signed
    subnet bytes; upload dropped the session before the importer could
    land it) · M3 save/send without a file picker (no SAF pickers exist;
    editor exports now `<safe title>.md/.txt`, .jex named via
    JexExport.fileName(scopeSuffix), Storage lists ALL exports and
    clear-temp no longer deletes user files) · M4 symbols + math
    (logic/MathNotation.kt, 25 ported tests, display+spoken walks wired
    into MarkdownText/SpeechText, preview renders display glyphs) ·
    **M5 NOT STARTED** (settings audit + security pass + trim).
  - **Ron (code-complete, device evidence partial):** R1 seconds-based
    render-ahead bank (P0-7, semaphore deleted) · R2 never-run-dry keepalive
    bridge (iOS 241888e, one pre-write divergence recorded) · R3
    `playFrom`/`playFromCurrent` + read-along tap-a-paragraph + editor
    speak-from-caret + narrate-from-viewed-spot (P0-2) · R4 ML Kit OCR
    into notes + PDF native/OCR merge (logic/PdfOcrMerge.kt) · R5
    forceOCR/pdfIsScanned + Force-OCR toggle + shelf badges (P1-7) · R6
    start-stall watchdog, Kokoro-small 92 MB download verified end-to-end,
    lock-screen ±15 s, export stops playback first, dead-head guards.
  - **Albus decisions recorded (2026-11-07):**
    (1) Storage export list + protected exports are RATIFIED as landed.
    (2) `android:usesCleartextTraffic="true"` is NOT accepted as a final
    answer — the sender moves to raw sockets (the receiver already does) so
    the app keeps the platform default-deny; if that is unsafe, the flag
    stays ONLY with a recorded tradeoff + an HTTPS audit of every other
    network path. (3) Platform backup must not carry the app's private data
    (vault keys are device-bound, so a restored backup yields undecryptable
    notes; with the vault off it carries plaintext notes) — exclude filesDir
    via backup rules; Settings → Backup (.jex) stays the sanctioned path.
    (4) The eager neural-model load is a P0 PERFORMANCE bug and lands next
    (see Pending Queue). (5) Pack members do NOT commit — Albus commits.
  - **Deviation recorded:** the wave lands as ONE checkpoint commit, not one
    commit per unit — three agents' work is interleaved inside eight shared
    files (ViewModel, NoteEditor, BookReader, BooksScreen, NotesList,
    Persistence, SpeechText/MarkdownText), and hunk-splitting that history
    would risk corrupting the record for no benefit. Per-unit commits resume
    for the follow-up wave.
- **Unit 0a (seconds-based ChunkSchedule) COMPLETE + verified on Waydroid** —
  see Completed Units.
- **Waydroid audio caveat this boot (2026-10-24 ~23:52)**: playback reached
  SPEAKING but the head froze at 0 — AudioFlinger logged `write blocked for
  574 msecs` then `BUFFER TIMEOUT: remove(58) from active list` 1 s in; the
  HAL sink never drained this session (known container pathology, pre-dates
  0a). Audible confirmation stays with the user on real hardware.
- **2026-10-24 — TTS-speed analysis unit (doc-only)**: traced the iOS
  seconds-based ChunkSchedule (`afdc1d1`, `e680114`) + never-run-dry bridge
  (`241888e`) to their Android gaps; port plan recorded as Pending Queue
  items 0a/0b/0c.
- **Unit 10 (Encryption vault) COMPLETE + verified on Waydroid**: full cycle
  enable → "2 note(s) sealed at rest" → Lock now → notes list lock card →
  cold-start locked card → "Unlock in Settings" nav → wrong passphrase
  rejected ("That passphrase doesn't match.") → correct unlock (logcat
  `NotesStore unlocked: 2 note(s)`) → live lock → Turn off → plain file.
- One fix landed during verification: `NotesStore.markLocked()` — Lock now
  now raises the locked list state live (previously only a cold start did).
- **Interrupted mid-verification**: Supertonic (quality=Fast) play was
  started on the "quick brown fox" note; only state transitions reached
  logcat before the session pivoted to the push. Render-time + export-RTF
  evidence NOT collected.

## Pending Queue

> 2026-11-07: the pack wave's code is in (see In Flight). What
> is left is the follow-up wave, assigned by Albus:
>
> 1. **R7 — LAZY ENGINE INIT (P0 PERFORMANCE, Harry's escalation).** The
>    neural model is loaded EAGERLY on every app start: `SpeechPlayer` is
>    built inside the ViewModel and `rebuildEngine()` calls
>    `activeEngine?.prewarm()` on every rebuild → logcat shows
>    "KokoroEngine: 115 vocab entries, 28 voices ready in 2191 ms" before
>    the user asked for speech, app RSS 428–466 MB with Kokoro-small vs
>    218–270 MB on the system engine, debug cold start +3.38 s vs +2.07 s,
>    and the host LMK killed the app 3× in ~90 min. Fix: construct/load the
>    ONNX session only on a real speak/prewarm request; no model load at
>    startup; keep the 5-minute idle unload; verify RSS + cold start on
>    Waydroid. Owner: Ron (speech/ + the ViewModel hooks).
> 2. **M5 — SETTINGS AUDIT + SECURITY PASS + TRIM (Hermione).** Every
>    settings row end-to-end on Waydroid (Stats excluded — shipped);
>    cleartext-traffic decision (In Flight, decision 2); platform-backup
>    exclusion (decision 3); FileProvider scope; AndroidReaderBridge
>    command allowlist; MainActivity VIEW/SEND name sanitisation; model
>    downloads HTTPS; remove the unused datastore-preferences dependency
>    and the dead `showOverflow` state; list speech/ dead code for Ron.
> 3. **DEVICE EVIDENCE still missing (Ron):** multi-chunk bank-hold /
>    keepalive-bridge log lines, R3 playFrom log, R4 OCR import run,
>    R5 Force-OCR toggle + shelf badges, R6e artifact hunt.
> 4. **VERIFY REMAINDER (Harry) — DONE 2026-11-09, with three leftovers.**
>    Closed on device: the **pomodoro Stop-confirm dialog** ("End this
>    pomodoro?" / "Keep going" / "End timer") and the **Flowmodoro claim
>    gate** (pure logic — covered by the existing 19 `PomodoroEngineTest`
>    tests: below the 3-break-minute threshold the pot is earned but not
>    claimable, at 15 focus minutes it claims and banks the break, a paused
>    focus cannot claim). The **note-editor minimise pill** finally FIREd
>    too (the chevron tap that never landed in H7b). **2026-11-09 (second
>    Harry session): item (a) is CLOSED — the PDF reader's pill renders,
>    see 4b.** (b) and (c) below are the only unexercised GUI items left
>    on the whole project.
>    (b) the recycle-bin **empty-all confirm dialog** — the bin is empty
>    and no scratch note was created under the contention;
>    (c) the **scope-empty "Nothing here yet" state** — needs an empty
>    notebook scope, which no device data provides.
> 4a. **HARRY2 addendum (2026-11-09):** (a) **A PDF NOW EXISTS ON THE
>    SHELF** — "MBS 400 Entomology assignment 2025 Ticks" (Hermione's/B
>    ookDrop-era import), and this session exercised the **PDF reader's
>    landscape rail** on it (chevron, Prev / "Page 1/1" / Next stepper,
>    voice chip, Play, 1.00× over the reduced reader chrome and the
>    "Expand playback controls" pill) — the H8 PDF branch's rail path is
>    device-verified; the pill/collapse sub-tap did not land (rotation
>    contention), and **Harry1 owns the PDF pill check**. (b)+(c) the
>    recycle-bin empty-all confirm and the scope-empty "Nothing here yet"
>    state were NOT run — the user stopped this session first, so they are
>    **back with Harry1 as assigned**. The H4 matrix re-verified on the
>    note editor (rail + collapse-to-capsule + expand) and the PDF reader;
>    the EPUB/audiobook rails and the portrait regression keep the H4
>    evidence (nothing in H8–H10 touched the rail path).
> 4b. **HARRY1 addendum (2026-11-09, portrait pass): the PDF reader's
>    PILL IS CLOSED.** A second PDF, "Approach… "-class 41 KB, was pushed
>    from the host with `research/localsend-probe.py` (receiver live:
>    `register → 200`, `prepare-upload → 200`, `upload → 200`), opened via
>    the Books shelf (detail sheet → Read). The reader rendered the shared
>    collapsed transport pill: **Play / Previous / "Paused" / Next / an
>    expand chevron ("Expand playback controls")** — the H8 PDF branch's
>    payload works. **Two facts worth recording:** (1) the reader OPENED
>    ALREADY COLLAPSED, because `bookBarMinimized` was still true from the
>    earlier EPUB/audiobook pill tests — the PDF surface honours the shared
>    iOS key exactly like the other two readers, and collapse is the
>    restored initial state (Harry2's read agrees: the same "Expand
>    playback controls" pill in landscape). (2) **NO screenshot survived**:
>    both `adb exec-out screencap -p` and `screencap`+pull produced 0-byte
>    files on the final invocation. The ONLY gesture left unexercised on
>    the PDF surface alone is an explicit expand→collapse round-trip with
>    the chevron (tap-driven); the steppers riding the pill are proven
>    live on EPUB and the audiobook through the identical composable.
> 5. **RULE VIOLATIONS (Harry) — DONE 2026-11-09 for Harry's files; the
>    rest need a sweep unit.** All `!!` in the new code are gone
>    (PomodoroEngine ×2, PomodoroViews ×1) via local snapshots/safe reads,
>    and while there I cleared the three that sat in Harry's own files
>    (`NoteEditorScreen.kt`: the clipboard image read — which ALSO crashed
>    on a null clip in its guard — and the two `page!!` PDF-export
>    derefs, now `ensurePage()` returns the page) plus the PDF reader's.
>    Remaining, NOT mine, for whoever owns those files: `ui/books/
>    BooksScreen.kt` ×2, `ui/settings/SpeechSettingsScreen.kt` ×1,
>    `logic/AudiobookChapters.kt` ×1, `ui/settings/BookDropSendSheet.kt` ×1
>    (Hermione's), `data/LocalSendHTTPServer.kt` ×3 (Hermione's),
>    `speech/KokoroEngine.kt` ×1, `speech/SupertonicEngine.kt` ×3 (Ron's).
>    AGENTS.md forbids `!!` in shipping paths — one sweep unit clears them.
> 5a. **RULE VIOLATIONS — ALL CLEARED 2026-11-09 (Harry2's sweep unit).**
>    The rest of the list above is now fixed: BooksScreen ×2,
>    SpeechSettingsScreen ×1, BookDropSendSheet ×1, AudiobookChapters ×1,
>    LocalSendHTTPServer ×3, KokoroEngine ×1, SupertonicEngine ×7 (it had
>    seven, not three) and the ViewModel's `APPLICATION_KEY!!` ×1 — every
>    `!!` in shipping paths is now a local snapshot or a `?: error(…)`
>    guard, and `grep '!!'` over `app/src/main/java` returns only comments.
>    248/248 green, x86_64 APK rebuilt + installed. RULE VIOLATIONS: CLOSED.
> 6. Items 0, 3, 4 and parts of 1 below are now covered by the wave above;
>    they stay here only for their leftovers.
> 7. **BOOKDROP DEVICE VERIFY — DONE 2026-11-09 (Hermione).** All
>    three bugs verified on Waydroid with the code-complete tree (231/231
>    green, x86_64 APK md5-verified as the installed one). Small file,
>    a 301 MB `.m4b`, the 401/200 access-code pair and the mini-bar
>    free-space tap all behave correctly. Full evidence under
>    "Completed Units". The follow-ups in item 8 below are now the
>    only open BookDrop items (and 8 stays open until a new boot).
> 8. **BookDrop follow-ups worth taking after the verify:** the
>    receive side refuses `.opus/.ogg/.oga` (iOS accepts them; Android
>    has no player for them) — decide whether to add an Opus backend
>    or keep the honest refusal; the send sheet's peer scan still
>    skips a phone-as-hotspot interface the way iOS's `lanSubnets()`
>    does NOT (iOS ranks `ap*` soft-AP interfaces — see
>    HANDOVER-BOOKDROP-NETWORK-TTS.md §1).

0. **TTS generation speed (user feedback):**
   - **0a — seconds-based ChunkSchedule: SHIPPED + Waydroid-verified**
     (Completed Units).
   - **0b — live seconds-based render-ahead bank: SHIPPED as Ron R1**
     (pack wave, `1075cb5`) — the chunk-count semaphore is deleted;
     `speak()` paces on `RenderAheadBankPolicy.allows(bankedSeconds(),
     bankTarget)` with a dead-sink escape (StreamingPlaybackCore.kt).
   - **0c — never-run-dry silence bridge: SHIPPED as Ron R2** (same wave)
     — `bridgeIfDry()` pre-writes one keepalive sized to the last render
     + 1 s margin, capped at 120 s/session; and R8 fixed the frozen-head
     root cause itself (`MAX_TRACK_BUFFER_BYTES` = 32 KB).
   - **0d — per-chunk RTF timeline instrumentation: SHIPPED (2026-11-10,
     code + 253/253 green + x86_64 APK; on-device smoke PENDING — the
     Waydroid adb path was down and the user called the pass off before
     the next update).** New pure `speech/ChunkTimeline.kt` (locale-pinned
     formatting, RTF arithmetic, 5 tests) and one line per chunk in
     `speak()` and `exportWav()`: index/chars/audio-s/render wall-s/RTF/
     banked before→after/bridge armed/track created. Observability-only
     diff — `bridgeIfDry` gained a Boolean return, control flow otherwise
     identical. The smoke is one neural play + grep when the container
     network returns.
   - **0e — XNNPACK EP + Supertonic `NO_OPT`→`ALL_OPT`, behind a debug
     flag:** `addXnnpack(...)` is built into onnxruntime-android (no new
     dependency); verify PCM parity vs CPU EP on a fixed sentence via the
     debug probe; meaningful numbers only on a phone (Waydroid = smoke).
   - **0f — physical-phone RTF/thermal baseline:** needs the user's phone
     on adb (real hardware, never an emulator). Run the release build with
     0d's logging, capture RTF + thermal state, then decide EP/threads.
     NNAPI: recorded as experimental-only (deprecated Android 15), no code
     unless a phone shows CPU-bound inference.
   - Token-ceiling answer (recorded): the speed win is NOT a bigger ceiling —
     Kokoro 160 chars already sits at MAX_TOKENS 510 after G2P expansion
     (raising it re-creates the "token count 681" abort); the win is
     adaptively filling the opener to the ceiling + no stalls + fewer/larger
     renders. Unit 0a's schedule now pins the opener at exactly that ceiling
     when the device is slow (verified on device: `next opener 160 chars`).
1. **Finish the Units 7–10 Waydroid pass** (the installed APK is current):
   Supertonic Fast play → chunk render times (expect ~half of the 8-step
   numbers); export WAV while Fast → export must render full 8 steps
   (compare RTF in logs); Stats page (Week/Month/Year switch, bars,
   Subjects, Mon-first heatmap); Storage page (Note images row + clear-
   orphans toast); Speech page system-voice sheet (select Android system
   voice → sheet lists device voices). Then release cold-start re-check
   (`-Psplits=true assembleRelease`, expect ~370 ms).
2. **Kokoro-small download with network** (Unit 4 open item): 92 MB int8
   model, Settings → Speech → Kokoro (small) → Download; validates the
   80–100 MB window end-to-end.
3. **P1 — neural playback start-stall watchdog (never-started variant)**
   (evidence in Completed Units, Unit 5-note).
4. P0-1 (editor preview lock-in), P0-3 (.jex image round-trip), then the
   state-report P0/P1 list in order.
5. Deferred/recorded: LocalSend sender (P1-3), Web-import paywall bypass
   (record as divergence), .jex per-note Joplin E2EE cipher for encrypted
   exports, books long-press menu, swipe actions.
6. Public-repo follow-ups (cheap, user-visible): README refresh (the state
   report's "known gaps" section is stale), screenshots for the release
   page.

## Session Handover

- **2026-11-10 HANDOVER (Unit 0d session).** No claims held; Unit 0d is
  committed + pushed. The Waydroid adb path was DOWN at close (session
  RUNNING, `IP address: UNKNOWN`, 192.168.240.2 unreachable — container
  restart clears it) and **no device mutations were made this session**, so
  there is no device state to restore. Open threads: 0d's one-play smoke
  (logcat `rtf=` lines), then 0e. The user is bringing a big update —
  start from what they say.
- **Environment**: Waydroid was RUNNING at handover (session started ~16:00
  host time 2026-10-24). Get the adb endpoint via `waydroid status`
  (Session IP address) → `adb connect <that-ip>:5555`. If adb shows
  `unauthorized`: copy `~/.android/adbkey.pub` to
  `~/.local/share/waydroid/data/misc/adb/adb_keys`, then `waydroid session
  stop` + `waydroid show-full-ui` + `adb connect`. `run-as`/`su` do NOT work.
- **BUILD UNDER RAM PRESSURE (2026-11-09, the standing hazard).** The host
  was at **~1 GB of free RAM with the swap nearly exhausted** — a normal
  Gradle run OOM-killed ZCode itself mid-session. The build that worked:
  `./gradlew --stop` + `pkill -f KotlinCompileDaemon` first, then
  `./gradlew --offline --no-daemon --max-workers=1
  -Dorg.gradle.jvmargs="-Xmx1200m -XX:MaxMetaspaceSize=512m"
  -Dkotlin.compiler.execution.strategy=in-process <task>` inside
  `flock /tmp/sn-gradle.lock`. One gradle at a time, never two. Check
  `free -h` before starting.
- **DEVICE LOCK IS BROKEN (2026-11-09) — see In Flight.** The Waydroid UI
  process inherits `/tmp/sn-waydroid.lock`'s fd and holds it for the
  session, so `flock` on it queues forever. Start the UI OUTSIDE the flock
  (bare `waydroid show-full-ui`, or with the fd closed). Device installs
  meanwhile go through push + `pm install -r` (streaming install still
  avoided) and — **always md5 the device `base.apk` against your own
  output before drawing any device conclusion**; another agent's silent
  `pm install` has now cost two sessions a false "the fix isn't in the APK"
  detour.
- **UI automation (2026-11-09):** `/tmp/sn_tap.py` re-reads the
  accessibility tree and resolves the target by content-desc/text INSIDE
  the same invocation that taps it, so no tap can land on a stale
  coordinate when a concurrent session moves the screen under you. Subpages
  have no bottom tab bar — the reader/editor backs out with
  `input keyevent 4` (KEYCODE_BACK), not the top-left Back, and the FAB's
  "New note" menu items only appear in `uiautomator dump` briefly, so tap
  the FAB's coordinates right after opening it. The screen is **1041×1920
  portrait at 216dpi ≈ 770dp — WIDE**, so nothing overflows by default:
  use `wm size 720x1280` + `wm density 320` (→ a 1280×720 landscape window,
  i.e. 640×360dp) to exercise narrow-screen layout, then
  `wm size reset` + `wm density reset` (device data survives; the app
  recreates).
- **JDK 17**: `export JAVA_HOME=~/tools/jdk-17.0.13+11` (expand `~` to the
  real home). Disk ~93% — check `df -h /` before builds; one gradle at a
  time, foreground, with timeout. **Never start an AVD/emulator** (AGENTS.md).
- **Build**: `./gradlew --offline -Psplits=true assembleDebug`
  (`app-x86_64-debug.apk` on Waydroid, NEVER arm64 there — SIGSEGV).
  Release: `./gradlew --offline -Psplits=true assembleRelease`.
  Tests: `./gradlew --offline testDebugUnitTest` → **90 green**
  (78 original + 12 JoplinCryptoTest).
- **App state on device**: encryption OFF (the verify cycle ended with
  disable), 2 test notes, engine = Supertonic quality=Fast, Kokoro fp32
  Ready, Supertonic Ready, small model not downloaded.
- **UI-automation notes**: subpages have NO bottom tab bar — go back to a
  tab route first; text fields: `input text` works but the IME hides lower
  fields (dismiss keyboard with `input keyevent 4` before tapping the
  second field); verify typed content with `uiautomator dump` (password
  dots show as `•` runs); read screenshots DIRECTLY (the built-in describer
  hallucinates).
- **Encryption internals** (for future work): `logic/JoplinCrypto.kt` is the
  format port (JED01, KeyV1 220k PBKDF2-HMAC-SHA512, StringV1, interop-
  proven vs an independent Python sealer — recipe in JoplinCryptoTest's
  header; generator script was in /tmp, regenerate if wiped).
  `data/EncryptionVault.kt` double-wraps the KeyV1 envelope under an
  Android Keystore AES-256-GCM key (`speechnotes_vault`) stored in
  `filesDir/vault.key` — the ThisDeviceOnly equivalent. NotesStore: sealed
  files are NEVER quarantined; writes are SKIPPED (not downgraded) while
  locked+sealed; `resealWithCurrentVault(forcePlain)` runs on the writer
  thread and writes the in-memory snapshot (fresher than disk).
- **Unit 10 files**: `data/EncryptionVault.kt`,
  `ui/settings/EncryptionSettingsScreen.kt` (route `settings/encryption` +
  Settings-home row), NotesStore seal/unseal/reload/reseal, VM
  `encryptionEnable/Unlock/Lock/Disable`, notes list `LockedNotesState`.
- **Cross-session facts**: iOS reference tree is the sibling workspace dir
  (`~/speechnotes-ios`, v1.7.4). Python3+cryptography IS available on the
  host for interop vectors.
- **HARRY2 HANDOVER (2026-11-09, session close).** No claims held. Code
  state: the `!!` sweep is complete and the tree is green + installed
  (248/248, x86_64 APK 89 MB). **Device state: `user_rotation 0` /
  `accelerometer_rotation 0` = LANDSCAPE — restore to 1/0 (portrait, as
  found) if the next pass needs a portrait baseline.** Environment
  re-confirmed: the Waydroid UI process holds `/tmp/sn-waydroid.lock`'s fd
  (`fuser -v` shows the `waydroid show-full-ui` python process), so device
  mutations must run unlocked; another session flipped rotation under me
  mid-pass and a Google "Sign in to use Gemini" system dialog appeared over
  the app — fresh `uiautomator dump` before every tap remains mandatory.
  Device clock runs ~2h10m behind the host (ignore `lastUpdateTime`).
- **HARRY HANDOVER (2026-11-09, session close).** No claims held.
  `ui/editor/NoteEditorScreen.kt` and `ui/books/BookReaderScreen.kt` are
  free for the next agent. **UPDATED 2026-11-09 (second Harry session,
  "Harry1"): item 1 is DONE** — the PDF reader's pill renders the shared
  collapsed transport (Play / Previous / Paused / Next / expand chevron);
  the details, the "opened already collapsed because `bookBarMinimized`
  was still true" fact and the missing screenshot are in Pending Queue 4b.
  The only PDF-surface gesture left is an explicit expand→collapse
  round-trip through the chevron. What the next Harry should do, in order:
  2. delete one scratch note, then open the recycle bin and fire "Empty
     all" — the H6 confirm dialog is still unproven on device.
  3. create an empty notebook, switch the scope to it, and confirm the
     "Nothing here yet" empty state.
  4. (Harry2 re-verified the landscape matrix on the editor + PDF reader.)
  Do NOT re-run the note-editor or EPUB/audiobook pill checks — those are
  done and green.
- **HARRY1 CLOSE-OUT (2026-11-09).** Ran no code and landed no unit —
  device verification only, and the user called the session to a close
  mid-pass. Evidence for item 1 is in Pending Queue 4b; items 2 and 3
  remain the only unexercised GUI items on the project and are recorded
  as pending above. **Device state left for the next agent:** `user_rotation
  1` (portrait) / `accelerometer_rotation 0` — **restore to 0/0 if the
  landscape matrix is re-run**; the installed APK is the current tree
  build (`app-x86_64-debug.apk`, `-Psplits=true`, 89 MB, pushed +
  `pm install -r` → `Success`). **The adb network path dropped at close**
  (`No route to host` to the container IP, ping 100% loss, `waydroid
  status` still RUNNING) — `adb kill-server` + `adb start-server` +
  `adb connect` did not recover it. Reconnect with a fresh
  `adb connect $(waydroid status | awk '/IP address/{print $3}'):5555`;
  a container restart also clears it (app data survives, per Ron's note).

## Completed Units

- **2026-11-09 — RELEASE v0.1.5 (prerelease) PUBLISHED.** Tag `v0.1.5` on the
  integration commit; GitHub prerelease carries `Speechnotes-0.1.5-arm64-v8a-
  release.apk` (58 MB, phones) and `Speechnotes-0.1.5-x86_64-release.apk`
  (65 MB, Waydroid). aapt2-verified: versionCode 7 / versionName 0.1.5 /
  arm64-v8a native code; R8 clean; ONNX + ML Kit OCR native libs present.
  Identifiers purged from the tree AND the full history (second rewrite,
  verified zero hits across all refs); remote main force-pushed to the
  rewritten chain — older clones need a fresh fetch + reset, not a pull.

- **2026-11-09 — INTEGRATION + RELEASE v0.1.5 (Albus).** The follow-up wave
  is integrated, scrubbed and released:
  - **Ron R7/R8 — the "TTS doesn't work" root cause is FIXED.** R7 (lazy
    engine init) removed the eager 2.2 s / 150–250 MB model load at app
    start. R8 root-caused the dead-sink symptom the user saw ON A PHONE:
    the native AudioTrack stream buffer (`max(byteSize*2, 1 MB)`) never
    drains once it exceeds ~32 KB — Ron reproduced it with a pure sine
    wave and no ONNX (head pinned at 0, AudioFlinger BUFFER TIMEOUT), and
    fixed it by capping the stream buffer at 32 KB (never below the
    platform minimum) and the prebuffer threshold to the real track
    capacity. Kokoro-small then played THROUGH on Waydroid: GENERATING →
    SPEAKING → natural drain to IDLE, session length == rendered audio,
    no "head frozen" line. Debug-only probe lives in `app/src/debug/`.
  - **Harry H8–H10.** H8: the book transport pill (`bookBarMinimized`,
    iOS AppStorage parity) across all three readers — EPUB chapter bar,
    PDF page bar and the audiobook transport collapse to one pill;
    40 dp touch targets; the overflowed audiobook row (sleep timer drawn
    off-screen) fixed. H9: the three `!!` cleared (raced-boundary crash
    risk). H10: the formatting bar was broken four ways — inclusive vs
    exclusive selection mismatch (duplicated a character; end-of-note
    selection read as empty so Bold REPLACED text), a list/quote crash
    (`substring` past the end), headings committed twice (the second
    commit overwrote the first — H1/H2 never changed the text), and a
    collapsed-range off-by-one that made the next keystroke replace a
    character. The pure text mutations moved to
    `logic/MarkdownFormatActions.kt` with 17 new tests; the bar now wraps
    on narrow screens (FlowRow).
  - **Hermione — BookDrop device-verified + M5 prep.** A 301.6 MB /
    10.5 h `.m4b` transferred in 7.5 s, landed and parsed (27 chapters);
    the receive access code verified (wrong/missing → 401 before any
    body; correct → 200 + landing); tapping the mini bar's free space
    opens the READER of the playing book (iOS jumpToPlayingContent
    parity). M5 checklist items (a)/(b) verified (traversal guards,
    LocalSend server controls); (c)–(g) + the trim remain open.
  - **Albus, at integration:** closed the two standing security decisions —
    platform backup / device transfer now OFF (`allowBackup=false`,
    `backup_rules.xml` + `data_extraction_rules.xml`; the vault-off case
    leaks plaintext notes to cloud backups and the vault-on case restores
    UNDECRYPTABLE notes; the sanctioned path is Settings → Backup .jex);
    audited the other network paths — every model download is HTTPS, the
    only cleartext is BookDrop's LAN protocol (by design; raw-socket
    sender migration queued). Suite 248/248 green (17 new
    MarkdownFormatActionsTest); version 0.1.5 (code 7) per user
    instruction; personal identifiers scrubbed from the tree AND the
    history (second history rewrite, see GitHub status).

- **2026-11-09 — HARRY2: the `!!` sweep unit (Pending Queue 5) + a partial
  H4 landscape re-verify. UNCOMMITTED — Albus commits.** The sweep cleared
  every remaining `!!` in shipping paths (AGENTS.md's rule): `grep '!!'` over
  `app/src/main/java` now returns only comments. Files and rewrites:
  `ui/books/BooksScreen.kt` ×2 (detail-sheet `book.author!!` /
  `book.importError!!` — the nullable fields are snapshotted into local vals
  so the `if` guards can smart-cast them and a concurrent reload cannot crash
  the sheet); `ui/settings/SpeechSettingsScreen.kt` ×1 (the system-voice
  row's `?: systemVoice!!` — the StateFlow property is read into a local
  `currentVoice` the `when` smart-casts); `ui/settings/BookDropSendSheet.kt`
  ×1 (`lastError.orEmpty()` inside the existing null guard);
  `logic/AudiobookChapters.kt` ×1 (local `chpl` snapshot);
  `data/LocalSendHTTPServer.kt` ×3 (the spill stream is read into a local
  `stream` with a `?: error("spilled body stream missing")`, and the terminal
  flush/close is a null-safe `trailing?.let { … }`);
  `speech/KokoroEngine.kt` ×1 (`ortEnv ?: error("ONNX environment
  unavailable")`); `speech/SupertonicEngine.kt` ×7 (each nullable var read in
  the denoise loop becomes `?: error("…")`, the per-step tensor is a local
  `stepT`, and the vocoder input is a local after assignment);
  `data/SpeechnotesViewModel.kt` ×1 (`APPLICATION_KEY ?: error("… missing
  from CreationExtras")` in the factory). No behavior change — the
  degenerated cases all previously threw NPE on a null they had already
  tested. **Evidence:** `flock /tmp/sn-gradle.lock -c './gradlew --offline
  --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx1200m
  -XX:MaxMetaspaceSize=512m" -Dkotlin.compiler.execution.strategy=in-process
  testDebugUnitTest -Psplits=true assembleDebug'` → BUILD SUCCESSFUL in
  1m41s; test-results XML = **248 tests, 0 failures, 0 errors**; x86_64 APK
  89 MB (md5 `4f6d914f7402eb0c76b974febac9dfa2`) pushed + `pm install -r` →
  Success. **H4 landscape re-verify:** note editor — rail (chevron "Minimize
  playback rail" at x≈1839, voice chip "M1 · male", Play/Stop/Read-along
  on/1.00× rate) owns the trailing edge, the bottom transport bar is gone,
  chevron → 66dp capsule (Play + "Expand playback rail") → expand restores;
  PDF reader — rail present (Prev / "Page 1/1" / Next, voice chip, Play,
  1.00×) over reader chrome with the "Expand playback controls" pill.
  Cut short by the user at device-contention; EPUB/audiobook rails and the
  portrait regression keep the H4 evidence (rail code untouched by H8–H10).
  Still open (Harry1 as assigned): the recycle-bin empty-all confirm dialog
  and the scope-empty "Nothing here yet" state, and the PDF pill's collapse
  sub-tap.

- **2026-11-09 — HARRY H8 + H9 + H10: the GUI follow-up wave, wound up.
  (UNCOMMITTED — Albus commits.)** Suite `testDebugUnitTest` →
  **248/248 green** (231 prior + 17 new). x86_64 APK (`-Psplits=true`,
  89 MB) pushed + `pm install -r` → `Success`; cold launch
  `Displayed +1.68 s`, 4 tabs, no FATAL/OOM lines. Unit detail and device
  evidence below; the four unexercised items are in Pending Queue 4.

  **H8 — the book readers' collapsed transport (`bookBarMinimized`).**
  iOS parity source: the v1.8.1 branch `batch-c-d-session`,
  `BookPlayerBar.swift` / `BookReaderView.swift` /
  `BookPDFReaderView.swift` / `BookAudioReaderView.swift` /
  `MinimizedBookTransport.swift`. One app-wide key on the same
  iOS-shared `speechnotes` SharedPreferences as `editorBarMinimized`
  (so a reader always opens the way it was left, exactly like iOS's
  `@AppStorage`), with a SharedPreferences change listener in
  `ui/playback/CompactPlayback.kt` so all three readers stay in step.
  Each reader got a 40dp chevron.down affordance and mounts the shared
  `MinimizedPlaybackPill`:
  - **EPUB** — the chapter bar is HIDDEN while collapsed (the iOS
    2026-10-25 device feedback: it used to survive the minimise and
    stack above the pill) and its steppers ride the pill.
  - **PDF** — the page bar goes away and the pill carries the page
    steppers. **Divergence recorded:** iOS keeps the page bar for a
    chapterless PDF (`if !bookBarMinimized || !hasChapters`) because iOS
    has no playback bar there; Android always has a play-from-page
    button, so it collapses on every PDF and the steppers stay one tap
    away.
  - **Audiobook** — the transport block (slider + ±15 s + chapter nav +
    speed row) collapses to the pill; the cover and chapter list
    reclaim the height.
  Also fixed on all three rows: **40dp targets**. The EPUB/PDF rows had
  6–7 × 48dp controls plus a weighted label that pushed the last child
  off a 360dp screen once Stop joined during narration, and the
  audiobook row needed 358dp of a 328dp row — its sleep timer was
  measured off the right edge and never drawn.
  **Device:** the note editor, the EPUB reader and the audiobook reader
  all collapsed to one slim pill on tap (Play + progress + steppers +
  expand chevron) and expanded back; the EPUB and audiobook chapter bars
  vanish with it and come back on expand; the chapter/page steppers are
  live inside the pill.

  **H9 — the `!!` rule violations.** `logic/PomodoroEngine.kt` ×2
  (`phaseProgress`) snapshot the mutable `phaseStart`/`phaseEnd`
  properties into local vals — Kotlin could not smart-cast them and the
  old `!!` would have crashed on a raced boundary; `ui/stats/
  PomodoroViews.kt` reads `windowEnd` into a local with a null fallback.
  While there, the three that sat in Harry's own files were cleared too
  (`NoteEditorScreen.kt`: the clipboard image read — whose guard also
  NPE'd on an empty clipboard — and the two `page!!` PDF-export derefs;
  `ensurePage()` now returns the page, and the PDF reader's). The
  remaining `!!` in other members' files are listed in Pending Queue 5
  as a sweep unit.

  **H10 — the markdown formatting bar was broken in four ways.** The
  pure text mutations moved to the new **`logic/MarkdownFormatActions.kt`**
  (Android-free, so testable on the `logic/` parity surface);
  `NoteEditorScreen.kt` now only calls it. The bugs, all now fixed and
  covered by **17 new `MarkdownFormatActionsTest` tests**:
  (a) the selection was read as an INCLUSIVE end while `mutate` read it
  as EXCLUSIVE — one disagreement produced every symptom: the character
  after a wrapped selection was duplicated, and a selection reaching the
  end of the note read as "no selection" so Bold REPLACED the text with
  the placeholder word "bold";
  (b) the same off-by-one made the list/quote buttons **throw
  `StringIndexOutOfBoundsException`** on that same selection — a crash
  from a formatting tap;
  (c) `applyHeading` committed twice — `mutate` then `onEdit` with the
  ORIGINAL draft — so the second commit overwrote the first and **H1/H2
  never changed the text at all**;
  (d) `onEdit`'s `it.last + 1` turned the returned collapsed range into
  a one-character selection, so after every formatting tap the next
  keystroke replaced a character instead of typing at the caret.
  Layout: the horizontal-scroll Row is now a **FlowRow that wraps** —
  on a 360dp phone all 13 actions sit on two visible rows instead of 8
  behind an undiscovered scroll (the user's "the toolbar has to fit
  narrow screens"). Divergence from iOS's horizontal ScrollView
  recorded. **Device:** H1 applies `# ` to the caret's line and the
  title follows; at `wm size 720x1280` + `density 320` the bar wraps with
  all 13 actions visible and every pill/transport row still fits.
  Every action also now ticks once through the haptics helper (the
  Feedback-landing-on-the-tap rule).

- **2026-11-09 — HERMIONE: the BookDrop unit — 3 user bugs
  CODE-COMPLETE + now DEVICE-VERIFIED on Waydroid.**
  The 2026-11-08 session left all three fixes code-complete, 231/231
  JVM-green and an installed APK, but **never exercised them on the
  device** (interrupted mid-verify). This session ran the full
  verification round. Code facts are unchanged from the original
  entry; what follows is the device evidence.
  **Suite:** `flock /tmp/sn-gradle.lock -c './gradlew --offline
  testDebugUnitTest --no-daemon -Dorg.gradle.jvmargs=-Xmx1200m
  -Dkotlin.compiler.execution.strategy=in-process'` → all tasks
  UP-TO-DATE; the results XML counts **231 tests, 0 failures, 0
  errors**.
  **Install:** `adb push` + `pm install -r` (streaming install still
  avoided) → `Success`; md5 of the device `base.apk`
  (`6fff4c0baabadc6c8bb584731822d74f`) confirms the running APK IS
  this build's x86_64 output. *(Caught one hazard: another agent's
  install silently replaced the APK mid-round — the first BookDrop
  screen I checked lacked the new "Access code" field purely because
  of that. Always md5 before concluding.)*
  **Bug 1 — large files.** Fixture: a **301,589,693-byte / 10.5 h**
  `.m4b` (`ffmpeg -i Ulysses.m4b -t 38000 -c copy
  -movflags +faststart`), with the `chpl` chapter track and the
  embedded cover intact.
  `python3 research/localsend-probe.py <host> 53317
  /tmp/sn-big-test.m4b 1844` →
  `register → 200`, `prepare-upload → 200 (0.1s)`,
  `upload → 200 (301.6 MB in 7.5s)`. The book then landed **on the
  shelf and opened**: Books tab shows "ULYSSES / James Joyce /
  Audiobook · 27 ch" with its cover; the detail sheet reads
  "Audiobook · 27 chapters" and the chapter list parses 27 entries
  with per-chapter durations. A 544 KB `.epub` pushed first also
  landed and appears on the shelf (29 ch). No FATAL / OOM / ANR lines
  in logcat. *(Audibility is not provable here — the Waydroid audio
  HAL caveat; the evidence is landing + parse + the reader opening.)*
  **Bug 2 — the receive access code.** With code `1844` set in
  Settings → BookDrop → "Access code (optional)":
  `probe <file>` (no pin) → `register → 200`,
  `prepare-upload → **401** (0.0s)`, exit 1 — the sender is refused
  before auto-accept, i.e. **before** any file body is accepted.
  `probe <file> 1844` → `register → 200`,
  `prepare-upload → 200`, `upload → 200 (0.5 MB in 0.0s)`, exit 0,
  and the book lands on the shelf. 401-without / 200-with both proven
  on device.
  **Bug 3 — the mini player's free space.** Played the imported
  audiobook (transport button reads "Pause"), left the reader to the
  Notes tab: the mini bar docks at y≈1761 as "Ulysses / Pause / Stop
  playback / Minimize player". Tapping its **free space at (450,1761)
  — between the title (x≈57) and the buttons (x≈784–979)** opened the
  **book reader**: "Chapter 14", a scrub bar at 4:57:19 / 10:33:20 and
  the 27-entry chapter list — i.e. the *reader of the book that is
  playing*, not the Books tab (iOS `jumpToPlayingContent` parity).
  **Two small findings from the round (not bugs in these fixes):**
  (a) the BookDrop "Recent transfers" list is **in-memory only** —
  it clears on an app relaunch, so it must not be used as evidence
  that a landing failed (check the shelf/list instead);
  (b) the mini bar is *suppressed* on the reader route (it supplies
  its own transport) — correct, and the reason the tap test is done
  from a tab route.
  **Still UNCOMMITTED — Albus commits** (the pack rule).
- **2026-11-07 — RELEASE BUILD for user hands-on testing (R8 release,
  per-ABI splits).** Built from the pack-wave checkpoint `72b62ad`:
  `app-arm64-v8a-release.apk` (58 MB, phone) + `app-x86_64-release.apk`
  (65 MB, Waydroid), copied to the user's Downloads as
  `Speechnotes-1.1.4-<abi>-release.apk`. versionCode/versionName UNCHANGED
  (6 / 1.1.4 — no bump without instruction).
  **Albus release verification (the minified build is the risky one):**
  R8 reported no missing-class errors; the APK carries
  `libonnxruntime.so`, `libonnxruntime4j_jni.so` and
  `libmlkit_google_ocr_pipeline.so` (the bundled OCR pipeline);
  installed on Waydroid, launched clean — `Displayed +1s0ms`, no
  FATAL/ClassNotFound/UnsatisfiedLink; the 4-tab nav renders; and the
  NEURAL PATH WORKS IN THE RELEASE BUILD: engine switched to
  "Kokoro — small · int8" (Ready · fully offline), Play →
  `KokoroEngine state: IDLE → GENERATING` → `58 tokens → 2.2s audio in
  4.23s` → `GENERATING → SPEAKING` → then the KNOWN container pathology
  (head frozen at 0/52200, HAL never drains) ended the session visibly via
  Ron's dead-head guard (15 s bound) → `SPEAKING → IDLE`. Audible
  confirmation remains the user's call on real hardware.

- **2026-11-07 — PACK WAVE checkpoint (Harry H1–H6 · Hermione M1–M4 ·
  Ron R1–R6).** Code-complete: 225/225 JVM tests green (forced rerun),
  x86_64 APK built (89 MB), installed on Waydroid (push + `pm install` —
  streaming `adb install` is unreliable on this boot), launched clean, 4-tab
  nav confirmed on device. Hermione's M1 shipped separately in dd20c7d; the
  rest lands as ONE checkpoint commit because three members' work is
  interleaved inside eight shared files. Unit-by-unit detail, the agents'
  own logs (in `ANDROID-REVIEW.md`) and Albus's five decisions are under
  In Flight. Follow-up wave assigned under Pending Queue.

- **2026-10-25 — Unit 0a: the seconds-based ChunkSchedule (iOS `afdc1d1` +
  `e680114` port).**
  - `logic/ChunkSchedule.kt` — pure-Kotlin port, verbatim: audio-seconds
    opener target (Kokoro seed 8 s / 16 chars-per-audio-s, Supertonic 10 s /
    10, band 3–30 s), grows `max(2×target, 1.2×wait)` when a session's second
    buffer is LATE, holds on a tight cover, settles `max(wait+2, 0.75×target)`
    on a comfortable one, learns chars-per-audio-second from the opener and
    scales it with the live rate; `openerCharacterBudget` clamps to the
    engine's batch/token ceiling.
  - `SentenceChunker.chunks(minOpenerChars:)` — whole-sentence opener
    completion (`completedOpenerEnd`), DEFAULT OFF; existing pinned tests
    unchanged.
  - `StreamingPlaybackCore` — Config gains `schedule: ChunkSchedule.State?`;
    `speak()` sizes firstMaxChars=minOpenerChars from the budget (fallback =
    the old fixed cap); per-session metrics (first/second SUCCESSFUL buffer
    stamps, opener audio seconds + chars); `reportToSchedule()` fired from
    finish() AND stop(); `exportWav` uses the same budget (iOS renderWAV
    parity), exports never feed back. Engines seed per-engine states (Kokoro
    8 s/16, Supertonic 10 s/10); the fixed 100/60 caps remain only as the
    no-schedule fallback.
  - Tests: +19 `ChunkScheduleTest` (full port of ChunkScheduleTests.swift) +
    6 `SentenceChunkerOpenerTest` → **115/115 green** (90 prior).
  - Waydroid evidence (Kokoro fp32): stop-path report `chunk schedule — no
    second buffer, target 8.00s of opener audio, 14.02 chars/audio-s, next
    opener 113 chars`; multi-chunk report `second buffer was LATE, target
    16.00s of opener audio (last second buffer LATE), 21.18 chars/audio-s,
    next opener 160 chars` — the opener pinned at the MAX_TOKENS ceiling on
    a slow device, the exact mechanism the user reported from iOS. Both
    sessions reached GENERATING→SPEAKING, chunks rendered without errors.
- **2026-10-24 — Unit 10: Encryption vault (E1) + on-device verification.**
  - `logic/JoplinCrypto.kt` (previous session) + `data/EncryptionVault.kt`:
    create/unlock/lock/disable, Keystore double-wrap, suspend KDF paths on
    Dispatchers.Default, `storedEnvelope()`.
  - NotesStore: sealed-load (never quarantines a sealed file), sealed writes
    when unlocked, skip-not-downgrade while locked+sealed,
    `resealWithCurrentVault(forcePlain)`, `reloadAfterUnlock()`,
    `markLocked()` (live lock → list lock card), `fileIsSealed`/`isLocked`
    StateFlows.
  - `EncryptionSettingsScreen` (off/locked/unlocked states, Joplin footer,
    disable confirm dialog, spinner during the 220k KDF), route +
    Settings-home row, VM passthroughs, `LockedNotesState` on the list.
  - Evidence: 90/90 tests; on-device cycle quoted under In Flight;
    logcat `NotesStore unlocked: 2 note(s)`; status rows flipped
    plain↔sealed correctly.
- **2026-10-24 — Units 7/8/9 + Unit 10 crypto half** (previous session):
  Speech settings parity (voice picker row, system-voice sheet, Supertonic
  quality dial with export-always-full), Stats rewrite (ranges, bars,
  Subjects, Mon-first heatmap, empty state), Storage note-images section,
  JoplinCrypto + 12 interop tests.
- **2026-10-24 — Unit 6: settings-parity audit vs iOS** (gap list S1–S3,
  T1–T5, ST1, E1 — all addressed by Units 7–10).
- **2026-10-24 — Unit 5: GitHub push** (repo created; made public 2026-10-24
  by user instruction).
- **2026-10-24 — Unit 5-note: neural playback start-stall** (P1 evidence,
  tail-stall guard shipped; never-started variant queued).
- **2026-10-24 — Unit 4: Kokoro small → quantized int8 (~92 MB)** (window
  80–100 MB, legacy uint8 file removed; download pending network).
- **2026-10-24 — Unit 3: Settings root → subpages** (9 subpages, shared
  scaffold; per-page pollers).
- **2026-10-24 — Unit 2: UI/main-thread performance pass + R8** (cold start
  1715→~1380 ms debug, 370 ms release; PDF load off main; writer threads;
  R8 rules for kotlinx-serialization/pdfbox/onnx).
- **2026-10-23 — Unit 1: neural TTS works; play/pause fixed** (Int→Long
  ClassCast, init NPE, share chooser, export silence, main-thread jams;
  fp32 + Supertonic GENERATING→SPEAKING→IDLE, real WAV exports).
- 2026-10-23: `AGENTS.md` created (constitution) + `QUEUE.md` seeded.
