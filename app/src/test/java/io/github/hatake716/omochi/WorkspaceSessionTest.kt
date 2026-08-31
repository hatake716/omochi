package io.github.hatake716.omochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSessionTest {
    @Test
    fun normalizesOnlyFoldersInsideWorkspace() {
        assertEquals("/workspace", WorkspaceSession.normalizeGuestFolder("/workspace/"))
        assertEquals(
            "/workspace/project/app",
            WorkspaceSession.normalizeGuestFolder("/workspace/project/./src/../app"),
        )
        assertNull(WorkspaceSession.normalizeGuestFolder("/root"))
        assertNull(WorkspaceSession.normalizeGuestFolder("/workspace/../../root"))
        assertNull(WorkspaceSession.normalizeGuestFolder("workspace/project"))
        assertNull(WorkspaceSession.normalizeGuestFolder("/workspace/bad\\name"))
    }

    @Test
    fun extractsEncodedFolderFromTrustedStyleUrl() {
        assertEquals(
            "/workspace/日本語 project",
            WorkspaceSession.folderFromWorkbenchUrl(
                "http://127.0.0.1:43127/?folder=%2Fworkspace%2F%E6%97%A5%E6%9C%AC%E8%AA%9E%20project",
            ),
        )
        assertNull(WorkspaceSession.folderFromWorkbenchUrl("http://127.0.0.1:43127/?workspace=/tmp/a"))
        assertNull(WorkspaceSession.folderFromWorkbenchUrl("http://127.0.0.1:43127/?folder=/root"))
    }

    @Test
    fun buildsLoopbackUrlWithoutLosingUnicodeOrSpaces() {
        assertEquals(
            "http://127.0.0.1:43127/?folder=/workspace/%E6%97%A5%E6%9C%AC%E8%AA%9E%20project",
            WorkspaceSession.buildWorkbenchUrl(43127, "/workspace/日本語 project"),
        )
    }

    @Test
    fun validatesPortableFolderNames() {
        assertTrue(WorkspaceSession.isValidFolderName("日本語 project"))
        assertFalse(WorkspaceSession.isValidFolderName(".."))
        assertFalse(WorkspaceSession.isValidFolderName("a/b"))
        assertFalse(WorkspaceSession.isValidFolderName("a\\b"))
        assertFalse(WorkspaceSession.isValidFolderName("line\nbreak"))
    }
}
