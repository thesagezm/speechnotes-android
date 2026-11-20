# QUEUE.md

Working memory for the Speechnotes-Android build pipeline. Owned by the agent.
Updated at the end of every unit (and in the same commit as the work it
describes). NOTE: this repo is PUBLIC — never commit host-specific paths,
IPs, usernames or machine details. Paths are written relative (`~/…`); the
Waydroid adb IP is read from `waydroid status` each session.

## Current Phase

**2026-11-19 — NANO EDGE TTS IS IN.** nano-edge-tts (drmhse's distilled
Kokoro decoder behind Kokoro's own text side, ~157 MB, Apache-2.0) is the
app's 6th engine — forked from speechnotes-linux `sn-tts/src/nano_edge.rs`
+ its manager.rs catalog entry in units NANO-1/NANO-2 on branch
`nano-edge-tts` (off `book-formats`). **Waydroid-verified end-to-end:
download (31 files) → both graphs load on ORT-Android 1.30.0 → GENERATING
→ SPEAKING (91 tokens → 5.7 s audio in 1.48 s, rtf 0.29) → natural finish
→ WAV export (rtf 0.05 warm, 242 KB on disk = 5.12 s × 24 kHz × 2 bytes
exactly).** Audible confirmation is the user's on real hardware. The unit
also fixed a REAL downloader bug the pass exposed: ModelManager's
readTimeout(0) let a stalled CDN connection hang a download forever — now
60 s per-read, converting stalls into resumable retries.

**2026-11-11 — PHONON TTS IS IN.** The Gradium Phonon 31M engine (English
streaming, ~192 MB, Apache-2.0) is the app's 5th engine — ported from
speechnotes-iOS's WORKING prefill-once contract (`PhononEngine.swift` @
`75ff1ba`) in units P1–P4 on branch `phonon-tts` (now merged): tokenizer
+ 191-step normalizer in `logic/` pinned to the SAME 14 reference fixtures
as iOS, model plumbing + settings UI, the engine itself, and full player
wiring. **Waydroid-verified end-to-end: download → both graphs load on
ORT-Android 1.30.0 (contrib ops present) → GENERATING → SPEAKING at RTF
0.21 (~4.7× realtime on the container) → natural finish → WAV export
(rtf=0.26).** Audible confirmation is the user's on real hardware.

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

0. **2026-11-20 — CONTEXT MENUS / COPY-PASTE AUDIT (user):** the user pasted
   the Linux app's assessment (GTK right-click menus are speak-only; no
   copy/cut/paste anywhere) and asked **"this is for linux but what is
   missing for android"**. ADDRESSED in chat the same day (assessment only —
   no code, no commit, HARRY1-close-out precedent). Findings: Compose gives
   the long-press selection toolbar natively on every TextField (editor body,
   title, search, dialogs, settings), the EPUB/DOC WebView carries the system
   selection action mode (reader.js deliberately keeps selections out of the
   chrome toggle), notebooks already have rename/delete UI, and note rows
   already have a long-press menu — so Android is AHEAD of Linux on those.
   The genuine Android gaps from the same audit: (A1) the markdown PREVIEW is
   not wrapped in SelectionContainer → nothing in the preview is copyable
   (`NoteEditorScreen.kt:1181`, the only SelectionContainer is the edit field
   at :1146); (A2) read-along rows are plain clickable Text → tap-a-paragraph
   speaks (playFromCurrent) but a passage cannot be copied
   (`ReadAlongScreen.kt:164`); (A3) PDF pages are bitmaps → no copy path at
   all, though the text layer/OCR text exists internally (a "Copy chapter
   text" row in the Contents sheet is the cheap fill); (A4) the notes
   long-press menu still lacks Delete + Share (inline icons only; also no
   Rename) — `NotesListScreen.kt:575-601`; (A5) book cards have NO long-press
   menu — the detail sheet covers Read/Send/Delete but "Add a goal" is
   missing (state-report P2); (A6) EPUB long-press-copy must be device-verified
   once (reader.js code looks correct; tap zones on). Linux bug #8 (open
   gesture accepts every button) has no Android analogue — Compose
   Card(onClick) only fires on primary taps. User said **start** → A1 shipped
   as **Unit SEL-1** the same day (see Completed Units, Waydroid-verified
   end-to-end). Remaining, in order: A2 read-along copy (SEL-2), A4 notes
   menu Delete+Share (MENU-1), A5 book long-press + Add-a-goal (BOOK-1), A3
   PDF copy chapter text (PDF-COPY), A6 EPUB long-press-copy device verify
   (EPUB-VERIFY).
0. **2026-11-18/19 — NANO EDGE TTS (user): "implement nano-edge-tts as part
   of the speech engines… fork/copy extensively from speechnotes-linux (not
   [linux-]rust)".** ADDRESSED as **Units NANO-1 + NANO-2** (see In Flight):
   the engine, its catalog entry and its UI are ports of
   `speechnotes-linux/crates/sn-tts/src/nano_edge.rs` + manager.rs; the
   Android platform differences (MisakiG2P instead of espeak, voices.npz vs
   per-voice .bin for the Kokoro sibling, ORT-Java tensors) are recorded
   in NanoEdgeEngine.kt/NanoEdgeText.kt doc comments.
0. **2026-11-18 — READER TITLE BAR NEVER HIDES (user): "make it able to
   disappear the way the chapter bar manages to disappear when I tap the
   screen and reappear when I tap back… right now it persists in both the
   portrait and landscape mode."** Root cause found: the outer
   `BookReaderScreen` Scaffold's `TopAppBar` sits OUTSIDE the
   `chromeHidden` toggle that hides the chapter bar + bottom bar
   (`chromeTap` from the WebView tap zone flips `chromeHidden` inside
   EpubReader/DocReader; the title bar never participates). iOS hides the
   nav bar on the same tap in every reader (`BookReaderView.swift`
   `immersiveBars`, `BookPDFReaderView.swift:332-336` `immersiveBars.pdf`).
   ADDRESSED as **Unit CHROME-1** (see In Flight).
0. **2026-11-18 — READ-ALONG DOESN'T FOLLOW THE READ TEXT (user): "why
   doesn't read along mode keep the currently being read text on the
   screen — sometimes it's very far at the bottom… see what speechnotes
   iOS does."** Two defects found against the iOS reference
   (`ReadAlongView.swift` + `BookReaderView.swift:357-390`):
   (1) Android's `ReadAlongScreen` auto-scrolls by a FIXED estimate
   (`target * 56 dp`, `ReadAlongScreen.kt:77-78`) — tall paragraphs
   undershoot and the read text lands below the viewport ("far at the
   bottom"); iOS scrolls the actual paragraph to CENTER on every sentence
   change (`proxy.scrollTo(id, anchor: .center)`). (2) The book readers
   never had read-along at all — iOS swaps the EPUB/PDF surface for the
   native ReadAlongView while the book narrates; Android left the WebView
   with its fixed-speed teleprompter (`AUTO.speed` px/s), which knows
   nothing about what is being spoken. ADDRESSED as **Unit RAL-1** (see
   In Flight).
0. **2026-11-15 — PHONON CHUNK-GAP DIAGNOSIS (user): "please diagnose that
   audible break between two chunks/sentences and recommend fixes"** —
   ADDRESSED as **Unit G2** (see In Flight): root cause was structural — the
   producer could not START render(N+1) until it had pushed ALL of chunk N
   into the 32 KB AudioTrack FIFO (0.68 s), so every render began with
   0.68 s of runway → dead air = render − 0.68 s ≈ 4–5 s per boundary; the
   30 s render-ahead bank was unreachable (bankedSeconds() capped by the
   FIFO) and GO1's bridge never armed (admission always saw a full FIFO).
   G2 gives the pipeline a real bank + writer thread. Code-complete,
   274/274 green, APK built; the audible verdict is the user's (they are
   testing on their own hardware — no Waydroid pass this session).
   Remaining from the same session's log: the EPUB reader JS storm
   (`RangeError: Maximum call stack size exceeded` ×~50 in 0.4 s at
   index-android.html:52 — infinite recursion in reader.js; also inflates
   RTF 0.5 → 0.93 via CPU contention, which lengthens every gap) — its own
   unit, queued.
0. **2026-11-15 — PDF READER LANDSCAPE (user): "Big GUI bug on mobile — in
   landscape mode the viewing space for pdfs is severely reduced … the page
   slider is not necessary there … that TOC should be in the same row as the
   title not at the bottom … the previous and next buttons in that bottom bar
   are already in the landscape rail … i dont want that bottom bar there in
   landscape mode — all that space should be used to fit the pdf … see and
   replicate exactly what speechnotes-ios does."** ADDRESSED as Unit PDF-L1
   (see In Flight): the landscape bottom band is GONE (iOS round 6), the
   page takes everything leading of the rail, Contents moved to the title
   row, no prev/next duplication. Device pass pending (Waydroid container
   down — the same root-restart blocker as GO1/EPUB-F1).
