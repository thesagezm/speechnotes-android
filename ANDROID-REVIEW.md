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

- (session start 2026-10-31, awaiting first unit entry)

## Hermione — log

- (session start 2026-10-31, awaiting first unit entry)

## Ron — log

- (session start 2026-10-31, awaiting first unit entry)
