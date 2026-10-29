package app.syntheticresin.audiocast

import org.junit.Assert.*
import org.junit.Test

class CastRecoveryTest {
    @Test fun repeatedBufferingRetriesQuicklyWithoutMinuteBackoff() {
        val recovery = CastRecovery()
        for (second in 0..11) assertFalse(recovery.shouldRetry(second * 1000L, second == 8, second != 8))
        assertTrue(recovery.shouldRetry(12000, false, true))
        assertFalse(recovery.shouldRetry(31000, false, true))
        assertTrue(recovery.shouldRetry(32000, false, true))
        assertTrue(recovery.shouldRetry(52000, false, true))
        assertTrue(recovery.shouldRetry(72000, false, true))
    }
    @Test fun stablePlaybackResetsAndPauseDoesNotRetry() {
        val recovery = CastRecovery()
        recovery.shouldRetry(0, false, true)
        assertFalse(recovery.shouldRetry(10000, true, false))
        assertFalse(recovery.shouldRetry(30000, true, false))
        assertFalse(recovery.shouldRetry(31000, false, true))
        assertFalse(recovery.shouldRetry(32000, false, false))
        assertFalse(recovery.shouldRetry(90000, false, false))
        assertFalse(recovery.shouldRetry(91000, false, true))
        assertTrue(recovery.shouldRetry(111000, false, true))
    }
}
