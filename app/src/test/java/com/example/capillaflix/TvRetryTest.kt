package com.example.capillaflix

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRetryTest {
    @Test fun fastHttpFailuresKeepRetryingBeyondTheDefaultAttemptCount() {
        assertEquals(1_000L, tvRetryDelayMs(192, 1))
        assertEquals(3_000L, tvRetryDelayMs(12_000, 6))
        assertTrue(tvRetryDelayMs(29_000, 12) > 0)
    }

    @Test fun delaysRespectRemainingRecoveryTime() {
        assertEquals(500L, tvRetryDelayMs(29_500, 12))
        assertEquals(C.TIME_UNSET, tvRetryDelayMs(30_000, 13))
        assertEquals(C.TIME_UNSET, tvRetryDelayMs(45_000, 2))
    }

    @Test fun aNewLoadGetsAFreshWindowAfterPreviousFailure() {
        assertEquals(C.TIME_UNSET, tvRetryDelayMs(30_001, 13))
        assertEquals(1_000L, tvRetryDelayMs(100, 1))
    }
}
