package io.github.hatake716.omochi

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    private val ui = HomeUiState(supported = OmochiRuntime.isSupportedAbi())

    /** Marker reads and preference writes, kept off the main thread after the first frame. */
    private val dashboardIo = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OmochiDashboardIo")
    }
    private var skipNextResumeRefresh = true
    private var runtimeRefreshGeneration = 0
    private var seccompWriteGeneration = 0
    private val pendingTransferProgress = AtomicReference<String?>(null)

    private val serverListener: (OmochiServerManager.State) -> Unit = { state ->
        ui.serverState = state
    }

    private val syncListener: (WorkspaceSyncManager.State) -> Unit = { state ->
        ui.syncState = state
    }

    private val openDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) importDocuments(uris)
    }

    private val importTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            persistAccess(it, write = false)
            importTree(it)
        }
    }

    private val exportTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            persistAccess(it, write = true)
            exportWorkspace(it)
        }
    }

    private val linkTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { WorkspaceSyncManager.connect(this, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light content: dark clock and battery icons over the light dashboard.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        ui.syncState = WorkspaceSyncManager.state(this)
        // The first frame must already show the real setup stage, so this read stays synchronous.
        applyRuntimeSnapshot(readRuntimeSnapshot(this))
        val actions = HomeActions(
            onSetup = ::startSetup,
            onOpenWorkbench = { openWorkbench(openTerminal = false) },
            onOpenClaude = { openWorkbench(openTerminal = true) },
            onStopSession = { OmochiServerService.stop(this) },
            onImportFiles = { openDocuments.launch(arrayOf("*/*")) },
            onImportFolder = { importTree.launch(null) },
            onExport = { exportTree.launch(null) },
            onLinkFolder = { linkTree.launch(null) },
            onSyncNow = { WorkspaceSyncManager.requestSync(this) },
            onDisconnectFolder = { WorkspaceSyncManager.disconnect(this) },
            onSeccompChange = ::setProotSeccomp,
            onLegal = { startActivity(Intent(this, LegalActivity::class.java)) },
        )
        setContent {
            OmochiTheme {
                HomeScreen(state = ui, actions = actions)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        OmochiServerManager.addListener(serverListener)
        WorkspaceSyncManager.addListener(syncListener)
    }

    override fun onStop() {
        WorkspaceSyncManager.removeListener(syncListener)
        OmochiServerManager.removeListener(serverListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // onCreate has just read the markers; later resumes (back from the workbench or a
        // picker) re-read them in the background.
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false
        } else if (!ui.setupRunning) {
            refreshRuntimeStateAsync()
        }
        WorkspaceSyncManager.requestSync(this, verifyBothSides = false)
    }

    override fun onDestroy() {
        // Queued preference writes still finish; nothing new is accepted.
        dashboardIo.shutdown()
        super.onDestroy()
    }

    private fun refreshRuntimeState() {
        applyRuntimeSnapshot(readRuntimeSnapshot(this))
    }

    private fun refreshRuntimeStateAsync() {
        val generation = ++runtimeRefreshGeneration
        val seccompGeneration = seccompWriteGeneration
        val app = applicationContext
        dashboardIo.execute {
            val snapshot = runCatching { readRuntimeSnapshot(app) }.getOrNull() ?: return@execute
            runOnUiThread {
                // A setup started meanwhile owns the message; a newer refresh supersedes this one.
                if (generation == runtimeRefreshGeneration && !ui.setupRunning) {
                    applyRuntimeSnapshot(
                        snapshot,
                        includeSeccomp = seccompGeneration == seccompWriteGeneration,
                    )
                }
            }
        }
    }

    private fun applyRuntimeSnapshot(
        snapshot: RuntimeSnapshot,
        updateMessage: Boolean = true,
        includeSeccomp: Boolean = true,
    ) {
        ui.installed = snapshot.installed
        ui.linuxInstalled = snapshot.linuxInstalled
        ui.japaneseClaudeInstalled = snapshot.japaneseClaudeInstalled
        ui.codeServerVersion = snapshot.codeServerVersion
        ui.claudeCodeVersion = snapshot.claudeCodeVersion
        if (includeSeccomp) {
            ui.seccompEnabled = snapshot.seccompEnabled
            ui.seccompFallbackReason = snapshot.seccompFallbackReason
        }
        if (updateMessage) {
            showSetupMessage(
                idleSetupMessage(
                    installed = snapshot.installed,
                    japaneseClaudeInstalled = snapshot.japaneseClaudeInstalled,
                    codeServerVersion = snapshot.codeServerVersion,
                    claudeCodeVersion = snapshot.claudeCodeVersion,
                )
            )
        }
    }

    private fun showSetupMessage(message: String, tone: MessageTone = MessageTone.Normal) {
        ui.setupMessage = compactForDisplay(message)
        ui.setupMessageTone = tone
    }

    private fun startSetup() {
        if (ui.setupRunning) return
        OmochiServerService.stop(this)
        // Drop a background refresh that would otherwise overwrite the progress message.
        runtimeRefreshGeneration++
        ui.setupRunning = true
        ui.setupPercent = 0
        showSetupMessage("セットアップを開始しています…")
        OmochiRuntime.installAll(
            context = this,
            onProgress = { value ->
                ui.setupPercent = value.percent ?: ui.setupPercent
                showSetupMessage(value.message)
            },
            onComplete = { result ->
                ui.setupRunning = false
                result.onSuccess {
                    refreshRuntimeState()
                    ui.setupPercent = 100
                    showSetupMessage("セットアップ完了。日本語ワークベンチを開けます。")
                }.onFailure {
                    runCatching { readRuntimeSnapshot(this) }.onSuccess { snapshot ->
                        applyRuntimeSnapshot(snapshot, updateMessage = false)
                    }
                    showSetupMessage(
                        "セットアップ失敗: ${it.message ?: it.javaClass.simpleName}",
                        MessageTone.Error,
                    )
                }
            }
        )
    }

    private fun openWorkbench(openTerminal: Boolean) {
        if (!OmochiRuntime.isInstalled(this)) {
            showSetupMessage("先に初回セットアップを完了してください。", MessageTone.Warning)
            return
        }
        val session = OmochiServerService.start(this)
        if (session.isFailure) {
            showSetupMessage(
                "IDEセッションを開始できません: " +
                    (session.exceptionOrNull()?.message ?: "Androidサービスエラー"),
                MessageTone.Error,
            )
            return
        }
        startActivity(
            Intent(this, WorkbenchActivity::class.java)
                .putExtra(WorkbenchActivity.EXTRA_OPEN_TERMINAL, openTerminal)
        )
    }

    private fun setProotSeccomp(enabled: Boolean) {
        // The switch moves at once; the preference is committed off the main thread.
        ui.seccompEnabled = enabled
        if (enabled) ui.seccompFallbackReason = null
        val generation = ++seccompWriteGeneration
        val app = applicationContext
        dashboardIo.execute {
            runCatching { OmochiRuntime.setProotSeccompPreference(app, enabled) }
            val actual = runCatching {
                OmochiRuntime.prootSeccompEnabled(app) to OmochiRuntime.prootSeccompFallbackReason(app)
            }.getOrNull()
            runOnUiThread {
                if (generation == seccompWriteGeneration && actual != null) {
                    ui.seccompEnabled = actual.first
                    ui.seccompFallbackReason = actual.second
                }
            }
        }
    }

    private fun importDocuments(uris: List<Uri>) {
        beginTransfer("ファイルを取り込んでいます…")
        WorkspaceTransfer.importDocuments(
            context = applicationContext,
            uris = uris,
            onProgress = { value ->
                postTransferProgress("${value.filesCopied}件取込: ${value.currentPath}")
            },
            onComplete = ::finishTransfer,
        )
    }

    private fun importTree(uri: Uri) {
        beginTransfer("フォルダを取り込んでいます…")
        WorkspaceTransfer.importTree(
            context = applicationContext,
            uri = uri,
            onProgress = { value ->
                postTransferProgress("${value.filesCopied}件取込: ${value.currentPath}")
            },
            onComplete = ::finishTransfer,
        )
    }

    private fun exportWorkspace(uri: Uri) {
        beginTransfer("ワークスペースを書き出しています…")
        WorkspaceTransfer.exportWorkspace(
            context = applicationContext,
            destinationTree = uri,
            onProgress = { value ->
                postTransferProgress("${value.filesCopied}件書出: ${value.currentPath}")
            },
            onComplete = ::finishTransfer,
        )
    }

    private fun beginTransfer(message: String) {
        pendingTransferProgress.set(null)
        ui.transferRunning = true
        ui.transferFailed = false
        ui.transferMessage = message
    }

    /**
     * Progress arrives for every copied file, often thousands per second. Only the newest
     * text matters, so at most one main-thread update is queued at a time.
     */
    private fun postTransferProgress(message: String) {
        if (pendingTransferProgress.getAndSet(message) != null) return
        runOnUiThread {
            val latest = pendingTransferProgress.getAndSet(null)
            if (latest != null && ui.transferRunning) ui.transferMessage = latest
        }
    }

    private fun finishTransfer(result: Result<WorkspaceTransfer.Summary>) {
        runOnUiThread {
            pendingTransferProgress.set(null)
            ui.transferRunning = false
            ui.transferFailed = result.isFailure
            ui.transferMessage = compactForDisplay(
                result.fold(
                    onSuccess = {
                        "完了: ${it.filesCopied}ファイル、${formatBytes(it.bytesCopied)} → ${it.destination}"
                    },
                    onFailure = { "転送失敗: ${it.message ?: it.javaClass.simpleName}" },
                )
            )
        }
    }

    private fun persistAccess(uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

// ---------------------------------------------------------------------------
// Screen state

private data class RuntimeSnapshot(
    val installed: Boolean,
    val linuxInstalled: Boolean,
    val japaneseClaudeInstalled: Boolean,
    val codeServerVersion: String?,
    val claudeCodeVersion: String?,
    val seccompEnabled: Boolean,
    val seccompFallbackReason: String?,
)

private fun readRuntimeSnapshot(context: Context): RuntimeSnapshot = RuntimeSnapshot(
    installed = OmochiRuntime.isInstalled(context),
    linuxInstalled = OmochiRuntime.isLinuxInstalled(context),
    japaneseClaudeInstalled = OmochiRuntime.isJapaneseClaudeInstalled(context),
    codeServerVersion = OmochiRuntime.installedVersion(context),
    claudeCodeVersion = OmochiRuntime.installedClaudeCodeVersion(context),
    seccompEnabled = OmochiRuntime.prootSeccompEnabled(context),
    seccompFallbackReason = OmochiRuntime.prootSeccompFallbackReason(context),
)

internal enum class MessageTone { Normal, Warning, Error }

/**
 * Dashboard state. Each section reads only the fields it shows, so setup progress, copy
 * progress and sync updates recompose a few small nodes instead of the whole screen.
 */
@Stable
private class HomeUiState(val supported: Boolean) {
    var installed by mutableStateOf(false)
    var linuxInstalled by mutableStateOf(false)
    var japaneseClaudeInstalled by mutableStateOf(false)
    var codeServerVersion by mutableStateOf<String?>(null)
    var claudeCodeVersion by mutableStateOf<String?>(null)
    var setupRunning by mutableStateOf(false)
    var setupPercent by mutableIntStateOf(0)
    var setupMessage by mutableStateOf("未セットアップ")
    var setupMessageTone by mutableStateOf(MessageTone.Normal)
    var transferRunning by mutableStateOf(false)
    var transferFailed by mutableStateOf(false)
    var transferMessage by mutableStateOf<String?>(null)
    var syncState by mutableStateOf<WorkspaceSyncManager.State>(WorkspaceSyncManager.State.Disconnected)
    var serverState by mutableStateOf(OmochiServerManager.state())
    var seccompEnabled by mutableStateOf(true)
    var seccompFallbackReason by mutableStateOf<String?>(null)
}

@Immutable
private class HomeActions(
    val onSetup: () -> Unit,
    val onOpenWorkbench: () -> Unit,
    val onOpenClaude: () -> Unit,
    val onStopSession: () -> Unit,
    val onImportFiles: () -> Unit,
    val onImportFolder: () -> Unit,
    val onExport: () -> Unit,
    val onLinkFolder: () -> Unit,
    val onSyncNow: () -> Unit,
    val onDisconnectFolder: () -> Unit,
    val onSeccompChange: (Boolean) -> Unit,
    val onLegal: () -> Unit,
)

private fun OmochiServerManager.State.isSessionActive(): Boolean =
    this is OmochiServerManager.State.Running || this is OmochiServerManager.State.Starting

private fun WorkspaceSyncManager.State.linkedFolder(): WorkspaceSyncManager.Link? = when (this) {
    WorkspaceSyncManager.State.Disconnected -> null
    is WorkspaceSyncManager.State.Ready -> link
    is WorkspaceSyncManager.State.Syncing -> link
    is WorkspaceSyncManager.State.Failed -> link
}

// ---------------------------------------------------------------------------
// Pure text rules (unit tested)

internal enum class SetupStage { NotInstalled, NeedsJapaneseClaude, Ready }

internal fun setupStage(installed: Boolean, japaneseClaudeInstalled: Boolean): SetupStage = when {
    installed && japaneseClaudeInstalled -> SetupStage.Ready
    installed -> SetupStage.NeedsJapaneseClaude
    else -> SetupStage.NotInstalled
}

internal fun heroHeadline(stage: SetupStage, sessionActive: Boolean): String = when (stage) {
    SetupStage.Ready -> if (sessionActive) "作業の続きを、すぐ開く。" else "コードを、指先のそばに。"
    SetupStage.NeedsJapaneseClaude -> "日本語UIとClaude Codeを追加します。"
    SetupStage.NotInstalled -> "Androidに、端末内IDEを。"
}

internal fun heroDescription(stage: SetupStage): String = when (stage) {
    SetupStage.Ready -> "編集・検索・Git・ターミナルを、タッチ向けドックから迷わず操作できます。"
    SetupStage.NeedsJapaneseClaude -> "既存のワークスペースを残したまま、必要な機能だけを追加します。"
    SetupStage.NotInstalled -> "Ubuntu Baseとcode-serverをアプリ領域へ展開し、127.0.0.1だけで動作します。"
}

internal fun idleSetupMessage(
    installed: Boolean,
    japaneseClaudeInstalled: Boolean,
    codeServerVersion: String?,
    claudeCodeVersion: String?,
    defaultCodeServerVersion: String = BuildConfig.CODE_SERVER_VERSION,
): String = when {
    installed && japaneseClaudeInstalled -> {
        val code = codeServerVersion ?: defaultCodeServerVersion
        val claude = claudeCodeVersion ?: "導入済み"
        "code-server $code・日本語UI・Claude Code $claude を利用できます"
    }
    installed -> "日本語UIとClaude Codeの追加セットアップが必要です"
    else -> "Linuxランタイム、IDE、日本語UI、Claude Codeを端末内へ準備します"
}

/** Status line of a linked folder after a finished pass. */
internal fun readySyncStatusText(
    lastSyncedAt: Long,
    summary: WorkspaceSyncManager.Summary?,
    formatTime: (Long) -> String = ::formatSyncTime,
): String {
    if (summary == null) return "自動同期を待機しています"
    val time = formatTime(lastSyncedAt)
    val synced = if (time.isEmpty()) "同期済み" else "$time 同期済み"
    if (summary.changed == 0) return "$synced・変更なし"
    return buildString {
        append(synced)
        if (summary.imported > 0) append("・取込${summary.imported}")
        if (summary.exported > 0) append("・書込${summary.exported}")
        val deleted = summary.deletedLocal + summary.deletedRemote
        if (deleted > 0) append("・削除$deleted")
        if (summary.conflicts > 0) append("・競合${summary.conflicts}")
    }
}

private fun syncStatusText(state: WorkspaceSyncManager.State): String = when (state) {
    is WorkspaceSyncManager.State.Ready -> readySyncStatusText(state.lastSyncedAt, state.summary)
    is WorkspaceSyncManager.State.Syncing -> state.message
    is WorkspaceSyncManager.State.Failed -> state.message
    WorkspaceSyncManager.State.Disconnected -> "未連携"
}

private fun formatSyncTime(timestamp: Long): String = if (timestamp <= 0L) {
    ""
} else {
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

/** Upper bound for status text shown on the dashboard (PRoot failures can carry kilobytes of log). */
internal const val DISPLAY_TEXT_LIMIT = 2_000
private const val LINE_SNAP_DISTANCE = 160

/**
 * Shortens [text] to at most [limit] characters by keeping its beginning (what failed)
 * and its end (usually the actual error in a log) around an omission marker. Cuts move
 * to a nearby line break when there is one and never split a surrogate pair.
 */
internal fun compactForDisplay(text: String, limit: Int = DISPLAY_TEXT_LIMIT): String {
    if (text.length <= limit) return text
    val budget = (limit - omissionMarker(text.length).length).coerceAtLeast(2)
    var headEnd = budget * 2 / 5
    var tailStart = text.length - (budget - headEnd)
    val headBreak = text.lastIndexOf('\n', headEnd - 1)
    if (headBreak >= 0 && headEnd - headBreak <= LINE_SNAP_DISTANCE) headEnd = headBreak
    val tailBreak = text.indexOf('\n', tailStart)
    if (tailBreak >= 0 && tailBreak - tailStart <= LINE_SNAP_DISTANCE) tailStart = tailBreak + 1
    if (headEnd > 0 && text[headEnd - 1].isHighSurrogate()) headEnd--
    if (tailStart < text.length && text[tailStart].isLowSurrogate()) tailStart++
    return text.substring(0, headEnd).trimEnd() +
        omissionMarker(tailStart - headEnd) +
        text.substring(tailStart).trimStart()
}

private fun omissionMarker(omitted: Int): String = "\n…(途中の${omitted}文字を省略)…\n"

// ---------------------------------------------------------------------------
// Layout

private val ScreenGutter = 16.dp
private val SectionGap = 28.dp
private val MaxContentWidth = 720.dp
private val HeroShape = ContinuousRoundedShape(26.dp)
private val SessionRowShape = ContinuousRoundedShape(18.dp)
/** Where an [IosListRow] with an icon starts its title: padding + 29dp tile + gap. */
private val RowTextInset = 16.dp + 29.dp + 12.dp

/** Readable variants for small text on light tints (4.5:1 or better). */
private val GreenText = Color(0xFF1E7B34)
private val IndigoText = Color(0xFF3634A3)
private val GrayText = Color(0xFF3C3C43)

/**
 * Weight variants of [IosType], built once. An object keeps them out of this file's
 * static initializer, which the JVM tests of the text rules above load.
 */
private object DashboardType {
    val HeroHeadline = IosType.Title2.copy(fontWeight = FontWeight.Bold)
    val EmphasizedSubheadline = IosType.Subheadline.copy(fontWeight = FontWeight.SemiBold)
    val EmphasizedFootnote = IosType.Footnote.copy(fontWeight = FontWeight.SemiBold)
    val CapsuleLabel = IosType.Caption1.copy(fontWeight = FontWeight.SemiBold)
}

@Composable
private fun HomeScreen(state: HomeUiState, actions: HomeActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Static brush: one shader per size change, nothing animates behind the lists.
            .background(AmbientBackground)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = ScreenGutter),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            HomeTitle(state)
            HeroCard(state, actions)
            if (state.installed) QuickSection(state, actions)
            if (state.installed && state.japaneseClaudeInstalled) {
                ClaudeSection(state, actions.onOpenClaude)
            }
            RuntimeSection(state)
            PerformanceSection(state, actions.onSeccompChange)
            InfoSection(actions.onLegal)
        }
        // Content scrolls behind the gesture bar but can always be lifted above it.
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HomeTitle(state: HomeUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 12.dp),
    ) {
        IosLargeTitle(
            title = "Omochi",
            subtitle = "端末内で動く、タッチ向けIDE",
            modifier = Modifier.weight(1f),
        )
        SessionChip(state)
    }
}

@Composable
private fun SessionChip(state: HomeUiState) {
    // Starting publishes progress messages; only the active/idle flip matters here.
    val active by remember(state) { derivedStateOf { state.serverState.isSessionActive() } }
    val label = if (active) "実行中" else "ローカル"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 12.dp)
            .liquidGlass(CapsuleShape, highlight = 0.7f)
            .semantics(mergeDescendants = true) { contentDescription = "IDEセッション: $label" }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (active) IosColors.Green else IosColors.Gray3, CircleShape)
        )
        Text(
            text = label,
            style = DashboardType.EmphasizedFootnote,
            color = OmochiColors.Muted,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun HeroCard(state: HomeUiState, actions: HomeActions) {
    val installed = state.installed
    val japaneseClaudeInstalled = state.japaneseClaudeInstalled
    val running = state.setupRunning
    val sessionActive by remember(state) { derivedStateOf { state.serverState.isSessionActive() } }
    val stage = setupStage(installed, japaneseClaudeInstalled)
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = HeroShape) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroTags(stage)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = heroHeadline(stage, sessionActive),
                    style = DashboardType.HeroHeadline,
                    color = IosColors.Label,
                )
                Text(
                    text = heroDescription(stage),
                    style = IosType.Subheadline,
                    color = OmochiColors.Muted,
                )
                SetupMessage(state)
            }

            if (japaneseClaudeInstalled) InstalledCheck(state)

            if (!state.supported) {
                Text(
                    text = "このビルドの内蔵LinuxランタイムはARM64端末専用です。",
                    style = IosType.Subheadline,
                    color = IosColors.RedText,
                )
            }

            SessionFailure(state)

            if (running) {
                SetupProgress(state)
                SetupRunningIndicator()
            } else {
                HeroActions(stage, sessionActive, state.supported, actions)
            }

            if (sessionActive && !running) SessionRow(actions.onStopSession)
        }
    }
}