0. **2026-11-14 — PHONON CLICK+GAP PORT (user): "There are some recent
   changes made to phonon on speechnotes-ios to improve sound quality
   reduce the click and gap... incorporate this".** The iOS change is ONE
   commit — `cf501b2` (2026-11-13, "Phonon chunk click+gap"), and its two
   parts live in the SHARED streaming core (StreamingTTSPlaybackCore.swift
   → our StreamingPlaybackCore.kt), so every streaming engine benefits:
   (1) **raised-cosine boundary fades** — 5 ms in / 12 ms out on every
   chunk at both sinks (playback + WAV export): engines cut PCM at
   synthesis boundaries and each chunk starts from fresh model state, so
   the outgoing chunk's last amplitude and the incoming chunk's first are
   unrelated samples — that step is the broadband click at the end of
   every chunk; a few ms of ramp turns the step into a slope;
   (2) **keepalive silence shortened 0.5 s → 0.2 s with ROLLING re-arms**
   — every slice of bridge silence enters the FIFO ahead of the chunk
   being rendered, so its length IS added latency: the old Android bridge
   pre-wrote ONE buffer sized to outlast the expected render
   (last render + 1 s margin → up to ~1 s of dead air after the chunk was
   already synthesized — the "slight delay before the next chunk resumes"
   report). Ported as Unit GO1 (see In Flight). Device evidence pending
   (Waydroid container down; audible click/gap confirmation is the
   user's on real hardware).
0. **2026-11-12 — EPUB READER WIDTH + LINUX-RUST FORMATTING (user): "Epub
   books … expand once i click the tts like am i in landscape mode instead
   of fitting the screen width … especially on a book like first aid for
   the usmle … also make sure they follow the formating and default format
   the way speechnotes-linux-rust does"; then "at the end commit everything
   and give me a local build … verify it works wit waydroid then give me a
   build".** Three proven defects + a formatting-parity wave, one unit:
   (1) **every EPUB page was 32px wider than the screen** in the default
   scrolled flow (measured in a real browser: shell `scrollWidth` 444 vs
   viewport 412) — `index-android.html`'s `sizeViewer()` pins `#viewer` to
   `innerWidth` px while `applyPadding()` added the margin padding OUTSIDE
   that width (content-box), so the page panned sideways;
   (2) **the live `readerAppearance({...})` command had no `flow` handler**,
   so the scrolled padding stayed on after a flow push (the same
   wider-than-the-screen symptom); (3) **Android's reader.js was an older
   copy missing iOS's `injectTablePanning`** — converted books (the user's
   First Aid for the USMLE is Calibre-converted; its CSS carries the blue
   banners/tinted panels the Rust reader's doc celebrates) clip/overflow at
   the page edge with no way to reach a table's lateral end. Formatting
   parity with `speechnotes-linux-rust` (`crates/sn-ui/src/epubview.rs`):
   `respectStyles` default **true** (`respect_styles: true`, calibre's
   "the book as the book" — First Aid keeps its design by default),
   `lineHeight` default **1.55** (`line_height`), and reader.js THEMES now
   carry the Rust scheme values (light #000/#fff, sepia #39322b/#f6f3e9,
   dark #e6e4e2/#1c1c1e, trueBlack #fff/#000) applied with the Rust
   MECHANISM (background `!important` on the html element, body forced
   transparent — a book painting its own body, inline styles included, can
   no longer defeat the theme; proven in-browser against a magenta-body
   probe book). Browser-verified fixes: scrolled and paginated both measure
   `docScrollW == innerWidth` (412==412), the live flow switch clears the
   padding, table-panning CSS injects per rendered section. **258/258 JVM
   green** (ReaderAppearanceTest pins updated to the Rust defaults), x86_64
   APK built. Divergences recorded: epub.js's paginated chapter iframe is
   sized to the full chapter width by design (epub.js pages it internally;
   the shell clips it — shell measured 412 in paginated, untouched); iOS's
   env(safe-area-inset-bottom) paginated height was NOT ported (Android's
   shell owns insets via Compose; sizeViewer pins the height explicitly);
   iOS's goChapterFailed reporting NOT ported (interaction parity, not
   formatting — queued). DEVICE VERIFY PENDING: the Waydroid container
   died overnight and restarting needs root (see Session Handover).
0. **2026-11-10 — PHONON PORT (user): "start porting phonon-tts to
   speechnotes android — also research from what speechnotes ios has done…
   it has its own phonon issues it is trying to handle so you can research
   from there but do it your way and in the most compatible way"; then
   "use latest fixes from speechnotes-ios" and "do this work on a separate
   branch so its easy to revert".** New engine wave on branch `phonon-tts`:
   Gradium Phonon 31M (English streaming TTS, Apache-2.0) via
   `onnx-community/phonon-31m-en-ONNX` — the app's 5th engine slot (iOS
   picker order: system → Kokoro → **Phonon** → Supertonic). Plan approved;
   contract ported from iOS `Docs/PHONON-PORT.md` + `PhononEngine.swift`
   AT COMMIT `49e8ab7` (the repeat-spiral KV fix: head-major
   `[1,heads,seq,dim]` cache slicing + the single +8/call counter — the
   old +7/+8 two-counter split is obsolete; do NOT port from older
   snapshots). Units P1–P4 under In Flight.
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

- **2026-11-19 — Units NANO-1 + NANO-2: nano-edge-tts, the app's 6th engine
  — DONE, Waydroid-verified end-to-end (440/440 green, both split APKs
  built), on branch `nano-edge-tts` (off `book-formats`, 2 commits).**
  User ask (see User Feedback Pending): "implement nano-edge-tts as part of
  the speech engines… fork/copy extensively from speechnotes-linux (not
  [linux-]rust)". Source of truth:
  `speechnotes-linux/crates/sn-tts/src/nano_edge.rs` + manager.rs's
  `nano-edge-tts` ModelSet. nano-edge-tts = Kokoro-82M's text side
  (`text-side.onnx`, ~112 MB) with its 53.3M waveform decoder replaced by
  drmhse's distilled 1.75M harmonic-plus-noise decoder
  (`nano-decoder.onnx`, ~30 MB) — same 28 English Kokoro voices, same
  tokenizer, 24 kHz; the BOS/EOS wrap lives inside the text-side graph so
  the ids fed are the same unwrapped stream Kokoro gets. Linux puts
  nano-edge-tts LAST in the speech picker — Android's EngineKind order
  mirrors that.
  - **NANO-1 (plumbing, commit `71ee84b`):** EngineKind.NANO_EDGE;
    AppFiles NanoEdge/NanoEdge/voices dirs; ModelFiles.nanoEdge (both
    graphs from `thesagezm/speechnotes-models/nano-edge-tts`, kokoro's own
    tokenizer.json + the 28 voice banks from the Kokoro repo at their
    published paths — every URL verified live with curl; banks gated
    min=max 522,240 bytes so a truncated transfer never counts as
    complete, Phonon voice-bin precedent); ModelManager state flow +
    delete-guard (NanoEdge owns its directory outright); Prefs
    .nanoEdgeVoice; SpeechSettingsScreen rows; VoicePickerSheet branch;
    SpeechPlayer voice/recents/description/audition wiring. **Bonus bug
    fix: VoiceCatalog `am_fenfir` → `am_fenrir`** (iOS fixed the same
    typo, ModelManager.swift:33-34; on Android the wrong name matched no
    npz member — Kokoro silently spoke the first sorted voice instead of
    Fenrir — and would have 404'd nano's Fenrir bank download; the set's
    clean 31-file download proves the fix on device).
  - **NANO-2 (engine, this commit):** `speech/NanoEdgeText.kt` — the pure
    text math (voice-bank validation, style-row offset, terminal-pause
    symbol, tokenize: unknown phonemes → space id never deletion, hyphen/
    apostrophe cleaning, token cap 510) ported from kokoro.rs's
    `tokenize_for_kokoro_family`; **16 new NanoEdgeTextTest JVM pins**
    (bank shape, endianness, style clamp, substitution, pause
    re-attach/dedupe/skip, range errors, constants). The tests caught one
    real port bug pre-device: the terminal-pause push overflowed the
    token array (fixed: +1 slot). Recorded divergence from Linux, in the
    code: Android's MisakiG2P passes `.;:!?` through (espeak drops
    punctuation), so the pause is appended only when the token stream
    does not already end with that id — an unpunctuated end still takes
    the "…" long-pause prosody. `speech/NanoEdgeEngine.kt` — the two-graph
    shell on the shared StreamingPlaybackCore (kokoro's chunk caps +
    schedule seed verbatim — the text side IS kokoro's), Linux's
    MAX_FRAMES 16384 F0-axis guard, f0/n frame agreement check, style row
    = clamp(phonemeChars−1, 0, rows−1)×256 (Linux formula; Kokoro engine
    keys on token count — nano follows its own reference), only the
    CURRENT voice's 522 KB bank resident (the Linux design, vs Kokoro's
    whole-npz). SpeechPlayer: nano() singleton, resolve/unload/idle-unload/
    setVoice/export/shutdown wiring.
  - **Downloader stall fix (the device pass exposed it):** ModelManager
    readTimeout(0) → 60 s. A zombie CDN connection (TCP alive, no bytes)
    parked the first download attempt FOREVER — ~28 min with the progress
    bar up and ~190 B/s on the interface; retry/resume only fires on an
    IOException and a silent stall never throws. OkHttp's readTimeout is
    PER READ (gap between bytes), not a file total, so 60 s cannot kill a
    healthy multi-hundred-MB transfer; it converts a stall into a
    SocketTimeoutException the per-file retry + Range resume already
    recovers. Force-stop + re-tap Download then completed the set.
  - **Device evidence (Waydroid, landscape, logcat pid 7924):**
    `engine = nanoEdge (fallback=false)`; download: 31 files (2 graphs +
    tokenizer + 28 banks); `NanoEdgeEngine: 115 vocab entries, 2 graphs
    ready in 3553 ms`; `91 tokens → 5.7s audio in 1.48s`; `chunk 0/1:
    chars=84 audio=5.12s render=1.48s rtf=0.29 banked=0.0→5.1s bridge=off
    track=new trim=0.61s`; AudioTrack ready rate=24000; `GENERATING →
    SPEAKING` → natural `→ IDLE` (schedule settled: 16.41 chars/audio-s,
    next opener 132); export: `export 1/1: chars=84 audio=5.12s render=0.28s
    rtf=0.05 trim=0.60s`, file on disk **242 KB · 18 Nov, 22:55** (=
    5.12 s × 24 kHz × 2 bytes exactly). Voice chip read "Heart · American ·
    female" (af_heart, the shared Kokoro bank). Audible confirmation is
    the user's on real hardware (Waydroid audio-HAL caveat).
  - **Device state left:** Nano Edge selected + downloaded + Ready; one
    scratch test note now carries the sentence "Hello from nano edge, the
    sixth engine…"; Render markdown pref was flipped OFF during the pass
    (it had trapped the editor in preview — the known P0-1).
  - AGENTS.md divergence note (frozen file — proposing here, not editing):
    the "four engines" engine contract is now SIX (Phonon joined in the
    phonon-tts wave; Nano Edge now). The contract's callback/state
    semantics are untouched and preserved by the new engine.
  - Commits: NANO-1 `71ee84b`, NANO-2 + QUEUE.md this commit. Next:
    `book-formats` device pass is still the pending gate for its merge;
    this branch rebases/merges cleanly after it.

