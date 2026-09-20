package app.fastdrive.android.upload

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Ports `fastdrive-app/lib/upload-rules.ts`'s `retryDelayMs()`/`retryable()` to Kotlin so
 * `UploadWorker`'s part/single-file retry loop follows the exact same backoff and
 * retryable-status rules as the web/desktop clients, instead of a bare `repeat(MAX_TRIES)` with no
 * delay and no check of whether the failure was even worth retrying.
 */

/**
 * A pause before the [attempt]-th retry (1-based): 1s, 2s, 4s... capped at 8s, with jitter so many
 * concurrent parts don't all retry in lockstep. Mirrors `retryDelayMs()` in upload-rules.ts exactly:
 * `base = min(8000, 1000 * 2^(attempt-1))`, scaled by a random factor in `[0.75, 1.25)`.
 */
internal fun retryDelayMs(attempt: Int, random: () -> Double = { Random.nextDouble() }): Long {
    val base = min(8000.0, 1000.0 * 2.0.pow(attempt - 1))
    return (base * (0.75 + random() * 0.5)).roundToLong()
}

/**
 * Whether an HTTP status (or `0` for a network-level failure with no response) is worth retrying.
 * Mirrors `retryable()` in upload-rules.ts exactly: only network errors and 408/429/5xx are
 * transient — everything else (400/401/403/404/413/...) is a permanent rejection that retrying
 * won't fix, so it should fail fast instead of burning through `MAX_TRIES` pointlessly.
 */
internal fun retryable(status: Int): Boolean =
    status == 0 || status == 408 || status == 429 || status >= 500