@Composable
private fun HeroTags(stage: SetupStage) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (stage) {
            SetupStage.Ready -> Tag("準備完了", GreenText, IosColors.Green.copy(alpha = 0.14f))
            SetupStage.NeedsJapaneseClaude -> Tag("追加設定あり", IosColors.BluePressed, IosColors.Blue.copy(alpha = 0.12f))
            SetupStage.NotInstalled -> Tag("初回設定", IosColors.BluePressed, IosColors.Blue.copy(alpha = 0.12f))
        }
        Tag("端末内完結", IndigoText, IosColors.Indigo.copy(alpha = 0.12f))
        Tag("root不要", GrayText, IosColors.TertiarySystemFill)
    }
}

@Composable
private fun Tag(text: String, ink: Color, fill: Color) {
    Text(
        text = text,
        style = DashboardType.CapsuleLabel,
        color = ink,
        modifier = Modifier
            .background(fill, CapsuleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun SetupMessage(state: HomeUiState) {
    val message = state.setupMessage
    when (state.setupMessageTone) {
        MessageTone.Normal -> Text(
            text = message,
            style = IosType.Footnote,
            color = OmochiColors.Muted,
        )
        MessageTone.Warning -> Text(
            text = message,
            style = IosType.Footnote,
            color = IosColors.OrangeText,
        )
        // Failures stay selectable so the log can be copied into a bug report.
        MessageTone.Error -> SelectionContainer {
            Text(
                text = message,
                style = IosType.Footnote,
                color = IosColors.RedText,
            )
        }
    }
}

@Composable
private fun InstalledCheck(state: HomeUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = IosColors.Green,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "日本語UI・Claude Code ${state.claudeCodeVersion ?: "導入済み"}",
            style = DashboardType.EmphasizedSubheadline,
            color = GreenText,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SessionFailure(state: HomeUiState) {
    val failure by remember(state) {
        derivedStateOf { (state.serverState as? OmochiServerManager.State.Failed)?.message }
    }
    val message = failure ?: return
    Text(
        text = "IDEセッションが停止しました: ${compactForDisplay(message, 600)}",
        style = IosType.Footnote,
        color = IosColors.RedText,
    )
}

@Composable
private fun SetupProgress(state: HomeUiState) {
    val percent = state.setupPercent.coerceIn(0, 100)
    // The bar eases between reported values; the animation is read in the draw phase only.
    val fraction = animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = iosSpring(),
        label = "setupProgress",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .progressSemantics(percent / 100f)
                .drawBehind {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(color = IosColors.Gray5, cornerRadius = radius)
                    val width = size.width * fraction.value.coerceIn(0f, 1f)
                    if (width > 0f) {
                        val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                        drawRoundRect(
                            color = IosColors.Blue,
                            topLeft = Offset(left, 0f),
                            size = Size(width, size.height),
                            cornerRadius = radius,
                        )
                    }
                },
        )
        Text(
            text = "セットアップ $percent%",
            style = DashboardType.EmphasizedFootnote,
            color = OmochiColors.Muted,
        )
    }
}

@Composable
private fun SetupRunningIndicator() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .background(IosColors.TertiarySystemFill, CapsuleShape)
            .semantics(mergeDescendants = true) {},
    ) {
        IosActivityIndicator(size = 20.dp)
        Text(
            text = "セットアップ中",
            style = IosType.Headline,
            color = OmochiColors.Muted,
        )
    }
}

