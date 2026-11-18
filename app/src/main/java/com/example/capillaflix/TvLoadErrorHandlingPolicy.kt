package com.example.capillaflix

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Give live TV a recovery window, including when the server returns HTTP errors immediately. */
@UnstableApi
internal class TvLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(Int.MAX_VALUE) {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        // Preserve Media3's fatal errors (invalid media, unsupported cleartext, etc.).
        if (super.getRetryDelayMsFor(info) == C.TIME_UNSET) return C.TIME_UNSET
        // Loader measures from the original load start, including attempts and retry delays.
        // Each successful load or manual retry starts a fresh task and recovery window.
        return tvRetryDelayMs(info.loadEventInfo.loadDurationMs, info.errorCount)
    }
}

internal fun tvRetryDelayMs(loadDurationMs: Long, errorCount: Int): Long {
    val remainingMs = 30_000L - loadDurationMs
    if (remainingMs <= 0) return C.TIME_UNSET
    return minOf(errorCount.coerceIn(1, 3) * 1_000L, remainingMs)
}