- **2026-11-18 — Unit RAL-1 CLOSE-OUT: the deep-chapter center-follow — DONE
  (Waydroid-verified end-to-end; 424/424 green; both split APKs built).**
  The RAL-1 open item ("no scroll past the first screenful") had TWO root
  causes, both located with a temporary branch-aware instrumentation pass
  (`RAL:` logcat lines logging every early-return branch + post-scroll
  state) and both fixed:
  - **(1) The read-along "viewport height" was the CONTENT height.**
    `ReadAlongScreen` measured the viewport with `onSizeChanged` placed
    AFTER `verticalScroll` in the modifier chain — a scroll node's content
    is measured with unbounded height, so the callback reported the FULL
    CONTENT height (device: vh=14280 vs the real viewport 695, max=13585).
    Every centering target (top + h/2 − vh/2) was therefore hugely
    negative, coerced to 0, and `animateScrollTo(0)` actively scrolled the
    view BACK to the top on every sentence change (device-proven: a manual
    swipe to s=648 was erased to s=0 by the next sentence's effect) — the
    highlight below the fold, scrollTop pinned at 0, forever. Fix: the
    `onSizeChanged` moved BEFORE `verticalScroll` (bounded size = viewport).
    The row geometry itself was verified correct as-is (content-space:
    `positionInParent` inside a scroll column does not move with the
    scroll — para tops stayed monotone in text order across differing
    scroll offsets).
  - **(2) The "start where I am" path desynced the read-along tables.**
    `SpeechPlayer.playBookChapter` pre-substringed the chapter
    (`actualText = text.substring(startCharOffset)`) AND passed
    `baseOffset = startCharOffset` — while `speak()` built its sentence
    pieces / activeSpeechText from the ALREADY-substring'd suffix, so
    fullChar = baseOffset + engineCharsDone (a full-text offset) was
    matched against a SUFFIX-local pieces table and the highlight LED THE
    SOUND by exactly baseOffset chars (device-proven: chapter 8 resumed at
    char 2778 and the very first highlight line showed r.first=2778 — the
    sentence 2778 chars ahead — with the read-along surface starting
    mid-chapter instead of at the chapter title). This is the reader-Play
    path whenever the book's saved scroll fraction is non-zero (the
    DEFAULT rail-Play path), and it also persisted bookmarks under the
    suffix hash with a full-text offset — a resume that could never match.
    Fix: `playBookChapter` passes the FULL text + baseOffset, and
    `speak()` handles baseOffset exactly like the bookmark path (full-text
    pieces/activeSpeechText, the engine speaks the suffix,
    engineSpeechOffset = full-text start) — the contract `playFrom` already
    followed. Device after the fix: `persistBookmark chars=7606
    textLen=31200` — full-text hash + full-text position, consistent.
  - **Device evidence (Waydroid, landscape, Phonon, chapter 8/29 of Think
    Faster, 209-chunk chapter):** first RAL line after the fix:
    `vh=695 target=1197` → `after s=1197` (real viewport, real scroll);
    screenshots at t≈0 / 60 s / 130 s of one narration show the sounding
    sentence around the viewport CENTER with the chapter heading scrolled
    off — `s` marched 1197 → 2005 → 2935 with every `after` equal to its
    target, the highlight advancing at the true spoken rate (it had run
    ahead before), and tall-paragraph centering using the measured h=180.
    The temporary per-sentence logging is REMOVED (the clean build greps
    0 "RAL:" lines during a 50 s narration) — no 400-entry ring-buffer
    flood during long listens. Clean APK reinstalled and re-passed
    (highlight centered at 50 s).
  - Evidence: 424 tests / 0 failures / 0 errors; `-Psplits=true
    assembleDebug` → app-x86_64-debug.apk (89.5 MB) +
    app-arm64-v8a-debug.apk (82.6 MB).
  - Note: the RAL-1 repro recipe (Contents jump) exercises the offset-0
    path; this session ALSO drove the positionFraction path (rail Play
    with a saved scroll fraction) — that is where bug (2) lived — so the
    close-out covers both narration entry paths.
  - Commits: this entry lands with the RAL-1 close-out commit (QUEUE.md
    same-commit rule).