@Composable
private fun HeroActions(
    stage: SetupStage,
    sessionActive: Boolean,
    supported: Boolean,
    actions: HomeActions,
) {
    val wide = Modifier
        .fillMaxWidth()
        .heightIn(min = 50.dp)
    when (stage) {
        SetupStage.Ready -> GlassCapsuleButton(
            text = if (sessionActive) "ワークベンチに戻る" else "ワークベンチを開く",
            onClick = actions.onOpenWorkbench,
            icon = Icons.Rounded.FolderOpen,
            prominent = true,
            modifier = wide,
        )
        SetupStage.NeedsJapaneseClaude -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCapsuleButton(
                text = "日本語UIとClaude Codeを導入",
                onClick = actions.onSetup,
                icon = Icons.Rounded.Download,
                prominent = true,
                enabled = supported,
                modifier = wide,
            )
            GlassCapsuleButton(
                text = "現在のワークベンチを開く",
                onClick = actions.onOpenWorkbench,
                modifier = wide,
            )
        }
        SetupStage.NotInstalled -> GlassCapsuleButton(
            text = "Omochiをセットアップ",
            onClick = actions.onSetup,
            icon = Icons.Rounded.Download,
            prominent = true,
            enabled = supported,
            modifier = wide,
        )
    }
}

