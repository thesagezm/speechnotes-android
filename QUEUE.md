# QUEUE.md

Working memory for the Speechnotes-Android build pipeline. Owned by the agent.
Updated at the end of every unit (and in the same commit as the work it
describes once the repo is on GitHub).

## Current Phase

**The Great Rewrite.** TTS fixed (Unit 1, 2026-10-23). Performance pass +
Settings subpages + Kokoro-small source switch shipped 2026-10-24 (Units 2–4).
**The GitHub push is armed but NOT done** — see Session Handover: the session
was cut short by host instability; `git init` + `gh repo create` + push is the
FIRST action of the next session.

## User Feedback Pending

1. **"The android app is the slowest"** — ADDRESSED 2026-10-24 (Unit 2):
   cold start on Waydroid debug 1715 ms → ~1380 ms; **R8 release 370 ms**.
   User's hands-on verdict pending.
2. **"The tts is also not working"** — RESOLVED 2026-10-23 (Unit 1). User's
   own play at 03:30 (Waydroid clock) on 2026-10-24 completed naturally.
3. **"The buttons dont feel right"** — root cause fixed in Unit 1; the new
   Settings subpages + instant radio/toggle flips verified 2026-10-24.
4. **"switch the small kokoro model just as i did in speechnotes-ios … the
   source for kokoro small is a smaller engine no longer the 177mb file"**
   — DONE 2026-10-24 (Unit 4, details below). **The ~92 MB quantized download
   has NOT run yet** (container offline): next launch with network, Settings →
   Speech engine → Kokoro (small) → Download. Until then the small tier shows
   "Model not downloaded yet" and playback falls back to system voice.
5. **GitHub push gate** — speed gate MET (370 ms release cold start), TTS gate
   met on 2026-10-23/24 evidence. Push is the next session's first action.

## GitHub Gate status

**BOTH GATES MET — PUSH PENDING.** Not yet a git repository (host instability
hit right before `git init`). Next session:

```bash
cd ~/speechnotes-android
git init && git add -A && git commit -m "Speechnotes Android: perf pass, settings subpages, kokoro-small quantized switch"
gh repo create speechnotes-android --private --source . --push
```

- Check `gh auth status` first; if unauthenticated, ask the user to `gh auth login`.
- `.gitignore`: NONE EXISTS YET — create one BEFORE `git add -A`:
  ignore `.gradle/`, `build/`, `app/build/`, `local.properties`, `*.iml`,
  `.idea/`. The `app/build` dir is ~1 GB of APKs — must not enter git.
- Never commit credentials (none exist; models download at runtime).

## In Flight / Last Push

- Nothing in flight. Units 2, 3, 4 complete and verified; Unit 5 (push) armed.

## Pending Queue

1. **GitHub push** (see exact commands above). Do it FIRST.
2. **P1 — neural playback start-stall (Waydroid, intermittent).** Full
   evidence in Completed Units (Unit 5 note). Symptom: after the output
   thread enters standby (3 s idle), a new AudioTrack's play() registers
   (track state "A") but audioflinger's mixer never consumes —
   `dumpsys media.audio_flinger` shows `Standby: yes`, track `Server=0`,
   `FrmRdy=216000` (all 9 s of audio parked in the 1 MB buffer), underruns 0.
   UI sticks on Pause forever; pause/stop still responsive. Reproduced 2/2 on
   fp32 at 04:35/04:42/04:50; the user's own play at 03:30 (uint8) finished
   naturally — a race, not a regression (playback code untouched by Units
   2–4). A live pause→resume re-arm did NOT kick the mixer (Server stayed 0).
   **Next unit: never-started watchdog in StreamingPlaybackCore's completion
   poller — if head==0 with frames ready for 10 s, rebuild the session
   (stop → re-speak from bookmark) once, with a Logs-screen error if it
   fails again.** Also verify on real hardware (user's arm64 phone).
3. P0-1 (editor preview lock-in), P0-3 (.jex image round-trip), then the
   state-report P0/P1 list in order.

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