- **2026-11-18 — Unit RAL-1: read-along follows the read text — CODE-COMPLETE
  (424/424 green, both split APKs built); device-verified for the surface
  swap + early-chapter follow; the deep-chapter OPEN ITEM was CLOSED the
  same day by Unit RAL-1 CLOSE-OUT (above).** User feedback (see User
  Feedback Pending). Two parts, both against the iOS reference
  (`ReadAlongView.swift`, `BookReaderView.swift:357-390`):
  - **RAL-1a — measured center-anchor auto-scroll in ReadAlongScreen.**
    The old estimate (paragraph index × fixed 56 dp) undershot tall
    paragraphs → the sounding sentence landed below the viewport ("far at
    the bottom"). Each row now reports its content-space geometry
    (`onGloballyPositioned` → `positionInParent` into a map) and on every
    sentence change the CONTAINING paragraph (sentences start mid-paragraph
    — the old code's own containment search is kept) animates to the
    viewport center (`top + height/2 − viewport/2`, clamped) — iOS
    `proxy.scrollTo(id, anchor: .center)` parity, first-paragraph skip
    included. Also gave the surface an opaque background
    (`colorScheme.surface`, iOS systemBackground/black parity) — over a
    book reader the page text otherwise showed through and collided with
    the rows (found on device).
  - **RAL-1b — the book readers swap to the read-along surface while the
    book narrates (iOS readerSurface parity).** EPUB, DOC and PDF readers
    overlay `ReadAlongScreen` when `readAlongEnabled && narratingThis &&
    activeSpeechText != null`; the WebView/page stays composed UNDERNEATH
    (removing it would destroy + reload the rendition on exit — iOS keeps
    its WKWebView alive for the same reason). Exit turns the pref off and
    returns to the book; speech continues. Divergence recorded: Android's
    read-along surface carries the Pause/Stop/Exit header row (it is the
    note editor's only transport too — one surface for both); iOS's rows
    are bare. The book rails still have no read-along toggle (P1-11) — the
    global pref + Exit cover it for now.
  - **Device evidence (Waydroid, landscape, Phonon):** open Think Faster
    EPUB → rail Play → GENERATING → SPEAKING (logcat chunk lines, rtf
    0.22–0.34) → the native read-along surface appears over the page with
    the sounding sentence highlighted (epigraph: "—Mark Twain"; after a
    Contents jump to chapter 8 the highlight advanced paragraph by
    paragraph — screenshots sn-ral-r1/sn-step5/sn-step6). Bank full at 30 s
    and holding (chunk 49/229, G2 healthy). **OPEN ITEM — CLOSED 2026-11-18
    by Unit RAL-1 CLOSE-OUT (top of this section):** the deep-chapter
    no-scroll was TWO bugs (the viewport was measured as the CONTENT
    height, and the start-where-I-am path desynced the read-along tables);
    both fixed and device-verified there. Everything else about the unit
    is verified.
  - Commits: this entry lands with the RAL-1 commit (QUEUE.md same-commit
    rule).

- **2026-11-18 — Unit CHROME-1: the reader title bar joins the immersive
  chrome — DONE (424/424 green, both split APKs built, Waydroid-verified
  hide+restore on the EPUB and PDF readers in landscape).** User feedback
  (see User Feedback Pending): the title bar persisted while the chapter
  bar/bottom bar hid on tap. Changes: (1) `chromeHidden` hoisted from the
  readers into `BookReaderScreen` and the outer `TopAppBar` wrapped in the
  same AnimatedVisibility — one tap hides title+chapter+bottom bars, one
  tap restores; (2) the PDF surface got the tap gesture iOS's
  `immersiveBars.pdf` has (scroll-mode LazyColumn, the single-page image —
  added to its existing double-tap-reset detector — and the loading
  spinner), and the portrait bottom band hides with it too; (3) iOS-parity
  `Haptics.tap` on every chrome toggle (the native-side toggle was
  silent). **The device round caught a deeper bug: the readers' bridge
  messages rode a MutableStateFlow, which DROPS a value equal to the
  previous one — every second identical chromeTap (the restore tap!) and
  consecutive pageTurn messages never reached the handler**, so the chrome
  could hide but never come back by tapping (the user's "reappear" only
  worked when another message — a relocation, a page turn — happened to
  intervene). Replaced with an UNLIMITED Channel in both EpubReader and
  DocReader (events, not state; `return@collect` → `continue`).
  Evidence: 424/424; device screenshots: EPUB tap → title+chapter+bottom
  bars all gone, tap → all restored (landscape 1920×1040); PDF tap →
  title gone, tap → restored; portrait shares the same hoisted state and
  AnimatedVisibility (not rotation-verified on the container — the user's
  phone pass covers it). DOC reader wired identically to EPUB.

- **2026-11-18 — Unit BOOK-F1 (CODE-COMPLETE on branch `book-formats`, 4
  commits, 424/424 JVM green, both split APKs built; DEVICE PASS PENDING —
  Waydroid up but adb UNAUTHORIZED, one manual tap owed): native viewers
  for MOBI/AZW3/FB2/RTF/HTML/TXT — the speechnotes-linux-rust BookDoc port
  — PLUS the EPUB TOC-jump fix.**
  User ask (2026-11-17): "incorporate the mobi/azw3/fb2/rtf/html formats
  that they are rendered well in the android app... their code should be
  like the speechnotes linux app... rendering should not parse or convert
  them to epub but rather views them in their own viewers... use as much of
  the speechnotes-linux-rust code as possible"; ALSO "in epub the toc
  chapters do not go to the linked chapters using toc also fix this". The
  branch IS the revert boundary: `book-formats` off `main` at `a78c043`
  (`git checkout main` is the pre-BOOK-F1 state).

  **COMMITS on `book-formats` (all tests green at each):**
  1. `e7ef850` — the parsers themselves (previous session): BookDoc.kt,
     BookCodepages/BookXml/BookTxtParser/BookRtfParser/BookFb2Parser/
     BookHtmlParser/BookHuffCdic/BookMobiParser + the ported test suites.
  2. `f8e4e05` — (a+b): the 5 mechanical test-compile fixes + the 9
     behavioral pins the suite surfaced, each fixed against the Rust
     ground truth: BookXml's comment/CDATA/PI skip branches overshot by the
     prefix length (the Rust's `end` is an index into the string AFTER the
     prefix; the Kotlin's is into the whole rest — text now
     `rest.substring(9, end)`, all three `consumed = pos + end + close`);
     BookMobiParser.items() sliced the UTF-16 String with UTF-8 BYTE
     offsets from nextBoundary (wrong chunks for any multi-byte char,
     StringIndexOutOfBounds when the byte length exceeded the char length —
     chunks now decode from the byte array, like chapterSegments already
     did); BookCodepages.decodeCp1252 now keeps the five undefined
     positions' Latin-1 glyphs (Java's windows-1252 maps them to U+FFFD —
     Latin-1 decode with the 27 defined positions of 0x80–0x9F remapped);
     BookHtmlParser.parseHtmlz sorts the zip names (the reference's
     Archive::entries() sorts, and calibre's cover.jpg must beat
     images/pic.png for the first-image cover pick); three test
     mistranslations corrected against the Rust (space_plus_ascii pins
     " A i"; the script raw-text pin expects Start THEN Raw; decodeCp1252
     pins were Char-vs-String).
  3. (c+d) — the wiring and the EPUB TOC fix:
     - `DocFamily` is ONE map now (`byExtension`) with the exported
       `extensions` set — the import gate, the BookDrop allowlist, the
       picker and the narration path read the same table.
     - `importBookDoc`: parse via ServedBookDoc.parse on IO; manifest =
       the doc's own title/author/chapters (virtual `chapter-N` hrefs) /
       toc (the doc's own contents with depth, or flat rows derived from
       chapter titles) / cover (PNG or JPEG bytes — cover.jpg is the
       legacy NAME, decoders sniff content); `format` = the file's own
       extension; `formatLabel` = the uppercase extension. Failures refuse
       exactly like importEpub. NO EPUB CONVERSION ANYWHERE; office
       formats (docx/odt/pptx/odp) keep the normalize-to-EPUB path. The
       importBook gate/dispatch, importBookFile's dispatch (a BookDropped
       .mobi would have fallen into the audio importer) and the mime
       fallback all know the family.
     - `servedDoc`/`servedDocOrNull`: a synchronized LRU (2 entries,
       accessOrder) of parsed docs — the WebView's request handler thread
       answers synchronously, the narration path shares the same parse.
     - `chapterText`: the doc branch extracts the BookDoc chapter through
       the ordinary XhtmlText pass, cached via bookSpeechTextFile —
       toggleBookChapter/narrateFromSpot work unchanged.
     - `DocReaderScreen.kt` (new) + `index-doc-android.html` +
       `docreader.js`: the doc reader's OWN WebView (NOT epub.js) — a
       custom WebViewAssetLoader PathHandler at `/docs/<bookId>/` answers
       `chapter-N` pages and `img/<name>` bytes from the served-doc cache
       (this androidx.webkit's PathHandler hands the raw path string);
       docreader.js fetches `{bookPath}chapter-N`, installs `<base href>`
       so the chapters' relative img srcs resolve to the handler, speaks
       the SAME readerAppearance JSON protocol (the Rust scheme mechanism:
       background !important on html, body transparent; typography on
       #content behind respectStyles), reports scroll-fraction
       relocations through AndroidReaderBridge, retries a cold cache
       (8×400 ms), carries auto-scroll with chapter advance. Chrome
       mirrors the EPUB reader: portrait bottom bar + bookBarMinimized
       pill + landscape PlaybackRail; the Contents sheet indents by the
       doc's own TOC depth. RECORDED DIVERGENCE: scrolled flow only (linux
       paginates bookdocs via foliate-js; column pagination is a
       follow-up) — the appearance sheet hides the Page-view switch and
       the paginated page-turn rows (new `showPagination` flag) and
       auto-scroll stays enabled there regardless of the shared flow
       pref. ScreenAwakeSurface.DOC_READER added.
     - BookDrop: `acceptedExtensions` += DocFamily.extensions (the old
       refusal comment said "the parsers here cannot read them" — they
       can now); .txt/.text keep landing as NOTES (the receiver routing
       wins). The four BookDrop/LocalSend pins that encoded the old
       refusal contract are re-pinned to the new one with the genuinely
       unlandable set (opus/ogg/oga).
     - BooksScreen picker: mobipocket/fictionbook/rtf/html/plain-text
       mimes.
     - **EPUB TOC FIX** ("in epub the toc chapters do not go to the
       linked chapters"): both spine hrefs and TOC hrefs go through the
       same resolve(), so the `#fragment` was the ONLY mismatch, and
       importEpub's `indexOfFirst { it == entry.href }` returned -1 for
       every ANCHORED TOC row — coerced to 0, so every anchored jump
       landed on chapter 1 (Calibre books anchor everything; that is the
       user's report). The spine match now strips the fragment; the FULL
       href rides the new `BookTocEntry.href`; the Contents sheet jumps
       anchored rows by href (readerGoToHref — epub.js resolves spine AND
       anchor) and plain rows by chapter index as before; the URLEncoder
       FORM encoding ("+" for space) at both href call sites became
       decode-safe percent form. BookTocEntry gained `depth` + `href`
       (additive defaults; book manifests are not an iOS JSON contract).
     - Bonus bug fixed en route: the EPUB reader's landscape-rail voice
       chip set `showVoicePicker` but never rendered VoicePickerSheet —
       the chip opened nothing (the PDF reader had the sheet; now both
       do).
  4. (real-file pins) — nine JVM pins over REAL calibre output (a hybrid
     MOBI6+KF8, a pure AZW3, a Gutenberg .txt, an RTF, an FB2, an .html,
     an .htmlz, an EPUB3 with anchored nav hrefs) under
     `app/src/test/resources/calibre/`. Four first-expectations failed,
     and the arbitration was running the RUST parsers on the same files
     (a temporary probe bin in sn-doc, built, run, DELETED — the sibling
     repo is back to clean): **the Kotlin output is byte-for-byte the
     Rust's on every file**, including the shapes that looked like bugs:
     calibre's publisher TOC offsets are the `<a filepos=…>` values and
     point at the chapter BODY one block past the styled heading, so
     every publisher-cut chapter ends with the next chapter's heading
     text (the reference's own snap+cut behavior — pinned so port drift
     cannot sneak in); the Gutenberg preamble stays as chapter 0; an RTF
     without `\info` has title=null. The parsers were NOT changed (the
     debug prints added during diagnosis round-tripped to zero diff).

  **Evidence:** 424 tests / 0 failures / 0 errors;
  `-Psplits=true assembleDebug` → app-x86_64-debug.apk (89 MB) +
  app-arm64-v8a-debug.apk (82 MB), both built AFTER the wiring landed
  (the only later main-source change is none — the diagnosis round-trip
  left BookMobiParser.kt identical, so the APKs are current).

  **DEVICE PASS PENDING — the one manual step:** Waydroid is RUNNING at
  192.168.240.112 (IP CHANGED from the .2 of past sessions) but adb says
  `unauthorized` — the container's Android shows the "Allow USB
  debugging?" dialog and it needs a human tap (the host adbkey is the
  same Oct 31 one; the approval just has to be re-granted for the new
  session). Tap Allow (ideally "Always allow") on the Waydroid window,
  then: `adb connect 192.168.240.112:5555`. Test corpus is ready under
  `/tmp/sn-bookf1/` (calibre-made: the-road.mobi hybrid, the-road.azw3,
  the-road.epub EPUB3 with anchored nav, gutenberg-style.txt, notes.rtf,
  story.fb2, plain.html, field-notes.htmlz). Checklist (from the
  handover): install the x86_64 APK; import one book per format (the
  proven push route is `research/localsend-probe.py` — BookDrop now
  accepts the doc extensions); check shelf covers + format labels
  (MOBI/AZW3/FB2/RTF/HTML/TXT); open each reader (the doc reader renders
  the parsed chapters, images included where the format carries them);
  TOC jumps — INCLUDING a fragment TOC row in the calibre EPUB (the TOC
  fix's whole point: anchored rows must land on their anchors, not
  chapter 1); TTS on a doc book (chapterText doc branch); narration jump
  from Contents; office-format regression (a .docx still converts to
  EPUB the old way); BookDrop receive of a .fb2.

  **ENVIRONMENT NOTES:** constrained gradle invocation is `flock
  /tmp/sn-gradle.lock ./gradlew --offline --no-daemon
  -Dorg.gradle.jvmargs=-Xmx1200m
  -Dkotlin.compiler.execution.strategy=in-process` — host RAM is 7.5G
  total and swap was 74% full at one point this session; a cargo release
  build of sn-doc COMPOUNDED with a gradle run and crashed the agent
  host once — NEVER run the Rust sibling's build and gradle in the same
  window, and stay at -Xmx1200m (1500m was the old value). Kotlin
  deprecations are ERRORS (`Byte.toChar()` banned — `(b.toInt() and
  0xFF).toChar()` is the form; `Char + Char` does NOT exist in Kotlin —
  only `Char.plus(Int)`/`Char.plus(String)`). `byteIndexOf` returns -1
  when not found (handle it — a debug loop once died on that).
  AFTER the device pass is green: merge `book-formats` to main and push
  (the user confirms the device pass first).

- **2026-11-15 — Unit G3: the sentence-gap tightening pass (chunk silence
  trim) — CODE-COMPLETE (282/282 green, both split APKs rebuilt); DEVICE
  VERDICT = the user's (their phone).** User verdict on G2 (phone,
  arm64): Phonon TTS "doing significantly better almost perfect … just
  needs to reduce the gap between sentences/chunks a bit more". Their log
  CONFIRMS G2 works — Phonon held 6→36 s banked ahead, `bridge=off` on
  every chunk, the hold-gate engaging at the 30 s target, zero dry-bank
  events — so the remaining gap is NOT the pipeline: it lives INSIDE the
  PCM. Every chunk starts from a fresh model state and runs to EOS, so
  each chunk carries the model's own lead-in + trail-off silence (Phonon's
  EOS countdown keeps a whole 80 ms frame past the crossing — see
  ChunkBoundaryFades), which stacks onto the sentence pause.
  - **New pure `speech/ChunkSilenceTrim.kt`** — trims both ends down to
    natural pads: samples below 0.004 amplitude (≈ −48 dBFS — room tone
    and EOS tail-off, never consonants at −40..−35 dB) are cut, always
    LEAVING 120 ms of tail pad and 40 ms of head pad beyond the last/first
    audible sample, so a word's decay is never clipped and the
    inter-sentence gap lands near ~160 ms. An entirely-silent chunk is
    returned untouched (never a zero-frame array — synthesis anomalies
    belong to the failed-chunk path); the input array is never mutated;
    nothing to trim returns the SAME array. **Recorded divergence: iOS
    (head 1c2a6ad, after cf501b2) has no PCM trim — SpeechSanitizer is
    text-only; this pass is Android-first per the user's device report.**
  - **Wired at BOTH chunk sinks** — the playback producer and exportWav,
    trim THEN fades (the fades ride the trimmed edges), all after the
    wall-clock read so RTF stays inference-only and `audio=` now reflects
    the audio actually delivered. The timeline line (playback) and export
    line carry a `trim=%.2fs (head %.2f / tail %.2f)` stat whenever the
    pass took anything off — the numbers to read next session to see how
    much silence the model was actually leaving. ChunkTimeline's API
    unchanged (5 pins intact); the stat is an appended fragment.
  - **8 new ChunkSilenceTrimTest JVM pins** (pad arithmetic, above/below-
    threshold tail, all-silent untouched, input immutability, degenerate
    rate/chunk, short-chunk pad clamp, custom pads). `testDebugUnitTest`
    → **282 tests / 0 failures / 0 errors** (274 prior + 8 new);
    `-Psplits=true assembleDebug` → fresh `app-arm64-v8a-debug.apk`
    (82.4 MB, the user's phone build — ChunkSilenceTrim confirmed in
    classes12.dex) + `app-x86_64-debug.apk` (89.3 MB, Waydroid).
  - If the user wants the pause tighter/looser after hearing it: the two
    dials are `DEFAULT_TAIL_PAD_SECONDS` (0.12) and
    `DEFAULT_HEAD_PAD_SECONDS` (0.04) in ChunkSilenceTrim — one-constant
    retune, no rework.
- **2026-11-15 — Unit G2: the REAL render-ahead bank + writer thread —
  CODE-COMPLETE (274/274 green, x86_64 APK built); DEVICE PASS = the
  user's own hardware test.** The chunk-gap diagnosis (User Feedback
  Pending, top item) landed as a rewrite of the streaming core's
  scheduling half — shared by ALL streaming engines (Phonon/Kokoro/
  Supertonic), exactly like GO1:
  - **New `speech/PcmBank.kt` (pure, JVM-tested)** — the bounded FIFO of
    rendered chunks between synthesis and the AudioTrack. The producer
    appends a rendered chunk and immediately renders the next one
    (`bankChunk`); a per-session WRITER THREAD (`writerLoop`) drains the
    bank into the track segment by segment. Rendering now OVERLAPS
    playback instead of trailing it — the serialization that produced
    `render − 0.68 s` dead air at every sentence boundary is gone by
    construction. Capacity = bank target + 30 s slack (~2.9 MB at 24 kHz);
    R8's 32 KB HAL cap STAYS (the depth moved to Java heap, where the
    render-ahead policy always meant it to live — iOS's ring-buffer shape).
  - **`bankedSeconds()` = bank + FIFO** — the 30 s nominal target is
    reachable for the first time; the hold-gate actually engages and the
    producer banks ~30 s ahead, then paces.
  - **GO1's bridge is finally REACHABLE**: it moved INTO the writer as the
    dry branch (`bridgeTick`) — bank empty + chunks still coming + FIFO
    nearly dry → feed one 0.2 s silence slice, re-checked every writer
    round (≤ 50 ms). The old admission-time arming never fired (at
    admission the FIFO was always ~full → `bridge=off` on every timeline
    line while the air was dead). No separate feeder thread, no join, no
    interleave hazard; the "keepalive bridge ended" log fires when real
    audio resumes.
  - **ChunkSchedule stamps at bank admission**, not write time — the old
    stamp made wait = write(0) + render(1) (structurally ALWAYS "second
    buffer LATE", ratcheting to a 30 s target the 180-char ceiling can
    never deliver). Honest wait ≈ render(1) only; the schedule settles.
  - **Fixes riding the same unit:** (1) progress/played-chars/SPEAKING now
    fire when a segment's FIRST bytes enter the FIFO — the read-along
    highlight advances with the sounding sentence instead of a full chunk
    late (behavior change, recorded); (2) the last-chunk-render-FAILED
    path registers the completion poller via new producer-side
    `totalPlannedFrames` — the old code hung at SPEAKING there; (3) all
    track writes are runCatching-guarded (a stop() releasing the track
    mid-write could kill the generate-queue runnable); (4) a dead-sink
    guard on the writer's full-FIFO spin (the producer parks in the bank
    valve by then, so the writer owns that escape); (5) setSampleRate
    drains the bank (old-rate banked audio would play at the wrong pitch).
  - **Deleted:** `schedule()`, `bridgeIfDry`, `bridgeFeederBody`,
    `stopBridgeFeeder`, `writeRemainder`, the feeder fields — the
    producer's blocked write loop is the whole bug class, gone.
  - **9 new PcmBank JVM tests** (FIFO order, payload, depth, empty-bank
    wait/wake, capacity backpressure, cancellation, drain) — one taught a
    real lesson: `Object.wait(0)` waits FOREVER, so `PcmBank.take`
    explicitly treats a non-positive wait as no-wait. `testDebugUnitTest`
    → **274 tests / 0 failures / 0 errors** (265 prior + 9 new).
  - **Evidence:** BUILD SUCCESSFUL testDebugUnitTest (274/0/0 from the
    JUnit XML) + `-Psplits=true assembleDebug` → fresh
    `app-x86_64-debug.apk` (89.3 MB; PcmBank confirmed in classes12.dex).
    **BUILD-INVOCATION CORRECTION for everyone:** `assembleDebugX86_64`
    does NOT exist in this AGP setup (tasks list has no per-ABI
    assembles even with `-Psplits=true`) — the per-ABI APKs are produced
    by **`./gradlew -Psplits=true assembleDebug`**, which emits
    `app-x86_64-debug.apk` + `app-arm64-v8a-debug.apk` directly (the
    arm64 one is what the user tests on their phone). AGENTS.md
    documents the old name (frozen — proposing the change here instead).
  - **Device pass:** none this session by instruction — the user tests on
    their own hardware. What to look for: chunk timeline lines with
    `banked=` after-figures well above 0.7 s (the bank working),
    `bridge=on` only during genuinely slow renders, no "second buffer was
    LATE" ratchet, and above all NO dead air between sentences.
