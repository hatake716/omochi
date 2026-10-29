package io.github.hatake716.omochi

import android.view.KeyEvent
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

internal enum class TouchPanelPage { Keys, Edit }

internal data class WorkspacePickerState(
    val currentGuestPath: String,
    val folders: List<WorkspaceSession.Folder>,
    val error: String?,
)

/** How the session dot in the top bar is colored. */
internal enum class WorkbenchSessionTone { Running, Pending, Failed }

/** Short session state shown under the folder name in the top bar. */
internal data class WorkbenchSessionStatus(val label: String, val tone: WorkbenchSessionTone)

/** Pure decisions behind the workbench chrome, kept apart from Compose so they can be unit tested. */
internal object WorkbenchChromePolicy {
    /**
     * Screen width from which the home button shows "ホーム" next to its chevron. Narrower
     * screens collapse it to the chevron, like an iOS back button that runs out of room,
     * so that the folder name keeps enough width between the toolbar buttons.
     */
    const val HOME_LABEL_MIN_WIDTH_DP = 520

    fun sessionStatus(state: OmochiServerManager.State): WorkbenchSessionStatus = when (state) {
        OmochiServerManager.State.Stopped ->
            WorkbenchSessionStatus("待機中", WorkbenchSessionTone.Pending)
        is OmochiServerManager.State.Starting ->
            WorkbenchSessionStatus("起動中", WorkbenchSessionTone.Pending)
        is OmochiServerManager.State.Running ->
            WorkbenchSessionStatus("実行中", WorkbenchSessionTone.Running)
        is OmochiServerManager.State.Failed ->
            WorkbenchSessionStatus("失敗", WorkbenchSessionTone.Failed)
    }

    fun syncLabel(state: WorkspaceSyncManager.State): String = when (state) {
        WorkspaceSyncManager.State.Disconnected -> "端末未連携"
        is WorkspaceSyncManager.State.Ready -> "端末同期"
        is WorkspaceSyncManager.State.Syncing -> "同期中"
        is WorkspaceSyncManager.State.Failed -> "同期要確認"
    }

    fun isSyncLinked(state: WorkspaceSyncManager.State): Boolean = when (state) {
        WorkspaceSyncManager.State.Disconnected -> false
        is WorkspaceSyncManager.State.Ready -> true
        is WorkspaceSyncManager.State.Syncing -> state.link != null
        is WorkspaceSyncManager.State.Failed -> state.link != null
    }

    /** Name shown as the top bar title: the last path segment, "workspace" for the root. */
    fun folderTitle(guestPath: String): String {
        val trimmed = guestPath.trimEnd('/')
        if (trimmed.isEmpty() || trimmed == WorkspaceSession.GUEST_ROOT) return ROOT_TITLE
        return trimmed.substringAfterLast('/').ifEmpty { ROOT_TITLE }
    }

    /**
     * Whether the soft keyboard covers more than the navigation bar. Called with the
     * target of the IME animation, so the answer flips once when the animation starts
     * instead of flickering while the keyboard slides.
     */
    fun isImeShown(imeBottom: Int, navigationBottom: Int): Boolean = imeBottom > navigationBottom

    fun showsHomeLabel(screenWidthDp: Int): Boolean = screenWidthDp >= HOME_LABEL_MIN_WIDTH_DP

    private const val ROOT_TITLE = "workspace"
}

