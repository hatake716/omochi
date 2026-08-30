package io.github.hatake716.omochi

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.net.URI
import org.json.JSONObject

class WorkbenchActivity : ComponentActivity() {
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
    private var fullscreenEnabled by mutableStateOf(false)
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val chooseWebFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    private val serverListener: (OmochiServerManager.State) -> Unit = { state ->
        serverState = state
        if (state is OmochiServerManager.State.Running) {
            webError = null
            loadWorkbench(state.url)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OmochiRuntime.isInstalled(this)) {
            finish()
            return
        }

        enableEdgeToEdge()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        installBackHandler()

        OmochiServerManager.addListener(serverListener)
        OmochiServerManager.start(this)

        setContent {
            OmochiTheme {
                WorkbenchScreen(
                    serverState = serverState,
                    webError = webError,
                    showTouchBar = showTouchBar,
                    ctrlLatched = ctrlLatched,
                    altLatched = altLatched,
                    shiftLatched = shiftLatched,
                    createWebView = ::createWebView,
                    onClose = { finish() },
                    onToggleTouchBar = { showTouchBar = !showTouchBar },
                    onToggleFullscreen = ::toggleFullscreen,
                    onExplorer = { sendShortcut(KeyEvent.KEYCODE_E, ctrl = true, shift = true) },
                    onSearch = { sendShortcut(KeyEvent.KEYCODE_F, ctrl = true, shift = true) },
                    onSourceControl = { sendShortcut(KeyEvent.KEYCODE_G, ctrl = true, shift = true) },
                    onTerminal = { sendShortcut(KeyEvent.KEYCODE_GRAVE, ctrl = true) },
                    onCommandPalette = { sendShortcut(KeyEvent.KEYCODE_P, ctrl = true, shift = true) },
                    onRetry = ::retry,
                    onToggleCtrl = { ctrlLatched = !ctrlLatched },
                    onToggleAlt = { altLatched = !altLatched },
                    onToggleShift = { shiftLatched = !shiftLatched },
                    onKey = ::sendLatchedKey,
                    onShortcut = ::handleNamedShortcut,
                    onShowIme = ::showIme,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        if (OmochiServerManager.state() is OmochiServerManager.State.Failed) {
            OmochiServerManager.start(this)
        }
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        OmochiServerManager.removeListener(serverListener)
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
        super.onDestroy()
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }

                override fun handleOnBackStarted(backEvent: BackEventCompat) = Unit
                override fun handleOnBackProgressed(backEvent: BackEventCompat) = Unit
                override fun handleOnBackCancelled() = Unit
            }
        )
    }

