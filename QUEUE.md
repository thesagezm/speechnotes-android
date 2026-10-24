# QUEUE.md

Working memory for the Speechnotes-Android build pipeline. Owned by the agent.
Updated at the end of every unit (and in the same commit as the work it
describes once the repo is on GitHub).

## Current Phase

**The Great Rewrite.** TTS fixed (Unit 1). Perf pass (Unit 2, release cold
start 370 ms). Settings subpages (Unit 3). Kokoro-small quantized (Unit 4).
**GitHub push DONE 2026-10-24 (Unit 5):**
https://github.com/thesagezm/speechnotes-android (private, branch `main`).
Now: **settings parity with iOS** per the user's 2026-10-24 instruction —
"make sure all the settings (including stats) are on par to Speechnotes iOS
or better; when they work and the app is also very fast, ask me and I can
allow you to release a prerelease v0.0.1". Units 7–10 below close the gaps,
then Waydroid verification, then ASK before the v0.0.1 tag.

## User Feedback Pending

1. **"make sure all the settings (including stats) are on par to speechnotes
   ios or better … then ask me … prerelease v0.0.1"** — IN PROGRESS (Units
   6–10). Audit done (Unit 6 below); gaps S1–S3, T1–T5, ST1, E1 queued.
2. **"The android app is the slowest"** — ADDRESSED 2026-10-24 (Unit 2):
   cold start on Waydroid debug 1715 ms → ~1380 ms; **R8 release 370 ms**.
   User's hands-on verdict pending.
3. **"The tts is also not working"** — RESOLVED 2026-10-23 (Unit 1). User's
   own play at 03:30 (Waydroid clock) on 2026-10-24 completed naturally.
4. **"The buttons dont feel right"** — root cause fixed in Unit 1; the new
   Settings subpages + instant radio/toggle flips verified 2026-10-24.
5. **"switch the small kokoro model just as i did in speechnotes-ios … the
   source for kokoro small is a smaller engine no longer the 177mb file"**
   — DONE 2026-10-24 (Unit 4). The ~92 MB download has NOT run yet
   (container offline): next launch with network, Settings → Speech →
   Kokoro (small) → Download.

## GitHub Gate status

**CLOSED.** Repo live: `github.com/thesagezm/speechnotes-android` (private,
main, commit 6713619 = Units 1–4). Git identity set locally
(`thesagezm@users.noreply.github.com`). `.gitignore` existed; added `.kotlin/`.
Discipline from here: every unit lands with QUEUE.md updated in the same
commit and `git push` after verify.

## In Flight / Last Push

- Unit 7 (Speech settings parity) starting.

## Pending Queue

1. **Unit 7 — Speech settings parity (S1–S3, from Unit 6 audit).**
2. **Unit 8 — Stats parity (T1–T5).** 3. **Unit 9 — Storage note-images
   cleanup (ST1).** 4. **Unit 10 — Encryption vault (E1, P0-4).**
5. **Waydroid verification of Units 7–10 + release cold-start re-check,
   then ASK the user for the v0.0.1 prerelease go-ahead.**
6. **P1 — neural playback start-stall watchdog (never-started variant)**
   (evidence in Completed Units, Unit 5-note).
7. P0-1 (editor preview lock-in), P0-3 (.jex image round-trip), then the
   state-report P0/P1 list in order.

### Unit 6 audit — settings gaps vs iOS (2026-10-24)

iOS reference: `~/speechnotes-ios`
(v1.7.4 working tree). Android parity verdicts:

- **Parity or better already:** Appearance (accent/mode/reader-theme/text-
  scale/spacing/reset — reader theme is Android-extra), Notes settings
  (markdown/read-along/haptics/image-cache), Goals (same editability),
  Backup (.jex both ways), BookDrop receiver surface, Storage core
  (usage/exports/temp), About/Logs, Kokoro quality (Android's 4-engine
  contract with KOKORO vs KOKORO_SMALL rows covers iOS's High/Compact dial
  — documented divergence).
