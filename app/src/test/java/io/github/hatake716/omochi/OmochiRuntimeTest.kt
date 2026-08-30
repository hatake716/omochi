package io.github.hatake716.omochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OmochiRuntimeTest {
    @Test
    fun sha256MatchesKnownVector() {
        val file = Files.createTempFile("omochi-sha", ".txt").toFile()
        try {
            file.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                OmochiRuntime.sha256(file),
            )
            assertTrue(
                OmochiRuntime.hasExpectedSha256(
                    file,
                    "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
                )
            )
            assertFalse(OmochiRuntime.hasExpectedSha256(file, "00".repeat(32)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun archiveTargetRejectsTraversalAndAbsolutePaths() {
        val root = Files.createTempDirectory("omochi-archive").toFile().canonicalFile
        try {
            assertEquals(
                File(root, "safe/folder/file.txt").canonicalFile,
                OmochiRuntime.safeArchiveTarget(root, "./safe/folder/file.txt"),
            )
            assertEquals(
                File(root, "absolute-is-contained.txt").canonicalFile,
                OmochiRuntime.safeArchiveTarget(root, "/absolute-is-contained.txt"),
            )
            assertThrows(IllegalStateException::class.java) {
                OmochiRuntime.safeArchiveTarget(root, "../escape.txt")
            }
            assertThrows(IllegalStateException::class.java) {
                OmochiRuntime.safeArchiveTarget(root, "safe/../../escape.txt")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun serverAlwaysUsesLoopback() {
        assertEquals("http://127.0.0.1:54321/?folder=/workspace", OmochiRuntime.serverUrl(54321))
        assertThrows(IllegalArgumentException::class.java) { OmochiRuntime.serverUrl(80) }
    }
}
