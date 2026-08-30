# Omochi architecture

## 1. Goal and boundaries

Omochi exposes the Code - OSS workbench as an Android application without depending on Google Play,
Google Play Services, root access, or another installed terminal application. It does not attempt to
reimplement the editor, terminal, SCM, task, and debug workbench independently. Instead it runs the same
browser workbench supplied by code-server inside a private Linux runtime and adds an Android-native setup,
storage, lifecycle, and touch layer.

The implementation intentionally excludes an external extension marketplace. Built-in Code components
remain because Git, basic language grammars, themes, and parts of the desktop-compatible workbench are
shipped in that form upstream.

## 2. Runtime layers

### Android shell

The package ID is `io.github.hatake716.omochi` (`.debug` suffix for debug builds). The shell is written in
Kotlin and Jetpack Compose. It owns:

- setup and integrity progress;
- the macOS-like window chrome;
- touch shortcuts and modifier latching;
- Android IME and hardware-key dispatch into WebView;
- SAF import/export;
- the loopback process lifecycle;
- legal/source disclosure.

### PRoot

Four native ELF objects are extracted from the APK for `arm64-v8a`:

- `libproot.so` — executable PRoot runtime;
- `libproot-loader.so` — guest loader;
- `libandroid-shmem.so`;
- `libtalloc.so`.

They run with the Android application UID. PRoot's apparent UID 0 is not Android root. The code canonicalizes
all host paths passed to `--rootfs`, `PROOT_L2S_DIR`, and bind arguments because Android may expose the same
private directory as both `/data/user/0/<package>` and `/data/data/<package>`.

### Ubuntu

Ubuntu Base 24.04.4 ARM64 is downloaded from Ubuntu's official HTTPS host and extracted under:

```text
files/embedded-runtime/containers/omochi-linux/rootfs/
```

The rootfs is configured with `/dev`, `/proc`, and `/sys` binds. The app-private workspace is bound at
`/workspace`. Setup installs only the common host tools needed by the core workbench: Bash, CA certificates,
Git, OpenSSH client, ripgrep, curl/wget, archive utilities, locales, libstdc++, and libatomic.

Language compilers and project SDKs are not silently installed. They belong to the selected project and can
be installed through the integrated terminal.

### code-server / Code - OSS

The pinned archive is extracted to:

```text
/opt/omochi/code-server-4.133.0-linux-arm64/
```

Omochi starts it with these important properties:

- a dynamically selected free port on bind address `127.0.0.1`;
- password authentication with a 256-bit URL-safe random secret stored mode 0600 in app-private storage;
- telemetry and update checks disabled;
- Code - OSS Workspace Trust left enabled;
- user data `/root/.local/share/omochi`;
- empty external extension directory;
- `EXTENSIONS_GALLERY={}` plus removal of `extensionsGallery` from `product.json`;
- startup folder `/workspace`.

The WebView communicates through HTTP and WebSocket. It allows only the exact active loopback host/port to
stay inside the app; HTTP(S) and mail links to other hosts are handed to Android. On the branded local login
page, Android injects the private password and submits the form so the user never handles server credentials.

## 3. Setup transaction

1. Verify supported ABI and embedded native runtime sizes.
2. Run `proot --version` as a host self-test.
3. Download Ubuntu Base to a `.part` file with retry and atomic move, then verify its pinned official SHA-256.
4. Extract with canonical destination checks and create the rootfs marker only after a guest shell self-test.
5. Download the pinned code-server archive to a `.part` file.
6. Compare the full SHA-256 with the release digest before extraction.
7. Remove only the exact old version directory, then extract into `/opt/omochi`.
8. Install common Linux tools.
9. Remove the external extension gallery and write default user settings.
10. Execute `code-server --version` inside PRoot.
11. Create `.omochi-code-server` only after every prior gate passes.

An interrupted download does not become the cache. An interrupted extraction has no IDE marker and is
replaced on the next attempt.

## 4. Runtime lifecycle

`OmochiServerManager` starts one process per Android app process, selects a currently free loopback port, and
records a generation number. Reader and health-probe threads ignore stale generations after a restart. Server
output is capped/rotated under `files/logs/`. Readiness requires the expected JSON response from `/healthz`;
process creation by itself is not treated as success.

The current implementation is intentionally foreground-oriented. It does not start an Android foreground
service, so Android may reclaim the process after the app is backgrounded. Reopening starts or probes the
server again.

## 5. Workspace and SAF

The authoritative project root is:

```text
files/workspace/  <->  /workspace in Ubuntu
```

This avoids broad shared-storage permissions. Import uses `ACTION_OPEN_DOCUMENT` or
`ACTION_OPEN_DOCUMENT_TREE`. Existing items are never overwritten; a collision receives an ` (import N)`
suffix. Export creates a new `Omochi-workspace-YYYYMMDD-HHmmss` folder, so it cannot replace an older backup.

The app does not access another application's Termux prefix or data directory.

## 6. Touch adaptation

The Android title bar maps taps to real workbench keyboard commands:

| Android control | Workbench command key |
|---|---|
| Explorer | Ctrl+Shift+E |
| Search | Ctrl+Shift+F |
| Source Control | Ctrl+Shift+G |
| Terminal | Ctrl+` |
| Command Palette | Ctrl+Shift+P |

The bottom key bar sends Android keyboard events, including latched Ctrl/Alt/Shift metadata. A CSS/DOM layer
increases list rows, tabs, action icons, menu rows, buttons, and scrollbars for touch. It also hides the
Extensions Activity without changing the workbench's file/editor layout engine.

## 7. Security decisions

- Network Security Config allows Android framework/WebView cleartext only for `127.0.0.1` and `localhost`.
- WebView file/content access is disabled.
- Third-party cookies and web permission requests are denied.
- The server never binds `0.0.0.0`.
- The setup archive uses an official HTTPS URL and exact SHA-256.
- Tar output paths are canonicalized and constrained under the selected extraction root.
- External links are not loaded with the privileged local workbench origin.
- No credentials are embedded in source or APK.

Git credentials entered inside the Linux environment remain in the app-private rootfs. The project does not
yet provide Android Keystore-backed Git credential storage.

Network Security Config does not sandbox sockets opened by guest Git, apt, terminals, tasks, or debugged
programs. Those processes can use the app's Android `INTERNET` permission. Untrusted project commands must be
treated as executable code.

## 8. Updating pinned components

For a code-server update:

1. Read the official release and Code version.
2. Select `linux-arm64.tar.gz` and obtain its release-asset SHA-256.
3. Update all three `BuildConfig` fields in `app/build.gradle.kts`.
4. Verify the archive's top-level directory and `bin/code-server` path.
5. Compare the tagged `src/node/cli.ts` against every launch flag.
6. Run unit tests, lint, APK build, archive-content checks, then the full ARM64 device setup.
7. Check that the gallery remains absent and built-in Git/terminal still work.

For PRoot or support-library updates, regenerate the binary with the included source/build material, replace
the corresponding source offer, refresh `SOURCE-AND-LICENSE-MANIFEST.sha256`, and verify 16 KiB page-size
compatibility on modern Android hardware.
