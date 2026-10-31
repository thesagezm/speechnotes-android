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

- [H4] claim ui/books/BookReaderScreen.kt for H4 (landscape playback rail)
- [H4] claim ui/editor/NoteEditorScreen.kt for H4 (landscape playback rail)

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

## Ron — log

- (session start 2026-10-31, awaiting first unit entry)