/** System bars and the display cutout, split by the part of the chrome that has to avoid them. */
@Immutable
private class WorkbenchChromeInsets(safe: WindowInsets) {
    val top: WindowInsets = safe.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    val sides: WindowInsets = safe.only(WindowInsetsSides.Horizontal)
    val bottom: WindowInsets = safe.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkbenchScreen(
    serverState: OmochiServerManager.State,
    webError: String?,
    showTouchBar: Boolean,
    ctrlLatched: Boolean,
    altLatched: Boolean,
    shiftLatched: Boolean,
    touchPanelPage: TouchPanelPage,
    fullscreenEnabled: Boolean,
    syncState: WorkspaceSyncManager.State,
    workspaceFolder: String,
    workspacePicker: WorkspacePickerState?,
    createWebView: () -> WebView,
    onClose: () -> Unit,
    onToggleTouchBar: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    onBrowseWorkspaceFolder: (String) -> Unit,
    onCreateWorkspaceFolder: (String) -> Unit,
    onSelectWorkspaceFolder: (String) -> Unit,
    onDismissWorkspacePicker: () -> Unit,
    onSyncFolder: () -> Unit,
    onExplorer: () -> Unit,
    onSearch: () -> Unit,
    onSourceControl: () -> Unit,
    onTerminal: () -> Unit,
    onCommandPalette: () -> Unit,
    onRetry: () -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onTouchPanelPage: (TouchPanelPage) -> Unit,
    onKey: (Int) -> Unit,
    onShortcut: (String) -> Unit,
    onShowIme: () -> Unit,
    browserNotice: String?,
    onDismissBrowserNotice: () -> Unit,
) {
    // The keyboard is followed through the *target* of its animation, never its
    // per-frame position. Resizing the WebView on every animation frame makes Code - OSS
    // lay out the whole workbench and resize every terminal PTY (SIGWINCH, and a full
    // redraw in TUIs such as Claude Code) about 20 times per keyboard slide. With the
    // target, the WebView changes height once when the animation starts and still ends
    // up entirely above the keyboard.
    val density = LocalDensity.current
    val imeTarget = WindowInsets.imeAnimationTarget
    val navigationBars = WindowInsets.navigationBars
    val imeVisible by remember(imeTarget, navigationBars, density) {
        derivedStateOf {
            WorkbenchChromePolicy.isImeShown(
                imeBottom = imeTarget.getBottom(density),
                navigationBottom = navigationBars.getBottom(density),
            )
        }
    }
    // Status and navigation bars plus the cutout, so that neither the top bar nor the
    // WebView slides under a punch hole in fullscreen or a side navigation bar in landscape.
    val systemBars = WindowInsets.systemBars
    val displayCutout = WindowInsets.displayCutout
    val insets = remember(systemBars, displayCutout) {
        WorkbenchChromeInsets(systemBars.union(displayCutout))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosColors.GroupedBackground),
    ) {
        StudioTopBar(
            status = WorkbenchChromePolicy.sessionStatus(serverState),
            syncLabel = WorkbenchChromePolicy.syncLabel(syncState),
            syncLinked = WorkbenchChromePolicy.isSyncLinked(syncState),
            workspaceFolder = workspaceFolder,
            touchPanelVisible = showTouchBar,
            fullscreenEnabled = fullscreenEnabled,
            onClose = onClose,
            onToggleTouchPanel = onToggleTouchBar,
            onToggleFullscreen = onToggleFullscreen,
            onOpenWorkspacePicker = onOpenWorkspacePicker,
            onSyncFolder = onSyncFolder,
            modifier = Modifier.windowInsetsPadding(insets.top),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(insets.sides),
        ) {
            AndroidView(
                factory = { createWebView() },
                modifier = Modifier.fillMaxSize(),
            )

            when (serverState) {
                OmochiServerManager.State.Stopped -> LoadingPanel("IDEセッションを開始しています…")
                is OmochiServerManager.State.Starting -> LoadingPanel(serverState.message)
                is OmochiServerManager.State.Failed -> ErrorPanel(serverState.message, onRetry)
                is OmochiServerManager.State.Running -> {
                    if (webError != null) ErrorPanel(webError, onRetry)
                }
            }

            browserNotice?.let {
                IosBanner(
                    message = it,
                    onDismiss = onDismissBrowserNotice,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .widthIn(max = BannerMaxWidth)
                        .fillMaxWidth(),
                )
            }
        }

        if (imeVisible) {
            // Reserves the keyboard's final height; read during layout only.
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(imeTarget),
            )
        } else {
            if (showTouchBar) {
                TouchControlPanel(
                    page = touchPanelPage,
                    ctrlLatched = ctrlLatched,
                    altLatched = altLatched,
                    shiftLatched = shiftLatched,
                    contentInsets = insets.sides,
                    onPageChange = onTouchPanelPage,
                    onToggleCtrl = onToggleCtrl,
                    onToggleAlt = onToggleAlt,
                    onToggleShift = onToggleShift,
                    onKey = onKey,
                    onShortcut = onShortcut,
                    onShowIme = onShowIme,
                )
            }

            WorkspaceDock(
                onExplorer = onExplorer,
                onSearch = onSearch,
                onSourceControl = onSourceControl,
                onRun = { onShortcut("runAndDebug") },
                onTerminal = onTerminal,
                onCommandPalette = onCommandPalette,
                modifier = Modifier.windowInsetsPadding(insets.bottom),
            )
        }
    }

    workspacePicker?.let { picker ->
        WorkspacePickerDialog(
            state = picker,
            selectedGuestPath = workspaceFolder,
            onBrowse = onBrowseWorkspaceFolder,
            onCreateFolder = onCreateWorkspaceFolder,
            onSelect = onSelectWorkspaceFolder,
            onDismiss = onDismissWorkspacePicker,
        )
    }
}

