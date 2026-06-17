---
name: heyclaude
description: Working procedure for the HeyClaude repo — an Android assistance app (tool integration, wake word, and more). Covers setup/build commands, how to handle bug reports (diagnose only, never fix unasked), and fish cp-deploy of changed files to ariel's Android Studio project dir, branch switching (fetch and discard, local tree is disposable), and temp-file cleanup. Use for any task in HeyClaude.
---

# HeyClaude working procedure

Origin: `https://github.com/arielvino/HeyClaude`
Repo (this checkout): `/home/coder/projects/HeyClaude` — Android app, Kotlin/Gradle, namespace `com.arielvino.heyclaude` (minSdk 29, targetSdk 36, Java 11). All commands run from that directory.

**Purpose:** HeyClaude is a Claude-native Android voice assistant that performs real device actions (alarms, timers, open app, calendar, …) via Anthropic-native tool-use, grounded in the user's own structured data. App-only (no server), Anthropic-only (no model-swap layer), by decision. It is an early scaffold; expect to build it out from the ground up. **Full vision, architecture, API details, and build order are in `.claude/PROJECT_BRIEF.md` — read it before starting feature work.**

## General info / setup

- Build with the Gradle wrapper: `./gradlew assembleDebug` (debug APK), `./gradlew build` (full), `./gradlew lint`, `./gradlew test` (unit), `./gradlew connectedAndroidTest` (instrumented, needs a device/emulator).
- Dependencies resolve from `google()` and `mavenCentral()` via the sandbox proxy. The first build downloads the Android Gradle Plugin and SDK artifacts — if a fetch returns 403 / `ProxyError`, it's the domain whitelist (see global CLAUDE.md `whitelist-request`), not a build error.
- Version catalog lives in `gradle/libs.versions.toml`; module config in `app/build.gradle.kts`.
- **This checkout is a disposable mirror.** The canonical working tree is ariel's `/home/ariel/AndroidStudioProjects/HeyClaude` (and the GitHub remote). Never assume local-only state here is precious.

## Questions / bug reports: diagnose only

When the user asks a question or reports a bug, the deliverable is the **explanation** — root cause, the exact file:line, and what a fix would look like. **Do NOT edit any file until explicitly told to fix it.** Investigate freely (read code, run greps, build, run tests), report findings, then stop and wait.

**The diagnosis must be the very last output of the turn.** Any text followed by a tool call is hidden from the user — so run `ai-notify-complete` (and any cleanup commands) BEFORE writing the findings, and never write "the diagnosis/summary is above": if it was followed by a tool call, the user never saw it. Restate the full findings in the final message.

## After making fixes: build, then hand over a cp block

Once told to fix and the edits are done:

1. Verify the change compiles: `./gradlew assembleDebug` (and `./gradlew lint` / `./gradlew test` if relevant to the change).
2. Get the changed-file list: `git status --porcelain` (or `git diff --name-only`).
3. Emit a **fish** block for the user to paste into their own shell (NOT prefixed with `!` — they paste it directly into fish). Format rules: set `SRC`/`DST` once, **then a `git -C $DST checkout <branch>` line so the copy lands on the intended branch** (ariel owns `$DST`, so no `sudo` on the checkout — and it aborts loudly if his tree is dirty, which is the desired safety check), **one complete command per line** (no `;`-joins, no backslash continuations), one `sudo cp` per changed file, `sudo rm -f` lines for deletions, and end with a single `sudo chown -R ariel` covering the touched top-level dirs:

```fish
set SRC /home/coder/projects/HeyClaude
set DST /home/ariel/AndroidStudioProjects/HeyClaude
git -C $DST checkout <correct-branch>
sudo cp $SRC/app/src/main/java/com/arielvino/heyclaude/MainActivity.kt $DST/app/src/main/java/com/arielvino/heyclaude/MainActivity.kt
sudo cp $SRC/app/src/main/res/values/strings.xml $DST/app/src/main/res/values/strings.xml
sudo cp $SRC/app/build.gradle.kts $DST/app/build.gradle.kts
sudo chown -R ariel $DST/app
```

Always fill in `<correct-branch>` with the actual branch the work belongs on — never leave it as a placeholder. Copying onto the wrong branch (then committing/pushing there) is the failure this line exists to prevent. The cp/chown can't run from tool calls (sudo needs an interactive password) — always hand them to the user.

**Every cp block must be self-contained — always include the `set SRC`, `set DST`, and `git -C $DST checkout <branch>` lines, even when an earlier block in the same session already delivered them.** Although the `set` vars persist in a fish session, never assume the user pasted the earlier block, is in the same shell, or is still on the right branch. Re-emitting the three lines costs nothing and guarantees each block lands on the intended branch from a known state; a follow-up snippet that opens straight into `sudo cp` is not acceptable.

Note: changes to Gradle files (`build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`) require an Android Studio / Gradle sync on ariel's side — flag that in the handover so he re-syncs after the copy.

## Branch switch requests: fetch and discard everything

When asked to move to another branch, the local tree is disposable — the real work already lives in ariel's checkout or on the remote. Don't try to preserve, stash, or commit anything:

```bash
git fetch origin
git checkout -f <branch>
git reset --hard origin/<branch>
git clean -fd
```

(For a branch that only exists on ariel's machine, ask him to push it to `origin` first.) Note `git clean -fd` will not remove `.gradle/` or `build/` if they're gitignored — that's fine; they're rebuildable caches.

## Temp-file cleanup

Before ending the task, remove every scratch file you created during it:

- Keep scratch artifacts in `/tmp` — never inside the repo, so they can't leak into a cp block or `git status`.
- Delete them when done (only your own files).
- If a scratch file had to live in the repo, delete it before generating the changed-file list so it doesn't get cp'd to ariel's tree, and check `git status --porcelain` is clean of leftovers afterwards.
