package io.github.hatake716.omochi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.net.URI
import java.util.concurrent.Executors
import org.json.JSONObject

class WorkbenchActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_TERMINAL = "io.github.hatake716.omochi.extra.OPEN_TERMINAL"
        private const val TAG = "OmochiWeb"
        private const val NATIVE_MENU_PROMPT = "omochi-native-menu-v1"
        private const val TOUCH_LAYER_ASSET = "workbench/touch-layer.js"

        /** The injected touch layer is immutable per APK, so read the asset once per process. */
        @Volatile private var cachedTouchLayerScript: String? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** File system work of this screen (folder picker, folder capture, login secret). */
    private val ioExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OmochiWorkbenchIo")
    }

    private var webView: WebView? = null

    /**
     * URL loaded from the current server session. It is cleared whenever the session
     * leaves Running (the page's server is gone) and by an explicit folder switch, so
     * the next Running loads afresh; a Running published again for the same session
     * leaves the live page, its WebSockets and terminals alone.
     */
    private var loadedUrl: String? = null

    /** URL handed to loadUrl() whose main frame has neither finished nor failed yet. */
    private var pendingLoadUrl: String? = null

    /** code-server password, read off the main thread before the login page appears. */
    @Volatile private var loginPassword: String? = null
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

    /**
     * Set from a folder selection until the old server session ends. Pages of the old
     * session may still report their folder meanwhile; saving that would race the new
     * choice that the restart is about to read.
     */
    private var folderSwitchPending = false
    private var folderSelectInFlight = false

    /** Latest picker request; results of older (or dismissed) requests are dropped. */
    private var pickerRequest = 0

    /** Sync failure seen last (null after any other state) and the one the user closed. */
    private var lastSyncFailure: String? = null
    private var dismissedSyncFailure: String? = null

    /** The banner text shown for a sync failure, paired with that failure's message. */
    private var syncFailureNotice: Pair<String, String>? = null

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
            loadWorkbench(state.url)
        } else {
            // The page belongs to a server that is gone or being replaced.
            loadedUrl = null
            folderSwitchPending = false
        }
    }

    private val syncListener: (WorkspaceSyncManager.State) -> Unit = { state ->
        syncState = state
        val previousFailure = lastSyncFailure
        lastSyncFailure = (state as? WorkspaceSyncManager.State.Failed)?.message
        when (state) {
            is WorkspaceSyncManager.State.Failed -> {
                openLinkedFolderAfterConnect = false
                // The sync worker retries a failure every few seconds. Announce it once
                // per failure instead of re-opening a banner the user already closed.
                if (WorkbenchSessionPolicy.shouldAnnounceSyncFailure(
                        previousFailure = previousFailure,
                        message = state.message,
                        dismissedFailure = dismissedSyncFailure,
                    )
                ) {
                    val notice = "端末フォルダ同期: ${state.message}"
                    browserNotice = notice
                    syncFailureNotice = notice to state.message
                }
            }
            WorkspaceSyncManager.State.Disconnected -> dismissedSyncFailure = null
            is WorkspaceSyncManager.State.Syncing -> Unit
            is WorkspaceSyncManager.State.Ready -> {
                dismissedSyncFailure = null
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
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OmochiRuntime.isInstalled(this)) {
            openHome()
            return
        }

        // The chrome around the workbench is light, so both bars need dark icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        syncState = WorkspaceSyncManager.state(this)
        workspaceFolder = WorkspaceSession.selectedGuestFolder(this)
        initialTerminalPending = intent.getBooleanExtra(EXTRA_OPEN_TERMINAL, false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        installBackHandler()

        val appContext = applicationContext
        runInBackground {
            loginPassword = runCatching { OmochiRuntime.authPassword(appContext) }.getOrNull()
        }

        // The session itself is requested by onResume(), which also covers returning to
        // this screen after the session was stopped elsewhere.
        OmochiServerManager.addListener(serverListener)
        WorkspaceSyncManager.addListener(syncListener)

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
                    onDismissWorkspacePicker = ::dismissWorkspacePicker,
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
                    onDismissBrowserNotice = ::dismissBrowserNotice,
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
        when (OmochiServerManager.state()) {
            // First start, or the session was stopped from the notification or the
            // dashboard while this screen was hidden: a visible workbench needs one.
            OmochiServerManager.State.Stopped -> OmochiServerService.start(this).onFailure {
                browserNotice =
                    "IDEセッションを開始できません: ${it.message ?: it.javaClass.simpleName}"
            }
            is OmochiServerManager.State.Failed -> OmochiServerService.restart(this).onFailure {
                webError = "IDEセッションを再起動できません: ${it.message ?: it.javaClass.simpleName}"
            }
            else -> Unit
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
        // Queued work still finishes; its results are dropped because isDestroyed is set.
        ioExecutor.shutdown()
        super.onDestroy()
    }

    private fun runInBackground(task: () -> Unit) {
        runCatching { ioExecutor.execute(task) }
            .onFailure { Log.w(TAG, "Workbench background task rejected", it) }
    }

    /** Applies a picker result on the main thread unless a newer request replaced it. */
    private fun postPickerResult(request: Int, apply: () -> Unit) {
        mainHandler.post {
            if (!isDestroyed && request == pickerRequest) apply()
        }
    }

    private fun openWorkspacePicker() {
        browseWorkspaceFolder(workspaceFolder)
    }

    private fun dismissWorkspacePicker() {
        pickerRequest += 1
        workspacePicker = null
    }

    private fun browseWorkspaceFolder(guestPath: String) {
        val request = ++pickerRequest
        val appContext = applicationContext
        runInBackground {
            val next = loadWorkspacePicker(appContext, guestPath)
            postPickerResult(request) { workspacePicker = next }
        }
    }

    /** Reads one picker page. File system work: call it off the main thread. */
    private fun loadWorkspacePicker(context: Context, guestPath: String): WorkspacePickerState {
        val current = WorkspaceSession.resolveFolder(context, guestPath).getOrElse {
            return WorkspacePickerState(
                currentGuestPath = WorkspaceSession.GUEST_ROOT,
                folders = emptyList(),
                error = it.message ?: "作業フォルダーを読み込めませんでした。",
            )
        }
        val folders = WorkspaceSession.listFolders(context, current.guestPath).getOrElse {
            return WorkspacePickerState(
                currentGuestPath = current.guestPath,
                folders = emptyList(),
                error = it.message ?: "フォルダー一覧を読み込めませんでした。",
            )
        }
        return WorkspacePickerState(
            currentGuestPath = current.guestPath,
            folders = folders,
            error = null,
        )
    }

    private fun createWorkspaceFolder(name: String) {
        val current = workspacePicker ?: return
        val request = ++pickerRequest
        val appContext = applicationContext
        runInBackground {
            val next = WorkspaceSession.createFolder(appContext, current.currentGuestPath, name)
                .fold(
                    onSuccess = { created -> loadWorkspacePicker(appContext, created.guestPath) },
                    onFailure = { error ->
                        current.copy(error = error.message ?: "フォルダーを作成できませんでした。")
                    },
                )
            postPickerResult(request) { workspacePicker = next }
        }
    }

    private fun selectWorkspaceFolder(guestPath: String) {
        // The picker stays open until the choice is saved; ignore repeated taps meanwhile.
        if (folderSelectInFlight) return
        folderSelectInFlight = true
        folderSwitchPending = true
        val request = pickerRequest
        val appContext = applicationContext
        runInBackground {
            // selectFolder commits, so the restart below always reads the new folder.
            val result = WorkspaceSession.selectFolder(appContext, guestPath)
            mainHandler.post {
                folderSelectInFlight = false
                result.onSuccess { selected ->
                    // The choice is saved; switch even if this screen went away meanwhile.
                    val restarted = OmochiServerService.restart(appContext)
                    if (isDestroyed) return@post
                    pickerRequest += 1
                    workspaceFolder = selected.guestPath
                    workspacePicker = null
                    loadedUrl = null
                    if (restarted.isSuccess) {
                        browserNotice = "作業フォルダーを ${selected.guestPath} に変更しています…"
                    } else {
                        folderSwitchPending = false
                        browserNotice = "作業フォルダーは保存しましたが、IDEを再起動できませんでした。"
                    }
                }.onFailure { error ->
                    if (isDestroyed) return@post
                    folderSwitchPending = false
                    val current = workspacePicker
                    val message = error.message ?: "作業フォルダーを変更できませんでした。"
                    if (current != null && request == pickerRequest) {
                        workspacePicker = current.copy(error = message)
                    } else {
                        browserNotice = message
                    }
                }
            }
        }
    }

    private fun captureWorkspaceFolder(uri: Uri) {
        if (folderSwitchPending || !isTrustedWorkbenchUri(uri)) return
        val url = uri.toString()
        // Most navigations repeat the open folder: skip them without any I/O.
        val candidate = WorkspaceSession.folderFromWorkbenchUrl(url) ?: return
        if (candidate == workspaceFolder) return
        val appContext = applicationContext
        runInBackground {
            val selected = WorkspaceSession.captureFolderFromUrl(appContext, url)
                ?: return@runInBackground
            mainHandler.post {
                if (!isDestroyed && !folderSwitchPending) workspaceFolder = selected
            }
        }
    }

    private fun dismissBrowserNotice() {
        val syncNotice = syncFailureNotice
        if (syncNotice != null && browserNotice == syncNotice.first) {
            dismissedSyncFailure = syncNotice.second
        }
        browserNotice = null
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
        val currentUrl = view.url?.takeUnless { it.isBlank() } ?: pendingLoadUrl
        when (
            WorkbenchSessionPolicy.loadAction(
                targetUrl = url,
                loadedUrl = loadedUrl,
                currentUrl = currentUrl,
                hasError = webError != null,
            )
        ) {
            WorkbenchSessionPolicy.LoadAction.LOAD -> {
                webError = null
                startLoading(view, url)
            }
            WorkbenchSessionPolicy.LoadAction.RELOAD -> {
                webError = null
                view.reload()
            }
            WorkbenchSessionPolicy.LoadAction.NONE -> Unit
        }
    }

    private fun startLoading(view: WebView, url: String) {
        loadedUrl = url
        pendingLoadUrl = url
        view.loadUrl(url)
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
        // Keep the renderer important even while hidden. Waiving it when not visible
        // would let Android reclaim the renderer during the external-browser sign-in,
        // which is exactly when the workbench, its WebSockets and terminals must live.
        // This is also WebView's default; it is stated here so it is not "optimised".
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

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
                pendingLoadUrl = null
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
                    pendingLoadUrl = null
                    webError = "画面を読み込めません: ${error.description}"
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                Log.w(TAG, "WebView renderer gone; crashed=${detail.didCrash()}")
                loadedUrl = null
                pendingLoadUrl = null
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
                    TAG,
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
            startLoading(this, state.url)
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
                webView?.takeIf { it !== popup }?.let { view -> startLoading(view, target) }
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
        val secret = loginPassword
            ?: runCatching { OmochiRuntime.authPassword(applicationContext) }.getOrElse {
                webError = "ローカルIDEの認証情報を読み込めませんでした。"
                return true
            }.also { loginPassword = it }
        val password = JSONObject.quote(secret)
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
        view.evaluateJavascript(touchLayerScript(), null)
    }

    private fun touchLayerScript(): String =
        cachedTouchLayerScript ?: assets.open(TOUCH_LAYER_ASSET).bufferedReader().use { it.readText() }
            .also { cachedTouchLayerScript = it }

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

/** Pure decisions of the workbench surface, free of Android types for JVM tests. */
internal object WorkbenchSessionPolicy {
    enum class LoadAction { NONE, LOAD, RELOAD }

    /**
     * How the WebView reacts to a published Running([targetUrl]).
     *
     * [loadedUrl] is what this screen loaded from the current server session (null when
     * nothing was, or when a fresh load is forced), [currentUrl] what the WebView shows
     * or is loading, and [hasError] whether an error panel covers it. The same session
     * published again must not reload a live page: that would drop the workbench's
     * WebSockets and with them every terminal. Only an error or an empty WebView is
     * worth another attempt.
     */
    fun loadAction(
        targetUrl: String,
        loadedUrl: String?,
        currentUrl: String?,
        hasError: Boolean,
    ): LoadAction {
        if (loadedUrl == null) return LoadAction.LOAD
        if (currentUrl.isNullOrBlank() || currentUrl == "about:blank") return LoadAction.LOAD
        // Another origin is another server process; its page cannot be kept.
        if (!sameOrigin(targetUrl, loadedUrl)) return LoadAction.LOAD
        return if (hasError) LoadAction.RELOAD else LoadAction.NONE
    }

    /**
     * Whether a sync failure deserves the banner: it follows a non-failed state or has a
     * new message, and it is not the failure the user already closed. [previousFailure]
     * is the message of the state seen just before, or null when that was not Failed.
     */
    fun shouldAnnounceSyncFailure(
        previousFailure: String?,
        message: String,
        dismissedFailure: String?,
    ): Boolean = message != dismissedFailure && message != previousFailure

    private fun sameOrigin(first: String, second: String): Boolean {
        val a = runCatching { URI(first) }.getOrNull() ?: return false
        val b = runCatching { URI(second) }.getOrNull() ?: return false
        val hostA = a.host ?: return false
        val hostB = b.host ?: return false
        return a.scheme.equals(b.scheme, ignoreCase = true) &&
            hostA.equals(hostB, ignoreCase = true) &&
            a.port == b.port
    }
}
