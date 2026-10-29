package io.github.hatake716.omochi

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    @Test
    fun capturedFolderIsWrittenOnlyWhenItChanges() {
        assertNull(WorkspaceSession.folderToPersist("/workspace/app", "/workspace/app"))
        assertNull(WorkspaceSession.folderToPersist("/workspace/app", "/workspace/app/"))
        // No stored value already means the workspace root.
        assertNull(WorkspaceSession.folderToPersist(null, "/workspace"))
        assertEquals("/workspace", WorkspaceSession.folderToPersist("/workspace/app", "/workspace"))
        assertEquals("/workspace/app", WorkspaceSession.folderToPersist(null, "/workspace/app"))
        // A stale, invalid stored value is replaced.
        assertEquals("/workspace/app", WorkspaceSession.folderToPersist("/root", "/workspace/app"))
        // Folders outside the workspace are never persisted.
        assertNull(WorkspaceSession.folderToPersist("/workspace/app", "/root"))
        assertNull(WorkspaceSession.folderToPersist("/workspace/app", null))
    }

    @Test
    fun listsChildFoldersSortedWithChildFlags() {
        val root = workspaceRoot()
        File(root, "beta/src").mkdirs()
        File(root, "Alpha").mkdirs()
        File(root, "alpha2").mkdirs()
        File(root, "alpha2/readme.md").writeText("x")
        File(root, "notes.txt").writeText("x")

        val folders = WorkspaceSession.listFoldersIn(root, "/workspace")

        assertEquals(listOf("Alpha", "alpha2", "beta"), folders.map { it.displayName })
        assertEquals(
            listOf("/workspace/Alpha", "/workspace/alpha2", "/workspace/beta"),
            folders.map { it.guestPath },
        )
        assertEquals(listOf(false, false, true), folders.map { it.hasChildren })
    }

    @Test
    fun childFolderCheckStopsAtFilesAndMissingFolders() {
        val root = workspaceRoot()
        File(root, "files").mkdirs()
        repeat(3) { File(root, "files/$it.txt").writeText("x") }
        File(root, "nested/deeper").mkdirs()

        assertFalse(WorkspaceSession.hasChildFolder(File(root, "files")))
        assertTrue(WorkspaceSession.hasChildFolder(File(root, "nested")))
        assertFalse(WorkspaceSession.hasChildFolder(File(root, "missing")))
    }

    @Test
    fun linksLeavingTheWorkspaceAreNotListed() {
        val root = workspaceRoot()
        val outside = temporary.newFolder("outside").canonicalFile
        File(root, "inside").mkdirs()
        val linked = runCatching {
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        }.isSuccess
        assumeTrue("symbolic links are unavailable", linked)

        val folders = WorkspaceSession.listFoldersIn(root, "/workspace")

        assertEquals(listOf("/workspace/inside"), folders.map { it.guestPath })
    }

    @Test
    fun resolvesAndCreatesFoldersInsideTheWorkspaceOnly() {
        val root = workspaceRoot()

        val created = WorkspaceSession.createFolderIn(root, "/workspace", " 日本語 project ")
        assertEquals("/workspace/日本語 project", created.guestPath)
        assertTrue(File(root, "日本語 project").isDirectory)
        assertEquals("workspace", WorkspaceSession.resolveFolderIn(root, "/workspace").displayName)

        assertFailsWith { WorkspaceSession.createFolderIn(root, "/workspace", "日本語 project") }
        assertFailsWith { WorkspaceSession.createFolderIn(root, "/workspace", "..") }
        assertFailsWith { WorkspaceSession.resolveFolderIn(root, "/workspace/missing") }
        assertFailsWith { WorkspaceSession.resolveFolderIn(root, "/root") }
    }

    @get:Rule
    val temporary = TemporaryFolder()

    private fun workspaceRoot(): File = temporary.newFolder("workspace").canonicalFile

    private fun assertFailsWith(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected a failure", failure != null)
    }
}