- **S1 — voice picker row missing from Speech settings** (only reachable
  from the note editor; iOS has it in settings).
- **S2 — system voice picker missing entirely.** `SpeechPlayer.setSystemVoice`
  (`SpeechPlayer.kt:416`) and `SystemTtsEngine.voiceIdentifier`
  (`SystemTtsEngine.kt:26`, applied `:169,:223`) exist with no UI; iOS lists
  all AVSpeechSynthesisVoices sorted by language+name with Default first.
- **S3 — Supertonic quality dial missing** (iOS: High=8 steps always /
  Balanced=6 / Automatic=8-sheds-at-critical / Fast=4; persisted
  `supertonicRenderQuality`; applies next chunk; **export always full 8**).
  Android `SupertonicEngine.totalStep` (`:59`) is fixed
  `RenderAheadBankPolicy.totalStep(thermal)`.
- **T1–T5 — Stats gaps** (user named stats explicitly): no Week/Month/Year
  range picker; no activity bar chart (reading vs listening — iOS: week
  daily bars, month scrollable daily, year monthly, legend); no Subjects
  card (top 8 by time in range, book covers + progress bars — note:
  `StatsStore.subjectTotals()` ALREADY EXISTS `StatsStore.kt:100`); heatmap
  not Mon-first week-aligned (Android = sequential 126 days); no empty
  state (`activeDays()` exists `:133`).
- **ST1 — Storage lacks the note-images section** (iOS imagesSection +
  per-note cleanup; Android stores under `filesDir/note-images`).
- **E1 — Encryption missing entirely (state-report P0-4).** iOS ships a
  Joplin-compatible vault (PBKDF2-HMAC-SHA512 220k wraps 256-byte master
  key; AES-256-GCM; off/locked/unlocked; passphrase never stored). Port as
  Unit 10 with format-exact tests vs the iOS implementation.
- **Deferred (recorded, not settings):** LocalSend sender (P1-3), Web-import
  paywall bypass (Safari/Readability-specific — record as divergence),
  books long-press menu, swipe actions.

## Session Handover

- **Environment**: Waydroid RUNNING at <waydroid-ip> (adb 5555 verified
  2026-10-24). App installed: debug x86_64 build with ALL of today's units
  (install at ~07:05 host time). If adb shows `unauthorized`: copy
  `~/.android/adbkey.pub` to `~/.local/share/waydroid/data/misc/adb/adb_keys`,
  then `waydroid session stop` + `waydroid show-full-ui` + `adb connect`.
  `run-as`/`su` do NOT work — verify app files via the app's own UI.
- **JDK 17**: `export JAVA_HOME=~/tools/jdk-17.0.13+11`.
  Disk 95% — check `df -h /` before builds; reclaim
  `~/.gradle/caches/build-cache-1` (45 MB, fine) if needed.
- **Build commands (CORRECTED)**: `./gradlew --offline -Psplits=true
  assembleDebug` for split debug APKs (`app-x86_64-debug.apk` on Waydroid,
  NEVER arm64 there — SIGSEGV). `assembleDebugX86_64` does NOT exist.
  Release splits (R8): `./gradlew --offline -Psplits=true assembleRelease`.
  78 unit tests green throughout: `./gradlew --offline testDebugUnitTest`.
- **Offline R8 gotchas solved this session** (already applied):
  1. `compose-group-mapping` module descriptor missing → local m2 shim at
     `~/.zcode/local-m2` + repo entry in `settings.gradle.kts`. Leave both.
  2. lint-vital needs intellij-core/kotlin-compiler jars (uncacheable) →
     `lint { checkReleaseBuilds = false }` in app/build.gradle.kts. Tests +
     Waydroid are the gates.
- **R8 release verified on Waydroid**: installed x86_64-release APK — notes
  decode (kotlinx-serialization rules work), storage usage correct, 370 ms
  cold start. The mapping/compose-mapping machinery also produced
  `baselineProfiles/` next to the release APKs.