- **2026-11-15 — Unit PDF-L2: SCROLL MODE for the PDF reader —
  CODE-COMPLETE (265/265 green, x86_64 APK built); DEVICE PASS PENDING.**
  User request: "add scroll mode to speechnotes-android for pdf files". iOS
  reference: BookPDFReaderView pins `pdfView.displayMode =
  .singlePageContinuous` — continuous scroll is iOS's ONLY mode — so scroll
  ships as the DEFAULT, and the old one-page reader stays behind a switch
  (it is the only one that can host pinch-zoom, which a LazyColumn of pages
  cannot). Implementation: `pdfSurface` now branches — scroll mode is a
  LazyColumn of width-fit page items (8dp gaps), placeholders at the common
  page aspect so the scroll position never jumps as bitmaps arrive; a
  width-fit WINDOW RENDERER renders visible ±1 pages off-main at the surface
  width (onSizeChanged) into the shared `bitmaps` map with an LRU cap
  (PDF_SCROLL_BITMAP_CAP 8, farthest-from-window evicted + recycled); a PAGE
  TRACKER derives pageIndex from the item whose bounds cross the viewport
  center (iOS PDFViewPageChanged parity) and persists the position as it
  moves; an INITIAL-POSITION effect opens the column at the saved page when
  the count lands or the mode switches on; a one-shot jumpRequest channel
  carries slider/stepper/Contents jumps to scrollToItem (instant — iOS
  go(to:) semantics; the tracker writes pageIndex WITHOUT a jump so the
  reader never chases its own tail). All existing navigation now goes
  through `stepPage`/`scrollToPage` locals (slider, portrait bar chevrons,
  pill steppers, rail steppers, Contents rows) — they work in BOTH modes
  (single-page ignores the jump; the bitmap is just shown). The single-page
  render effect is gated `!scrollMode` and re-keyed on it. Toggle: a
  "Continuous scroll" row (Switch) in the Contents sheet, above Force-OCR;
  global pref `pdfScrollMode` default TRUE (Android-only key — iOS has no
  toggle, nothing to share; recorded divergence: no pinch-zoom in scroll
  mode — feasible follow-up if wanted). Landscape (PDF-L1) is untouched:
  the rail + no-bottom-band layout works identically over the scrolling
  column, and the rail's "Page X/Y" stepper tracks the scroll live.
  **Evidence:** testDebugUnitTest → **265/265**, x86_64 debug APK built
  (89 MB). Device pass pending (Waydroid container still STOPPED, root
  restart still the blocker): open a PDF → pages flow in one column,
  scroll updates the rail stepper label + persists position, stepper/
  slider/Contents land the column, Contents sheet switch flips to
  one-page mode with pinch-zoom intact, landscape = page column + rail.
