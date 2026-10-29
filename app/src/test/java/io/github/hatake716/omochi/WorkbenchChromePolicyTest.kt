package io.github.hatake716.omochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchChromePolicyTest {
    @Test
    fun sessionStatusNamesEachServerState() {
        assertEquals(
            WorkbenchSessionStatus("待機中", WorkbenchSessionTone.Pending),
            WorkbenchChromePolicy.sessionStatus(OmochiServerManager.State.Stopped),
        )
        assertEquals(
            WorkbenchSessionStatus("起動中", WorkbenchSessionTone.Pending),
            WorkbenchChromePolicy.sessionStatus(OmochiServerManager.State.Starting("apt を準備しています")),
        )
        assertEquals(
            WorkbenchSessionStatus("実行中", WorkbenchSessionTone.Running),
            WorkbenchChromePolicy.sessionStatus(
                OmochiServerManager.State.Running("http://127.0.0.1:43127/?folder=/workspace"),
            ),
        )
        assertEquals(
            WorkbenchSessionStatus("失敗", WorkbenchSessionTone.Failed),
            WorkbenchChromePolicy.sessionStatus(OmochiServerManager.State.Failed("exit 1")),
        )
    }

    @Test
    fun startingProgressKeepsTheSameStatus() {
        // Progress messages change often; the top bar must see an equal value and skip.
        assertEquals(
            WorkbenchChromePolicy.sessionStatus(OmochiServerManager.State.Starting("1/3")),
            WorkbenchChromePolicy.sessionStatus(OmochiServerManager.State.Starting("2/3")),
        )
    }

    @Test
    fun syncLabelAndLinkWithoutATree() {
        val disconnected = WorkspaceSyncManager.State.Disconnected
        val syncing = WorkspaceSyncManager.State.Syncing(link = null, message = "接続中")
        val failed = WorkspaceSyncManager.State.Failed(link = null, message = "権限がありません")

        assertEquals("端末未連携", WorkbenchChromePolicy.syncLabel(disconnected))
        assertEquals("同期中", WorkbenchChromePolicy.syncLabel(syncing))
        assertEquals("同期要確認", WorkbenchChromePolicy.syncLabel(failed))

        assertFalse(WorkbenchChromePolicy.isSyncLinked(disconnected))
        assertFalse(WorkbenchChromePolicy.isSyncLinked(syncing))
        assertFalse(WorkbenchChromePolicy.isSyncLinked(failed))
    }

    @Test
    fun folderTitleIsTheLastSegment() {
        assertEquals("workspace", WorkbenchChromePolicy.folderTitle("/workspace"))
        assertEquals("workspace", WorkbenchChromePolicy.folderTitle("/workspace/"))
        assertEquals("workspace", WorkbenchChromePolicy.folderTitle(""))
        assertEquals("app", WorkbenchChromePolicy.folderTitle("/workspace/app"))
        assertEquals("Notes", WorkbenchChromePolicy.folderTitle("/workspace/phone/Notes/"))
        assertEquals("メモ (linked 2)", WorkbenchChromePolicy.folderTitle("/workspace/phone/メモ (linked 2)"))
    }

    @Test
    fun imeCountsOnlyWhenItCoversMoreThanTheNavigationBar() {
        // Hidden keyboard, with gesture or three-button navigation.
        assertFalse(WorkbenchChromePolicy.isImeShown(imeBottom = 0, navigationBottom = 63))
        // Some devices report the navigation bar height as the IME inset while it is hidden.
        assertFalse(WorkbenchChromePolicy.isImeShown(imeBottom = 63, navigationBottom = 63))
        assertTrue(WorkbenchChromePolicy.isImeShown(imeBottom = 820, navigationBottom = 63))
        // Fullscreen hides the navigation bar.
        assertTrue(WorkbenchChromePolicy.isImeShown(imeBottom = 760, navigationBottom = 0))
        assertFalse(WorkbenchChromePolicy.isImeShown(imeBottom = 0, navigationBottom = 0))
    }

    @Test
    fun homeLabelNeedsRoomForTheFolderName() {
        // Portrait phones (Pixel 10a is 411dp wide) keep the chevron only.
        assertFalse(WorkbenchChromePolicy.showsHomeLabel(360))
        assertFalse(WorkbenchChromePolicy.showsHomeLabel(411))
        assertFalse(WorkbenchChromePolicy.showsHomeLabel(WorkbenchChromePolicy.HOME_LABEL_MIN_WIDTH_DP - 1))
        assertTrue(WorkbenchChromePolicy.showsHomeLabel(WorkbenchChromePolicy.HOME_LABEL_MIN_WIDTH_DP))
        // Landscape phones, foldables and tablets.
        assertTrue(WorkbenchChromePolicy.showsHomeLabel(891))
    }
}
