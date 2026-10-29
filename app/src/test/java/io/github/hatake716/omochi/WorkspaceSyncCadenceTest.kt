package io.github.hatake716.omochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSyncCadenceTest {
    @Test
    fun idleScansStayFastAfterActivityThenStretchToThirtySeconds() {
        assertEquals(
            listOf(3_000L, 3_000L, 3_000L, 3_000L, 3_000L, 6_000L, 12_000L, 24_000L, 30_000L, 30_000L),
            (0 until 10).map(WorkspaceSyncPolicy::idleScanDelayMs),
        )
        assertEquals(30_000L, WorkspaceSyncPolicy.idleScanDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun failuresBackOffExponentiallyUpToOneMinute() {
        assertEquals(
            listOf(0L, 3_000L, 6_000L, 12_000L, 24_000L, 48_000L, 60_000L, 60_000L),
            (0 until 8).map(WorkspaceSyncPolicy::retryDelayMs),
        )
        assertEquals(60_000L, WorkspaceSyncPolicy.retryDelayMs(Int.MAX_VALUE))
        assertEquals(0L, WorkspaceSyncPolicy.retryDelayMs(-1))
    }

    @Test
    fun changesRetryWithinTheActiveIntervalEvenWhenBackedOff() {
        assertEquals(0L, WorkspaceSyncPolicy.changeRetryDelayMs(0))
        assertEquals(3_000L, WorkspaceSyncPolicy.changeRetryDelayMs(1))
        assertEquals(3_000L, WorkspaceSyncPolicy.changeRetryDelayMs(10))
    }

    @Test
    fun failureBackoffOverridesTheIdleCadenceAndSuccessResetsIt() {
        // Many quiet scans would mean 30 s, but a failure restarts the backoff at 3 s.
        assertEquals(3_000L, WorkspaceSyncPolicy.nextScanDelayMs(quietScans = 50, consecutiveFailures = 1))
        assertEquals(48_000L, WorkspaceSyncPolicy.nextScanDelayMs(quietScans = 0, consecutiveFailures = 5))
        // After a success the failure count is 0 and the idle cadence applies again.
        assertEquals(3_000L, WorkspaceSyncPolicy.nextScanDelayMs(quietScans = 0, consecutiveFailures = 0))
        assertEquals(30_000L, WorkspaceSyncPolicy.nextScanDelayMs(quietScans = 50, consecutiveFailures = 0))
    }

    @Test
    fun fullVerificationRunsEveryFiveMinutes() {
        val start = 1_000_000L

        assertFalse(WorkspaceSyncPolicy.fullVerificationDue(start + 299_999L, start))
        assertTrue(WorkspaceSyncPolicy.fullVerificationDue(start + 300_000L, start))
        assertTrue(WorkspaceSyncPolicy.fullVerificationDue(start, lastVerifiedAtMs = 0L))
        // A wall clock moved backwards must not postpone verification indefinitely.
        assertTrue(WorkspaceSyncPolicy.fullVerificationDue(start - 1L, start))
    }
}