- **2026-11-15 — Unit PDF-L1: the PDF reader's landscape = iOS round 6 —
  CODE-COMPLETE (265/265 green, x86_64 APK built); DEVICE PASS PENDING.**
  User feedback (see User Feedback Pending): landscape PDF viewing space
  severely reduced by the bottom bar (slider + steppers + Contents) that
  duplicates the rail. Ported iOS `BookPDFReaderView.readerLayout` round 6
  verbatim in shape: **in landscape the bottom band is GONE** — iOS's own
  comment: "the bottom page-number band is GONE in landscape — it covered
  the page it named" — and the layout is the iOS `HStack { readerSurface;
  railPlayerBar }`: `Row { Box(weight 1f) { pdfSurface() }; PlaybackRail }`.
  One `pdfSurface` lambda (the pinch-zoom bitmap or spinner) is now the
  single reader-surface identity shared by both orientation branches (iOS's
  single `readerSurface`). Changes, per the user's five items: (1) landscape
  page space = full height leading of the rail — no bottom bar, no pill
  (the portrait `bookBarMinimized` pill never applies in landscape on iOS
  either; the rail's own `landscapeRailMinimized` collapse is the landscape
  minimizer and keeps its collapsibility); (2) the page slider is portrait-
  only — landscape navigates by the rail's prev/next stepper and the
  Contents sheet (iOS has no page slider anywhere; page turning is PDFKit's
  scroll/swipe there); (3) **Contents moved to the TITLE ROW** — a TopAppBar
  `actions` item for PDF (iOS `ToolbarItem(placement: .topBarTrailing)`
  "Contents", both orientations), the `showOutline` flag hoisted into
  `BookReaderScreen` and passed down; the bottom row's Contents button is
  deleted; (4) no prev/next duplication — the bottom bar that carried the
  duplicates does not exist in landscape; (5) the tautological `!landscape`
  guards on the portrait bar's play/stop/minimize buttons are deleted (the
  bar now only renders in the portrait branch). **Recorded divergences:**
  the rail steps PAGES (iOS steps CHAPTERS because PDFKit's scroll/swipe
  turns pages; Android renders single-page bitmaps with no swipe-turn
  gesture, so the rail is the only page stepper in landscape), and the rail
  shows for chapter-less PDFs too (iOS gates on `hasChapters` — Android's
  rail is their only play affordance). Portrait layout is unchanged (slider
  + steppers + play/stop/minimize + pill). **Evidence:** `testDebugUnitTest`
  → **265/265** (pure UI diff — no logic touched), x86_64 debug APK built
  (89 MB). Device pass pending: Waydroid container STOPPED and
  `waydroid container start` needs the user's root password (verified this
  session: "Action 'container' needs root access") — after it boots, open a
  PDF in landscape: the page must fill the screen leading of the rail, no
  bottom band, Contents in the title row (portrait too), rail
  collapse/expand still works, portrait regression (slider + bar + pill).
- **2026-11-14 — Unit GO1: the Phonon click+gap port (iOS `cf501b2`) —
  CODE-COMPLETE (265/265 green, x86_64 APK built); DEVICE PASS PENDING.**
  Two changes in the shared streaming core, one per iOS half:
  - **Click → new pure `speech/ChunkBoundaryFades.kt`** —
    `apply(samples, sampleRate)`: raised-cosine 5 ms in / 12 ms out
    (iOS constants verbatim), fade lengths clamped to half the chunk, the
    input array never mutated. Applied at BOTH chunk sinks — the speak
    loop (AFTER the wall-clock measurement so the per-chunk RTF line stays
    inference-only) and `exportWav` (same fades, so an exported WAV does
    not carry the click its live twin no longer has). 7 new
    `ChunkBoundaryFadesTest` pins (zero at both ends, exact raised-cosine
    factors mid-ramp, middle untouched, envelope never exceeded, degenerate
    clamps, empty/invalid-rate passthrough, input immutability).
  - **Gap → the bridge is now a ROLLING FEEDER.** Old behavior:
    `bridgeIfDry` pre-wrote ONE keepalive of
    `max(0.5, lastRender + 1.0)` s — the sink never drained, but every
    unconsumed second of that silence sat in the FIFO AHEAD of the chunk,
    so each dry-bank resume carried ~1 s of dead air after the chunk was
    already synthesized. New behavior (iOS cf501b2's completion re-arms,
    translated — the divergence is only where the re-arm runs): arming
    starts a small feeder thread that writes
    `RenderAheadBankPolicy.BRIDGE_SLICE_SECONDS` = **0.2 s** slices while
    the bank is dry and the render is in flight (re-checking the dry
    condition between slices; a FRESH stop flag per arm so a late-exiting
    predecessor can never be revived), and the producer STOPS and JOINS it
    before writing the chunk — silence and chunk audio never interleave in
    the FIFO, the track never drains (the frozen-head pathology stays
    fixed), and the dead air is bounded by one slice remainder (≤ 0.2 s).
    `RenderAheadBankPolicy`: `bridgeSilenceSeconds()` +
    `BRIDGE_MARGIN_SECONDS` DELETED (dead with the one-shot design —
    `BRIDGE_MARGIN_SECONDS`'s outlast-the-render requirement now lives in
    the schedule() prebuffer, unchanged); `totalFramesWritten` is
    `@Volatile` (feeder writes it); `stop()` flips the feeder's flag and
    drops it without joining (stop() runs on main; the generation guard
    exits the feeder within one round). The 120 s session cap, the
    incident ledger, the `bridge=on` timeline fact and the
    `keepalive bridge ended` log all keep their semantics; the feeder's
    slice write catches `IllegalStateException` (track released under it
    by stop()/sample-rate change) and logs the failure only when its
    session is still live.
  - **Evidence:** constrained gradle (flock, --no-daemon, 1 worker,
    in-process Kotlin) → BUILD SUCCESSFUL, test-results XML =
    **265 tests / 0 failures / 0 errors** (258 prior + 7 new − 1 replaced
    bridge-sizing pin → `BRIDGE_SLICE_SECONDS` = 0.2 iOS pin), x86_64
    debug APK built (89 MB). NO device pass yet: the Waydroid container is
    down (needs the user's root restart) — the audible click/gap verdict
    is the user's on real hardware regardless (Waydroid audio-HAL
    caveat).
- **BRANCH `phonon-tts` (2026-11-10) — the Phonon engine wave, off `main`
  at `1025854`.** All Phonon units landed here; MERGED to main after P4
  verified (branch kept for revert). Rationale: user asked for easy revert.
- **2026-11-11 — Unit P4: the Waydroid device pass — DONE. PHONON SPEAKS
  ON WAYDROID.** Evidence (logcat, pid 5927/6514, 2026-11-11):
  - **Download:** the full ~192 MB set (2 graphs + external weights +
    tokenizer + 26 voice bins) over the flaky HF CDN — two
    `StreamResetException: stream was reset: CANCEL` resets (33 MB into
    model.onnx_data, 29 MB into mimi_decoder.onnx_data) both recovered by
    the ModelManager resume/retry; validity gates all passed.
  - **THE LOAD-BEARING CHECKPOINT RESOLVED: both fp32 graphs LOAD on
    ORT-Android 1.30.0** (the full Maven AAR ships the com.microsoft
    contrib ops — GroupQueryAttention/RotaryEmbedding/QuickGelu — and
    accepts zero-size input tensors; empirically proven, not from docs).
  - **Synthesis:** `PhononEngine: 44 tokens → 10.24s audio (128 frames, 16
    calls) in 2.15s, eos peak 9.74 at call 15 (Default)` — the prefill-once
    loop end-to-end with the healthy deep-negative-then-spike EOS
    signature; `GENERATING → SPEAKING` → natural `→ IDLE`.
  - **SPEED: per-chunk RTF 0.21 (speak) / 0.26 (export)** on the container
    — ~4.7× faster than realtime, vs Kokoro-small's measured RTF ≈ 1.9 on
    the same box. Phonon is by far the fastest neural engine here. Unit
    0d's ChunkTimeline covered it with zero extra code.
  - **WAV export:** `export 1/1: chars=110 audio=9.60s render=2.52s
    rtf=0.26` — non-trivial audio; the share chooser showed the container's
    "No apps can perform this action" (no share targets on bare Waydroid —
    container artifact, not an export failure).
  - **Memory rules on device:** engine-switch unload fired (Supertonic →
    phonon), and the 5-min idle unload unloaded Phonon's sessions
    (`21:34:32 model unloaded (idle)`).
  - Audible confirmation stays with the user on real hardware (Waydroid
    audio-HAL caveat; the drain ended ~0.7 s after SPEAKING — the known
    container pathology).
  - **Two device-found bugs fixed in this unit:** (1) the tokenizer path —
    iOS's downloader maps EVERY non-voice file into `Phonon/onnx/`
    (`(subpath as NSString).lastPathComponent` into phononOnnxDirectory),
    the Android ModelSet had tokenizer.json at the Phonon root → ENOENT on
    first play; ModelFiles now uses `onnx/tokenizer.json` (iOS layout
    parity). (2) `SpeechPlayer.export()`'s `when(engine)` had NO
    PhononEngine branch — the WAV export silently reported the system-voice
    failure; the branch is added (mirrors Kokoro's). Also fixed mid-pass:
    **Android's `Pattern.compile` REJECTS `UNICODE_CHARACTER_CLASS` with
    "Unsupported flags: 256"** (flag 0x100 = 256 — a desktop-JVM-only
    flag; the JVM tests passed on the host and the device round caught it
    — recorded in PhononNormalizerTable's doc) → the table now carries the
    EXPLICIT reference whitespace set (Rust regex's \s = White_Space
    property) instead of `\s`/`\S`, identical on the desktop JVM, on
    Android, and in the reference; 258/258 stayed green. The load-failure
    log now carries the exception class + top frames (the device round
    diagnosed "Unsupported flags: 256" from a bare message the first
    time).
  - Device state left: Phonon engine selected + downloaded + Ready; the
    test note played; share sheet dismissed.
- **2026-11-11 — Unit P3: PhononEngine (the WORKING prefill-once recipe)
  + full player wiring — CODE-COMPLETE (258/258 green, x86_64 APK 89 MB);
  DEVICE CHECKPOINT PENDING.** Ported from speechnotes-iOS
  `PhononEngine.swift` AT COMMIT `75ff1ba` — the user confirmed Phonon
  works on iOS there and told us to use the latest. The contract CHANGED
  under us mid-wave (iOS commits 1b232f5 + 75ff1ba, 2026-11-11):
  **prefill-once** — call 0 prefills voice+text against an EMPTY cache,
  every present.* is fed back WHOLE (prompt K/V frozen forever), calls 1+
  feed an EMPTY prompt (`input_ids` [1,0], voice [1,0,512] zero-size
  tensors) and positions collapse to arange(kv_rows, kv_rows+8). The old
  re-run-prompt recipe caused the sentence-end long tail (layers 1–5
  attended to latents AFTER them; 8/120 degenerate x86 runs → 0/120).
  Whole-present feedback DELETES the per-head KV copy — the head-major
  striding bug class is gone. Two counters remain (kvRows: P+7 after
  prefill, +8/call; latentCount: +8/call). Soft-EOS insurance: 2
  consecutive calls with per-call eos peak > 1.0 without a real crossing
  stops the chunk; budget stays hub ×1.5; non-finite latent guard; eos
  peak logged. Engine: `speech/PhononEngine.kt` (tensor plumbing via
  direct native-order ByteBuffers; the previous result's present.* tensors
  ARE the next call's past.* inputs — previous result closes when the new
  one exists; SplitMix64+Box-Muller noise identical to iOS — Kotlin needs
  `uL` for the over-Long hex constants). Wiring: SpeechPlayer phonon()
  singleton + resolveEngine + rebuildEngine fallback/unload + setVoice +
  idle-unload + shutdown + voice description (P2's temporary system()
  routing is gone). **NEXT (P4): the load-bearing device checkpoint —
  install on Waydroid, download the ~192 MB set, select Phonon, play:
  (a) ORT-Android 1.30.0 must load the graphs (com.microsoft contrib ops:
  GroupQueryAttention/RotaryEmbedding/QuickGelu — the full Maven AAR is
  documented as the full build, unproven on device); (b) zero-size input
  tensors must be accepted; (c) GENERATING → SPEAKING with rtf= lines;
  (d) WAV export non-trivial.** If (a) fails: fallback = the fuller
  onnxruntime-training-android AAR (documented, not built speculatively).
- **2026-11-11 — Unit P2: Phonon model plumbing + settings UI — DONE
  (258/258 green, x86_64 APK 89 MB).** `EngineKind.PHONON` ("Phonon —
  small · English (~192 MB)", picker slot between Kokoro and Supertonic =
  iOS order); `AppFiles.phonon{,Onnx,Voices}Dir` ("Phonon/onnx",
  "Phonon/voices", iOS parity); `ModelFiles.phonon` (hub
  `onnx-community/phonon-31m-en-ONNX`: 2 graphs + external weights +
  tokenizer + **26 voice bins gated EXACTLY 256,000 bytes** min=max so
  truncation never counts as complete); `ModelManager._phononState` +
  refresh/isReadyCached/stateFlowFor branches + **deleteModel guard fix**
  (Phonon's tokenizer.json/voices must NOT hit the shared-Kokoro-file
  exclusion); `VoiceCatalog.phononVoiceIds` + 26 descriptors (iOS
  VoiceCatalog verbatim); `Prefs.PHONON_VOICE = "phononVoice"` +
  `"recentPhononVoices"` (iOS-shared key names); SpeechPlayer
  `phononVoice` StateFlow + setVoice/recents/audition/description
  branches. **P2 semantics: PHONON is selectable/downloadable/configurable
  but playback routes to system() (usingSystemFallback=true
  unconditionally) until Unit P3 wires PhononEngine** — recorded in code
  comments at both sites. UI: SpeechSettingsScreen (readyNow, VoiceRow,
  ModelDownloadRow, phononState collect) + VoicePickerSheet ("Phonon
  voice" title + phononVoice active-voice flow) — descriptors(route) by
  engine.key made phonon work there with no extra UI code. Next: P3.
- **2026-11-10 — Unit P1: the Phonon tokenizer + normalizer in `logic/`
  — DONE (258/258 green, x86_64 APK 89 MB).** New
  `logic/PhononNormalizerTable.kt` (the hub's 191-step chain as ordered
  data, verbatim port of the iOS generated table) and
  `logic/PhononTokenizer.kt` (Metaspace pre-tokenizer + Unigram Viterbi +
  byte fallback, ported from `PhononTokenizer.swift` @ `49e8ab7`; strict
  UTF-8 decode so invalid byte runs skip pieces exactly like Swift's
  `String(bytes:encoding:)`; Prepend-skips-empty guarded). Parity: the SAME
  fixture files as iOS now live in `app/src/test/resources/`
  (`phonon-tokenizer.json` = the real hub tokenizer, 169,734 bytes;
  `phonon-tokenizer-fixtures.json` = the 14 reference-verified cases) →
  Android/iOS/reference emit identical ids by construction. 5 new
  `PhononTokenizerTest` tests. One port translation recorded: ICU
  `\p{Alphabetic}` → Java `\p{IsAlphabetic}` (same property; first test run
  failed on it, fixtures pin the behavior). Next: Unit P2.
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

- **2026-11-19 HANDOVER (Units NANO-1 + NANO-2 — nano-edge-tts in, device
  pass DONE).** The unit is committed on branch `nano-edge-tts` (off
  `book-formats`; do NOT merge before `book-formats` itself clears its
  device pass + user confirmation). 440/440 green, both split APKs built,
  x86_64 installed on Waydroid and verified end-to-end (evidence in the
  In-Flight entry). Device left: Nano Edge selected + downloaded (Ready),
  one scratch note holds the test sentence, Render markdown pref OFF, adb
  to 192.168.240.112:5555 was authorized the whole session (no manual tap
  needed). Open threads elsewhere, unchanged: the BOOK-F1 device pass (one
  adb tap + corpus recipe in the BOOK-F1 In-Flight entry), G2/G3's audible
  verdict (user's phone), 0e/0f.
- **2026-11-18 HANDOVER (Unit RAL-1 CLOSE-OUT — both read-along follow bugs
  fixed and device-verified).** The unit is committed on `book-formats`
  (see the RAL-1 CLOSE-OUT In-Flight entry for the two root causes and the
  evidence): 424/424 green, both split APKs built, the x86_64 one
  installed + passed on Waydroid (chapter 8/29 of Think Faster narrated
  ~4 min across the verify rounds; the sounding sentence stayed around
  the viewport center with the chapter heading scrolled off). Device
  left: reader at chapter 8/29, Phonon selected + downloaded, read-along
  pref ON, a mid-chapter-8 bookmark saved (chars=7606/31200, full-text
  contract — resumes on the right sentence). Waydroid session left
  RUNNING; read the IP from `waydroid status` next session (this session
  it came up in ~20 s and adb authorized without a manual tap). Still
  pending elsewhere, unchanged: the BOOK-F1 device pass (corpus recipe in
  the BOOK-F1 In-Flight entry) and the `book-formats` → main merge after
  the user confirms it.
- **2026-11-15 HANDOVER (Unit G3 — chunk silence trim; user tests on their
  phone).** G3 rides on G2 (both in the tree, uncommitted — push waits for
  the user's audible verdict): **282/282 JVM green**, fresh
  `app-arm64-v8a-debug.apk` (82.4 MB, 04:56 — the user's phone build) +
  `app-x86_64-debug.apk`, built with `./gradlew -Psplits=true
  assembleDebug`. G2 was device-CONFIRMED by the user ("significantly
  better almost perfect"); G3 trims the model's own lead-in/EOS trail-off
  silence per chunk (120 ms tail pad + 40 ms head pad, −48 dBFS threshold)
  — the remaining perceived sentence gap. If the user wants it tighter or
  looser, retune `DEFAULT_TAIL_PAD_SECONDS`/`DEFAULT_HEAD_PAD_SECONDS` in
  ChunkSilenceTrim (one-constant change); the per-chunk `trim=` stat on
  the timeline line shows how much the model was actually leaving.
  If a gap STILL remains after G3, read the timeline lines: `banked=`
  after-figures ~0.7 s would mean a bank/writer regression; rtf > 1 means
  inference-bound physics (Supertonic measured RTF 2.4–4.3 on the user's
  phone this session — that one is physics, the 0e XNNPACK item owns it).
  Next queued unit regardless: the EPUB reader JS recursion storm
  (index-android.html:52, RangeError flood, EPUB-F1-era code).
- **2026-11-15 HANDOVER (Unit G2 — the real render-ahead bank + writer
  thread; user tests on their own hardware).** G2 is code-complete on
  main's tree: **274/274 JVM green** (265 prior + 9 new PcmBank tests),
  fresh `app-x86_64-debug.apk` (89.3 MB) built with
  **`./gradlew -Psplits=true assembleDebug`** (the `assembleDebugX86_64`
  task name in AGENTS.md does not exist in this AGP setup — see the G2
  In-Flight entry's build-invocation correction). The user was told the
  APK path and tests themselves — NO Waydroid pass from the agent this
  session. Nothing was committed (push waits for the user's audible
  verdict per the "git push after verify" rule). If the user still hears
  breaks, read the per-chunk timeline lines first: `banked=` after-figures
  stuck at ~0.7 s would mean the bank isn't filling (writer thread
  wedged — check for a `-writer` thread / errors), `bridge=on` on
  non-dry banks would mean the tick mis-fires, and rtf > 1 lines mean
  inference-bound physics (CPU contention — check the EPUB JS storm).
  Next queued unit regardless: the EPUB reader JS recursion storm
  (index-android.html:52, RangeError flood, EPUB-F1-era code).
- **2026-11-15 HANDOVER (Unit PDF-L2 — scroll mode; PDF-L1, GO1 + EPUB-F1
  device passes still pending).** PDF-L2 is code-complete, 265/265 green,
  x86_64 debug APK built (89 MB). **ALL FOUR device passes wait on the SAME
  blocked resource: the Waydroid container is STOPPED and `waydroid
  container start` errors "needs root access" (re-verified this session) —
  the user must restart it.** After it boots: `adb connect
  $(waydroid status | awk '/IP address/{print $3}'):5555`, install the
  current `app-x86_64-debug.apk` (push + `pm install -r`, md5 the installed
  `base.apk` against your output first), then:
  - **PDF-L2 (this unit):** open a PDF — pages flow in one continuous
    column (scroll is the DEFAULT; the Contents sheet's "Continuous scroll"
    switch restores one-page mode with pinch-zoom); scroll updates the rail/
    bar "Page X/Y" labels and persists the position; steppers/slider/
    Contents land the column on the page; landscape = scrolling column +
    rail, no bottom band (PDF-L1 unchanged).
  - **PDF-L1:** open a PDF, rotate landscape — the page must
    fill ALL the height leading of the rail (no bottom band, no slider, no
    pill), the rail carries prev/next page stepping + voice + play + rate +
    its own collapse, Contents lives in the TITLE ROW (top-right icon,
    portrait as well), tapping it opens the outline sheet, and portrait
    still has slider + steppers + play/stop/minimize + pill.
  - **GO1:** Phonon play → logcat `keepalive bridge — … feeding 0.20s
    silence slices`, gap between sentences ≤ 0.2 s; click is the user's
    audible call on real hardware.
  - **EPUB-F1:** First Aid EPUB — page fits screen width edge-to-edge,
    wide tables pan inside the page, themes beat book body styles.

- **2026-11-14 HANDOVER (Unit GO1 — Phonon click+gap port; EPUB-F1 device
  pass still pending).** GO1 is committed on main; the tree is green
  (265/265) with the x86_64 debug APK built. **TWO device passes now wait
  on the same blocked resource: the Waydroid container is DOWN
  (`waydroid status`: Session STOPPED, Container STOPPED) and
  `waydroid container start` needs the user's root password — the user was
  asked to run it (or restart Waydroid); nothing else recovers it.** After
  it boots: `adb connect $(waydroid status | awk '/IP address/{print
  $3}'):5555`, install the current `app-x86_64-debug.apk` (push +
  `pm install -r`, and md5 the installed `base.apk` against your output
  first — silent overwrites have cost two sessions a false detour), then:
  - **GO1 (click+gap):** select Phonon (already downloaded + Ready from
    P4), play the test note, and read the logcat under `Speechnotes`: the
    per-chunk `chunk i/N` timeline lines stay the same shape; a dry-bank
    resume now logs `keepalive bridge — … feeding 0.20s silence slices`
    and the gap between sentences should shrink to ≤ 0.2 s (was ~1 s on
    slow renders). The click fix is audible only on real hardware — the
    user confirms; Waydroid's evidence is that synthesis/playback run
    clean with no new error lines and WAV export still produces non-trivial
    audio (`export 1/1: … rtf=`).
  - **EPUB-F1 (from the 2026-11-12 unit, commit `5d37645`):** open the
    First Aid EPUB — page fits the screen width edge-to-edge in scrolled
    flow (no sideways pan), Pages mode likewise, a wide table pans INSIDE
    the page, dark/sepia themes paint the page even where the book paints
    its own background, and First Aid's blue/red banners show by default
    (respectStyles now defaults true). NOTE: existing users who ever
    touched an appearance setting keep their stored prefs; the new
    defaults land for fresh/default state.
  - **Releases already handed over before verify (user asked):** GO1's
    pair `Speechnotes-0.1.5-clickgap-{arm64,x86_64}-release.apk` in
    ~/Downloads (md5s 9e3b07dc1244fa0d96770201ed1594f8 /
    926c2d32becc8e5c3f172d6c268ab043, aapt2-verified 0.1.5 / code 7 +
    ABIs; the new `feeding %.2fs silence slices` log string confirmed in
    classes.dex) — this pair SUPERSEDES the EPUB-F1 pair
    (`Speechnotes-0.1.5-epubfix-…`, md5s 2b0f6eae… / 8f305d7d…) since it
    carries both units. NOT PUSHED: both units wait for their device pass
    per the "git push after verify" rule.

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

