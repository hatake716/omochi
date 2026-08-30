package io.github.hatake716.omochi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var installed by mutableStateOf(false)
    private var japaneseClaudeInstalled by mutableStateOf(false)
    private var claudeCodeVersion by mutableStateOf<String?>(null)
    private var setupRunning by mutableStateOf(false)
    private var setupPercent by mutableIntStateOf(0)
    private var setupMessage by mutableStateOf("未セットアップ")
    private var transferRunning by mutableStateOf(false)
    private var transferMessage by mutableStateOf<String?>(null)
    private var serverState by mutableStateOf<OmochiServerManager.State>(
        OmochiServerManager.state()
    )

    private val serverListener: (OmochiServerManager.State) -> Unit = { state ->
        serverState = state
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshRuntimeState()
        setContent {
            OmochiTheme {
                HomeScreen(
                    installed = installed,
                    japaneseClaudeInstalled = japaneseClaudeInstalled,
                    claudeCodeVersion = claudeCodeVersion,
                    supported = OmochiRuntime.isSupportedAbi(),
                    setupRunning = setupRunning,
                    setupPercent = setupPercent,
                    setupMessage = setupMessage,
                    transferRunning = transferRunning,
                    transferMessage = transferMessage,
                    serverState = serverState,
                    onSetup = ::startSetup,
                    onOpenWorkbench = { openWorkbench(openTerminal = false) },
                    onOpenClaude = { openWorkbench(openTerminal = true) },
                    onStopSession = { OmochiServerService.stop(this) },
                    onImportFiles = { openDocuments.launch(arrayOf("*/*")) },
                    onImportFolder = { importTree.launch(null) },
                    onExport = { exportTree.launch(null) },
                    onLegal = { startActivity(Intent(this, LegalActivity::class.java)) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        OmochiServerManager.addListener(serverListener)
    }

    override fun onStop() {
        OmochiServerManager.removeListener(serverListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!setupRunning) refreshRuntimeState()
    }

    private fun refreshRuntimeState() {
        installed = OmochiRuntime.isInstalled(this)
        japaneseClaudeInstalled = OmochiRuntime.isJapaneseClaudeInstalled(this)
        claudeCodeVersion = OmochiRuntime.installedClaudeCodeVersion(this)
        setupMessage = when {
            installed && japaneseClaudeInstalled -> {
                val code = OmochiRuntime.installedVersion(this) ?: BuildConfig.CODE_SERVER_VERSION
                val claude = claudeCodeVersion ?: "導入済み"
                "code-server $code・日本語UI・Claude Code $claude を利用できます"
            }
            installed -> "日本語UIとClaude Codeの追加セットアップが必要です"
            else -> "Linuxランタイム、IDE、日本語UI、Claude Codeを端末内へ準備します"
        }
    }

    private fun startSetup() {
        if (setupRunning) return
        OmochiServerService.stop(this)
        setupRunning = true
        setupPercent = 0
        setupMessage = "セットアップを開始しています…"
        OmochiRuntime.installAll(
            context = this,
            onProgress = { value ->
                setupPercent = value.percent ?: setupPercent
                setupMessage = value.message
            },
            onComplete = { result ->
                setupRunning = false
                result.onSuccess {
                    refreshRuntimeState()
                    setupPercent = 100
                    setupMessage = "セットアップ完了。日本語ワークベンチを開けます。"
                }.onFailure {
                    installed = OmochiRuntime.isInstalled(this)
                    japaneseClaudeInstalled = OmochiRuntime.isJapaneseClaudeInstalled(this)
                    claudeCodeVersion = OmochiRuntime.installedClaudeCodeVersion(this)
                    setupMessage = "セットアップ失敗: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        )
    }

    private fun openWorkbench(openTerminal: Boolean) {
        if (!OmochiRuntime.isInstalled(this)) {
            setupMessage = "先に初回セットアップを完了してください。"
            return
        }
        val session = OmochiServerService.start(this)
        if (session.isFailure) {
            setupMessage = "IDEセッションを開始できません: " +
                (session.exceptionOrNull()?.message ?: "Androidサービスエラー")
            return
        }
        startActivity(
            Intent(this, WorkbenchActivity::class.java)
                .putExtra(WorkbenchActivity.EXTRA_OPEN_TERMINAL, openTerminal)
        )
    }

    private fun importDocuments(uris: List<Uri>) {
        transferRunning = true
        transferMessage = "ファイルを取り込んでいます…"
        WorkspaceTransfer.importDocuments(
            context = applicationContext,
            uris = uris,
            onProgress = { value ->
                runOnUiThread {
                    transferMessage = "${value.filesCopied}件取込: ${value.currentPath}"
                }
            },
            onComplete = ::finishTransfer,
        )
    }

    private fun importTree(uri: Uri) {
        transferRunning = true
        transferMessage = "フォルダを取り込んでいます…"
        WorkspaceTransfer.importTree(
            context = applicationContext,
            uri = uri,
            onProgress = { value ->
                runOnUiThread {
                    transferMessage = "${value.filesCopied}件取込: ${value.currentPath}"
                }
            },
            onComplete = ::finishTransfer,
        )
    }

    private fun exportWorkspace(uri: Uri) {
        transferRunning = true
        transferMessage = "ワークスペースを書き出しています…"
        WorkspaceTransfer.exportWorkspace(
            context = applicationContext,
            destinationTree = uri,
            onProgress = { value ->
                runOnUiThread {
                    transferMessage = "${value.filesCopied}件書出: ${value.currentPath}"
                }
            },
            onComplete = ::finishTransfer,
        )
    }

    private fun finishTransfer(result: Result<WorkspaceTransfer.Summary>) {
        runOnUiThread {
            transferRunning = false
            transferMessage = result.fold(
                onSuccess = {
                    "完了: ${it.filesCopied}ファイル、${formatBytes(it.bytesCopied)} → ${it.destination}"
                },
                onFailure = { "転送失敗: ${it.message ?: it.javaClass.simpleName}" },
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

@Composable
private fun HomeScreen(
    installed: Boolean,
    japaneseClaudeInstalled: Boolean,
    claudeCodeVersion: String?,
    supported: Boolean,
    setupRunning: Boolean,
    setupPercent: Int,
    setupMessage: String,
    transferRunning: Boolean,
    transferMessage: String?,
    serverState: OmochiServerManager.State,
    onSetup: () -> Unit,
    onOpenWorkbench: () -> Unit,
    onOpenClaude: () -> Unit,
    onStopSession: () -> Unit,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onExport: () -> Unit,
    onLegal: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFF8F5F2), OmochiColors.Window, Color(0xFFEDE7E2))
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 780.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HomeWindowBar(serverState)

            WelcomePanel(
                installed = installed,
                japaneseClaudeInstalled = japaneseClaudeInstalled,
                claudeCodeVersion = claudeCodeVersion,
                supported = supported,
                running = setupRunning,
                percent = setupPercent,
                message = setupMessage,
                serverState = serverState,
                onSetup = onSetup,
                onOpen = onOpenWorkbench,
                onStopSession = onStopSession,
            )

            if (installed) {
                SectionHeading("すぐ使う", "指先で選びやすい大きな操作にまとめました")
                QuickActionsPanel(
                    transferRunning = transferRunning,
                    transferMessage = transferMessage,
                    onImportFiles = onImportFiles,
                    onImportFolder = onImportFolder,
                    onExport = onExport,
                )
            }

            if (installed && japaneseClaudeInstalled) {
                ClaudePanel(
                    version = claudeCodeVersion,
                    onOpenClaude = onOpenClaude,
                )
            }

            RuntimePanel(
                installed = installed,
                japaneseClaudeInstalled = japaneseClaudeInstalled,
                claudeCodeVersion = claudeCodeVersion,
            )

            CapabilityPanel()

            LegalPanel(onLegal)

            Text(
                text = "OmochiはVisual Studio Code、Microsoft、Coder、Anthropicとは提携していません。" +
                    "IDEは端末内の127.0.0.1だけで待ち受けます。",
                style = MaterialTheme.typography.bodyMedium,
                color = OmochiColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun HomeWindowBar(state: OmochiServerManager.State) {
    val active = state is OmochiServerManager.State.Running ||
        state is OmochiServerManager.State.Starting
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(Color(0xE6FFFFFF))
            .border(1.dp, OmochiColors.Border, RoundedCornerShape(17.dp))
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrafficDot(OmochiColors.Red, 12)
        Spacer(Modifier.width(7.dp))
        TrafficDot(OmochiColors.Yellow, 12)
        Spacer(Modifier.width(7.dp))
        TrafficDot(OmochiColors.Green, 12)
        Text(
            text = "Omochi",
            style = MaterialTheme.typography.labelLarge,
            color = OmochiColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (active) OmochiColors.Green else OmochiColors.Border)
        )
        Text(
            if (active) "実行中" else "ローカル",
            style = MaterialTheme.typography.labelSmall,
            color = OmochiColors.Muted,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun TrafficDot(color: Color, size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
            .border(0.5.dp, Color.Black.copy(alpha = 0.14f), CircleShape)
    )
}

@Composable
private fun StatusPill(text: String, color: Color, ink: Color = OmochiColors.Ink) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = ink,
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(color)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun WelcomePanel(
    installed: Boolean,
    japaneseClaudeInstalled: Boolean,
    claudeCodeVersion: String?,
    supported: Boolean,
    running: Boolean,
    percent: Int,
    message: String,
    serverState: OmochiServerManager.State,
    onSetup: () -> Unit,
    onOpen: () -> Unit,
    onStopSession: () -> Unit,
) {
    val ready = installed && japaneseClaudeInstalled
    val sessionActive = serverState is OmochiServerManager.State.Running ||
        serverState is OmochiServerManager.State.Starting
    Card(
        colors = CardDefaults.cardColors(containerColor = OmochiColors.Raised),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OmochiColors.Border, RoundedCornerShape(26.dp)),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                StatusPill(
                    if (ready) "準備完了" else if (installed) "追加設定あり" else "初回設定",
                    if (ready) Color(0xFFDDEEDC) else OmochiColors.AccentSoft,
                    if (ready) Color(0xFF237437) else OmochiColors.AccentDark,
                )
                StatusPill("端末内完結", Color(0xFFDDE9F5), Color(0xFF295E8C))
                StatusPill("root不要", Color(0xFFEAE4F4), Color(0xFF68528A))
            }

            Column {
                Text(
                    when {
                        ready && sessionActive -> "作業の続きを、すぐ開く。"
                        ready -> "コードを、指先のそばに。"
                        installed -> "日本語UIとClaude Codeを追加します。"
                        else -> "Androidに、端末内IDEを。"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = OmochiColors.Ink,
                )
                Text(
                    when {
                        ready -> "編集・検索・Git・ターミナルを、タッチ向けドックから迷わず操作できます。"
                        installed -> "既存のワークスペースを残したまま、必要な機能だけを追加します。"
                        else -> "Ubuntu Baseとcode-serverをアプリ領域へ展開し、127.0.0.1だけで動作します。"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = OmochiColors.Muted,
                    modifier = Modifier.padding(top = 7.dp),
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OmochiColors.Muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (japaneseClaudeInstalled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF27833B),
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "日本語UI・Claude Code ${claudeCodeVersion ?: "導入済み"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF27833B),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            if (!supported) {
                Text(
                    "このビルドの内蔵LinuxランタイムはARM64端末専用です。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (running) {
                LinearProgressIndicator(
                    progress = { percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                    color = OmochiColors.Accent,
                    trackColor = OmochiColors.AccentSoft,
                )
                Text("セットアップ $percent%", style = MaterialTheme.typography.labelLarge, color = OmochiColors.Muted)
            }

            if (running) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("セットアップ中")
                }
            } else if (ready) {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OmochiColors.Accent),
                    shape = RoundedCornerShape(17.dp),
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(if (sessionActive) "ワークベンチに戻る" else "ワークベンチを開く")
                }
            } else if (installed) {
                Button(
                    onClick = onSetup,
                    enabled = supported,
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OmochiColors.Accent),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("日本語UIとClaude Codeを導入")
                }
                OutlinedButton(
                    onClick = onOpen,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("現在のワークベンチを開く")
                }
            } else {
                Button(
                    onClick = onSetup,
                    enabled = supported,
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("Omochiをセットアップ")
                }
            }

            if (sessionActive && !running) {
                Surface(
                    color = Color(0xFFF0F7EF),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(OmochiColors.Green))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text("IDEセッション実行中", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "ブラウザ認証中もターミナルを維持します",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OmochiColors.Muted,
                            )
                        }
                        OutlinedButton(
                            onClick = onStopSession,
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(13.dp),
                        ) {
                            Text("停止")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = OmochiColors.Ink)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = OmochiColors.Muted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun QuickActionsPanel(
    transferRunning: Boolean,
    transferMessage: String?,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onExport: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OmochiColors.Surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OmochiColors.Border, RoundedCornerShape(24.dp)),
    ) {
        Column {
            QuickActionRow(
                icon = Icons.Outlined.FileOpen,
                title = "ファイルを取り込む",
                detail = "Androidのファイル選択から /workspace へコピー",
                enabled = !transferRunning,
                onClick = onImportFiles,
            )
            HorizontalDivider(color = OmochiColors.Divider)
            QuickActionRow(
                icon = Icons.Outlined.FolderCopy,
                title = "フォルダを取り込む",
                detail = "選択したフォルダ構造を保ってコピー",
                enabled = !transferRunning,
                onClick = onImportFolder,
            )
            HorizontalDivider(color = OmochiColors.Divider)
            QuickActionRow(
                icon = Icons.Outlined.FileDownload,
                title = "ワークスペースを書き出す",
                detail = ".gitを除く作業ファイルをSAFへ保存",
                enabled = !transferRunning,
                onClick = onExport,
            )
            if (transferRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            transferMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OmochiColors.Muted,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun QuickActionRow(
    icon: ImageVector,
    title: String,
    detail: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().height(82.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(OmochiColors.AccentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = OmochiColors.AccentDark, modifier = Modifier.size(23.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = OmochiColors.Ink)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = OmochiColors.Muted)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = OmochiColors.Muted)
        }
    }
}

@Composable
private fun ClaudePanel(version: String?, onOpenClaude: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF24262B)),
        shape = RoundedCornerShape(25.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF3B3D44)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Terminal, contentDescription = null, tint = Color(0xFFE6A797))
                }
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text("Claude Code", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(
                        "${version ?: "導入済み"}・公式stable",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                }
                StatusPill("導入済み", Color(0xFF31553A), Color(0xFFDDF4E1))
            }
            Text(
                "統合ターミナルを開いて「claude」を実行します。ログイン用ブラウザへ移動しても、OmochiのIDEとターミナルは背後で継続します。",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
            )
            Button(
                onClick = onOpenClaude,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OmochiColors.Accent),
                shape = RoundedCornerShape(15.dp),
            ) {
                Icon(Icons.Outlined.Terminal, contentDescription = null)
                Spacer(Modifier.width(9.dp))
                Text("ターミナルでClaudeを開く")
            }
        }
    }
}

@Composable
private fun RuntimePanel(
    installed: Boolean,
    japaneseClaudeInstalled: Boolean,
    claudeCodeVersion: String?,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OmochiColors.Surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, OmochiColors.Border, RoundedCornerShape(24.dp)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("端末内ランタイム", style = MaterialTheme.typography.titleLarge)
            Text(
                "保存領域・ネットワーク境界・導入状態をひと目で確認できます。",
                style = MaterialTheme.typography.bodyMedium,
                color = OmochiColors.Muted,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            RuntimeRow(
                icon = Icons.Outlined.Storage,
                title = "Ubuntu / PRoot",
                detail = if (installed) "アプリ専用領域・/workspace" else "セットアップ待ち",
                ready = installed,
            )
            RuntimeRow(
                icon = Icons.Outlined.Code,
                title = "code-server ${BuildConfig.CODE_SERVER_VERSION}",
                detail = "127.0.0.1のみ・ローカル自動認証",
                ready = installed,
            )
            RuntimeRow(
                icon = Icons.Outlined.Translate,
                title = "日本語UI / Claude Code",
                detail = claudeCodeVersion?.let { "Claude Code $it" } ?: "追加セットアップ待ち",
                ready = japaneseClaudeInstalled,
            )
        }
    }
}

@Composable
private fun RuntimeRow(icon: ImageVector, title: String, detail: String, ready: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                .background(if (ready) Color(0xFFDDEEDC) else Color(0xFFECE7E2)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (ready) Color(0xFF27833B) else OmochiColors.Muted,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = OmochiColors.Muted)
        }
        Icon(
            if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule,
            contentDescription = if (ready) "準備完了" else "待機中",
            tint = if (ready) Color(0xFF27833B) else OmochiColors.Muted,
        )
    }
}

@Composable
private fun CapabilityPanel() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0ECE8)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("タッチ向けワークベンチ", style = MaterialTheme.typography.titleLarge)
            Text(
                "固定ドックで主要ビューへ移動し、キー面ではCtrl・Alt・Shift・矢印、日本語IME、保存・検索・分割を操作できます。",
                style = MaterialTheme.typography.bodyMedium,
                color = OmochiColors.Muted,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                StatusPill("エクスプローラー", Color.White)
                StatusPill("検索と置換", Color.White)
                StatusPill("Gitとdiff", Color.White)
                StatusPill("実行とデバッグ", Color.White)
                StatusPill("統合ターミナル", Color.White)
                StatusPill("画面分割", Color.White)
            }
        }
    }
}

@Composable
private fun LegalPanel(onLegal: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OmochiColors.Surface),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, OmochiColors.Border, RoundedCornerShape(22.dp)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = OmochiColors.Muted)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("ライセンスと実行境界", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "公式配布物の検証情報、PRoot対応ソース、第三者ライセンスを確認できます。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OmochiColors.Muted,
                    )
                }
            }
            OutlinedButton(
                onClick = onLegal,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(15.dp),
            ) {
                Text("ライセンス情報を表示")
            }
        }
    }
}