- **Verification screenshots taken 2026-10-24** (session only, not archived):
  settings root (9 rows), Speech subpage (engine picker + 3 model rows),
  Storage (correct byte counts), Stats (heatmap + goals), notes list, books
  shelf, editor. Logcat evidence lines quoted in Completed Units.
- **Screenshot discipline**: the ZCode built-in image-describer hallucinated
  details repeatedly (invented a "fallback banner", misread byte values, and
  described launcher screenshots as app screens after my back-press exited
  the app). Read screenshots DIRECTLY; re-capture after every navigation.
- **Never start an AVD/emulator** — crashes the host (AGENTS.md). Waydroid
  only. Heavy/dumpsys-style commands plus parallel gradle runs may have
  contributed to host instability — run gradle builds ONE at a time, in
  foreground, with a timeout.

## Completed Units

- **2026-10-24 — Unit 6: settings-parity audit vs iOS (this session).** Read
  the iOS v1.7.4 settings surface (SettingsTabView, SettingsView,
  SpeechSettingsView, AppearanceSettingsView, StatsTabView,
  StorageSettingsView sections, EncryptionSettingsView, AboutView) against
  every Android subpage. Result: the gap list S1–S3 (speech), T1–T5 (stats),
  ST1 (storage images), E1 (encryption) above; everything else at parity or
  better. Evidence in the audit block under Pending Queue.

- **2026-10-24 — Unit 5: GitHub push.** `.gitignore` (+`.kotlin/`), `git init
  -b main`, 134 files / 26,969 insertions, commit 6713619, `gh repo create
  speechnotes-android --private --source . --push` as `thesagezm` →
  https://github.com/thesagezm/speechnotes-android. app/build correctly
  excluded. Gate closed.

