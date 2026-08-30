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
- a user-visible foreground service for the loopback process lifecycle;
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
`/workspace`. Setup installs the common host tools needed by the core workbench: Bash, CA certificates,
Git, OpenSSH client, ripgrep, curl/wget, archive utilities, locales, libstdc++, libatomic, and GnuPG.
It also registers Anthropic's signed stable APT repository after checking the complete signing-key fingerprint,
then installs Claude Code into this private Ubuntu environment.

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
- locale `ja` plus a product-managed Microsoft Japanese Language Pack in the user extension directory;
- `EXTENSIONS_GALLERY={}` plus removal of `extensionsGallery` from `product.json`;
- startup folder `/workspace`.

The code-server CLI extracts the language-pack VSIX but does not create VS Code's pre-start localization
cache in this headless installation path. Omochi validates every declared translation path, writes the same
`languagepacks.json` index used by `NativeLanguagePackService`, and lets code-server generate its commit-scoped
`clp/<hash>.ja/.../nls.messages.js` before serving the workbench. This makes core menus and Settings Japanese
without enabling an extension marketplace.

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
10. Download the pinned official Japanese Language Pack VSIX and verify its SHA-256.
11. Install the pack, validate its manifest paths, generate `languagepacks.json`, and select locale `ja`.
12. Verify Anthropic's full signing-key fingerprint, register its stable APT source, and install Claude Code.
13. Execute `claude --version` and `code-server --version` inside PRoot.
14. Create the IDE and Japanese/Claude completion markers only after their respective gates pass.

An interrupted download does not become the cache. An interrupted extraction has no IDE marker and is
replaced on the next attempt.

Existing v0.1 installations use a non-destructive migration: Omochi stops only its IDE server, preserves the
rootfs and `/workspace`, adds the Japanese UI and Claude Code, verifies both, and then writes the new marker.

## 4. Runtime lifecycle

`LauncherActivity` is the only exported Activity. It reads the runtime and Japanese/Claude completion markers,
then immediately routes an app-icon launch to `WorkbenchActivity` only when both setup stages are complete.
Fresh or migration-pending installations route to the setup dashboard instead. The router runs in a
recents-excluded empty-affinity task, has no history, and opens the destination with
`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_REORDER_TO_FRONT`. Android therefore executes the routing decision on
every icon tap while reusing an existing workbench instead of stacking a duplicate. `MainActivity` and
`WorkbenchActivity` remain unexported; the workbench back action explicitly opens the dashboard when import,
export, setup, or session controls are needed.

`OmochiServerManager` starts one process per Android app process, selects a currently free loopback port, and
records a generation number. Reader and health-probe threads ignore stale generations after a restart. Server
output is capped/rotated under `files/logs/`. Readiness requires the expected JSON response from `/healthz`;
process creation by itself is not treated as success.

`OmochiServerService` owns that manager as a user-started foreground service of type `specialUse`. The service
starts while `WorkbenchActivity` is visible, posts an ongoing low-importance notification, and remains visible
to Android when an OAuth or documentation link moves the external browser to the foreground. This prevents
the app UID and its PRoot/code-server children from being treated as an ordinary cached background process.
The notification opens the workbench and provides an explicit **セッションを停止** action. Swiping the
Omochi task does not silently end a terminal login flow; stopping the notification session terminates the
app-owned PRoot tree, including code-server, extension-host, and terminal descendants, before another session
can start.

If the app process is reclaimed despite foreground importance, the sticky service recreates the local server
against the same app-private user data and `/workspace`. An Activity or WebView recreation reconnects to the
manager's current URL instead of owning the server lifetime. The service never changes the network boundary:
code-server still binds only the dynamically selected `127.0.0.1` port.

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

The fixed bottom dock maps large labeled taps to real workbench keyboard commands:

| Android control | Workbench command key |
|---|---|
| Explorer | Ctrl+Shift+E |
| Search | Ctrl+Shift+F |
| Source Control | Ctrl+Shift+G |
| Run and Debug | Ctrl+Shift+D |
| Terminal | Ctrl+` |
| Command Palette | Ctrl+Shift+P |

The touch panel separates keyboard controls from editing actions so the user does not have to search one very
long strip. The key page provides Esc, latched Ctrl/Alt/Shift, Tab, arrows, Enter, Backspace, and Japanese IME.
The editing page provides Save, Undo/Redo, Find/Replace, Quick Open, editor split, and a new terminal. Every
native primary target is at least 48 dp. When the Android IME is visible the navigation dock collapses and the
workbench root consumes the IME inset while the redundant touch-key panel and navigation dock collapse. The
editor and terminal are therefore resized above the keyboard instead of being covered. On phone-width viewports
the restored primary and auxiliary sidebars are initially collapsed; the fixed dock opens them on demand.

A bounded CSS/DOM layer increases Code list rows, tabs, action icons, menus, buttons, quick-pick rows, status
bar, and scrollbars for touch. Extension hiding uses a short bounded scan after load rather than an unbounded
whole-document mutation scan. The WebView supports user-initiated popup links but routes every non-loopback
HTTP(S)/mail target through Android, including Claude authentication pages.

## 7. Security decisions

- Network Security Config allows Android framework/WebView cleartext only for `127.0.0.1` and `localhost`.
- WebView file/content access is disabled.
- Third-party cookies and web permission requests are denied.
- The server never binds `0.0.0.0`.
- The setup archive uses an official HTTPS URL and exact SHA-256.
- The Japanese VSIX uses an official Microsoft asset URL, exact SHA-256, and contained translation paths.
- Claude's APT source is enabled only after the downloaded signing key matches
  `31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE`.
- Tar output paths are canonicalized and constrained under the selected extraction root.
- External links are not loaded with the privileged local workbench origin.
- The foreground notification makes every long-running local IDE session visible and user-stoppable.
- No credentials are embedded in source or APK.

Git credentials entered inside the Linux environment remain in the app-private rootfs. The project does not
yet provide Android Keystore-backed Git credential storage.

Claude Code authentication is separate from Omochi's loopback password. Omochi does not embed Anthropic
credentials or bypass login. Claude Code can read and modify the selected workspace and run approved guest
commands, so its prompts and permission requests are part of the user's trust boundary.

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

For a Japanese Language Pack update, select a version compatible with the pinned Code engine, verify the
official VSIX publisher/name/version, update the exact SHA-256, and repeat the Japanese Settings and command
palette device checks. For Claude Code, re-check Anthropic's official install documentation and signing-key
fingerprint before changing the repository/channel contract.

For PRoot or support-library updates, regenerate the binary with the included source/build material, replace
the corresponding source offer, refresh `SOURCE-AND-LICENSE-MANIFEST.sha256`, and verify 16 KiB page-size
compatibility on modern Android hardware.
