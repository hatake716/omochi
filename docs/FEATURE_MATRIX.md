# Feature matrix

Status legend:

- **Implemented** — wired into the application and covered by build/static/unit gates where applicable.
- **Upstream** — provided by the pinned Code - OSS/code-server workbench, not reimplemented in Kotlin.
- **Conditional** — UI exists, but the project-specific compiler, runtime, debugger, or credentials must be installed/configured.
- **Excluded** — outside this product's extension-free scope.
- **Device pending** — the debug APK was installed on a Pixel 10a, but the app was not brought to the foreground while the device was actively in use; physical interaction acceptance is still required.

| Area | Status | Notes |
|---|---|---|
| File Explorer | Upstream / device pending | Create, rename, move, delete, multi-select, context menus |
| Text editor | Upstream / device pending | Tabs, split groups, diff, minimap, folding, multi-cursor, find/replace |
| Syntax highlighting | Upstream | Built-in Code language grammars |
| Completion / diagnostics | Conditional | Built-in JS/TS/JSON/HTML/CSS support; other languages need a tool or are extension-dependent |
| Workspace search | Upstream / device pending | ripgrep is installed during setup |
| Command Palette / Quick Open | Implemented + upstream | Dedicated touch button and Ctrl-based key dispatch |
| Integrated terminal | Upstream / device pending | Ubuntu Bash through code-server's PTY; Android touch modifiers added |
| Tasks | Upstream / device pending | Project commands require their toolchains in Ubuntu |
| Problems / Output / Debug Console | Upstream / device pending | Workbench panels available |
| Run and Debug UI | Upstream / conditional | Debug adapter/runtime availability depends on the language |
| Git clone/status/diff/stage/commit/branch | Upstream / device pending | Git and OpenSSH client installed during setup |
| Git pull/push | Conditional | Requires network and user-provided remote credentials |
| Settings / keybindings / workspace settings | Upstream / device pending | Stored in app-private rootfs |
| Themes / icon theme | Upstream | Default Light Modern with Omochi color customizations |
| macOS-like Android shell | Implemented | Traffic lights, rounded cards, warm neutral palette, native action bar |
| Touch target enlargement | Implemented / device pending | DOM/CSS layer for rows, tabs, menus, actions, buttons, scrollbars |
| Android IME | Implemented / device pending | WebView IME plus explicit IME focus button |
| Hardware keyboard | Implemented / device pending | WebView receives native key events |
| File import | Implemented | SAF files or folder; collision-safe names, no overwrite |
| Workspace export | Implemented | New timestamped SAF folder on every export; symlinks/out-of-workspace targets are rejected |
| Offline work after setup | Conditional | Core IDE works locally; Git remotes/package installs still need their own network |
| External extension marketplace | Excluded | Product gallery removed and Extensions Activity hidden |
| Manually installed VSIX support | Not guaranteed | Not a supported/tested product capability |
| Microsoft Settings Sync / account UI | Excluded | External service/account integration is not configured |
| Remote SSH / Dev Containers / WSL | Excluded | These are extension-led desktop workflows |
| Native Electron windows | Excluded | Android uses one WebView workbench |
| Google Play / Play Services | Not required | No runtime dependency |
| Root / external Termux | Not required | Private PRoot runtime only |
| ARM64 Android | Implemented / device pending | APK packages only `arm64-v8a` native runtime |
| x86_64 / 32-bit Android | Excluded in v0.1 | No matching embedded PRoot binaries |

## Acceptance still required on a physical device

The following must be observed before calling v0.1 device-ready:

1. Fresh install, Ubuntu download/extract, package installation, code-server download/SHA/extract.
2. `/healthz` readiness and WebSocket stability in Android WebView.
3. Create/edit/save/reopen a Unicode and Japanese filename.
4. Long-press, selection handles, copy/paste, multi-cursor, find/replace, split editor.
5. Explorer/Search/Git/Terminal/Command touch shortcuts.
6. Japanese IME composition in both editor and integrated terminal.
7. `git clone`, status, stage, commit, diff, local branch; remote operations with user-owned test credentials.
8. SAF import collision and timestamped export, then inspect exported files outside the app.
9. Rotation, split-screen, keyboard attach/detach, background/reopen, low-memory recreation.
10. Confirm the Extensions Activity remains hidden and no external gallery request occurs.
