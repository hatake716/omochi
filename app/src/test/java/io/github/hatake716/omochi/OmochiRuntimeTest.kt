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
        assertEquals(
            "http://127.0.0.1:54321/?folder=/workspace/project%20one",
            OmochiRuntime.serverUrl(54321, "/workspace/project one"),
        )
        assertThrows(IllegalArgumentException::class.java) { OmochiRuntime.serverUrl(80) }
        assertThrows(IllegalArgumentException::class.java) {
            OmochiRuntime.serverUrl(54321, "/root")
        }
    }

    @Test
    fun languagePackHashMatchesVsCodeCacheAlgorithm() {
        assertEquals(
            "5cc3bef61c1e4b1db1f6c78dc4120a56",
            OmochiRuntime.languagePackHash(
                "ms-ceintl.vscode-language-pack-ja",
                "1.131.2026082318",
            ),
        )
    }

    @Test
    fun seccompFastPathLeavesProotNoSeccompUnset() {
        val environment = prootEnvironment(seccomp = true, verbose = false)

        assertFalse(environment.containsKey("PROOT_NO_SECCOMP"))
        assertFalse(environment.containsKey("PROOT_VERBOSE"))
        assertEquals("/data/user/0/app/files/embedded-runtime/tmp", environment["PROOT_TMP_DIR"])
        assertEquals("/data/app/lib/arm64/libproot-loader.so", environment["PROOT_LOADER"])
        assertEquals("/data/app/lib/arm64", environment["LD_LIBRARY_PATH"])
        assertEquals("/rootfs/.l2s", environment["PROOT_L2S_DIR"])
    }

    @Test
    fun ptraceOnlyModeSetsProotNoSeccomp() {
        val environment = prootEnvironment(seccomp = false, verbose = true)

        assertEquals("1", environment["PROOT_NO_SECCOMP"])
        assertEquals("9", environment["PROOT_VERBOSE"])
        // PROOT_NO_SECCOMP 以外は seccomp の有無で変わらない。
        assertEquals(
            prootEnvironment(seccomp = true, verbose = true),
            environment.filterKeys { it != "PROOT_NO_SECCOMP" },
        )
    }

    @Test
    fun hostOnlyEnvironmentHasNoLinkToSymlinkDirectory() {
        val environment = OmochiRuntime.prootHostEnvironment(
            home = "/home",
            tempDir = "/tmp",
            loader = "/loader",
            libraryPath = "/lib",
            l2sDir = null,
            seccomp = true,
            verbose = false,
        )

        assertFalse(environment.containsKey("PROOT_L2S_DIR"))
        assertEquals(
            listOf(
                "HOME", "TMPDIR", "PROOT_TMP_DIR", "PROOT_LOADER", "LD_LIBRARY_PATH",
                "PATH", "ANDROID_DATA", "ANDROID_ROOT", "TERM",
            ),
            environment.keys.toList(),
        )
    }

    @Test
    fun shortDiagnosticsAreKeptVerbatim() {
        val output = "line\n".repeat(100)

        assertEquals(output, OmochiRuntime.boundedDiagnostic(output, 8 * 1024, "/logs/x.log"))
    }

    @Test
    fun longDiagnosticsKeepHeadAndTailWithinTheLimit() {
        val output = "HEAD-MARKER\n" + "verbose trace line\n".repeat(50_000) + "fatal: execve failed\n"

        val bounded = OmochiRuntime.boundedDiagnostic(
            output,
            OmochiRuntime.UI_DIAGNOSTIC_LIMIT,
            "/data/files/logs/proot-self-test.log",
        )

        assertTrue(bounded.startsWith("HEAD-MARKER\n"))
        assertTrue(bounded.endsWith("fatal: execve failed\n"))
        assertTrue("/data/files/logs/proot-self-test.log" in bounded)
        assertTrue(bounded.length <= OmochiRuntime.UI_DIAGNOSTIC_LIMIT + 200)
        val tailLength = OmochiRuntime.UI_DIAGNOSTIC_LIMIT - 1_024
        assertTrue(bounded.endsWith(output.takeLast(tailLength)))
    }

    @Test
    fun diagnosticsNeverSplitSurrogatePairs() {
        val output = "😀".repeat(10_000)

        val bounded = OmochiRuntime.boundedDiagnostic(output, 1_000, null)

        bounded.forEachIndexed { index, c ->
            if (c.isHighSurrogate()) assertTrue(bounded[index + 1].isLowSurrogate())
            if (c.isLowSurrogate()) assertTrue(bounded[index - 1].isHighSurrogate())
        }
    }

    private fun prootEnvironment(seccomp: Boolean, verbose: Boolean) =
        OmochiRuntime.prootHostEnvironment(
            home = "/data/user/0/app/files",
            tempDir = "/data/user/0/app/files/embedded-runtime/tmp",
            loader = "/data/app/lib/arm64/libproot-loader.so",
            libraryPath = "/data/app/lib/arm64",
            l2sDir = "/rootfs/.l2s",
            seccomp = seccomp,
            verbose = verbose,
        )

    @Test
    fun markerParserIgnoresCommentsAndMalformedRows() {
        assertEquals(
            mapOf(
                "schema" to "2",
                "locale" to "ja",
                "value" to "left=right",
            ),
            OmochiRuntime.markerValues(
                """
                # Omochi marker
                schema=2
                malformed
                locale=ja
                value=left=right
                """.trimIndent(),
            ),
        )
    }
}
