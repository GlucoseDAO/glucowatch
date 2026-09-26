package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareFallbackPolicyTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `fallback starts at seven minutes while old-reading alert remains separate`() {
        assertFalse(ShareFallbackPolicy.shouldAttempt(true, now - ShareFallbackPolicy.AFTER_MS + 1, now, null))
        assertTrue(ShareFallbackPolicy.shouldAttempt(true, now - ShareFallbackPolicy.AFTER_MS, now, null))
        assertTrue(ShareFallbackPolicy.shouldAttempt(false, now - 60_000L, now, null))
        assertFalse(ShareFallbackPolicy.shouldAttempt(false, null, now, now - 60_000L))
        assertTrue(ShareFallbackPolicy.shouldAttempt(false, null, now, now - ShareFallbackPolicy.RETRY_INTERVAL_MS))
    }
}