    private fun retry() {
        webError = null
        loadedUrl = null
        OmochiServerManager.stop()
        OmochiServerManager.start(this)
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
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = false
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
                if (!submitLocalLogin(view, url.toUri())) {
                    injectTouchWorkbench(view)
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
        }

        setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("http://") || url.startsWith("https://")) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            }
        }

        val state = serverState
        if (state is OmochiServerManager.State.Running) {
            loadedUrl = state.url
            loadUrl(state.url)
        }
    }

    private fun routeUrl(uri: Uri): Boolean {
        if (uri.scheme == "about" || uri.scheme == "blob" || uri.scheme == "data") return false
        if (isTrustedWorkbenchUri(uri)) return false

        if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
        return true
    }

    private fun isTrustedWorkbenchUri(uri: Uri): Boolean {
        val running = serverState as? OmochiServerManager.State.Running ?: return false
        val expected = running.url.toUri()
        return uri.scheme == "http" &&
            uri.host == "127.0.0.1" &&
            uri.host == expected.host &&
            uri.port == expected.port
    }

    private fun submitLocalLogin(view: WebView, uri: Uri): Boolean {
        if (!isTrustedWorkbenchUri(uri) || !uri.path.orEmpty().trimEnd('/').endsWith("/login")) {
            return false
        }
        val password = JSONObject.quote(OmochiRuntime.authPassword(applicationContext))
        val script = """
            (() => {
              if (window.__omochiLoginSubmitted) return true;
              if (!document.body || !document.body.innerText.includes('Omochi local workbench')) return false;
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
                .monaco-workbench .part.activitybar .action-label { min-height: 46px !important; min-width: 46px !important; }
                .monaco-workbench .part.sidebar .monaco-list-row,
                .monaco-workbench .part.auxiliarybar .monaco-list-row { min-height: 30px !important; }
                .monaco-workbench .tabs-container .tab { min-height: 40px !important; }
                .monaco-workbench .tabs-container .tab .tab-label { line-height: 40px !important; }
                .monaco-workbench .monaco-action-bar .action-item > .action-label { min-width: 34px; min-height: 34px; }
                .monaco-workbench .monaco-button { min-height: 38px !important; border-radius: 8px !important; }
                .monaco-workbench input, .monaco-workbench textarea { font-size: max(14px, 1em); }
                .monaco-workbench .monaco-scrollable-element > .scrollbar.vertical { width: 13px !important; }
                .monaco-workbench .monaco-scrollable-element > .scrollbar.horizontal { height: 13px !important; }
                .quick-input-widget { top: 6% !important; width: min(92vw, 720px) !important; margin-left: auto !important; margin-right: auto !important; border-radius: 14px !important; overflow: hidden; }
                .context-view, .monaco-menu-container { max-width: calc(100vw - 12px) !important; }
                .monaco-menu .monaco-action-bar.vertical .action-item { min-height: 38px !important; }
                .monaco-workbench .part.statusbar { min-height: 27px !important; }
                [data-id="workbench.view.extensions"],
                [data-command-id="workbench.view.extensions"] { display: none !important; }
                @media (max-width: 600px) {
                  .monaco-workbench .part.sidebar { min-width: 220px !important; }
                  .monaco-workbench .part.panel { min-height: 180px !important; }
                  .monaco-workbench .codicon { font-size: 18px; }
                }
              `;
              document.head.appendChild(style);

              let scheduled = false;
              const hideExtensions = () => {
                scheduled = false;
                document.querySelectorAll('[aria-label], [data-id], [data-command-id]').forEach((element) => {
                  const label = (element.getAttribute('aria-label') || '').toLowerCase();
                  const id = (element.getAttribute('data-id') || element.getAttribute('data-command-id') || '').toLowerCase();
                  if (id.includes('workbench.view.extensions') || label === 'extensions' || label.startsWith('extensions ') || label.includes('拡張機能')) {
                    const target = element.closest('.action-item') || element;
                    target.style.setProperty('display', 'none', 'important');
                  }
                });
              };
              const scheduleHide = () => {
                if (!scheduled) {
                  scheduled = true;
                  requestAnimationFrame(hideExtensions);
                }
              };
              new MutationObserver(scheduleHide).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['aria-label'] });
              hideExtensions();
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
        dispatchKey(keyCode, modifierState(ctrl, alt, shift))
    }

    private fun handleNamedShortcut(name: String) {
        when (name) {
            "save" -> sendShortcut(KeyEvent.KEYCODE_S, ctrl = true)
            "undo" -> sendShortcut(KeyEvent.KEYCODE_Z, ctrl = true)
            "redo" -> sendShortcut(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)
            "find" -> sendShortcut(KeyEvent.KEYCODE_F, ctrl = true)
            "quickOpen" -> sendShortcut(KeyEvent.KEYCODE_P, ctrl = true)
            "newTerminal" -> sendShortcut(KeyEvent.KEYCODE_GRAVE, ctrl = true, shift = true)
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

@Composable
private fun WorkbenchScreen(
    serverState: OmochiServerManager.State,
    webError: String?,
    showTouchBar: Boolean,
    ctrlLatched: Boolean,
    altLatched: Boolean,
    shiftLatched: Boolean,
    createWebView: () -> WebView,
    onClose: () -> Unit,
    onToggleTouchBar: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onExplorer: () -> Unit,
    onSearch: () -> Unit,
    onSourceControl: () -> Unit,
    onTerminal: () -> Unit,
    onCommandPalette: () -> Unit,
    onRetry: () -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onKey: (Int) -> Unit,
    onShortcut: (String) -> Unit,
    onShowIme: () -> Unit,
) {
    val imeBottom = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    val navigationBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val imeVisible = imeBottom > navigationBottom

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OmochiColors.Window)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        WorkbenchTitleBar(
            state = serverState,
            onClose = onClose,
            onToggleTouchBar = onToggleTouchBar,
            onToggleFullscreen = onToggleFullscreen,
            onExplorer = onExplorer,
            onSearch = onSearch,
            onSourceControl = onSourceControl,
            onTerminal = onTerminal,
            onCommandPalette = onCommandPalette,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { createWebView() },
                modifier = Modifier.fillMaxSize(),
            )

            when (serverState) {
                OmochiServerManager.State.Stopped -> LoadingPanel("IDEサーバーを開始しています…")
                is OmochiServerManager.State.Starting -> LoadingPanel(serverState.message)
                is OmochiServerManager.State.Failed -> ErrorPanel(serverState.message, onRetry)
                is OmochiServerManager.State.Running -> {
                    if (webError != null) ErrorPanel(webError, onRetry)
                }
            }
        }

        if (showTouchBar) {
            TouchKeyBar(
                ctrlLatched = ctrlLatched,
                altLatched = altLatched,
                shiftLatched = shiftLatched,
                onToggleCtrl = onToggleCtrl,
                onToggleAlt = onToggleAlt,
                onToggleShift = onToggleShift,
                onKey = onKey,
                onShortcut = onShortcut,
                onShowIme = onShowIme,
                modifier = if (imeVisible) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        } else if (!imeVisible) {
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun WorkbenchTitleBar(
    state: OmochiServerManager.State,
    onClose: () -> Unit,
    onToggleTouchBar: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onExplorer: () -> Unit,
    onSearch: () -> Unit,
    onSourceControl: () -> Unit,
    onTerminal: () -> Unit,
    onCommandPalette: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(
                Brush.verticalGradient(listOf(Color(0xFFF9F7F5), Color(0xFFE8E3DE)))
            )
            .border(0.5.dp, OmochiColors.Border)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrafficButton(OmochiColors.Red, "閉じる", onClose)
        TrafficButton(OmochiColors.Yellow, "タッチキー", onToggleTouchBar)
        TrafficButton(OmochiColors.Green, "フルスクリーン", onToggleFullscreen)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Omochi",
            style = MaterialTheme.typography.labelLarge,
            color = OmochiColors.Ink,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (state is OmochiServerManager.State.Running) OmochiColors.Green else OmochiColors.Yellow)
        )
        Spacer(Modifier.width(4.dp))
        ToolbarButton(Icons.Outlined.FolderOpen, "Explorer", onExplorer)
        ToolbarButton(Icons.Outlined.Search, "検索", onSearch)
        ToolbarButton(Icons.Outlined.AccountTree, "Git", onSourceControl)
        ToolbarButton(Icons.Outlined.Terminal, "ターミナル", onTerminal)
        ToolbarButton(Icons.Outlined.MoreHoriz, "コマンド", onCommandPalette)
    }
}

@Composable
private fun TrafficButton(color: Color, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(34.dp).semantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .size(13.dp)
                .clip(CircleShape)
                .background(color)
                .border(0.5.dp, Color.Black.copy(alpha = 0.17f), CircleShape)
        )
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(icon, contentDescription = label, tint = OmochiColors.Ink, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun TouchKeyBar(
    ctrlLatched: Boolean,
    altLatched: Boolean,
    shiftLatched: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onKey: (Int) -> Unit,
    onShortcut: (String) -> Unit,
    onShowIme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(55.dp)
            .background(OmochiColors.Terminal)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyButton("ESC") { onKey(KeyEvent.KEYCODE_ESCAPE) }
        KeyButton("CTRL", ctrlLatched, onToggleCtrl)
        KeyButton("ALT", altLatched, onToggleAlt)
        KeyButton("SHIFT", shiftLatched, onToggleShift)
        KeyButton("TAB") { onKey(KeyEvent.KEYCODE_TAB) }
        KeyButton("←") { onKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        KeyButton("↑") { onKey(KeyEvent.KEYCODE_DPAD_UP) }
        KeyButton("↓") { onKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        KeyButton("→") { onKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        KeyButton("SAVE") { onShortcut("save") }
        KeyButton("UNDO") { onShortcut("undo") }
        KeyButton("REDO") { onShortcut("redo") }
        KeyButton("FIND") { onShortcut("find") }
        KeyButton("OPEN") { onShortcut("quickOpen") }
        KeyButton("TERM+") { onShortcut("newTerminal") }
        Surface(
            onClick = onShowIme,
            color = OmochiColors.Accent,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.height(42.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Keyboard, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("IME", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) OmochiColors.Accent else Color(0xFF34363B),
        contentColor = Color.White,
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier.height(42.dp),
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}

@Composable
private fun LoadingPanel(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(OmochiColors.Window),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = OmochiColors.Accent)
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = OmochiColors.Muted,
                modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp),
            )
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(OmochiColors.Window.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = OmochiColors.Raised,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, tint = OmochiColors.Accent)
                Text(
                    "ワークベンチを開けません",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OmochiColors.Muted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Button(onClick = onRetry) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("再起動")
                }
            }
        }
    }
}
