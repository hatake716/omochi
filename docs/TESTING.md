# Testing Omochi

## Automated gates

Run from the repository root:

```bash
./gradlew --no-daemon \
  :app:verifyEmbeddedRuntime \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

The unit suite currently checks:

- SHA-256 against a known vector and case-insensitive expected values;
- tar destination containment and traversal rejection;
- loopback-only server URL;
- VS Code-compatible language-pack cache hashing;
- completion-marker parsing, including comments, malformed rows, and values containing `=`;
- exact active-loopback URL acceptance and rejection of lookalike host, port, scheme, and user-info URLs;
- explicit external-browser scheme allowlist;
- SAF destination-name sanitization and component length;
- SAF export containment, including direct and nested symlink rejection.

Inspect the APK:

```bash
APK=app/build/outputs/apk/debug/app-debug.apk
unzip -l "$APK" | grep 'lib/arm64-v8a/libproot.so'
unzip -l "$APK" | grep 'assets/legal/sources/proot-v5.1.107.92.zip'
unzip -l "$APK" | grep 'assets/legal/SOURCE-AND-LICENSE-MANIFEST.sha256'
```

## Physical-device acceptance

Use an ARM64 device with at least 2 GiB free. The test is intentionally touch-driven; do not use ADB input
injection to claim touch or IME success.

### 1. Install boundary

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm path io.github.hatake716.omochi.debug
```

An installed APK is not a successful IDE test. Continue through setup and visible interaction.

### 2. Fresh setup

1. If this is a disposable test installation, uninstall only `io.github.hatake716.omochi.debug` first.
2. Open Omochi manually.
3. Verify the unsupported-ABI warning is absent.
4. Tap **Omochiをセットアップ**.
5. Observe Ubuntu download, extraction, PRoot self-test, IDE download, SHA verification, Linux packages,
   Japanese Language Pack verification/indexing, Claude signing-key verification/install, and both self-tests.
6. Confirm 100% and **ワークベンチを開く**.
7. Leave the workbench, tap the Omochi app icon, and confirm the first visible app screen is the workbench rather
   than the setup dashboard. Tap the workbench back action and confirm the dashboard remains reachable.

Before setup, repeat an app-icon launch and confirm it routes to the dashboard rather than starting an invalid
workbench session. For a migration-pending installation, confirm the dashboard remains the first screen until
the Japanese UI and Claude Code completion marker is valid.

Pixel 10a / Android 17 evidence from 2026-08-31: a force-stopped, setup-complete installation followed
`LauncherActivity -> WorkbenchActivity` with no `MainActivity` transition and displayed the workbench in
1.194 seconds. Launching from the dashboard then reached the workbench while retaining the same app and PRoot
PIDs. Launching while an existing workbench was backgrounded reused the same Activity record and returned it
to the foreground in 92 milliseconds.

For an existing v0.1 installation, use **日本語UIとClaude Codeを導入** and verify that `/workspace`
contents and Git state remain unchanged after migration.

Use logcat only as supplementary evidence:

```bash
adb logcat -c
adb logcat --pid="$(adb shell pidof io.github.hatake716.omochi.debug)"
```

### 3. Editor

