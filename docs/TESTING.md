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
4. Tap **IDEをセットアップ**.
5. Observe Ubuntu download, extraction, PRoot self-test, IDE download, SHA verification, Linux packages, and Code self-test.
6. Confirm 100% and **ワークベンチを開く**.

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

Tap every Omochi title-bar action and confirm the visible destination:

- Explorer;
- Search;
- Source Control;
- Terminal;
- Command Palette;
- yellow traffic light (touch bar);
- green traffic light (fullscreen);
- red traffic light (return home).

Latch Ctrl/Alt/Shift in the bottom bar and verify it resets after the next ordinary key.

### 5. Terminal and Git

In the integrated terminal:

```bash
uname -m
pwd
git --version
rg --version
printf '日本語 terminal OK\n'
```

Expected architecture is `aarch64` and path is `/workspace`. Clone a disposable repository, edit a file,
inspect diff, stage, commit with a test identity, create/switch a branch, and inspect history. Do not store
production credentials in a disposable test environment.

### 6. SAF

1. Import two files with the same display name and confirm neither is overwritten.
2. Import a nested folder containing Unicode names and binary data.
3. Export twice and confirm two timestamped directories exist.
4. Hash selected binary files before import and after export.
5. Add a symlink inside `/workspace` and confirm export stops with a clear rejection instead of following it.

### 7. Lifecycle

- portrait ↔ landscape;
- Android split-screen;
- show/hide IME repeatedly;
- background for 1, 5, and 20 minutes;
- lock/unlock;
- return after WebView renderer memory pressure;
- reopen after Android kills the app process.

Report setup/build, process readiness, visible editor behavior, and persistence as separate boundaries.