// ---------------------------------------------------------------------------
// Top bar

@Composable
private fun StudioTopBar(
    status: WorkbenchSessionStatus,
    syncLabel: String,
    syncLinked: Boolean,
    workspaceFolder: String,
    touchPanelVisible: Boolean,
    fullscreenEnabled: Boolean,
    onClose: () -> Unit,
    onToggleTouchPanel: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    onSyncFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only the decision is observed, so resizes that keep it do not recompose the bar.
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val showHomeLabel by remember(windowInfo, density) {
        derivedStateOf {
            val widthDp = with(density) { windowInfo.containerSize.width.toDp() }
            WorkbenchChromePolicy.showsHomeLabel(widthDp.value.toInt())
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .bottomHairline()
            .heightIn(min = TopBarHeight)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (showHomeLabel) {
            GlassCapsuleButton(
                text = "ホーム",
                onClick = onClose,
                icon = Icons.AutoMirrored.Rounded.ArrowBackIos,
                modifier = Modifier.semantics { contentDescription = "ホームへ戻る" },
            )
        } else {
            GlassIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBackIos,
                contentDescription = "ホームへ戻る",
                onClick = onClose,
            )
        }

        WorkbenchTitle(
            title = WorkbenchChromePolicy.folderTitle(workspaceFolder),
            status = status,
            syncLabel = syncLabel,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            GlassIconButton(
                icon = Icons.Rounded.FolderOpen,
                contentDescription = "作業フォルダーを変更: $workspaceFolder",
                onClick = onOpenWorkspacePicker,
                active = workspaceFolder != WorkspaceSession.GUEST_ROOT,
            )
            GlassIconButton(
                icon = Icons.Rounded.Sync,
                contentDescription = if (syncLinked) "端末フォルダを今すぐ同期" else "端末フォルダを連携",
                onClick = onSyncFolder,
                active = syncLinked,
            )
            GlassIconButton(
                icon = if (touchPanelVisible) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard,
                contentDescription = if (touchPanelVisible) "タッチキーを隠す" else "タッチキーを表示",
                onClick = onToggleTouchPanel,
                active = touchPanelVisible,
            )
            GlassIconButton(
                icon = if (fullscreenEnabled) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                contentDescription = if (fullscreenEnabled) "フルスクリーンを終了" else "フルスクリーン",
                onClick = onToggleFullscreen,
            )
        }
    }
}

