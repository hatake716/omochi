package io.github.hatake716.omochi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

class LegalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val notice = assets.open("legal/NOTICE.txt").bufferedReader().use { it.readText() }
        setContent {
            OmochiTheme {
                LegalScreen(
                    notice = notice,
                    onBack = { finish() },
                    onOpen = ::openUrl,
                )
            }
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}

@Composable
private fun LegalScreen(
    notice: String,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OmochiColors.Window)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF9F7F5))
                .border(0.5.dp, OmochiColors.Border)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
            }
            Text("ライセンスと法的情報", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = OmochiColors.Raised),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Omochi", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "アプリ本体: Apache License 2.0",
                        color = OmochiColors.Muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 14.dp))
                    ComponentLine("PRoot", "GPL-2.0・対応ソースをAPK内に同梱")
                    ComponentLine("libandroid-shmem", "BSD-3-Clause・対応ソースを同梱")
                    ComponentLine("talloc", "LGPL-3.0-or-later・対応ソースを同梱")
                    ComponentLine("Apache Commons Compress", "Apache-2.0")
                    ComponentLine(
                        "code-server ${BuildConfig.CODE_SERVER_VERSION}",
                        "初回セットアップ時に公式配布物をSHA-256検証して取得",
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = OmochiColors.Surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OmochiColors.Ink,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            }

            OutlinedButton(
                onClick = { onOpen("https://github.com/hatake716/omochi") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Omochiソース")
            }
            OutlinedButton(
                onClick = { onOpen("https://github.com/coder/code-server") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("code-server公式ソース")
            }

            Text(
                "OmochiはVisual Studio Code、Microsoft Corporation、Coder Technologies, Inc.と" +
                    "提携・承認関係にありません。Visual Studio Code名称は説明目的でのみ使用しています。",
                style = MaterialTheme.typography.bodyMedium,
                color = OmochiColors.Muted,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ComponentLine(name: String, detail: String) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = OmochiColors.Muted)
    }
}
