package io.github.hatake716.omochi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmochiSessionPolicyTest {
    @Test
    fun seccompLaunchThatNeverBecameReadyFallsBackOnce() {
        assertTrue(
            OmochiSessionPolicy.shouldFallBackFromSeccomp(
                launchUsedSeccomp = true,
                fallbackAllowed = true,
                reachedReady = false,
            )
        )
        // The compatibility relaunch itself must never fall back again.
        assertFalse(
            OmochiSessionPolicy.shouldFallBackFromSeccomp(
                launchUsedSeccomp = true,
                fallbackAllowed = false,
                reachedReady = false,
            )
        )
    }

    @Test
    fun otherFailuresStayFailed() {
        // ptrace-only launches have nothing to fall back to.
        assertFalse(
            OmochiSessionPolicy.shouldFallBackFromSeccomp(
                launchUsedSeccomp = false,
                fallbackAllowed = true,
                reachedReady = false,
            )
        )
        // A server that already served the workbench crashed for another reason.
        assertFalse(
            OmochiSessionPolicy.shouldFallBackFromSeccomp(
                launchUsedSeccomp = true,
                fallbackAllowed = true,
                reachedReady = true,
            )
        )
    }

    @Test
    fun seccompIsDisabledByPresenceOfTheVariable() {
        val env = arrayOf("HOME=/data", "PROOT_TMP_DIR=/tmp", "PATH=/system/bin")
        val disabled = OmochiSessionPolicy.withSeccompDisabled(env)
        assertArrayEquals(
            arrayOf("HOME=/data", "PROOT_TMP_DIR=/tmp", "PATH=/system/bin", "PROOT_NO_SECCOMP=1"),
            disabled,
        )
        // Idempotent, and never two entries.
        val again = OmochiSessionPolicy.withSeccompDisabled(
            arrayOf("PROOT_NO_SECCOMP=0", "HOME=/data")
        )
        assertEquals(1, again.count { it.startsWith("PROOT_NO_SECCOMP=") })
        assertArrayEquals(arrayOf("HOME=/data", "PROOT_NO_SECCOMP=1"), again)
    }
}