- **2026-11-20 — Unit SEL-1: the markdown preview is selectable — Copy out
  of the reading view — DONE (440/440 green, both split APKs built,
  Waydroid-verified end-to-end).** First unit from the 2026-11-20
  context-menu/copy-paste audit (User Feedback Pending top item, gap A1).
  The preview was the one surface nothing could be copied out of — the edit
  field had its SelectionContainer, the rendered blocks sat outside one, so
  long-press did nothing there (the Linux audit's finding #3, mirrored on
  Android). Change: MarkdownPreview's block list (headings, paragraphs,
  lists, quotes, code blocks, table cells) is now wrapped in ONE
  SelectionContainer inside the scroll column (NoteEditorScreen.kt,
  MarkdownPreview) — long-press starts a selection, the system toolbar
  offers Copy / Select all, and the selection spans blocks (one container,
  not per-block). Image tap-to-zoom keeps working (selection only claims
  long-press/handle drags) and the zoomed overlay renders outside the wrap.
  Image-bearing notes were not exercised on the container (none exists
  there); the safety is structural — SelectionContainer does not consume
  taps.
  **Device evidence (Waydroid, landscape):** open the markdown scratch note
  → preview → long-press on body text → selection handles + Copy/Select all
  toolbar; Copy → switch to edit → Paste → the pasted word ("verification")
  appeared in the note text = the clipboard round-trip proven; Select all →
  the whole preview highlights as ONE selection with a bare Copy chip; the
  note text was restored to its original 12 words afterward. Logcat clean
  through all flows (only container noise: OpenGLRenderer/cpufreq).
  `testDebugUnitTest` → **440/440**; `-Psplits=true assembleDebug` →
  app-x86_64-debug.apk (89.3 MB) + app-arm64-v8a-debug.apk (82.4 MB).
  **Observed pre-existing, NOT this unit:** the preview's first paragraph
  renders overlapping the heading in this note — cosmetic, unrelated to
  selection (SelectionContainer is gesture-only; no layout path touched);
  noted for a polish unit. Next: SEL-2 (read-along copy), MENU-1 (notes
  menu Delete+Share), BOOK-1 (book long-press + Add-a-goal), PDF-COPY,
  EPUB-VERIFY.

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
