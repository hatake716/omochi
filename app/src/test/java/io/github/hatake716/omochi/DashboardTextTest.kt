package io.github.hatake716.omochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardTextTest {
    @Test
    fun shortMessagesAreShownAsIs() {
        val message = "セットアップ失敗: HTTP 404"
        assertSame(message, compactForDisplay(message))
        val exact = "x".repeat(DISPLAY_TEXT_LIMIT)
        assertSame(exact, compactForDisplay(exact))
    }

    @Test
    fun longLogsKeepTheirBeginningAndEndWithinTheLimit() {
        val log = buildString {
            append("セットアップ失敗: guest command exited with 100\n")
            repeat(4_000) { append("proot info: vpid 1: syscall $it\n") }
            append("E: Unable to locate package claude-code")
        }
        val shown = compactForDisplay(log)
        assertTrue("length was ${shown.length}", shown.length <= DISPLAY_TEXT_LIMIT)
        assertTrue(shown.startsWith("セットアップ失敗: guest command exited with 100"))
        assertTrue(shown.endsWith("E: Unable to locate package claude-code"))
        assertTrue(shown.contains("文字を省略"))
    }

    @Test
    fun cutsSnapToLineBreaks() {
        val log = (1..400).joinToString("\n") { "line %03d with some proot output".format(it) }
        val shown = compactForDisplay(log, limit = 600)
        val (head, tail) = shown.split(Regex("\n…\\(途中の\\d+文字を省略\\)…\n"))
        // Every kept line is whole: no line is cut in the middle.
        (head.lines() + tail.lines()).forEach { line ->
            assertTrue("partial line: '$line'", line.matches(Regex("line \\d{3} with some proot output")))
        }
    }

    @Test
    fun reportsHowManyCharactersWereLeftOut() {
        val text = "a".repeat(10_000)
        val shown = compactForDisplay(text, limit = 1_000)
        val omitted = Regex("途中の(\\d+)文字を省略").find(shown)!!.groupValues[1].toInt()
        val kept = shown.replace(Regex("\n…\\(途中の\\d+文字を省略\\)…\n"), "")
        assertEquals(text.length, kept.length + omitted)
    }

    @Test
    fun neverSplitsSurrogatePairs() {
        // Emoji are surrogate pairs; any cut position must fall between them.
        for (limit in 300..320) {
            val text = "😀".repeat(2_000)
            val shown = compactForDisplay(text, limit = limit)
            shown.forEachIndexed { index, char ->
                if (char.isHighSurrogate()) {
                    assertTrue("limit $limit index $index", shown.getOrNull(index + 1)?.isLowSurrogate() == true)
                }
                if (char.isLowSurrogate()) {
                    assertTrue("limit $limit index $index", shown.getOrNull(index - 1)?.isHighSurrogate() == true)
                }
            }
        }
    }

    @Test
    fun setupStageFollowsTheInstallMarkers() {
        assertEquals(SetupStage.NotInstalled, setupStage(installed = false, japaneseClaudeInstalled = false))
        // A stale Japanese/Claude marker without a working IDE is still a first-time setup.
        assertEquals(SetupStage.NotInstalled, setupStage(installed = false, japaneseClaudeInstalled = true))
        assertEquals(SetupStage.NeedsJapaneseClaude, setupStage(installed = true, japaneseClaudeInstalled = false))
        assertEquals(SetupStage.Ready, setupStage(installed = true, japaneseClaudeInstalled = true))
    }

    @Test
    fun heroCopyKeepsTheExistingWording() {
        assertEquals("作業の続きを、すぐ開く。", heroHeadline(SetupStage.Ready, sessionActive = true))
        assertEquals("コードを、指先のそばに。", heroHeadline(SetupStage.Ready, sessionActive = false))
        assertEquals("日本語UIとClaude Codeを追加します。", heroHeadline(SetupStage.NeedsJapaneseClaude, true))
        assertEquals("Androidに、端末内IDEを。", heroHeadline(SetupStage.NotInstalled, false))
        assertTrue(heroDescription(SetupStage.NotInstalled).contains("127.0.0.1"))
    }

    @Test
    fun idleMessageNamesTheInstalledVersions() {
        assertEquals(
            "code-server 4.133.0・日本語UI・Claude Code 2.1.3 を利用できます",
            idleSetupMessage(true, true, "4.133.0", "2.1.3", defaultCodeServerVersion = "9.9.9"),
        )
        assertEquals(
            "code-server 9.9.9・日本語UI・Claude Code 導入済み を利用できます",
            idleSetupMessage(true, true, null, null, defaultCodeServerVersion = "9.9.9"),
        )
        assertEquals("日本語UIとClaude Codeの追加セットアップが必要です", idleSetupMessage(true, false, null, null))
        assertEquals(
            "Linuxランタイム、IDE、日本語UI、Claude Codeを端末内へ準備します",
            idleSetupMessage(false, false, null, null),
        )
    }

    @Test
    fun readySyncStatusSummarizesTheLastPass() {
        val time: (Long) -> String = { if (it <= 0L) "" else "12:34:56" }
        assertEquals("自動同期を待機しています", readySyncStatusText(1L, null, time))
        assertEquals("12:34:56 同期済み・変更なし", readySyncStatusText(1L, summary(), time))
        assertEquals("同期済み・変更なし", readySyncStatusText(0L, summary(), time))
        assertEquals(
            "12:34:56 同期済み・取込2・書込1・削除3・競合1",
            readySyncStatusText(
                1L,
                summary(imported = 2, exported = 1, deletedLocal = 1, deletedRemote = 2, conflicts = 1),
                time,
            ),
        )
        val onlyExported = readySyncStatusText(1L, summary(exported = 4), time)
        assertEquals("12:34:56 同期済み・書込4", onlyExported)
        assertFalse(onlyExported.contains("取込"))
    }

    @Test
    fun noticeParagraphsReflowButHeadingsKeepTheirRule() {
        val notice = """
            Omochi distribution notices
            ===========================

            The APK includes an Android/Bionic PRoot runtime derived from Termux packages.
            PRoot is licensed under GPL-2.0.

            日本語の行は
            空白なしで連結します。
        """.trimIndent()
        assertEquals(
            "Omochi distribution notices\n===========================\n\n" +
                "The APK includes an Android/Bionic PRoot runtime derived from Termux packages. " +
                "PRoot is licensed under GPL-2.0.\n\n" +
                "日本語の行は空白なしで連結します。",
            reflowNoticeText(notice),
        )
    }

    @Test
    fun noticeReflowHandlesWindowsLineEndingsAndExtraBlankLines() {
        assertEquals("a b\n\nc", reflowNoticeText("a\r\nb\r\n\r\n\r\n  \r\nc\r\n"))
    }

    private fun summary(
        imported: Int = 0,
        exported: Int = 0,
        deletedLocal: Int = 0,
        deletedRemote: Int = 0,
        conflicts: Int = 0,
    ) = WorkspaceSyncManager.Summary(
        imported = imported,
        exported = exported,
        deletedLocal = deletedLocal,
        deletedRemote = deletedRemote,
        conflicts = conflicts,
        bytesCopied = 0L,
        excluded = 0,
        recoveryPath = null,
    )
}
