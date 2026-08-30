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
}