@Composable
private fun SessionRow(onStopSession: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(IosColors.Green.copy(alpha = 0.10f), SessionRowShape)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(IosColors.Green, CircleShape)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                text = "IDEセッション実行中",
                style = DashboardType.EmphasizedSubheadline,
                color = IosColors.Label,
            )
            Text(
                text = "ブラウザ認証中もターミナルを維持します",
                style = IosType.Footnote,
                color = OmochiColors.Muted,
            )
        }
        GlassCapsuleButton(
            text = "停止",
            onClick = onStopSession,
            icon = Icons.Rounded.Stop,
            destructive = true,
        )
    }
}

// ---------------------------------------------------------------------------
// Grouped sections

@Composable
private fun QuickSection(state: HomeUiState, actions: HomeActions) {
    // The completion message stays after a transfer, so this flips only rarely.
    val hasTransferStatus by remember(state) {
        derivedStateOf { state.transferRunning || state.transferMessage != null }
    }
    IosGroupedSection(header = "すぐ使う") {
        SyncRows(state, actions)
        TransferRows(state, actions, lastRowDivider = hasTransferStatus)
        if (hasTransferStatus) TransferStatus(state)
    }
}

@Composable
private fun SyncRows(state: HomeUiState, actions: HomeActions) {
    val sync = state.syncState
    val syncing = sync is WorkspaceSyncManager.State.Syncing
    val failed = sync is WorkspaceSyncManager.State.Failed
    val link = sync.linkedFolder()
    if (link == null) {
        IosListRow(
            title = "端末フォルダをリアルタイム連携",
            subtitle = "選択したフォルダへ保存内容を双方向同期",
            icon = Icons.Rounded.Sync,
            iconTint = IosColors.Green,
            showChevron = !syncing,
            showDivider = !failed,
            enabled = !syncing,
            onClick = actions.onLinkFolder,
            trailing = if (syncing) {
                { IosActivityIndicator(size = 20.dp) }
            } else {
                null
            },
        )
        if (sync is WorkspaceSyncManager.State.Failed) {
            RowNotes {
                Text(
                    text = compactForDisplay(sync.message),
                    style = IosType.Footnote,
                    color = IosColors.RedText,
                )
            }
        }
        return
    }

    IosListRow(
        title = link.label,
        modifier = Modifier.semantics(mergeDescendants = true) {},
        subtitle = "${link.workspacePath} ⇄ 端末",
        icon = if (failed) Icons.Rounded.SyncProblem else Icons.Rounded.Sync,
        iconTint = if (failed) IosColors.Red else IosColors.Green,
        showDivider = false,
        trailing = { SyncBadge(syncing = syncing, failed = failed) },
    )
    RowNotes {
        Text(
            text = compactForDisplay(syncStatusText(sync)),
            style = IosType.Footnote,
            color = if (failed) IosColors.RedText else OmochiColors.Muted,
        )
        if (sync is WorkspaceSyncManager.State.Ready) {
            sync.summary?.let { summary ->
                if (summary.conflicts > 0) {
                    Text(
                        text = "同時編集した端末側の版を .device-conflict-日時 付きで両側に保存しました。",
                        style = IosType.Footnote,
                        color = IosColors.OrangeText,
                    )
                }
                summary.recoveryPath?.let { recovery ->
                    Text(
                        text = "削除・置換前の回復コピー: $recovery",
                        style = IosType.Footnote,
                        color = OmochiColors.Muted,
                    )
                }
            }
        }
    }
    IosListRow(
        title = "今すぐ同期",
        icon = Icons.Rounded.Refresh,
        iconTint = IosColors.Blue,
        enabled = !syncing,
        onClick = actions.onSyncNow,
    )
    if (failed) {
        IosListRow(
            title = "選び直す",
            subtitle = "連携する端末フォルダを選択し直します",
            icon = Icons.Rounded.FolderOpen,
            iconTint = IosColors.Orange,
            showChevron = true,
            enabled = !syncing,
            onClick = actions.onLinkFolder,
        )
    }
    IosListRow(
        title = "連携解除",
        subtitle = "両側のファイルはそのまま残ります",
        icon = Icons.Rounded.LinkOff,
        iconTint = IosColors.Red,
        enabled = !syncing,
        onClick = actions.onDisconnectFolder,
    )
}

