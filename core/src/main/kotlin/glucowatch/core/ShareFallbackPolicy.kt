package glucowatch.core

/** When the watch should try a second Share request, independently of the old-reading alert. */
object ShareFallbackPolicy {
    const val AFTER_MS = 7 * 60_000L
    const val RETRY_INTERVAL_MS = 2 * 60_000L

    fun shouldAttempt(primarySucceeded: Boolean, newestMillis: Long?, now: Long, lastAttemptMillis: Long?): Boolean {
        if (primarySucceeded && newestMillis != null && now - newestMillis < AFTER_MS) return false
        if (lastAttemptMillis != null && now - lastAttemptMillis < RETRY_INTERVAL_MS) return false
        return true
    }
}
