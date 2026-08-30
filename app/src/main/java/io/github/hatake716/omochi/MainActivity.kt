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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var installed by mutableStateOf(false)
    private var setupRunning by mutableStateOf(false)
    private var setupPercent by mutableIntStateOf(0)
    private var setupMessage by mutableStateOf("未セットアップ")
    private var transferRunning by mutableStateOf(false)
    private var transferMessage by mutableStateOf<String?>(null)

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
        installed = OmochiRuntime.isInstalled(this)
        setupMessage = if (installed) {
            "Code ${OmochiRuntime.installedVersion(this) ?: BuildConfig.CODE_SERVER_VERSION} を利用できます"
        } else {
            "LinuxランタイムとIDEエンジンを端末内へ準備します"
        }
        setContent {
            OmochiTheme {
                HomeScreen(
                    installed = installed,
                    supported = OmochiRuntime.isSupportedAbi(),
                    setupRunning = setupRunning,
                    setupPercent = setupPercent,
                    setupMessage = setupMessage,
                    transferRunning = transferRunning,
                    transferMessage = transferMessage,
                    onSetup = ::startSetup,
                    onOpenWorkbench = ::openWorkbench,
                    onImportFiles = { openDocuments.launch(arrayOf("*/*")) },
                    onImportFolder = { importTree.launch(null) },
                    onExport = { exportTree.launch(null) },
                    onLegal = { startActivity(Intent(this, LegalActivity::class.java)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        installed = OmochiRuntime.isInstalled(this)
    }

    private fun startSetup() {
        if (setupRunning) return
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
                    installed = true
                    setupPercent = 100
                    setupMessage = "セットアップ完了。ワークベンチを開けます。"
                }.onFailure {
                    installed = OmochiRuntime.isInstalled(this)
                    setupMessage = "セットアップ失敗: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        )
    }

    private fun openWorkbench() {
        if (!OmochiRuntime.isInstalled(this)) {
            setupMessage = "先に初回セットアップを完了してください。"
            return
        }
        startActivity(Intent(this, WorkbenchActivity::class.java))
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
    supported: Boolean,
    setupRunning: Boolean,
    setupPercent: Int,
    setupMessage: String,
    transferRunning: Boolean,
    transferMessage: String?,
    onSetup: () -> Unit,
    onOpenWorkbench: () -> Unit,
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
                .widthIn(max = 920.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MacHeader()

            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                Text(
                    text = "Omochi",
                    style = MaterialTheme.typography.displaySmall,
                    color = OmochiColors.Ink,
                )
                Text(
                    text = "Androidのための、端末内完結コードワークベンチ",
                    style = MaterialTheme.typography.bodyLarge,
                    color = OmochiColors.Muted,
                    modifier = Modifier.padding(top = 5.dp),
                )
                FlowRow(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusPill("Google Play不要", OmochiColors.AccentSoft)
                    StatusPill("root不要", Color(0xFFDDE9F5))
                    StatusPill("端末内127.0.0.1", Color(0xFFDDEEDC))
                    StatusPill("外部拡張なし", Color(0xFFE7E2F1))
                }
            }

            SetupCard(
                installed = installed,
                supported = supported,
                running = setupRunning,
                percent = setupPercent,
                message = setupMessage,
                onSetup = onSetup,
                onOpen = onOpenWorkbench,
            )

            if (installed) {
                WorkspaceCard(
                    transferRunning = transferRunning,
                    transferMessage = transferMessage,
                    onImportFiles = onImportFiles,
                    onImportFolder = onImportFolder,
                    onExport = onExport,
                )
            }

            FeatureCard()

            Card(
                colors = CardDefaults.cardColors(containerColor = OmochiColors.Surface),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, OmochiColors.Border, RoundedCornerShape(22.dp)),
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = OmochiColors.Muted)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text("ライセンスと実行境界", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Code - OSSエンジンは初回セットアップ時に公式配布物を取得します。" +
                                "PRootの対応ソースと第三者ライセンスはAPKに同梱しています。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OmochiColors.Muted,
                        )
                    }
                    OutlinedButton(onClick = onLegal) {
                        Text("表示")
                    }
                }
            }

            Text(
                text = "OmochiはVisual Studio Code、Microsoft、Coderとは提携していません。" +
                    "初回セットアップには約260 MiBのダウンロードと、展開・ツール用の追加容量が必要です。",
                style = MaterialTheme.typography.bodyMedium,
                color = OmochiColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MacHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE6FFFFFF))
            .border(1.dp, OmochiColors.Border, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrafficDot(OmochiColors.Red)
        Spacer(Modifier.width(8.dp))
        TrafficDot(OmochiColors.Yellow)
        Spacer(Modifier.width(8.dp))
        TrafficDot(OmochiColors.Green)
        Text(
            text = "Omochi — Welcome",
            style = MaterialTheme.typography.labelLarge,
            color = OmochiColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(52.dp))
    }
}