1. Create `日本語テスト.md` and type Japanese through the normal Android IME.
2. Save, close the tab, reopen, and compare visible content.
3. Test selection handles, copy/paste, Undo/Redo, Find, replacement, word wrap, minimap, and split editor.
4. Attach a hardware keyboard if available and verify Ctrl+P, Ctrl+Shift+P, Ctrl+S, and Ctrl+`.

### 4. Touch chrome

Tap every fixed bottom-dock action and confirm the visible destination:

- File Explorer;
- Search;
- Source Control;
- Run and Debug;
- Terminal;
- Command Palette;

Use the 48dp top actions to show/hide the touch panel, enter/leave fullscreen, and return home. Switch the
touch panel between **キー** and **編集**. Confirm Enter, Backspace, Find/Replace, Quick Open, editor split,
and new-terminal actions in addition to Esc/Tab/arrows.

Latch Ctrl/Alt/Shift in the bottom bar and verify it resets after the next ordinary key.

### 5. Terminal and Git

In the integrated terminal:

```bash
uname -m
pwd
git --version
rg --version
printf '日本語 terminal OK\n'
claude --version
```

Expected architecture is `aarch64` and path is `/workspace`. Clone a disposable repository, edit a file,
inspect diff, stage, commit with a test identity, create/switch a branch, and inspect history. Do not store
production credentials in a disposable test environment.

Expected Claude output contains a semantic version and `(Claude Code)`. Running bare `claude` is a separate
online/account boundary: complete the first login manually with a supported Anthropic account, inspect every
requested permission, and do not record tokens or private login screens.

For the browser handoff regression:

1. Start `claude` in the integrated terminal and open its HTTPS authentication link.
2. Confirm Android shows the **Omochi IDEセッション実行中** foreground notification.
3. Leave the browser foreground for at least five minutes without swiping either task away.
4. Return from Recents or the notification and verify the same terminal still shows the pending/login result.
5. Confirm `/healthz` and both code-server WebSockets remained connected, and that no
   `ApplicationExitInfo` crash/low-memory exit was recorded for Omochi during the handoff.
6. Tap **セッションを停止** only after the test and confirm PRoot/code-server exits.

Pixel 10a / Android 17 regression evidence from 2026-08-31:

- Before the IME opened, the workbench occupied `[0,304][1080,1872]`; with the Japanese IME visible it
  resized to `[0,304][1080,1496]`. The xterm input remained at `[716,1214][740,1262]`, above the keyboard.
- The native touch-key panel and navigation dock were absent from the accessibility tree while the IME was
  visible. A physical Japanese Gboard tap produced `あ` in xterm, and Backspace removed it.
- An external HTTPS documentation link kept the browser foreground for 60 seconds. The Omochi app PID,
  PRoot/code-server process tree, foreground-service state, terminal, and loopback port remained unchanged;
  `/healthz` heartbeats advanced and the remote-agent log recorded no WebSocket disconnect.
- Android Back returned to the same `WorkbenchActivity`, which displayed the session-continuity notice.
  No Anthropic credentials were entered, so the real account-login result and five-minute soak remain manual
  acceptance steps.

### 6. Japanese UI

1. Confirm Explorer, Search, Source Control, command palette, Settings, Workspace Trust, and terminal labels
   use the official Japanese localization.
2. Open **基本設定: 設定 (UI) を開く** and verify the search placeholder, tabs, descriptions, and controls.
3. Restart the app and confirm Japanese remains selected before extension-host startup.
4. Treat product names, setting identifiers, and newly added untranslated upstream strings as expected
   fallbacks; do not confuse those with a completely missing language pack.

Internal diagnostic evidence may check that `languagepacks.json`, commit-scoped `nls.messages.json` /
`nls.messages.js`, and `_VSCODE_NLS_LANGUAGE=ja` exist. Those checks supplement, but do not replace, the
visible Japanese Settings/workbench check.

### 7. SAF

1. Import two files with the same display name and confirm neither is overwritten.
2. Import a nested folder containing Unicode names and binary data.
3. Export twice and confirm two timestamped directories exist.
4. Hash selected binary files before import and after export.
5. Add a symlink inside `/workspace` and confirm export stops with a clear rejection instead of following it.

### 8. Lifecycle

- portrait ↔ landscape;
- Android split-screen;
- show/hide IME repeatedly;
- background for 1, 5, and 20 minutes;
- external browser foreground during a running terminal/OAuth flow;
- notification reopen and explicit session stop;
- lock/unlock;
- return after WebView renderer memory pressure;
- reopen after Android kills the app process.

Report setup/build, process readiness, visible editor behavior, and persistence as separate boundaries.