- **2026-10-24 — Unit 5 note (P1 evidence, not a code unit): neural playback
  start-stall.** Play on fp32 after the output thread's standby window:
  SPEAKING + chunks render (83/78 tokens → 4.3/4.7 s audio), then silence
  forever; UI stuck on Pause; `dumpsys media.audio_flinger` → output thread
  `Standby: yes`, track Active=no, state letter A, `Server=0x00000000`,
  `FrmCnt=500000`, `FrmRdy=216000`, underruns 0, flushed 0. Pause (→PAUSED)
  and Stop (→IDLE) instant. Re-arm attempt pause→play: Server stayed 0.
  Counter-evidence: same container, 03:30, uint8 engine, natural finish
  (persistBookmark chars=784 progress=1.0 → SPEAKING→IDLE). Tail-stall guard
  shipped in the completion poller (ends the session if the head freezes
  within 300 ms of the end for 1 s — covers the near-tail variant). The
  never-started variant needs the session-rebuild watchdog (Pending Queue #2).

- **2026-10-24 — Unit 4: Kokoro small → quantized int8 (~92 MB), iOS parity.**
  - `speech/ModelFiles.kt`: KOKORO_SMALL entry is now `model_quantized.onnx`
    (URL `…/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model_quantized.onnx`,
    min 80 MB, **max 100 MB** — `Entry.maxBytes` added; window rejects the
    old ~177 MB uint8 file and truncated downloads, iOS
    `smallFilesAreValid()` parity). fp16 stays banned (NaN on ORT CPU).
  - `speech/KokoroEngine.kt:143`: loads `model_quantized.onnx`.
  - `speech/SpeechEngine.kt:54`: label → "Kokoro — small · int8 (~92 MB)".
  - `ModelManager.refreshStates()` calls `ModelFiles.removeLegacySmallModel()`
    — reclaims `model_uint8.onnx` on launch. **Verified on Waydroid**:
    logcat `ModelFiles: removed superseded uint8 Kokoro model (177 MB)`;
    small tier flips to "Model not downloaded yet" + Download button +
    fallback banner ("Kokoro (small) is selected…" — banner disambiguation
    added, same truncation fix as the download rows); fp32 + Supertonic
    stayed "Ready · fully offline".
  - **Open**: the 92 MB download needs network (container offline this
    session). Downloading also re-validates the window end-to-end.

- **2026-10-24 — Unit 3: Settings root → subpages.**
  - Deleted `ui/settings/SettingsScreen.kt` (1,284-line flat 9-card column).
  - New: `SettingsHomeScreen.kt` (nav root, 9 link rows) + one subpage file
    each for Speech (`SpeechSettingsScreen`), Notes, Appearance (fixes the
    P2 True-Black mislabel — "True black" now selectable/labeled), Stats &
    goals (60 s stats + 5 s goals pollers now run ONLY on that page), BookDrop,
    Backup, Storage (storage walk + exports refresh only on that page), About.
  - `SettingsCommon.kt`: shared `SettingsSubpage` scaffold (back arrow +
    scrolling column), SettingsCard/ToggleRow/RadioRow/ActionRow, formatters.
  - Routes `settings/speech|notes|appearance|stats|bookdrop|backup|storage|about`
    wired in `SpeechnotesApp.kt`; mini-player export jump now lands on
    `settings/storage` directly (was Settings tab root).
  - **Verified on Waydroid**: every page renders correct live data (engine
    states, storage bytes, stats, goals); back navigation correct.

- **2026-10-24 — Unit 2: UI/main-thread performance pass.**
  - `SpeechnotesViewModel` init: `notes.load()`, `notebooks.load()`,
    `bookmarks.load()`, `books.refresh()`, `models.refreshStates()`,
    `exports.refresh()`, `StatsStore.init`, `statsCenter.attach()`,
    `goals.init()`, `PDFBoxResourceLoader.init` all moved OFF the main thread
    into one `Dispatchers.IO` loader. Cold start Waydroid debug:
    1715–2180 ms → ~1380 ms steady.
  - `BooksStore.save()`: was re-read+re-decoding EVERY manifest after each
    save (fires per EPUB page turn / PDF page change, main thread). Now
    in-memory upsert + async manifest write on a `BooksStore-writer` thread;
    `purge`/`emptyRecycleBin` delete directories off-thread too.
  - `NotesStore.flushNow()`: JSON encode moved to the writer thread (was
    encoding the whole library on the caller/main per edit). `load()` now
    preserves notes created during the async load window (share-intake race).
  - `BookReaderScreen` PDF: `PDDocument.load` moved off main (P0-5) — async
    `LaunchedEffect`, spinner shows until the first page bitmap lands.
  - `NoteCard`: removed the doubled click pipeline (Card onClick +
    combinedClickable); notebook chip counts computed in one pass (was one
    list scan per chip per recomposition). Dead `showOverflow` deleted.
  - `Note.kt`: shared compiled Regexes for wordCount/preview (per-row churn).
  - **R8 enabled for release** (was off entirely): minify + shrinkResources +
    debug-signed so it installs. New `app/proguard-rules.pro` (kotlinx-
    serialization keeps incl. @Serializable field names = iOS JSON contract,
    pdfbox/onnxruntime JNI, okhttp dontwarn, line numbers kept for Logs).
    Universal release dex: 6.4 MB single dex; per-ABI release
    **arm64 56 MB / x86_64 63 MB** (debug splits were 78/85 MB).
    **Release cold start on Waydroid: 370 ms.**

- **2026-10-23 evening — Unit 1: neural TTS works; play/pause fixed.**
  - Root causes fixed: `StreamingPlaybackCore.kt:309` Int→Long
    ClassCastException crash on every neural play; ViewModel init NPE
    (declarations moved above init); share chooser FLAG_ACTIVITY_NEW_TASK;
    exportWav silence (isModelReady gate); play/pause main-thread jams
    (schedule/writeRemainder → generateQueue; eager empty-track play removed).
  - Evidence: Kokoro fp32 + Supertonic GENERATING→SPEAKING→IDLE, WAV exports
    4 MB/1 MB real audio, pause/resume/stop cycle, 0 FATAL, 78 tests green.

- 2026-10-23: `AGENTS.md` created (constitution, frozen) + `QUEUE.md` seeded.