@Composable
private fun WorkbenchTitle(
    title: String,
    status: WorkbenchSessionStatus,
    syncLabel: String,
    modifier: Modifier = Modifier,
) {
    val dotColor = when (status.tone) {
        WorkbenchSessionTone.Running -> IosColors.Green
        WorkbenchSessionTone.Pending -> IosColors.Orange
        WorkbenchSessionTone.Failed -> IosColors.Red
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.semantics(mergeDescendants = true) { heading() },
    ) {
        Text(
            text = title,
            style = IosType.Headline,
            color = IosColors.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "${status.label}・$syncLabel",
                style = IosType.Footnote,
                color = SecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Hairline between the top bar and the workbench below it. */
private fun Modifier.bottomHairline(): Modifier = drawBehind {
    val stroke = HairlineWidth.toPx().coerceAtLeast(1f)
    val y = size.height - stroke / 2f
    drawLine(
        color = IosColors.Separator,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = stroke,
    )
}

// ---------------------------------------------------------------------------
// Dock

/**
 * Floating glass tab bar in its own band below the WebView, so it never covers the
 * workbench's status bar. The six entries are actions, not tabs, so none is selected.
 */
@Composable
private fun WorkspaceDock(
    onExplorer: () -> Unit,
    onSearch: () -> Unit,
    onSourceControl: () -> Unit,
    onRun: () -> Unit,
    onTerminal: () -> Unit,
    onCommandPalette: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
    ) {
        GlassSurface(
            shape = CapsuleShape,
            modifier = Modifier
                .widthIn(max = DockMaxWidth)
                .fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DockHeight)
                    .padding(horizontal = 6.dp),
            ) {
                DockButton(Icons.Rounded.Folder, "ファイル", onExplorer, Modifier.weight(1f))
                DockButton(Icons.Rounded.Search, "検索", onSearch, Modifier.weight(1f))
                DockButton(Icons.Rounded.AccountTree, "Git", onSourceControl, Modifier.weight(1f))
                DockButton(Icons.Rounded.PlayArrow, "実行", onRun, Modifier.weight(1f))
                DockButton(Icons.Rounded.Terminal, "端末", onTerminal, Modifier.weight(1f))
                DockButton(Icons.Rounded.MoreHoriz, "コマンド", onCommandPalette, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .iosPressable(interactionSource, pressedScale = 0.9f, pressedAlpha = 0.6f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Icon(icon, contentDescription = null, tint = IosColors.Label, modifier = Modifier.size(24.dp))
        Text(
            text = label,
            style = DockLabelStyle,
            color = IosColors.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Touch key panel

/** Kind of a touch key cap, matching the light iOS keyboard. */
private enum class TouchKeyKind {
    /** White cap: arrows and edit commands. */
    Character,

    /** Darker gray cap: escape, tab, return, delete and unlatched modifiers. */
    Function,

    /** Blue cap with white text: a latched modifier or the Japanese input key. */
    Accent,
}

@Composable
private fun TouchControlPanel(
    page: TouchPanelPage,
    ctrlLatched: Boolean,
    altLatched: Boolean,
    shiftLatched: Boolean,
    contentInsets: WindowInsets,
    onPageChange: (TouchPanelPage) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onKey: (Int) -> Unit,
    onShortcut: (String) -> Unit,
    onShowIme: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(IosColors.KeyboardTray)
            .windowInsetsPadding(contentInsets)
            .padding(top = 4.dp, bottom = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            IosSegmentedControl(
                options = TouchPanelPageLabels,
                selectedIndex = page.ordinal,
                onSelect = { index -> TouchPanelPage.entries.getOrNull(index)?.let(onPageChange) },
                modifier = Modifier.width(SegmentedControlWidth),
            )
            Text(
                text = if (page == TouchPanelPage.Keys) "修飾キーは次の1入力に適用" else "よく使う編集操作",
                style = IosType.Footnote,
                color = TrayHintColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
        }

        // Each page keeps its own scroll position.
        key(page) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            ) {
                if (page == TouchPanelPage.Keys) {
                    TouchKey("ESC", kind = TouchKeyKind.Function) { onKey(KeyEvent.KEYCODE_ESCAPE) }
                    ModifierKey("CTRL", ctrlLatched, onToggleCtrl)
                    ModifierKey("ALT", altLatched, onToggleAlt)
                    ModifierKey("SHIFT", shiftLatched, onToggleShift)
                    TouchKey("TAB", kind = TouchKeyKind.Function) { onKey(KeyEvent.KEYCODE_TAB) }
                    TouchKey("←", description = "左矢印") { onKey(KeyEvent.KEYCODE_DPAD_LEFT) }
                    TouchKey("↑", description = "上矢印") { onKey(KeyEvent.KEYCODE_DPAD_UP) }
                    TouchKey("↓", description = "下矢印") { onKey(KeyEvent.KEYCODE_DPAD_DOWN) }
                    TouchKey("→", description = "右矢印") { onKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
                    TouchKey("ENTER", kind = TouchKeyKind.Function) { onKey(KeyEvent.KEYCODE_ENTER) }
                    TouchKey("⌫", kind = TouchKeyKind.Function, description = "バックスペース") {
                        onKey(KeyEvent.KEYCODE_DEL)
                    }
                } else {
                    TouchKey("保存") { onShortcut("save") }
                    TouchKey("元に戻す") { onShortcut("undo") }
                    TouchKey("やり直す") { onShortcut("redo") }
                    TouchKey("検索") { onShortcut("find") }
                    TouchKey("置換") { onShortcut("replace") }
                    TouchKey("ファイルを開く") { onShortcut("quickOpen") }
                    TouchKey("エディタ分割") { onShortcut("splitEditor") }
                    TouchKey("新しい端末") { onShortcut("newTerminal") }
                }
                TouchKey(
                    label = "日本語入力",
                    kind = TouchKeyKind.Accent,
                    icon = Icons.Rounded.Keyboard,
                    onClick = onShowIme,
                )
            }
        }
    }
}

/** A latching modifier: gray while off, blue while it waits for the next key. */
@Composable
private fun ModifierKey(label: String, latched: Boolean, onToggle: () -> Unit) {
    TouchKey(
        label = label,
        kind = if (latched) TouchKeyKind.Accent else TouchKeyKind.Function,
        latched = latched,
        onClick = onToggle,
    )
}

@Composable
private fun TouchKey(
    label: String,
    kind: TouchKeyKind = TouchKeyKind.Character,
    description: String? = null,
    latched: Boolean? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val fill = when (kind) {
        TouchKeyKind.Character -> IosColors.KeyCap
        TouchKeyKind.Function -> IosColors.KeyCapPressed
        TouchKeyKind.Accent -> KeyAccent
    }
    val contentColor = if (kind == TouchKeyKind.Accent) Color.White else IosColors.Label
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = KeyHeight)
            .widthIn(min = KeyMinWidth)
            .iosPressable(interactionSource, pressedScale = 0.96f, pressedAlpha = 0.6f)
            .keyCap(fill)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                if (description != null) contentDescription = description
                if (latched != null) stateDescription = if (latched) "オン" else "オフ"
            }
            // The bottom padding keeps the label centered on the cap above its shadow.
            .padding(start = 14.dp, end = 14.dp, bottom = KeyCapShadowDrop),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
        }
        Text(text = label, style = IosType.Callout, color = contentColor, maxLines = 1)
    }
}

/** iOS key cap: a rounded [fill] with a 1dp darker edge showing below it. */
private fun Modifier.keyCap(fill: Color): Modifier = drawWithCache {
    val radius = CornerRadius(KeyCapRadius.toPx())
    val drop = KeyCapShadowDrop.toPx()
    val capSize = Size(size.width, (size.height - drop).coerceAtLeast(0f))
    onDrawBehind {
        drawRoundRect(color = KeyCapShadow, topLeft = Offset(0f, drop), size = capSize, cornerRadius = radius)
        drawRoundRect(color = fill, size = capSize, cornerRadius = radius)
    }
}

// ---------------------------------------------------------------------------
// Workspace folder picker

/**
 * iOS style sheet for choosing the workspace folder. It floats above the bottom edge,
 * blurs the window behind it with Android 12+ window blur (composited by SurfaceFlinger,
 * so the WebView is never copied offscreen) and falls back to a denser dim without it.
 */
@Composable
private fun WorkspacePickerDialog(
    state: WorkspacePickerState,
    selectedGuestPath: String,
    onBrowse: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newFolderName by remember(state.currentGuestPath) { mutableStateOf("") }
    val parent = WorkspaceSession.parentGuestFolder(state.currentGuestPath)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // The sheet handles the insets itself so that it can sit above the keyboard.
            decorFitsSystemWindows = false,
            // Taps outside the sheet are handled below: the window is full-screen.
            dismissOnClickOutside = false,
        ),
    ) {
        val blurred = rememberDialogWindowBackdropBlur()
        DialogWindowDim(if (blurred) DialogScrimBlurred.alpha else DialogScrimOpaque.alpha)
        val entrance = remember { Animatable(1f) }
        LaunchedEffect(Unit) { entrance.animateTo(0f, iosSpring()) }

        Box(Modifier.fillMaxSize()) {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = null, indication = null, onClick = onDismiss)
                    // "キャンセル" and Back already dismiss for screen reader users.
                    .clearAndSetSemantics { },
            )
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = SheetMaxWidth)
                        .fillMaxWidth()
                        .heightIn(max = SheetMaxHeight)
                        // Slides up once when it appears; read in the layer only.
                        .graphicsLayer { translationY = entrance.value * size.height }
                        .glassShadow(SheetShape, elevation = 24.dp)
                        .background(
                            if (blurred) SheetFillOverBlur else IosColors.GroupedBackground,
                            SheetShape,
                        )
                        // Keeps taps on the sheet from reaching the dismiss area behind it.
                        .pointerInput(Unit) {}
                        .padding(bottom = 16.dp),
                ) {
                    SheetNavigationBar(
                        leading = { IosTextButton(text = "キャンセル", onClick = onDismiss) },
                        title = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.semantics(mergeDescendants = true) { heading() },
                            ) {
                                Text(
                                    text = "作業フォルダー",
                                    style = IosType.Headline,
                                    color = IosColors.Label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = state.currentGuestPath,
                                    style = IosType.Footnote,
                                    color = SecondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.StartEllipsis,
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )

                    Text(
                        text = "Omochi内の /workspace と、連携した端末フォルダーのミラーから選択できます。",
                        style = IosType.Footnote,
                        color = SecondaryText,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 8.dp),
                    )

                    IosGroupedSection(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(horizontal = 16.dp),
                    ) {
                        WorkspaceFolderList(
                            state = state,
                            parent = parent,
                            selectedGuestPath = selectedGuestPath,
                            onBrowse = onBrowse,
                        )
                    }

                    state.error?.let { error ->
                        Text(
                            text = error,
                            style = IosType.Footnote,
                            color = IosColors.RedText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, end = 32.dp, top = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }

                    IosGroupedSection(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    ) {
                        NewFolderRow(
                            name = newFolderName,
                            onNameChange = { newFolderName = it },
                            onCreate = { onCreateFolder(newFolderName) },
                        )
                    }

                    GlassCapsuleButton(
                        text = "ここで開始",
                        onClick = { onSelect(state.currentGuestPath) },
                        prominent = true,
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Parent row and subfolders, scrolling inside the section card. */
@Composable
private fun WorkspaceFolderList(
    state: WorkspacePickerState,
    parent: String?,
    selectedGuestPath: String,
    onBrowse: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        if (parent != null) {
            item(key = "parent") {
                IosListRow(
                    title = "ひとつ上のフォルダー",
                    subtitle = parent,
                    icon = Icons.Rounded.ArrowUpward,
                    iconTint = IosColors.Gray,
                    onClick = { onBrowse(parent) },
                )
            }
        }
        itemsIndexed(state.folders, key = { _, folder -> folder.guestPath }) { index, folder ->
            val selected = folder.guestPath == selectedGuestPath
            IosListRow(
                title = folder.displayName,
                subtitle = when {
                    selected -> "現在開いています"
                    folder.hasChildren -> "タップして中を見る"
                    else -> folder.guestPath
                },
                icon = Icons.Rounded.Folder,
                iconTint = IosColors.Blue,
                showChevron = true,
                showDivider = index != state.folders.lastIndex,
                onClick = { onBrowse(folder.guestPath) },
                trailing = if (selected) {
                    {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = IosColors.Blue,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
        if (state.folders.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "このフォルダー内にサブフォルダーはありません。",
                    style = IosType.Subheadline,
                    color = SecondaryText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun NewFolderRow(
    name: String,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val canCreate = WorkspaceSession.isValidFolderName(name.trim())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        IosSectionIcon(icon = Icons.Rounded.CreateNewFolder, tint = IosColors.Blue)
        Spacer(Modifier.width(12.dp))
        CompositionLocalProvider(LocalTextSelectionColors provides PickerSelectionColors) {
            BasicTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                textStyle = IosType.Body.copy(color = IosColors.Label),
                cursorBrush = SolidColor(IosColors.Blue),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canCreate) onCreate() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        // Merged into the field's semantics, so it doubles as its label.
                        if (name.isEmpty()) {
                            Text(
                                text = "新しいフォルダー名",
                                style = IosType.Body,
                                color = SecondaryText,
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        IosTextButton(
            text = "作成",
            onClick = onCreate,
            enabled = canCreate,
            modifier = Modifier.semantics { contentDescription = "フォルダーを作成" },
        )
    }
}

/**
 * iOS navigation bar for a sheet: [leading] at the start and [title] centered on the
 * whole bar, narrowed symmetrically so that it never runs under the leading button.
 */
@Composable
private fun SheetNavigationBar(
    leading: @Composable () -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        contents = listOf(leading, title),
        modifier = modifier,
    ) { (leadingMeasurables, titleMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leadingPlaceables = leadingMeasurables.map { it.measure(loose) }
        val side = leadingPlaceables.maxOfOrNull { it.width } ?: 0
        val titleMaxWidth = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - side * 2).coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }
        val titlePlaceables = titleMeasurables.map {
            it.measure(Constraints(maxWidth = titleMaxWidth, maxHeight = loose.maxHeight))
        }
        val titleWidth = titlePlaceables.maxOfOrNull { it.width } ?: 0
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else side * 2 + titleWidth
        val contentHeight = maxOf(
            leadingPlaceables.maxOfOrNull { it.height } ?: 0,
            titlePlaceables.maxOfOrNull { it.height } ?: 0,
        )
        val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            leadingPlaceables.forEach { it.placeRelative(0, (height - it.height) / 2) }
            titlePlaceables.forEach {
                it.placeRelative((width - it.width) / 2, (height - it.height) / 2)
            }
        }
    }
}

/** Sets the dim of the dialog window; like the blur, SurfaceFlinger composites it. */
@Composable
private fun DialogWindowDim(amount: Float) {
    val view = LocalView.current
    val window = remember(view) { view.findHostDialogWindow() }
    DisposableEffect(window, amount) {
        window?.run {
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(amount)
        }
        onDispose { }
    }
}

private fun View.findHostDialogWindow(): Window? {
    if (this is DialogWindowProvider) return window
    var current: ViewParent? = parent
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent
    }
    return null
}

// ---------------------------------------------------------------------------
// Overlays on the WebView

@Composable
private fun LoadingPanel(message: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(IosColors.GroupedBackground)
            // The half-loaded page underneath is hidden, so it must not take taps either.
            .pointerInput(Unit) {},
    ) {
        GlassSurface(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = PanelMaxWidth)
                .fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                IosActivityIndicator(size = 32.dp)
                Text(
                    text = message,
                    style = IosType.Callout,
                    color = IosColors.Label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    text = "このセッションはブラウザを開いても継続します",
                    style = IosType.Footnote,
                    color = SecondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(IosColors.GroupedBackground.copy(alpha = 0.97f))
            .pointerInput(Unit) {},
    ) {
        GlassSurface(
            modifier = Modifier
                .padding(22.dp)
                .widthIn(max = PanelMaxWidth)
                .fillMaxWidth(),
        ) {
            // A long server message scrolls instead of pushing the button off screen.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 24.dp),
            ) {
                Icon(
                    Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = IosColors.Orange,
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = "ワークベンチへ再接続できません",
                    style = ErrorTitleStyle,
                    color = IosColors.Label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .semantics { heading() },
                )
                Text(
                    text = message,
                    style = IosType.Subheadline,
                    color = SecondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 20.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                GlassCapsuleButton(
                    text = "IDEセッションを再起動",
                    onClick = onRetry,
                    icon = Icons.Rounded.Refresh,
                    prominent = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tokens

private val TopBarHeight = 60.dp
private val HairlineWidth = 0.5.dp
private val DockHeight = 60.dp
private val DockMaxWidth = 640.dp
private val BannerMaxWidth = 600.dp
private val PanelMaxWidth = 440.dp
private val SheetMaxWidth = 640.dp
private val SheetMaxHeight = 720.dp
private val SheetShape = ContinuousRoundedShape(38.dp)
private val SegmentedControlWidth = 176.dp
private val KeyHeight = 48.dp
private val KeyMinWidth = 48.dp
private val KeyCapRadius = 8.dp
private val KeyCapShadowDrop = 1.dp

/**
 * Secondary text. The translucent iOS secondary label is about 3.4:1 on white, below
 * 4.5:1 for footnote-sized text, so the opaque readable gray of the theme is used.
 */
private val SecondaryText = OmochiColors.Muted

/** Hint text on the key tray: #3C3C43 keeps 4.5:1 on the #D1D4DA tray. */
private val TrayHintColor = Color(0xFF3C3C43)

/** Edge under each key cap, as on the light iOS keyboard. */
private val KeyCapShadow = Color(0xFF898A8D)

/** System blue deepened just enough for 4.5:1 white labels, like the prominent buttons. */
private val KeyAccent: Color = readableTintedGlassFill(IosColors.Blue, alpha = 1f)

/** Grouped background that lets the window blur show through a little. */
private val SheetFillOverBlur = IosColors.GroupedBackground.copy(alpha = 0.92f)

private val TouchPanelPageLabels = listOf("キー", "編集")
private val DockLabelStyle = IosType.Caption2.copy(fontWeight = FontWeight.Medium)
private val ErrorTitleStyle = IosType.Title3.copy(fontWeight = FontWeight.SemiBold)
private val PickerSelectionColors = TextSelectionColors(
    handleColor = IosColors.Blue,
    backgroundColor = IosColors.Blue.copy(alpha = 0.3f),
)