@Composable
private fun SyncBadge(syncing: Boolean, failed: Boolean) {
    val (text, ink, fill) = when {
        syncing -> Triple("同期中", IosColors.BluePressed, IosColors.Blue.copy(alpha = 0.12f))
        failed -> Triple("要確認", IosColors.RedText, IosColors.Red.copy(alpha = 0.12f))
        else -> Triple("自動同期", GreenText, IosColors.Green.copy(alpha = 0.14f))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(fill, CapsuleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        if (syncing) {
            IosActivityIndicator(size = 14.dp, color = IosColors.BluePressed)
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = DashboardType.CapsuleLabel,
            color = ink,
        )
    }
}

@Composable
private fun TransferRows(state: HomeUiState, actions: HomeActions, lastRowDivider: Boolean) {
    val enabled = !state.transferRunning
    IosListRow(
        title = "ファイルを取り込む",
        subtitle = "Androidのファイル選択から /workspace へコピー",
        icon = Icons.Rounded.FileOpen,
        iconTint = IosColors.Blue,
        showChevron = true,
        enabled = enabled,
        onClick = actions.onImportFiles,
    )
    IosListRow(
        title = "フォルダを取り込む",
        subtitle = "選択したフォルダ構造を保ってコピー",
        icon = Icons.Rounded.FolderCopy,
        iconTint = IosColors.Indigo,
        showChevron = true,
        enabled = enabled,
        onClick = actions.onImportFolder,
    )
    IosListRow(
        title = "ワークスペースを書き出す",
        subtitle = ".gitを除く作業ファイルをSAFへ保存",
        icon = Icons.Rounded.IosShare,
        iconTint = IosColors.Orange,
        showChevron = true,
        showDivider = lastRowDivider,
        enabled = enabled,
        onClick = actions.onExport,
    )
}

@Composable
private fun TransferStatus(state: HomeUiState) {
    val running = state.transferRunning
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (running) {
            IosActivityIndicator(size = 18.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = state.transferMessage.orEmpty(),
            style = IosType.Footnote,
            color = if (state.transferFailed) IosColors.RedText else OmochiColors.Muted,
            // Per-file progress stays on two lines so the list does not jump while copying.
            maxLines = if (running) 2 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ClaudeSection(state: HomeUiState, onOpenClaude: () -> Unit) {
    IosGroupedSection(
        header = "Claude Code",
        footer = "統合ターミナルを開いて「claude」を実行します。" +
            "ログイン用ブラウザへ移動しても、OmochiのIDEとターミナルは背後で継続します。",
    ) {
        IosListRow(
            title = "ターミナルでClaudeを開く",
            subtitle = "Claude Code ${state.claudeCodeVersion ?: "導入済み"}・公式stable",
            icon = Icons.Rounded.Terminal,
            iconTint = IosColors.Orange,
            showChevron = true,
            showDivider = false,
            onClick = onOpenClaude,
        )
    }
}

@Composable
private fun RuntimeSection(state: HomeUiState) {
    val installed = state.installed
    val linuxInstalled = state.linuxInstalled
    val japaneseClaudeInstalled = state.japaneseClaudeInstalled
    val pending = if (installed) "追加セットアップ待ち" else "セットアップ待ち"
    IosGroupedSection(
        header = "ランタイム",
        footer = "保存領域・ネットワーク境界・導入状態をひと目で確認できます。",
    ) {
        RuntimeRow(
            title = "Ubuntu / PRoot",
            detail = "アプリ専用領域・/workspace",
            icon = Icons.Rounded.Storage,
            readyTint = IosColors.Orange,
            ready = linuxInstalled,
            value = if (linuxInstalled) OmochiRuntime.UBUNTU_RELEASE else "セットアップ待ち",
        )
        RuntimeRow(
            title = "code-server",
            detail = "127.0.0.1のみ・ローカル自動認証",
            icon = Icons.Rounded.Code,
            readyTint = IosColors.Blue,
            ready = installed,
            value = if (installed) {
                state.codeServerVersion ?: BuildConfig.CODE_SERVER_VERSION
            } else {
                "セットアップ待ち"
            },
        )
        RuntimeRow(
            title = "日本語UI",
            detail = "Microsoft 日本語Language Pack",
            icon = Icons.Rounded.Translate,
            readyTint = IosColors.Indigo,
            ready = japaneseClaudeInstalled,
            value = if (japaneseClaudeInstalled) "導入済み" else pending,
        )
        RuntimeRow(
            title = "Claude Code",
            detail = "Anthropic公式stable",
            icon = Icons.Rounded.Terminal,
            readyTint = IosColors.Pink,
            ready = japaneseClaudeInstalled,
            value = state.claudeCodeVersion ?: pending,
            showDivider = false,
        )
    }
}

@Composable
private fun RuntimeRow(
    title: String,
    detail: String,
    icon: ImageVector,
    readyTint: Color,
    ready: Boolean,
    value: String,
    showDivider: Boolean = true,
) {
    // A gray tile marks a component that is not ready yet; the value says why.
    IosListRow(
        title = title,
        // Read-only rows are announced as one item: name, detail and state.
        modifier = Modifier.semantics(mergeDescendants = true) {},
        subtitle = detail,
        icon = icon,
        iconTint = if (ready) readyTint else IosColors.Gray2,
        value = value,
        showDivider = showDivider,
    )
}

@Composable
private fun PerformanceSection(state: HomeUiState, onSeccompChange: (Boolean) -> Unit) {
    val reason = state.seccompFallbackReason
    IosGroupedSection(
        header = "パフォーマンス",
        footer = "互換性の問題がある端末では自動的にオフになります。変更は次回のIDE起動から有効です。",
    ) {
        IosListRow(
            title = "高速モード(PRoot seccomp)",
            modifier = Modifier.semantics(mergeDescendants = true) {},
            subtitle = "システムコールの中継を減らし、IDEとターミナルを軽くします",
            icon = Icons.Rounded.Bolt,
            iconTint = IosColors.Orange,
            showDivider = false,
            trailing = {
                IosSwitch(
                    checked = state.seccompEnabled,
                    onCheckedChange = onSeccompChange,
                    modifier = Modifier.semantics { contentDescription = "高速モード(PRoot seccomp)" },
                )
            },
        )
        if (reason != null) {
            RowNotes(showDivider = false) {
                Text(
                    text = "起動に失敗したため自動的にオフにしました: ${compactForDisplay(reason, 600)}",
                    style = IosType.Footnote,
                    color = IosColors.OrangeText,
                )
            }
        }
    }
}

@Composable
private fun InfoSection(onLegal: () -> Unit) {
    IosGroupedSection(
        header = "情報",
        footer = "OmochiはVisual Studio Code、Microsoft、Coder、Anthropicとは提携していません。" +
            "IDEは端末内の127.0.0.1だけで待ち受けます。",
    ) {
        IosListRow(
            title = "ライセンスとソース",
            subtitle = "公式配布物の検証情報、PRoot対応ソース、第三者ライセンス",
            icon = Icons.Rounded.Info,
            iconTint = IosColors.Gray,
            showChevron = true,
            onClick = onLegal,
        )
        IosListRow(
            title = "バージョン",
            modifier = Modifier.semantics(mergeDescendants = true) {},
            icon = Icons.Rounded.Tag,
            iconTint = IosColors.Gray,
            value = BuildConfig.VERSION_NAME,
            showDivider = false,
        )
    }
}

/**
 * Explanatory lines under an [IosListRow] with an icon, aligned with its title. The
 * hairline below keeps the list rhythm when more rows follow.
 */
@Composable
private fun RowNotes(
    showDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (showDivider) Modifier.bottomHairline(RowTextInset) else Modifier)
            .padding(start = RowTextInset, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

private fun Modifier.bottomHairline(startInset: Dp): Modifier = drawBehind {
    val stroke = 0.5.dp.toPx().coerceAtLeast(1f)
    val inset = startInset.toPx()
    val y = size.height - stroke / 2f
    val (startX, endX) = if (layoutDirection == LayoutDirection.Rtl) {
        0f to size.width - inset
    } else {
        inset to size.width
    }
    drawLine(
        color = IosColors.Separator,
        start = Offset(startX, y),
        end = Offset(endX, y),
        strokeWidth = stroke,
    )
}
