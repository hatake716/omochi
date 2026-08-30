package io.github.hatake716.omochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceTransferTest {
    @Test
    fun safeNamePreventsPathInjection() {
        assertEquals(".._secret_key", WorkspaceTransfer.safeName("../secret\\key"))
        assertEquals("unnamed", WorkspaceTransfer.safeName(".."))
        assertEquals("unnamed", WorkspaceTransfer.safeName("   "))
        assertEquals("main.kt", WorkspaceTransfer.safeName(" main.kt "))
    }

    @Test
    fun safeNameLimitsFileSystemComponentLength() {
        assertEquals(240, WorkspaceTransfer.safeName("a".repeat(400)).length)
        val japanese = WorkspaceTransfer.safeName("餅".repeat(200))
        assertEquals(240, japanese.toByteArray(Charsets.UTF_8).size)
        assertEquals(80, japanese.length)
    }

    @Test
    fun exportContainmentRejectsSymlinksAndOutsidePaths() {
        val sandbox = Files.createTempDirectory("omochi-export-test").toFile()
        try {
            val workspace = File(sandbox, "workspace").apply { mkdirs() }
            val nested = File(workspace, "project/main.kt").apply {
                parentFile?.mkdirs()
                writeText("fun main() = Unit\n")
            }
            val outsideDirectory = File(sandbox, "private").apply { mkdirs() }
            val outside = File(outsideDirectory, "credential").apply { writeText("secret\n") }
            val directLink = File(workspace, "linked-credential")
            val directoryLink = File(workspace, "linked-private")
            Files.createSymbolicLink(directLink.toPath(), outside.toPath())
            Files.createSymbolicLink(directoryLink.toPath(), outsideDirectory.toPath())

            assertTrue(WorkspaceTransfer.isSafeExportSource(workspace, nested))
            assertFalse(WorkspaceTransfer.isSafeExportSource(workspace, outside))
            assertFalse(WorkspaceTransfer.isSafeExportSource(workspace, directLink))
            assertFalse(
                WorkspaceTransfer.isSafeExportSource(
                    workspace,
                    File(directoryLink, "credential"),
                )
            )
        } finally {
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun exportExcludesGitMetadataButKeepsWorkingFiles() {
        assertTrue(WorkspaceTransfer.isExcludedExportEntry(File("/workspace/.git")))
        assertFalse(WorkspaceTransfer.isExcludedExportEntry(File("/workspace/.gitignore")))
        assertFalse(WorkspaceTransfer.isExcludedExportEntry(File("/workspace/src")))
    }

    @Test
    fun exportMimeTypesDoNotAppendTxtToSourceFiles() {
        assertEquals("text/plain", WorkspaceTransfer.mimeType("notes.txt"))
        assertEquals("application/octet-stream", WorkspaceTransfer.mimeType("README.md"))
        assertEquals("application/octet-stream", WorkspaceTransfer.mimeType("Main.kt"))
        assertEquals("image/png", WorkspaceTransfer.mimeType("icon.png"))
    }
}
