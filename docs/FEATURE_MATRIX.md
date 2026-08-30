# Feature matrix

Status legend:

- **Implemented** — wired into the application and covered by build/static/unit gates where applicable.
- **Upstream** — provided by the pinned Code - OSS/code-server workbench, not reimplemented in Kotlin.
- **Conditional** — UI exists, but the project-specific compiler, runtime, debugger, or credentials must be installed/configured.
- **Excluded** — outside this product's extension-free scope.
- **Device verified** — visibly exercised on a Pixel 10a (Android 17) on 2026-08-30 and 2026-08-31.
- **Device pending** — implemented, but that interaction still needs release-level physical-device acceptance.

| Area | Status | Notes |
|---|---|---|
| File Explorer | Upstream / device pending | Create, rename, move, delete, multi-select, context menus |
| Text editor | Upstream / device pending | Tabs, split groups, diff, minimap, folding, multi-cursor, find/replace |
| Syntax highlighting | Upstream | Built-in Code language grammars |
| Completion / diagnostics | Conditional | Built-in JS/TS/JSON/HTML/CSS support; other languages need a tool or are extension-dependent |
| Workspace search | Upstream / device pending | ripgrep is installed during setup |
| Command Palette / Quick Open | Implemented + upstream | Dedicated touch button and Ctrl-based key dispatch |
| Post-setup app-icon launch | Implemented / Pixel 10a verified | Completed installs route directly to the workbench on cold and warm icon launches; fresh and migration-pending installs route to the setup dashboard |
| Fixed touch navigation dock | Implemented / Pixel 10a verified | 48dp+ File, Search, Git, Run, Terminal, and Command targets; dock retreats while the IME is visible |
| Integrated terminal | Upstream / device verified | Ubuntu Bash through code-server's PTY; `claude --version` visibly returned from the terminal |
| Tasks | Upstream / device pending | Project commands require their toolchains in Ubuntu |
| Problems / Output / Debug Console | Upstream / device pending | Workbench panels available |
| Run and Debug UI | Upstream / conditional | Debug adapter/runtime availability depends on the language |
| Git clone/status/diff/stage/commit/branch | Upstream / device pending | Git and OpenSSH client installed during setup |
| Git pull/push | Conditional | Requires network and user-provided remote credentials |
| Settings / keybindings / workspace settings | Upstream / device verified | Japanese Settings UI opened; values remain in the app-private rootfs |
| Japanese workbench UI | Implemented / device verified | Official Microsoft language pack; Explorer, Settings, commands, trust dialog, and terminal labels observed in Japanese |
| Claude Code | Implemented / device verified + conditional | Signed stable APT install and version self-test passed; first account login remains a user action |
| Claude browser authentication continuity | Implemented / 60-second device smoke verified | Pixel 10a retained the same app, PRoot, code-server, terminal, and WebSocket session during an external HTTPS handoff; account login and the five-minute soak remain manual acceptance boundaries |
| Themes / icon theme | Upstream | Default Light Modern with Omochi color customizations |
| macOS-like Android shell | Implemented | Window chrome, rounded dashboard cards, warm neutral palette, native status surfaces |
| Touch target enlargement | Implemented / Pixel 10a verified | Native 48dp+ targets plus DOM/CSS rows, tabs, menus, actions, buttons, and scrollbars |
| Android IME | Implemented / Pixel 10a verified | IME inset resizes the workbench above the keyboard, touch chrome retreats, and Japanese composition reaches the integrated terminal |
| Hardware keyboard | Implemented / device pending | WebView receives native key events |
| File import | Implemented | SAF files or folder; collision-safe names, no overwrite |
| Workspace export | Implemented | New timestamped SAF folder on every export; symlinks/out-of-workspace targets are rejected |
| Offline work after setup | Conditional | Core IDE works locally; Git remotes/package installs still need their own network |
| External extension marketplace | Excluded | Product gallery removed and Extensions Activity hidden; managed Japanese display pack is the only explicit exception |
| Manually installed VSIX support | Not guaranteed | Not a supported/tested product capability |
| Microsoft Settings Sync / account UI | Excluded | External service/account integration is not configured |
| Remote SSH / Dev Containers / WSL | Excluded | These are extension-led desktop workflows |
| Native Electron windows | Excluded | Android uses one WebView workbench |
| Google Play / Play Services | Not required | No runtime dependency |
| Root / external Termux | Not required | Private PRoot runtime only |
| ARM64 Android | Implemented / device verified | APK packages only `arm64-v8a` native runtime |
| x86_64 / 32-bit Android | Excluded in v0.3 | No matching embedded PRoot binaries |

## v0.2-v0.3 device evidence

The following boundaries were verified on the Pixel 10a rather than inferred from a build log:

1. The existing v0.1 rootfs and workspace survived the v0.2 migration.
2. The language-pack VSIX passed SHA verification and produced the commit-scoped NLS cache.
3. The loaded WebView reported `_VSCODE_NLS_LANGUAGE=ja`; Explorer, Settings, command palette, Workspace Trust,
   and terminal labels rendered in Japanese.
4. `/healthz` returned HTTP 200 and the workbench established its management and extension-host WebSockets.
5. The signed Anthropic APT install completed and the integrated terminal visibly returned
   `2.1.236 (Claude Code)` from `claude --version`.
6. The workbench resized above the Japanese IME; xterm remained visible, accepted `あ`, and the touch panel and
   fixed dock retreated until the keyboard closed.
7. The six fixed navigation targets, two-page touch controls, compact initial layout, and responsive Workspace
   Trust dialog were visibly exercised at phone width.
8. A 60-second external-browser handoff retained the same Omochi, PRoot, code-server, terminal, and WebSocket
   session and returned to the same `WorkbenchActivity`.
9. Explicit session stop removed the foreground service and the complete app-owned PRoot/code-server process
   tree; the old `/healthz` endpoint became unreachable.
10. Cold app-icon launch routed `LauncherActivity` directly to `WorkbenchActivity` without displaying the
    dashboard. Warm relaunch reused the same workbench Activity, app process, PRoot process, and IDE session.

## Remaining physical-device acceptance

The following broader interactions remain release-level acceptance items:

1. Long-press, selection handles, copy/paste, multi-cursor, find/replace, and split-editor gestures across supported WebView versions.
2. Japanese IME composition in both editor and integrated terminal across multiple keyboard applications.
3. Remote Git pull/push with user-owned test credentials; no production credentials in test captures.
4. Rotation, split-screen, keyboard attach/detach, foreground-service stop/restart, and forced low-memory recreation.
5. Claude login browser launch, at least a five-minute browser foreground interval, return to the same terminal,
   and completion of the callback without a new PRoot process.
6. Large repositories, long-running tasks, and project-specific debuggers/toolchains.
