package io.github.hatake716.omochi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchUrlPolicyTest {
    private val running = "http://127.0.0.1:43127/?folder=/workspace"

    @Test
    fun acceptsOnlyTheActiveLoopbackOrigin() {
        assertTrue(WorkbenchUrlPolicy.isTrustedLoopback(running, running))
        assertTrue(
            WorkbenchUrlPolicy.isTrustedLoopback(
                running,
                "http://127.0.0.1:43127/stable/workbench/workbench.html",
            )
        )

        assertFalse(WorkbenchUrlPolicy.isTrustedLoopback(running, "https://127.0.0.1:43127/"))
        assertFalse(WorkbenchUrlPolicy.isTrustedLoopback(running, "http://127.0.0.1:43128/"))
        assertFalse(WorkbenchUrlPolicy.isTrustedLoopback(running, "http://localhost:43127/"))
        assertFalse(WorkbenchUrlPolicy.isTrustedLoopback(running, "http://127.0.0.1@evil.example:43127/"))
        assertFalse(WorkbenchUrlPolicy.isTrustedLoopback(null, running))
    }

    @Test
    fun externalBrowserSchemesAreExplicit() {
        assertTrue(WorkbenchUrlPolicy.shouldOpenExternally("https"))
        assertTrue(WorkbenchUrlPolicy.shouldOpenExternally("HTTP"))
        assertTrue(WorkbenchUrlPolicy.shouldOpenExternally("mailto"))
        assertFalse(WorkbenchUrlPolicy.shouldOpenExternally("file"))
        assertFalse(WorkbenchUrlPolicy.shouldOpenExternally("javascript"))
        assertFalse(WorkbenchUrlPolicy.shouldOpenExternally(null))
    }

    @Test
    fun popupRoutesNeverLeaveAHiddenWorkbenchWindow() {
        assertTrue(
            WorkbenchUrlPolicy.classifyPopup(running, "about:blank") ==
                WorkbenchUrlPolicy.PopupTarget.INITIAL_BLANK
        )
        assertTrue(
            WorkbenchUrlPolicy.classifyPopup(
                running,
                "http://127.0.0.1:43127/?folder=/workspace/project",
            ) == WorkbenchUrlPolicy.PopupTarget.REUSE_WORKBENCH
        )
        assertTrue(
            WorkbenchUrlPolicy.classifyPopup(running, "https://code.visualstudio.com/docs") ==
                WorkbenchUrlPolicy.PopupTarget.EXTERNAL_APP
        )
        assertTrue(
            WorkbenchUrlPolicy.classifyPopup(running, "file:///data/local/tmp/private") ==
                WorkbenchUrlPolicy.PopupTarget.BLOCKED
        )
    }

    @Test
    fun nativeMenuBridgeRequiresTheActiveTrustedPageAndAnExactAction() {
        assertTrue(
            WorkbenchUrlPolicy.nativeMenuAction(
                running,
                running,
                "omochi://menu/open-folder",
            ) == WorkbenchUrlPolicy.NativeMenuAction.OPEN_FOLDER
        )
        assertTrue(
            WorkbenchUrlPolicy.nativeMenuAction(
                running,
                "http://127.0.0.1:43127/stable/workbench.html",
                "omochi://menu/close-folder",
            ) == WorkbenchUrlPolicy.NativeMenuAction.CLOSE_FOLDER
        )
        assertTrue(
            WorkbenchUrlPolicy.nativeMenuAction(
                running,
                running,
                "omochi://menu/link-device-folder",
            ) == WorkbenchUrlPolicy.NativeMenuAction.LINK_DEVICE_FOLDER
        )
        assertTrue(
            WorkbenchUrlPolicy.nativeMenuAction(
                running,
                running,
                "omochi://menu/exit-workbench",
            ) == WorkbenchUrlPolicy.NativeMenuAction.EXIT_WORKBENCH
        )
        assertFalse(
            WorkbenchUrlPolicy.nativeMenuAction(
                running,
                "https://evil.example/",
                "omochi://menu/open-folder",
            ) != null
        )
        assertFalse(
            WorkbenchUrlPolicy.nativeMenuAction(
                running,
                running,
                "omochi://evil/open-folder",
            ) != null
        )
        assertFalse(
            WorkbenchUrlPolicy.nativeMenuAction(
                running,
                running,
                "omochi://menu/open-folder?unexpected=1",
            ) != null
        )
    }
}
