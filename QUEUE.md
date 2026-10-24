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

## User Feedback Pending

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

**PUBLIC.** `github.com/thesagezm/speechnotes-android` (main). Identity:
`thesagezm <thesagezm@users.noreply.github.com>`. History was rewritten once
(2026-10-24) to purge host paths + the Waydroid IP before the public flip —
discipline going forward: every unit lands with QUEUE.md updated in the same
commit, `git push` after verify, and NO machine identifiers in any file.
**Release: v0.1.0 (prerelease)** — tag + notes + x86_64/arm64 release APKs.

## In Flight / Last Push

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

0. **TTS generation speed (user feedback):**
   - **0a — seconds-based ChunkSchedule: SHIPPED + Waydroid-verified**
     (Completed Units).
   - **0b — Live seconds-based render-ahead bank** (fixes P0-7): replace the
     chunk-count semaphore (`bankTargetSeconds/4`, StreamingPlaybackCore.kt)
     with `RenderAheadBankPolicy.allows(bankedSeconds, target)` per chunk —
     banked = (framesWritten − head)/sampleRate, the iOS core's real pacing.
   - **0c — Never-run-dry silence bridge** (iOS `241888e`): when the last
     scheduled buffer completes and the next chunk is still rendering, write
     0.5 s silent keepalive buffers so the track is never disabled by
     AudioFlinger — speech resumes when synthesis lands instead of the
     session dying. On the 2026-10-24 Waydroid boot this is ALSO the
     frozen-head-at-0 fix path (AudioFlinger `BUFFER TIMEOUT: remove` the
     moment playback started — the HAL write blocked 574 ms and the track
     was dropped; the completion poller spun at head 0/58200 until manual
     stop; pre-existing pathology, two guards already fight it).
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

- **Environment**: Waydroid was RUNNING at handover (session started ~16:00
  host time 2026-10-24). Get the adb endpoint via `waydroid status`
  (Session IP address) → `adb connect <that-ip>:5555`. If adb shows
  `unauthorized`: copy `~/.android/adbkey.pub` to
  `~/.local/share/waydroid/data/misc/adb/adb_keys`, then `waydroid session
  stop` + `waydroid show-full-ui` + `adb connect`. `run-as`/`su` do NOT work.
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

## Completed Units

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
