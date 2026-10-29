package io.github.hatake716.omochi

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LegalActivity : ComponentActivity() {
    private var notice by mutableStateOf<NoticeState>(NoticeState.Loading)
    private var bannerMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        // Asset I/O stays off the main thread; the section shows a spinner until it lands.
        lifecycleScope.launch {
            notice = withContext(Dispatchers.IO) {
                runCatching {
                    assets.open("legal/NOTICE.txt").bufferedReader().use { it.readText() }
                }.fold(
                    onSuccess = { NoticeState.Loaded(reflowNoticeText(it)) },
                    onFailure = { NoticeState.Failed },
                )
            }
        }
        setContent {
            OmochiTheme {
                LegalScreen(
                    notice = { notice },
                    bannerMessage = { bannerMessage },
                    onDismissBanner = { bannerMessage = null },
                    onBack = { finish() },
                    onOpen = ::openUrl,
                )
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            bannerMessage = "リンクを開けるブラウザが見つかりません: $url"
        } catch (_: SecurityException) {
            bannerMessage = "リンクを開けませんでした: $url"
        }
    }
}

private sealed interface NoticeState {
    data object Loading : NoticeState
    data class Loaded(val text: String) : NoticeState
    data object Failed : NoticeState
}

/**
 * NOTICE.txt is hard-wrapped for an 80-column terminal, which leaves ragged half-lines on
 * a phone. Lines of one paragraph are joined so the text wraps to the screen; paragraphs
 * and underlined headings ("=====") keep their breaks. CJK lines join without a space.
 */
internal fun reflowNoticeText(text: String): String =
    text.replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n"))
        .map { paragraph ->
            val lines = paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.any { it.isRuleLine() }) {
                lines.joinToString("\n")
            } else {
                buildString {
                    for (line in lines) {
                        if (isNotEmpty() && !last().isCjk() && !line.first().isCjk()) append(' ')
                        append(line)
                    }
                }
            }
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

private fun String.isRuleLine(): Boolean =
    length >= 3 && (all { it == '=' } || all { it == '-' })

private fun Char.isCjk(): Boolean = this >= '\u2E80'

private val LegalMaxWidth = 720.dp

@Composable
private fun LegalScreen(
    notice: () -> NoticeState,
    bannerMessage: () -> String?,
    onDismissBanner: () -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IosColors.GroupedBackground)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // iOS 27 navigation bar: a floating glass back button over the page.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                GlassIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBackIos,
                    contentDescription = "戻る",
                    onClick = onBack,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = LegalMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    IosLargeTitle(
                        title = "ライセンスと法的情報",
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                    BundledSection()
                    DownloadedSection()
                    NoticeSection(notice)
                    SourcesSection(onOpen)
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
                Spacer(Modifier.height(24.dp))
            }
        }
        LegalBanner(
            message = bannerMessage,
            onDismiss = onDismissBanner,
            // Below the navigation row, so the back button stays reachable.
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp)
                .widthIn(max = LegalMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun BundledSection() {
    IosGroupedSection(header = "アプリに同梱") {
        ComponentRow("Omochi", "アプリ本体: Apache License 2.0")
        ComponentRow("PRoot", "GPL-2.0・対応ソースをAPK内に同梱")
        ComponentRow("libandroid-shmem", "BSD-3-Clause・対応ソースを同梱")
        ComponentRow("talloc", "LGPL-3.0-or-later・対応ソースを同梱")
        ComponentRow("Apache Commons Compress", "Apache-2.0", showDivider = false)
    }
}

@Composable
private fun DownloadedSection() {
    IosGroupedSection(header = "セットアップ時に取得") {
        ComponentRow(
            "code-server ${BuildConfig.CODE_SERVER_VERSION}",
            "初回セットアップ時に公式配布物をSHA-256検証して取得",
        )
        ComponentRow(
            "Microsoft 日本語Language Pack ${BuildConfig.VSCODE_JA_LANGUAGE_PACK_VERSION}",
            "MIT License・公式VSIXをSHA-256検証して取得",
        )
        ComponentRow(
            "Claude Code",
            "Anthropic公式の署名済みstable aptリポジトリから取得",
            showDivider = false,
        )
    }
}

@Composable
private fun ComponentRow(name: String, detail: String, showDivider: Boolean = true) {
    IosListRow(
        title = name,
        modifier = Modifier.semantics(mergeDescendants = true) {},
        subtitle = detail,
        showDivider = showDivider,
    )
}

@Composable
private fun NoticeSection(notice: () -> NoticeState) {
    IosGroupedSection(header = "NOTICE") {
        when (val state = notice()) {
            NoticeState.Loading -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
            ) {
                IosActivityIndicator()
            }
            is NoticeState.Loaded -> SelectionContainer {
                Text(
                    text = state.text,
                    style = IosType.Footnote,
                    color = IosColors.Label,
                    modifier = Modifier.padding(16.dp),
                )
            }
            NoticeState.Failed -> Text(
                text = "NOTICE.txt を読み込めませんでした。",
                style = IosType.Subheadline,
                color = IosColors.RedText,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun SourcesSection(onOpen: (String) -> Unit) {
    IosGroupedSection(
        header = "ソースとドキュメント",
        footer = "OmochiはVisual Studio Code、Microsoft Corporation、Coder Technologies, Inc.、" +
            "Anthropicと提携・承認関係にありません。Claude CodeはAnthropicの製品であり、" +
            "利用にはAnthropicの利用条件と対応アカウントが適用されます。" +
            "各名称は説明目的でのみ使用しています。",
    ) {
        LinkRow(
            title = "Omochiソース",
            host = "github.com/hatake716/omochi",
            icon = Icons.Rounded.Code,
            tint = IosColors.Blue,
            onClick = { onOpen("https://github.com/hatake716/omochi") },
        )
        LinkRow(
            title = "code-server公式ソース",
            host = "github.com/coder/code-server",
            icon = Icons.Rounded.Code,
            tint = IosColors.Indigo,
            onClick = { onOpen("https://github.com/coder/code-server") },
        )
        LinkRow(
            title = "日本語Language Pack公式ソース",
            host = "github.com/microsoft/vscode-loc",
            icon = Icons.Rounded.Translate,
            tint = IosColors.Teal,
            onClick = {
                onOpen(
                    "https://github.com/microsoft/vscode-loc/" +
                        "tree/main/i18n/vscode-language-pack-ja"
                )
            },
        )
        LinkRow(
            title = "Claude Code公式ドキュメント",
            host = "code.claude.com",
            icon = Icons.AutoMirrored.Rounded.MenuBook,
            tint = IosColors.Orange,
            showDivider = false,
            onClick = { onOpen("https://code.claude.com/docs/ja/overview") },
        )
    }
}

@Composable
private fun LinkRow(
    title: String,
    host: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    IosListRow(
        title = title,
        subtitle = host,
        icon = icon,
        iconTint = tint,
        showDivider = showDivider,
        onClick = onClick,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = IosColors.TertiaryLabel,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun LegalBanner(
    message: () -> String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = message() ?: return
    LaunchedEffect(current) {
        delay(BANNER_TIMEOUT_MS)
        onDismiss()
    }
    IosBanner(message = current, onDismiss = onDismiss, modifier = modifier)
}

private const val BANNER_TIMEOUT_MS = 5_000L
