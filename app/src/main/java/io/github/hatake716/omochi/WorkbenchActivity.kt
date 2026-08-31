package io.github.hatake716.omochi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

class WorkbenchActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_TERMINAL = "io.github.hatake716.omochi.extra.OPEN_TERMINAL"
        private const val NATIVE_MENU_PROMPT = "omochi-native-menu-v1"
    }

    private var webView: WebView? = null
    private var loadedUrl: String? = null
    private var serverState by mutableStateOf<OmochiServerManager.State>(
        OmochiServerManager.state()
    )
    private var webError by mutableStateOf<String?>(null)
    private var showTouchBar by mutableStateOf(true)
    private var ctrlLatched by mutableStateOf(false)
    private var altLatched by mutableStateOf(false)
    private var shiftLatched by mutableStateOf(false)
    private var touchPanelPage by mutableStateOf(TouchPanelPage.Keys)
    private var fullscreenEnabled by mutableStateOf(false)
    private var browserNotice by mutableStateOf<String?>(null)
    private var syncState by mutableStateOf<WorkspaceSyncManager.State>(
        WorkspaceSyncManager.State.Disconnected
    )
    private var workspaceFolder by mutableStateOf(WorkspaceSession.GUEST_ROOT)
    private var workspacePicker by mutableStateOf<WorkspacePickerState?>(null)
    private var openLinkedFolderAfterConnect = false
    private var externalBrowserPending = false
    private var initialTerminalPending = false
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val popupWebViews = mutableMapOf<WebView, Boolean>()

    private val chooseWebFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    private val linkTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            openLinkedFolderAfterConnect = false
        } else {
            WorkspaceSyncManager.connect(this, uri)
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The foreground session remains valid even when shade notifications are denied. */ }

    private val serverListener: (OmochiServerManager.State) -> Unit = { state ->
        serverState = state
        if (state is OmochiServerManager.State.Running) {
            webError = null
            loadWorkbench(state.url)
        }
    }

    private val syncListener: (WorkspaceSyncManager.State) -> Unit = { state ->
        syncState = state
        when (state) {
            is WorkspaceSyncManager.State.Failed -> {
                openLinkedFolderAfterConnect = false
                browserNotice = "端末フォルダ同期: ${state.message}"
            }
            is WorkspaceSyncManager.State.Ready -> {
                if (openLinkedFolderAfterConnect) {
                    openLinkedFolderAfterConnect = false
                    selectWorkspaceFolder(state.link.workspacePath)
                }
                val summary = state.summary
                if (summary != null && summary.conflicts > 0) {
                    browserNotice =
                        "同時編集を検出しました。端末側の版を日時付き競合ファイルとして保存しました。"
                }
            }
            else -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OmochiRuntime.isInstalled(this)) {
            openHome()
            return
        }

        enableEdgeToEdge()
        syncState = WorkspaceSyncManager.state(this)
        workspaceFolder = WorkspaceSession.selectedGuestFolder(this)
        initialTerminalPending = intent.getBooleanExtra(EXTRA_OPEN_TERMINAL, false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        installBackHandler()

        OmochiServerManager.addListener(serverListener)
        WorkspaceSyncManager.addListener(syncListener)
        OmochiServerService.start(this).onFailure {
            webError = "IDEセッションを開始できません: ${it.message ?: it.javaClass.simpleName}"
        }

        setContent {
            OmochiTheme {
                WorkbenchScreen(
                    serverState = serverState,
                    webError = webError,
                    showTouchBar = showTouchBar,
                    ctrlLatched = ctrlLatched,
                    altLatched = altLatched,
                    shiftLatched = shiftLatched,
                    touchPanelPage = touchPanelPage,
                    fullscreenEnabled = fullscreenEnabled,
                    syncState = syncState,
                    workspaceFolder = workspaceFolder,
                    workspacePicker = workspacePicker,
                    createWebView = ::createWebView,
                    onClose = ::openHome,
                    onToggleTouchBar = { showTouchBar = !showTouchBar },
                    onToggleFullscreen = ::toggleFullscreen,
                    onOpenWorkspacePicker = ::openWorkspacePicker,
                    onBrowseWorkspaceFolder = ::browseWorkspaceFolder,
                    onCreateWorkspaceFolder = ::createWorkspaceFolder,
                    onSelectWorkspaceFolder = ::selectWorkspaceFolder,
                    onDismissWorkspacePicker = { workspacePicker = null },
                    onSyncFolder = ::handleSyncFolder,
                    onExplorer = { activateWorkbenchView("codicon-explorer-view-icon") },
                    onSearch = { activateWorkbenchView("codicon-search-view-icon") },
                    onSourceControl = { activateWorkbenchView("codicon-source-control-view-icon") },
                    onTerminal = { sendShortcut(KeyEvent.KEYCODE_GRAVE, ctrl = true) },
                    onCommandPalette = { sendShortcut(KeyEvent.KEYCODE_P, ctrl = true, shift = true) },
                    onRetry = ::retry,
                    onToggleCtrl = { ctrlLatched = !ctrlLatched },
                    onToggleAlt = { altLatched = !altLatched },
                    onToggleShift = { shiftLatched = !shiftLatched },
                    onTouchPanelPage = { touchPanelPage = it },
                    onKey = ::sendLatchedKey,
                    onShortcut = ::handleNamedShortcut,
                    onShowIme = ::showIme,
                    browserNotice = browserNotice,
                    onDismissBrowserNotice = { browserNotice = null },
                )
            }
        }

        requestSessionNotificationPermissionOnce()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        if (externalBrowserPending) {
            externalBrowserPending = false
            browserNotice = "ブラウザから戻りました。IDEとターミナルのセッションは継続しています。"
        }
        if (OmochiServerManager.state() is OmochiServerManager.State.Failed) {
            OmochiServerService.restart(this).onFailure {
                webError = "IDEセッションを再起動できません: ${it.message ?: it.javaClass.simpleName}"
            }
        }
        WorkspaceSyncManager.requestSync(this, verifyBothSides = false)
    }

    override fun onPause() {
        // Keep the WebView connection warm for the explicit browser-auth flow. Other
        // background transitions may pause rendering to save resources.
        if (!externalBrowserPending) webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        OmochiServerManager.removeListener(serverListener)
        WorkspaceSyncManager.removeListener(syncListener)
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            view.destroy()
        }
        webView = null
        popupWebViews.keys.toList().forEach(::destroyPopup)
        super.onDestroy()
    }

    private fun openWorkspacePicker() {
        browseWorkspaceFolder(workspaceFolder)
    }

    private fun browseWorkspaceFolder(guestPath: String) {
        val current = WorkspaceSession.resolveFolder(this, guestPath).getOrElse {
            workspacePicker = WorkspacePickerState(
                currentGuestPath = WorkspaceSession.GUEST_ROOT,
                folders = emptyList(),
                error = it.message ?: "作業フォルダーを読み込めませんでした。",
            )
            return
        }
        val folders = WorkspaceSession.listFolders(this, current.guestPath).getOrElse {
            workspacePicker = WorkspacePickerState(
                currentGuestPath = current.guestPath,
                folders = emptyList(),
                error = it.message ?: "フォルダー一覧を読み込めませんでした。",
            )
            return
        }
        workspacePicker = WorkspacePickerState(
            currentGuestPath = current.guestPath,
            folders = folders,
            error = null,
        )
    }

    private fun createWorkspaceFolder(name: String) {
        val current = workspacePicker ?: return
        WorkspaceSession.createFolder(this, current.currentGuestPath, name)
            .onSuccess { created -> browseWorkspaceFolder(created.guestPath) }
            .onFailure { error ->
                workspacePicker = current.copy(
                    error = error.message ?: "フォルダーを作成できませんでした。",
                )
            }
    }

    private fun selectWorkspaceFolder(guestPath: String) {
        WorkspaceSession.selectFolder(this, guestPath)
            .onSuccess { selected ->
                workspaceFolder = selected.guestPath
                workspacePicker = null
                loadedUrl = null
                browserNotice = "作業フォルダーを ${selected.guestPath} に変更しています…"
                OmochiServerService.restart(this).onFailure {
                    browserNotice = "作業フォルダーは保存しましたが、IDEを再起動できませんでした。"
                }
            }
            .onFailure { error ->
                val current = workspacePicker
                if (current != null) {
                    workspacePicker = current.copy(
                        error = error.message ?: "作業フォルダーを変更できませんでした。",
                    )
                } else {
                    browserNotice = error.message ?: "作業フォルダーを変更できませんでした。"
                }
            }
    }

    private fun captureWorkspaceFolder(uri: Uri) {
        if (!isTrustedWorkbenchUri(uri)) return
        WorkspaceSession.captureFolderFromUrl(this, uri.toString())?.let { selected ->
            workspaceFolder = selected.guestPath
        }
    }

    private fun handleSyncFolder() {
        when (val state = syncState) {
            WorkspaceSyncManager.State.Disconnected -> {
                openLinkedFolderAfterConnect = false
                linkTree.launch(null)
            }
            is WorkspaceSyncManager.State.Syncing -> {
                if (state.link == null) {
                    openLinkedFolderAfterConnect = false
                    linkTree.launch(null)
                }
            }
            is WorkspaceSyncManager.State.Ready,
            is WorkspaceSyncManager.State.Failed -> WorkspaceSyncManager.requestSync(this)
        }
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    openHome()
                }

                override fun handleOnBackStarted(backEvent: BackEventCompat) = Unit
                override fun handleOnBackProgressed(backEvent: BackEventCompat) = Unit
                override fun handleOnBackCancelled() = Unit
            }
        )
    }

    private fun openHome() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }

    private fun retry() {
        webError = null
        loadedUrl = null
        OmochiServerService.restart(this).onFailure {
            webError = "IDEセッションを再起動できません: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun requestSessionNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return

        val preferences = getSharedPreferences("omochi-ui", MODE_PRIVATE)
        if (preferences.getBoolean("session-notification-prompted-v1", false)) return
        preferences.edit { putBoolean("session-notification-prompted-v1", true) }
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadWorkbench(url: String) {
        val view = webView ?: return
        if (loadedUrl != url || view.url.isNullOrBlank() || view.url == "about:blank") {
            loadedUrl = url
            view.loadUrl(url)
        } else {
            view.reload()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        webView = this
        setBackgroundColor(AndroidColor.rgb(244, 241, 237))
        isFocusable = true
        isFocusableInTouchMode = true
        overScrollMode = WebView.OVER_SCROLL_NEVER

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
            userAgentString = "$userAgentString Omochi/${BuildConfig.VERSION_NAME}"
            safeBrowsingEnabled = true
        }

        val currentWebView = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(currentWebView, false)
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = routeUrl(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                webError = null
                val uri = url.toUri()
                captureWorkspaceFolder(uri)
                if (!submitLocalLogin(view, uri)) {
                    injectTouchWorkbench(view)
                    if (initialTerminalPending) {
                        initialTerminalPending = false
                        view.postDelayed(
                            { sendShortcut(KeyEvent.KEYCODE_GRAVE, ctrl = true) },
                            1_200L,
                        )
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    webError = "画面を読み込めません: ${error.description}"
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                Log.w("OmochiWeb", "WebView renderer gone; crashed=${detail.didCrash()}")
                loadedUrl = null
                if (webView === view) webView = null
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                window.decorView.post { recreate() }
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val types = fileChooserParams.acceptTypes.filter { it.isNotBlank() }
                    .ifEmpty { listOf("*/*") }
                    .toTypedArray()
                chooseWebFiles.launch(types)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    "OmochiWeb",
                    "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                )
                return true
            }

            override fun onJsPrompt(
                view: WebView,
                url: String,
                message: String,
                defaultValue: String?,
                result: JsPromptResult,
            ): Boolean {
                if (message != NATIVE_MENU_PROMPT) {
                    return super.onJsPrompt(view, url, message, defaultValue, result)
                }
                val runningUrl = (serverState as? OmochiServerManager.State.Running)?.url
                val action = WorkbenchUrlPolicy.nativeMenuAction(
                    runningUrl = runningUrl,
                    currentPageUrl = url,
                    candidateUrl = "omochi://menu/${defaultValue.orEmpty()}",
                )
                if (action == null) {
                    result.cancel()
                    return true
                }
                result.confirm("handled")
                view.post {
                    // Code - OSS ignores synthetic JavaScript Escape events for some
                    // menu states. Send a trusted Android key before the Compose dialog
                    // appears, then allow one frame for the upstream menu to close.
                    dispatchKey(KeyEvent.KEYCODE_ESCAPE, 0)
                    view.postDelayed({ handleNativeMenuAction(action) }, 50L)
                }
                return true
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val popup = createRoutedPopupWebView()
                popupWebViews[popup] = isUserGesture
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
                    destroyPopup(popup)
                    return false
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                if (window === webView) {
                    openHome()
                } else {
                    destroyPopup(window)
                }
            }
        }

        setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("http://") || url.startsWith("https://")) {
                openExternalUri(url.toUri())
            }
        }

        val state = serverState
        if (state is OmochiServerManager.State.Running) {
            loadedUrl = state.url
            loadUrl(state.url)
        }
    }

    private fun routeUrl(uri: Uri): Boolean {
        val runningUrl = (serverState as? OmochiServerManager.State.Running)?.url
        val nativeAction = WorkbenchUrlPolicy.nativeMenuAction(
            runningUrl = runningUrl,
            currentPageUrl = webView?.url,
            candidateUrl = uri.toString(),
        )
        if (nativeAction != null) return handleNativeMenuAction(nativeAction)
        if (uri.scheme == "about" || uri.scheme == "blob" || uri.scheme == "data") return false
        if (isTrustedWorkbenchUri(uri)) {
            captureWorkspaceFolder(uri)
            return false
        }

        if (WorkbenchUrlPolicy.shouldOpenExternally(uri.scheme)) {
            openExternalUri(uri)
        }
        return true
    }

    private fun handleNativeMenuAction(action: WorkbenchUrlPolicy.NativeMenuAction): Boolean {
        when (action) {
            WorkbenchUrlPolicy.NativeMenuAction.OPEN_FOLDER -> {
                openWorkspacePicker()
                return true
            }
            WorkbenchUrlPolicy.NativeMenuAction.LINK_DEVICE_FOLDER -> {
                openLinkedFolderAfterConnect = true
                linkTree.launch(null)
                return true
            }
            WorkbenchUrlPolicy.NativeMenuAction.CLOSE_FOLDER -> {
                selectWorkspaceFolder(WorkspaceSession.GUEST_ROOT)
                return true
            }
            WorkbenchUrlPolicy.NativeMenuAction.EXIT_WORKBENCH -> {
                OmochiServerService.stop(this)
                finishAndRemoveTask()
                return true
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createRoutedPopupWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return routePopupUrl(view, request.url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                val uri = runCatching { url.toUri() }.getOrNull() ?: return
                if (uri.scheme != "about") routePopupUrl(view, uri)
            }
        }
    }

    /**
     * Code - OSS models desktop window operations with window.open(). Android has one
     * workbench surface, so trusted popups are deliberately adopted by the main WebView.
     * This keeps Open Folder, Open Recent, workspace switching and new-window commands
     * visible instead of stranding them in an unattached WebView.
     */
    private fun routePopupUrl(popup: WebView, uri: Uri): Boolean {
        val runningUrl = (serverState as? OmochiServerManager.State.Running)?.url
        return when (WorkbenchUrlPolicy.classifyPopup(runningUrl, uri.toString())) {
            WorkbenchUrlPolicy.PopupTarget.INITIAL_BLANK -> false
            WorkbenchUrlPolicy.PopupTarget.REUSE_WORKBENCH -> {
                captureWorkspaceFolder(uri)
                val target = uri.toString()
                loadedUrl = target
                webView?.takeIf { it !== popup }?.loadUrl(target)
                destroyPopup(popup)
                true
            }
            WorkbenchUrlPolicy.PopupTarget.EXTERNAL_APP -> {
                if (popupWebViews[popup] == true) {
                    openExternalUri(uri)
                } else {
                    browserNotice = "自動的に開こうとした外部ページをブロックしました。"
                }
                destroyPopup(popup)
                true
            }
            WorkbenchUrlPolicy.PopupTarget.BLOCKED -> {
                browserNotice = "このメニュー操作は安全なAndroid画面へ変換できませんでした。"
                destroyPopup(popup)
                true
            }
        }
    }

    private fun destroyPopup(view: WebView) {
        popupWebViews.remove(view)
        runCatching {
            view.stopLoading()
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            view.destroy()
        }
    }

    private fun openExternalUri(uri: Uri) {
        val session = OmochiServerService.start(this)
        if (session.isFailure) {
            externalBrowserPending = false
            browserNotice = "IDEセッションを維持できないため、外部ブラウザを開きませんでした。"
            return
        }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: "外部サイト"
        val result = runCatching {
            externalBrowserPending = true
            browserNotice = "$host をブラウザで開いています。Omochiのセッションは背後で維持されます。"
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
        if (result.isFailure) {
            externalBrowserPending = false
            browserNotice = "このリンクを開けるブラウザが見つかりませんでした。"
        }
    }

    private fun isTrustedWorkbenchUri(uri: Uri): Boolean {
        val running = serverState as? OmochiServerManager.State.Running ?: return false
        return WorkbenchUrlPolicy.isTrustedLoopback(running.url, uri.toString())
    }

    private fun submitLocalLogin(view: WebView, uri: Uri): Boolean {
        if (!isTrustedWorkbenchUri(uri) || !uri.path.orEmpty().trimEnd('/').endsWith("/login")) {
            return false
        }
        val password = JSONObject.quote(OmochiRuntime.authPassword(applicationContext))
        val script = """
            (() => {
              if (window.__omochiLoginSubmitted) return true;
              if (!document.body || !document.body.innerText.includes('Omochi')) return false;
              const input = document.querySelector('input[name="password"]');
              const form = document.querySelector('form.login-form');
              if (!input || !form) return false;
              window.__omochiLoginSubmitted = true;
              document.documentElement.style.opacity = '0';
              input.value = $password;
              if (form.requestSubmit) form.requestSubmit(); else form.submit();
              return true;
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { submitted ->
            if (submitted != "true") {
                webError = "ローカルIDEの認証画面を確認できませんでした。"
            }
        }
        return true
    }

    private fun injectTouchWorkbench(view: WebView) {
        val script = """
            (() => {
              if (window.__omochiTouchInstalled) return;
              window.__omochiTouchInstalled = true;
              const viewport = document.querySelector('meta[name="viewport"]') || document.createElement('meta');
              viewport.name = 'viewport';
              viewport.content = 'width=device-width, initial-scale=1, maximum-scale=3, viewport-fit=cover';
              if (!viewport.parentNode) document.head.appendChild(viewport);

              const style = document.createElement('style');
              style.id = 'omochi-touch-style';
              style.textContent = `
                html, body { overscroll-behavior: none; }
                .monaco-workbench { font-family: -apple-system, BlinkMacSystemFont, Roboto, sans-serif !important; }
                .monaco-workbench .part.activitybar .action-item,
                .monaco-workbench .part.activitybar .action-label { min-height: 48px !important; min-width: 48px !important; }
                .monaco-workbench .menubar-menu-button,
                .monaco-workbench .menubar-menu-button .menubar-menu-title {
                  min-width: 48px !important;
                  min-height: 48px !important;
                  line-height: 48px !important;
                }
                .monaco-workbench .part.sidebar .monaco-list-row,
                .monaco-workbench .part.auxiliarybar .monaco-list-row,
                .monaco-workbench .quick-input-list .monaco-list-row { min-height: 40px !important; }
                .monaco-workbench .tabs-container .tab { min-height: 44px !important; }
                .monaco-workbench .tabs-container .tab .tab-label { line-height: 44px !important; }
                .monaco-workbench .monaco-action-bar .action-item > .action-label { min-width: 42px; min-height: 42px; }
                .monaco-workbench .monaco-button { min-height: 44px !important; border-radius: 10px !important; }
                .monaco-workbench input, .monaco-workbench textarea { font-size: max(16px, 1em); }
                .monaco-workbench .xterm-helper-textarea { font-size: 16px !important; }
                .monaco-workbench .monaco-scrollable-element > .scrollbar.vertical { width: 16px !important; }
                .monaco-workbench .monaco-scrollable-element > .scrollbar.horizontal { height: 16px !important; }
                .quick-input-widget { top: 6% !important; width: min(92vw, 720px) !important; margin-left: auto !important; margin-right: auto !important; border-radius: 14px !important; overflow: hidden; }
                .context-view, .monaco-menu-container { max-width: calc(100vw - 12px) !important; }
                .monaco-menu .monaco-action-bar.vertical .action-item,
                .monaco-menu .monaco-action-bar.vertical .action-menu-item {
                  min-height: 48px !important;
                  line-height: 48px !important;
                }
                .monaco-menu .action-label { padding-top: 4px !important; padding-bottom: 4px !important; }
                .menubar-menu-button.open .menubar-menu-items-holder > .monaco-scrollable-element > .scrollbar.vertical {
                  opacity: 0 !important;
                  pointer-events: none !important;
                }
                .monaco-workbench .part.statusbar { min-height: 32px !important; }
                .monaco-workbench .breadcrumbs-below-tabs { min-height: 38px !important; }
                .monaco-workbench .notifications-toasts .notification-toast { border-radius: 12px !important; }
                [data-id="workbench.view.extensions"],
                [data-command-id="workbench.view.extensions"] { display: none !important; }
                @media (max-width: 600px) {
                  .monaco-workbench .part.panel { min-height: 200px !important; }
                  .monaco-workbench .codicon { font-size: 18px; }
                  .monaco-dialog-box {
                    box-sizing: border-box !important;
                    width: calc(100vw - 16px) !important;
                    min-width: 0 !important;
                    max-width: calc(100vw - 16px) !important;
                    height: min(76vh, 420px) !important;
                    min-height: 300px !important;
                    max-height: calc(100vh - 24px) !important;
                    overflow: hidden !important;
                  }
                  .monaco-dialog-box .dialog-toolbar-row {
                    box-sizing: border-box !important;
                    width: 100% !important;
                    max-width: 100% !important;
                    flex: 0 0 48px !important;
                  }
                  .monaco-dialog-box .dialog-message-row {
                    box-sizing: border-box !important;
                    width: 100% !important;
                    max-width: 100% !important;
                    height: auto !important;
                    min-height: 0 !important;
                    flex: 1 1 auto !important;
                    overflow: hidden !important;
                  }
                  .monaco-dialog-box .dialog-message-container {
                    box-sizing: border-box !important;
                    width: calc(100% - 24px) !important;
                    min-width: 0 !important;
                    max-width: calc(100% - 24px) !important;
                    height: 100% !important;
                    max-height: 100% !important;
                    overflow: auto !important;
                  }
                  .monaco-dialog-box .dialog-message {
                    height: auto !important;
                    min-height: 44px !important;
                    align-items: flex-start !important;
                  }
                  .monaco-dialog-box .dialog-buttons-row {
                    box-sizing: border-box !important;
                    width: 100% !important;
                    max-width: 100% !important;
                    height: auto !important;
                    min-height: 60px !important;
                    flex: 0 0 auto !important;
                    overflow: visible !important;
                  }
                  .monaco-dialog-box .dialog-buttons {
                    box-sizing: border-box !important;
                    width: 100% !important;
                    max-width: 100% !important;
                    height: auto !important;
                    flex-wrap: wrap !important;
                    overflow: visible !important;
                  }
                  .monaco-dialog-box .dialog-buttons .monaco-button { max-width: 100% !important; }
                }
              `;
              document.head.appendChild(style);

              const nativeMenuRoutes = new Map([
                ['workbench.action.files.openFolder', 'omochi://menu/open-folder'],
                ['workbench.action.files.openFolderInNewWindow', 'omochi://menu/open-folder'],
                ['workbench.action.files.openFolderViaWorkspace', 'omochi://menu/open-folder'],
                ['workbench.action.files.openLocalFolder', 'omochi://menu/link-device-folder'],
                ['workbench.action.closeFolder', 'omochi://menu/close-folder'],
                ['workbench.action.quit', 'omochi://menu/exit-workbench'],
                ['workbench.action.exit', 'omochi://menu/exit-workbench'],
              ]);
              const nativeMenuLabelRoutes = new Map([
                ['フォルダーを開く', 'omochi://menu/open-folder'],
                ['open folder', 'omochi://menu/open-folder'],
                ['新しいウィンドウでフォルダーを開く', 'omochi://menu/open-folder'],
                ['open folder in new window', 'omochi://menu/open-folder'],
                ['ローカル フォルダーを開く', 'omochi://menu/link-device-folder'],
                ['open local folder', 'omochi://menu/link-device-folder'],
                ['フォルダーを閉じる', 'omochi://menu/close-folder'],
                ['ワークスペースを閉じる', 'omochi://menu/close-folder'],
                ['close folder', 'omochi://menu/close-folder'],
                ['close workspace', 'omochi://menu/close-folder'],
                ['終了', 'omochi://menu/exit-workbench'],
                ['quit', 'omochi://menu/exit-workbench'],
                ['exit', 'omochi://menu/exit-workbench'],
                ['ウィンドウを閉じる', 'omochi://menu/exit-workbench'],
                ['close window', 'omochi://menu/exit-workbench'],
              ]);
              const normalizedMenuLabel = (value) => (value || '')
                .split('\n', 1)[0]
                .trim()
                .replace(/[.\u2026]+$/u, '')
                .trim()
                .toLocaleLowerCase();
              const unsupportedMenuLabels = new Set([
                '拡張機能',
                'extensions',
                'その他のデバッガーをインストールします',
                'install additional debuggers',
                'sign out of code-server',
              ]);
              const dismissWorkbenchOverlay = () => {
                const target = document.activeElement || document.body;
                const options = {
                  key: 'Escape',
                  code: 'Escape',
                  keyCode: 27,
                  which: 27,
                  bubbles: true,
                  cancelable: true,
                  composed: true,
                };
                target.dispatchEvent(new KeyboardEvent('keydown', options));
                target.dispatchEvent(new KeyboardEvent('keyup', options));
                document.querySelector('.menubar-menu-button.open')?.click();
              };
              const menuActionForEvent = (event) => {
                const action = event.target instanceof Element
                  ? event.target.closest('.action-menu-item')
                  : null;
                return !action || action.classList.contains('disabled') ? null : action;
              };
              const nativeMenuRouteForAction = (action) => {
                if (!action) return null;
                const commandTarget = action.closest('[data-command-id]')
                  || action.querySelector('[data-command-id]');
                return nativeMenuRoutes.get(commandTarget?.getAttribute('data-command-id'))
                  || nativeMenuLabelRoutes.get(normalizedMenuLabel(action.innerText));
              };
              let armedMenuAction = null;
              let ignoreMenuClickUntil = 0;
              let dispatchingMenuClick = false;
              const dispatchNativeMenu = (route) => {
                dismissWorkbenchOverlay();
                window.prompt(
                  'omochi-native-menu-v1',
                  route.substring('omochi://menu/'.length),
                );
              };
              const dispatchWorkbenchMenu = (action) => {
                if (action.getAttribute('aria-haspopup') === 'true' ||
                    action.querySelector('.submenu-indicator')) {
                  const rect = action.getBoundingClientRect();
                  ['mouseover', 'mousemove'].forEach((type) => {
                    action.dispatchEvent(new MouseEvent(type, {
                      bubbles: true,
                      cancelable: true,
                      composed: true,
                      view: window,
                      clientX: rect.left + rect.width / 2,
                      clientY: rect.top + rect.height / 2,
                      movementX: type === 'mousemove' ? 1 : 0,
                    }));
                  });
                  return;
                }
                dispatchingMenuClick = true;
                try {
                  action.click();
                } finally {
                  dispatchingMenuClick = false;
                }
              };
              const dispatchMenuAction = (selected) => {
                if (selected.route) dispatchNativeMenu(selected.route);
                else if (selected.action?.isConnected) dispatchWorkbenchMenu(selected.action);
              };
              const stopMenuEvent = (event) => {
                event.preventDefault();
                event.stopImmediatePropagation();
              };
              const handleTouchMenuPointer = (event) => {
                const liveAction = menuActionForEvent(event);
                const selected = liveAction ? {
                  action: liveAction,
                  route: nativeMenuRouteForAction(liveAction),
                } : armedMenuAction;
                if (!selected) return;

                if (event.type === 'click') {
                  if (dispatchingMenuClick) return;
                  if (performance.now() < ignoreMenuClickUntil) {
                    stopMenuEvent(event);
                    return;
                  }
                  if (!selected.route) return;
                  stopMenuEvent(event);
                  dispatchNativeMenu(selected.route);
                  return;
                }

                if (event.type === 'pointerdown') {
                  armedMenuAction = {
                    action: selected.action,
                    route: selected.route,
                    pointerId: event.pointerId,
                    x: event.clientX,
                    y: event.clientY,
                    moved: false,
                  };
                  // Native replacements must never reach Code - OSS. Regular menu
                  // rows keep their down/move stream so long menus remain scrollable.
                  if (selected.route) stopMenuEvent(event);
                  return;
                }
                if (event.type === 'pointermove' && armedMenuAction) {
                  if (Math.abs(event.clientX - armedMenuAction.x) > 12 ||
                      Math.abs(event.clientY - armedMenuAction.y) > 12) {
                    armedMenuAction.moved = true;
                  }
                  if (armedMenuAction.route) stopMenuEvent(event);
                  return;
                }
                if (event.type === 'pointercancel') {
                  armedMenuAction = null;
                  return;
                }
                if (event.type === 'pointerup') {
                  const armed = armedMenuAction;
                  armedMenuAction = null;
                  if (!armed) return;
                  ignoreMenuClickUntil = performance.now() + 600;
                  if (armed.route || !armed.moved) stopMenuEvent(event);
                  if (!armed.moved) {
                    dispatchMenuAction(armed);
                  }
                  return;
                }
                if (event.type === 'mousedown' || event.type === 'mouseup' ||
                    event.type === 'touchstart' || event.type === 'touchend') {
                  if (selected.route || performance.now() < ignoreMenuClickUntil) {
                    stopMenuEvent(event);
                  }
                  return;
                }
              };
              [
                'pointerdown',
                'pointermove',
                'pointerup',
                'pointercancel',
                'mousedown',
                'mouseup',
                'touchstart',
                'touchend',
                'click',
              ].forEach((type) => window.addEventListener(
                type,
                handleTouchMenuPointer,
                { capture: true, passive: false },
              ));

              const hideUnsupportedActions = () => {
                let hidden = false;
                document.querySelectorAll('[aria-label], [data-id], [data-command-id]').forEach((element) => {
                  const label = (element.getAttribute('aria-label') || '').toLowerCase();
                  const id = (element.getAttribute('data-id') || element.getAttribute('data-command-id') || '').toLowerCase();
                  if (id.includes('workbench.view.extensions') || label === 'extensions' || label.startsWith('extensions ') || label.includes('拡張機能')) {
                    const target = element.closest('.action-item') || element;
                    target.style.setProperty('display', 'none', 'important');
                    hidden = true;
                  }
                });
                document.querySelectorAll('.monaco-menu .action-menu-item').forEach((element) => {
                  if (!unsupportedMenuLabels.has(normalizedMenuLabel(element.innerText))) return;
                  const target = element.closest('.action-item') || element;
                  target.style.setProperty('display', 'none', 'important');
                  target.setAttribute('aria-hidden', 'true');
                  hidden = true;
                });
                return hidden;
              };

              let unsupportedActionsFrame = 0;
              const scheduleUnsupportedActionsPass = () => {
                if (unsupportedActionsFrame) return;
                unsupportedActionsFrame = window.requestAnimationFrame(() => {
                  unsupportedActionsFrame = 0;
                  hideUnsupportedActions();
                });
              };
              const unsupportedActionsObserver = new MutationObserver((mutations) => {
                if (mutations.some((mutation) => [...mutation.addedNodes].some((node) =>
                  node instanceof Element && (
                    node.matches('.action-item, .action-menu-item, .monaco-menu, .monaco-menu-container') ||
                    node.querySelector('.action-item, .action-menu-item, .monaco-menu, .monaco-menu-container')
                  )
                ))) scheduleUnsupportedActionsPass();
              });
              unsupportedActionsObserver.observe(document.body, { childList: true, subtree: true });
              window.__omochiUnsupportedActionsObserver = unsupportedActionsObserver;

              const prepareCompactLayout = () => {
                if (window.innerWidth > 600 || window.__omochiCompactLayoutPrepared) return true;
                const workbench = document.querySelector('.monaco-workbench');
                const activeView = document.querySelector('.part.activitybar .action-item.checked');
                if (!workbench || !activeView) return false;

                const auxiliaryClose = document.querySelector(
                  '.part.auxiliarybar .action-label.codicon-auxiliarybar-close'
                );
                if (auxiliaryClose) auxiliaryClose.click();

                const sidebar = document.querySelector('.part.sidebar');
                if (sidebar && sidebar.getBoundingClientRect().width > 0) activeView.click();
                window.__omochiCompactLayoutPrepared = true;
                return true;
              };

              const pinMobileWorkbenchOrigin = () => {
                if (window.innerWidth > 600) return true;
                const activityBar = document.querySelector('.part.activitybar.left');
                const splitView = activityBar?.parentElement?.parentElement;
                if (!splitView?.classList.contains('split-view-container')) return false;
                if (!splitView.__omochiOriginPinned) {
                  splitView.__omochiOriginPinned = true;
                  splitView.addEventListener('scroll', () => {
                    if (window.innerWidth <= 600 && splitView.scrollLeft !== 0) {
                      splitView.scrollLeft = 0;
                    }
                  }, { passive: true });
                }
                if (splitView.scrollLeft !== 0) splitView.scrollLeft = 0;
                return true;
              };
              window.addEventListener('resize', pinMobileWorkbenchOrigin);

              hideUnsupportedActions();
              pinMobileWorkbenchOrigin();
              prepareCompactLayout();
              let passes = 0;
              const extensionTimer = window.setInterval(() => {
                const hidden = hideUnsupportedActions();
                const compact = prepareCompactLayout();
                const pinned = pinMobileWorkbenchOrigin();
                passes += 1;
                if ((hidden && compact && pinned) || passes >= 20) window.clearInterval(extensionTimer);
              }, 250);
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun toggleFullscreen() {
        fullscreenEnabled = !fullscreenEnabled
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreenEnabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showIme() {
        val view = webView ?: return
        view.requestFocus()
        val input = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        input.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun activateWorkbenchView(iconClass: String) {
        val selector = JSONObject.quote(".part.activitybar .action-label.$iconClass")
        webView?.evaluateJavascript("document.querySelector($selector)?.click()", null)
    }

    private fun sendLatchedKey(keyCode: Int) {
        val meta = modifierState(ctrlLatched, altLatched, shiftLatched)
        dispatchKey(keyCode, meta)
        ctrlLatched = false
        altLatched = false
        shiftLatched = false
    }

    private fun sendShortcut(
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false
    ) {
        val view = webView ?: return
        val browserKey = when (keyCode) {
            KeyEvent.KEYCODE_BACKSLASH -> BrowserKey("\\", "Backslash", 220)
            KeyEvent.KEYCODE_D -> BrowserKey("d", "KeyD", 68)
            KeyEvent.KEYCODE_E -> BrowserKey("e", "KeyE", 69)
            KeyEvent.KEYCODE_F -> BrowserKey("f", "KeyF", 70)
            KeyEvent.KEYCODE_G -> BrowserKey("g", "KeyG", 71)
            KeyEvent.KEYCODE_H -> BrowserKey("h", "KeyH", 72)
            KeyEvent.KEYCODE_P -> BrowserKey("p", "KeyP", 80)
            KeyEvent.KEYCODE_S -> BrowserKey("s", "KeyS", 83)
            KeyEvent.KEYCODE_Z -> BrowserKey("z", "KeyZ", 90)
            KeyEvent.KEYCODE_GRAVE -> BrowserKey("`", "Backquote", 192)
            else -> {
                dispatchKey(keyCode, modifierState(ctrl, alt, shift))
                return
            }
        }

        // Android KeyEvent -> WebView の変換では、仮想キーボード由来の英字に
        // KeyboardEvent.code が設定されない。VS Code は code ベースで
        // Ctrl+Shift+G / Ctrl+` などを解決するため、タッチツールバーからの
        // ショートカットだけは DOM 側へ明示的な key/code を送る。
        val options = """
            {
              key: ${JSONObject.quote(browserKey.key)},
              code: ${JSONObject.quote(browserKey.code)},
              keyCode: ${browserKey.virtualKeyCode},
              which: ${browserKey.virtualKeyCode},
              ctrlKey: $ctrl,
              altKey: $alt,
              shiftKey: $shift,
              bubbles: true,
              cancelable: true,
              composed: true
            }
        """.trimIndent()
        view.evaluateJavascript(
            """
                (() => {
                  const target = document.activeElement || document.body;
                  const options = $options;
                  target.dispatchEvent(new KeyboardEvent('keydown', options));
                  target.dispatchEvent(new KeyboardEvent('keyup', options));
                })();
            """.trimIndent(),
            null,
        )
    }

    private data class BrowserKey(
        val key: String,
        val code: String,
        val virtualKeyCode: Int,
    )

    private fun handleNamedShortcut(name: String) {
        when (name) {
            "save" -> sendShortcut(KeyEvent.KEYCODE_S, ctrl = true)
            "undo" -> sendShortcut(KeyEvent.KEYCODE_Z, ctrl = true)
            "redo" -> sendShortcut(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)
            "find" -> sendShortcut(KeyEvent.KEYCODE_F, ctrl = true)
            "replace" -> sendShortcut(KeyEvent.KEYCODE_H, ctrl = true)
            "quickOpen" -> sendShortcut(KeyEvent.KEYCODE_P, ctrl = true)
            "splitEditor" -> sendShortcut(KeyEvent.KEYCODE_BACKSLASH, ctrl = true)
            "newTerminal" -> sendShortcut(KeyEvent.KEYCODE_GRAVE, ctrl = true, shift = true)
            "runAndDebug" -> sendShortcut(KeyEvent.KEYCODE_D, ctrl = true, shift = true)
        }
    }

    private fun modifierState(ctrl: Boolean, alt: Boolean, shift: Boolean): Int {
        var value = 0
        if (ctrl) value = value or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) value = value or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (shift) value = value or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        return value
    }

    private fun dispatchKey(keyCode: Int, metaState: Int) {
        val view = webView ?: return
        view.requestFocus()
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_SOFT_KEYBOARD,
            InputDevice.SOURCE_KEYBOARD,
        )
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        view.dispatchKeyEvent(down)
        view.dispatchKeyEvent(up)
    }
}

private enum class TouchPanelPage { Keys, Edit }

private data class WorkspacePickerState(
    val currentGuestPath: String,
    val folders: List<WorkspaceSession.Folder>,
    val error: String?,
)

@Composable
private fun WorkbenchScreen(
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
    val imeBottom = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    val navigationBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val imeVisible = imeBottom > navigationBottom

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OmochiColors.Window)
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        StudioTopBar(
            state = serverState,
            syncState = syncState,
            workspaceFolder = workspaceFolder,
            touchPanelVisible = showTouchBar,
            fullscreenEnabled = fullscreenEnabled,
            onClose = onClose,
            onToggleTouchPanel = onToggleTouchBar,
            onToggleFullscreen = onToggleFullscreen,
            onOpenWorkspacePicker = onOpenWorkspacePicker,
            onSyncFolder = onSyncFolder,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                BrowserSessionNotice(
                    message = it,
                    onDismiss = onDismissBrowserNotice,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }

        if (showTouchBar && !imeVisible) {
            TouchControlPanel(
                page = touchPanelPage,
                ctrlLatched = ctrlLatched,
                altLatched = altLatched,
                shiftLatched = shiftLatched,
                onPageChange = onTouchPanelPage,
                onToggleCtrl = onToggleCtrl,
                onToggleAlt = onToggleAlt,
                onToggleShift = onToggleShift,
                onKey = onKey,
                onShortcut = onShortcut,
                onShowIme = onShowIme,
            )
        }

        if (!imeVisible) {
            WorkspaceDock(
                onExplorer = onExplorer,
                onSearch = onSearch,
                onSourceControl = onSourceControl,
                onRun = { onShortcut("runAndDebug") },
                onTerminal = onTerminal,
                onCommandPalette = onCommandPalette,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
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
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = OmochiColors.Raised,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 14.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .widthIn(max = 680.dp)
                .heightIn(max = 660.dp),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = OmochiColors.Accent,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 11.dp)) {
                        Text(
                            "作業フォルダーを変更",
                            style = MaterialTheme.typography.titleLarge,
                            color = OmochiColors.Ink,
                        )
                        Text(
                            state.currentGuestPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = OmochiColors.Muted,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "閉じる")
                    }
                }

                Text(
                    "Omochi内の /workspace と、連携した端末フォルダーのミラーから選択できます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OmochiColors.Muted,
                    modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                )

                HorizontalDivider(color = OmochiColors.Border)

                if (parent != null) {
                    WorkspaceFolderRow(
                        title = "ひとつ上のフォルダー",
                        detail = parent,
                        selected = false,
                        onClick = { onBrowse(parent) },
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 270.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.folders, key = { it.guestPath }) { folder ->
                        WorkspaceFolderRow(
                            title = folder.displayName,
                            detail = if (folder.guestPath == selectedGuestPath) {
                                "現在開いています"
                            } else if (folder.hasChildren) {
                                "タップして中を見る"
                            } else {
                                folder.guestPath
                            },
                            selected = folder.guestPath == selectedGuestPath,
                            onClick = { onBrowse(folder.guestPath) },
                        )
                    }
                }

                if (state.folders.isEmpty()) {
                    Text(
                        "このフォルダー内にサブフォルダーはありません。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OmochiColors.Muted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }

                state.error?.let { error ->
                    Text(
                        error,
                        color = OmochiColors.Red,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("新しいフォルダー名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onCreateFolder(newFolderName) },
                        enabled = WorkspaceSession.isValidFolderName(newFolderName.trim()),
                    ) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("フォルダー作成")
                    }
                    Button(onClick = { onSelect(state.currentGuestPath) }) {
                        Text("ここで開始")
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFolderRow(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) OmochiColors.AccentSoft else Color.Transparent,
        shape = RoundedCornerShape(13.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = if (selected) OmochiColors.Accent else OmochiColors.Ink,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = OmochiColors.Ink)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = OmochiColors.Muted,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = "開く",
                tint = OmochiColors.Muted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun StudioTopBar(
    state: OmochiServerManager.State,
    syncState: WorkspaceSyncManager.State,
    workspaceFolder: String,
    touchPanelVisible: Boolean,
    fullscreenEnabled: Boolean,
    onClose: () -> Unit,
    onToggleTouchPanel: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    onSyncFolder: () -> Unit,
) {
    val stateText = when (state) {
        OmochiServerManager.State.Stopped -> "待機中"
        is OmochiServerManager.State.Starting -> "起動中"
        is OmochiServerManager.State.Running -> "ローカル接続中"
        is OmochiServerManager.State.Failed -> "再接続が必要"
    }
    val stateColor = when (state) {
        is OmochiServerManager.State.Running -> OmochiColors.Green
        is OmochiServerManager.State.Failed -> OmochiColors.Red
        else -> OmochiColors.Yellow
    }
    val syncText = when (syncState) {
        WorkspaceSyncManager.State.Disconnected -> "端末未連携"
        is WorkspaceSyncManager.State.Ready -> "端末同期"
        is WorkspaceSyncManager.State.Syncing -> "同期中"
        is WorkspaceSyncManager.State.Failed -> "同期要確認"
    }
    val syncLinked = when (syncState) {
        WorkspaceSyncManager.State.Disconnected -> false
        is WorkspaceSyncManager.State.Ready -> true
        is WorkspaceSyncManager.State.Syncing -> syncState.link != null
        is WorkspaceSyncManager.State.Failed -> syncState.link != null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFFFBFAF8), Color(0xFFE9E4DF))))
            .border(0.5.dp, OmochiColors.Border)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopAction(Icons.AutoMirrored.Outlined.ArrowBack, "ホームへ戻る", onClose)
        MacTrafficLights()
        Column(modifier = Modifier.weight(1f).padding(horizontal = 9.dp)) {
            Text(
                "Omochi",
                style = MaterialTheme.typography.labelLarge,
                color = OmochiColors.Ink,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(stateColor))
                Spacer(Modifier.width(5.dp))
                Text(
                    "$stateText・$syncText",
                    style = MaterialTheme.typography.labelSmall,
                    color = OmochiColors.Muted,
                    maxLines = 1,
                )
            }
        }
        TopAction(
            icon = Icons.Outlined.FolderOpen,
            label = "作業フォルダーを変更: $workspaceFolder",
            onClick = onOpenWorkspacePicker,
            active = workspaceFolder != WorkspaceSession.GUEST_ROOT,
        )
        TopAction(
            icon = Icons.Outlined.Sync,
            label = if (syncLinked) "端末フォルダを今すぐ同期" else "端末フォルダを連携",
            onClick = onSyncFolder,
            active = syncLinked,
        )
        TopAction(
            icon = if (touchPanelVisible) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard,
            label = if (touchPanelVisible) "タッチキーを隠す" else "タッチキーを表示",
            onClick = onToggleTouchPanel,
            active = touchPanelVisible,
        )
        TopAction(
            icon = if (fullscreenEnabled) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
            label = if (fullscreenEnabled) "フルスクリーンを終了" else "フルスクリーン",
            onClick = onToggleFullscreen,
        )
    }
}

@Composable
private fun MacTrafficLights() {
    Row(
        modifier = Modifier.padding(start = 1.dp, end = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(OmochiColors.Red, OmochiColors.Yellow, OmochiColors.Green).forEach { color ->
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(0.5.dp, Color.Black.copy(alpha = 0.13f), CircleShape)
            )
        }
    }
}

@Composable
private fun TopAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Surface(
        onClick = onClick,
        color = if (active) OmochiColors.AccentSoft else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = OmochiColors.Ink, modifier = Modifier.size(23.dp))
        }
    }
}

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
    Surface(
        color = Color(0xFFFAF8F6),
        tonalElevation = 5.dp,
        shadowElevation = 7.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 3.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockButton(Icons.Outlined.FolderOpen, "ファイル", onExplorer, Modifier.weight(1f))
            DockButton(Icons.Outlined.Search, "検索", onSearch, Modifier.weight(1f))
            DockButton(Icons.Outlined.AccountTree, "Git", onSourceControl, Modifier.weight(1f))
            DockButton(Icons.Outlined.PlayArrow, "実行", onRun, Modifier.weight(1f))
            DockButton(Icons.Outlined.Terminal, "端末", onTerminal, Modifier.weight(1f))
            DockButton(Icons.Outlined.MoreHoriz, "コマンド", onCommandPalette, Modifier.weight(1f))
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
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(13.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = OmochiColors.Ink, modifier = Modifier.size(23.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = OmochiColors.Ink,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TouchControlPanel(
    page: TouchPanelPage,
    ctrlLatched: Boolean,
    altLatched: Boolean,
    shiftLatched: Boolean,
    onPageChange: (TouchPanelPage) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onKey: (Int) -> Unit,
    onShortcut: (String) -> Unit,
    onShowIme: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OmochiColors.Terminal)
            .border(0.5.dp, Color.White.copy(alpha = 0.1f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 7.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelTab("キー", page == TouchPanelPage.Keys) { onPageChange(TouchPanelPage.Keys) }
            PanelTab("編集", page == TouchPanelPage.Edit) { onPageChange(TouchPanelPage.Edit) }
            Text(
                if (page == TouchPanelPage.Keys) "修飾キーは次の1入力に適用" else "よく使う編集操作",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                maxLines = 1,
            )
        }

        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (page == TouchPanelPage.Keys) {
                item { KeyButton("ESC") { onKey(KeyEvent.KEYCODE_ESCAPE) } }
                item { KeyButton("CTRL", ctrlLatched, onToggleCtrl) }
                item { KeyButton("ALT", altLatched, onToggleAlt) }
                item { KeyButton("SHIFT", shiftLatched, onToggleShift) }
                item { KeyButton("TAB") { onKey(KeyEvent.KEYCODE_TAB) } }
                item { KeyButton("←") { onKey(KeyEvent.KEYCODE_DPAD_LEFT) } }
                item { KeyButton("↑") { onKey(KeyEvent.KEYCODE_DPAD_UP) } }
                item { KeyButton("↓") { onKey(KeyEvent.KEYCODE_DPAD_DOWN) } }
                item { KeyButton("→") { onKey(KeyEvent.KEYCODE_DPAD_RIGHT) } }
                item { KeyButton("ENTER") { onKey(KeyEvent.KEYCODE_ENTER) } }
                item { KeyButton("⌫") { onKey(KeyEvent.KEYCODE_DEL) } }
                item { ImeButton(onShowIme) }
            } else {
                item { KeyButton("保存") { onShortcut("save") } }
                item { KeyButton("元に戻す") { onShortcut("undo") } }
                item { KeyButton("やり直す") { onShortcut("redo") } }
                item { KeyButton("検索") { onShortcut("find") } }
                item { KeyButton("置換") { onShortcut("replace") } }
                item { KeyButton("ファイルを開く") { onShortcut("quickOpen") } }
                item { KeyButton("エディタ分割") { onShortcut("splitEditor") } }
                item { KeyButton("新しい端末") { onShortcut("newTerminal") } }
                item { ImeButton(onShowIme) }
            }
        }
    }
}

@Composable
private fun PanelTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) OmochiColors.Accent else Color(0xFF36383E),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.height(48.dp).width(84.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun KeyButton(label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) OmochiColors.Accent else Color(0xFF36383E),
        contentColor = Color.White,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.height(50.dp).widthIn(min = 52.dp),
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
        }
    }
}

@Composable
private fun ImeButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = OmochiColors.Accent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.height(50.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Keyboard, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(7.dp))
            Text("日本語入力", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun BrowserSessionNotice(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xF2232529),
        contentColor = Color.White,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 8.dp,
        modifier = modifier.padding(10.dp).fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 15.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(OmochiColors.Green))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 11.dp, vertical = 11.dp),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "閉じる", tint = Color.White)
            }
        }
    }
}

@Composable
private fun LoadingPanel(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(OmochiColors.Window),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = OmochiColors.Raised,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 6.dp,
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = OmochiColors.Accent)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OmochiColors.Muted,
                    modifier = Modifier.padding(top = 18.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    "このセッションはブラウザを開いても継続します",
                    style = MaterialTheme.typography.labelMedium,
                    color = OmochiColors.Muted,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(OmochiColors.Window.copy(alpha = 0.97f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = OmochiColors.Raised,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(22.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, tint = OmochiColors.Accent, modifier = Modifier.size(30.dp))
                Text(
                    "ワークベンチへ再接続できません",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 11.dp),
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OmochiColors.Muted,
                    modifier = Modifier.padding(vertical = 13.dp),
                )
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("IDEセッションを再起動")
                }
            }
        }
    }
}