@Composable
private fun TrafficDot(color: Color) {
    Box(
        Modifier
            .size(13.dp)
            .clip(CircleShape)
            .background(color)
            .border(0.5.dp, Color.Black.copy(alpha = 0.14f), CircleShape)
    )
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = OmochiColors.Ink,
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(color)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun SetupCard(
    installed: Boolean,
    supported: Boolean,
    running: Boolean,
    percent: Int,
    message: String,
    onSetup: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OmochiColors.Raised),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OmochiColors.Border, RoundedCornerShape(24.dp)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (installed) Color(0xFFDDEEDC) else OmochiColors.AccentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Code,
                        contentDescription = null,
                        tint = if (installed) Color(0xFF27833B) else OmochiColors.AccentDark,
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        if (installed) "ワークベンチ準備完了" else "初回セットアップ",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = OmochiColors.Muted)
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
                Text("$percent%", style = MaterialTheme.typography.labelLarge, color = OmochiColors.Muted)
            }

            if (installed) {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OmochiColors.Accent),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("ワークベンチを開く")
                }
            } else {
                Button(
                    onClick = onSetup,
                    enabled = supported && !running,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(if (running) "セットアップ中" else "IDEをセットアップ")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceCard(
    transferRunning: Boolean,
    transferMessage: String?,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onExport: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OmochiColors.Surface),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OmochiColors.Border, RoundedCornerShape(22.dp)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ワークスペース", style = MaterialTheme.typography.titleLarge)
            Text(
                "コードはアプリ専用の /workspace に保存されます。SAFで選んだ項目だけを安全に入出力します。スナップショットは .git を除く作業ファイルを書き出します。",
                style = MaterialTheme.typography.bodyMedium,
                color = OmochiColors.Muted,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                OutlinedButton(onClick = onImportFiles, enabled = !transferRunning) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("ファイル取込")
                }
                OutlinedButton(onClick = onImportFolder, enabled = !transferRunning) {
                    Icon(Icons.Outlined.FolderCopy, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("フォルダ取込")
                }
                OutlinedButton(onClick = onExport, enabled = !transferRunning) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("スナップショット書出")
                }
            }
            if (transferRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            transferMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = OmochiColors.Muted)
            }
        }
    }
}

@Composable
private fun FeatureCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF24262B)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("組み込みワークベンチ", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                "PC版の操作密度を保ちながら、主要操作を44dp以上のタッチ対象へ広げます。",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.68f),
                modifier = Modifier.padding(top = 5.dp, bottom = 12.dp),
            )
            FeatureRow(Icons.Outlined.Description, "エディタ", "タブ、分割、差分、複数カーソル、補完、折り畳み、検索・置換")
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            FeatureRow(Icons.Outlined.Search, "Explorer / Search", "作成、移動、削除、全体検索、正規表現、除外設定")
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            FeatureRow(Icons.Outlined.Source, "Git", "clone、差分、ステージ、コミット、ブランチ、pull / push")
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            FeatureRow(Icons.Outlined.Terminal, "Terminal / Tasks / Debug", "Ubuntuシェル、複数端末、タスク実行、問題表示、デバッグUI")
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFFE6A797), modifier = Modifier.size(24.dp))
        Column(Modifier.padding(start = 13.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(body, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
