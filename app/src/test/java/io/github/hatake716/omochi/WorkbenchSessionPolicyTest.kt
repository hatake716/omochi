package io.github.hatake716.omochi

import io.github.hatake716.omochi.WorkbenchSessionPolicy.LoadAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchSessionPolicyTest {
    private val running = "http://127.0.0.1:43127/?folder=/workspace"

    @Test
    fun republishedSessionKeepsTheLivePage() {
        assertEquals(
            LoadAction.NONE,
            WorkbenchSessionPolicy.loadAction(running, running, running, hasError = false),
        )
    }

    @Test
    fun republishedSessionKeepsPagesNavigatedInsideTheSameServer() {
        // Login redirects and Code - OSS folder switches stay on the same origin.
        listOf(
            "http://127.0.0.1:43127/login?to=%2F%3Ffolder%3D%2Fworkspace",
            "http://127.0.0.1:43127/?folder=/workspace/app",
        ).forEach { current ->
            assertEquals(
                LoadAction.NONE,
                WorkbenchSessionPolicy.loadAction(running, running, current, hasError = false),
            )
        }
        // A captured folder changes the published URL, not the server.
        assertEquals(
            LoadAction.NONE,
            WorkbenchSessionPolicy.loadAction(
                targetUrl = "http://127.0.0.1:43127/?folder=/workspace/app",
                loadedUrl = running,
                currentUrl = "http://127.0.0.1:43127/?folder=/workspace/app",
                hasError = false,
            ),
        )
    }

    @Test
    fun errorPanelIsRetriedWithReload() {
        assertEquals(
            LoadAction.RELOAD,
            WorkbenchSessionPolicy.loadAction(running, running, running, hasError = true),
        )
    }

    @Test
    fun emptyWebViewIsLoaded() {
        listOf(null, "", "about:blank").forEach { current ->
            assertEquals(
                LoadAction.LOAD,
                WorkbenchSessionPolicy.loadAction(running, running, current, hasError = false),
            )
        }
    }

    @Test
    fun newSessionOrForcedSwitchIsLoaded() {
        // Nothing loaded from this session yet (first Running, folder switch, retry).
        assertEquals(
            LoadAction.LOAD,
            WorkbenchSessionPolicy.loadAction(running, null, running, hasError = false),
        )
        // A restarted server listens on another port.
        assertEquals(
            LoadAction.LOAD,
            WorkbenchSessionPolicy.loadAction(
                targetUrl = "http://127.0.0.1:50211/?folder=/workspace",
                loadedUrl = running,
                currentUrl = running,
                hasError = false,
            ),
        )
        assertEquals(
            LoadAction.LOAD,
            WorkbenchSessionPolicy.loadAction(running, "not a url", running, hasError = true),
        )
    }

    @Test
    fun syncFailureIsAnnouncedOncePerFailure() {
        // First failure after a healthy state.
        assertTrue(WorkbenchSessionPolicy.shouldAnnounceSyncFailure(null, "権限がありません", null))
        // The worker retrying the same failure every few seconds.
        assertFalse(
            WorkbenchSessionPolicy.shouldAnnounceSyncFailure("権限がありません", "権限がありません", null)
        )
        // A different failure is news.
        assertTrue(
            WorkbenchSessionPolicy.shouldAnnounceSyncFailure("権限がありません", "容量不足", null)
        )
    }

    @Test
    fun dismissedSyncFailureStaysClosed() {
        assertFalse(
            WorkbenchSessionPolicy.shouldAnnounceSyncFailure(null, "権限がありません", "権限がありません")
        )
        assertTrue(
            WorkbenchSessionPolicy.shouldAnnounceSyncFailure(null, "容量不足", "権限がありません")
        )
    }
}
