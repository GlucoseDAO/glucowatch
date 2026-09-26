package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HistoryRetentionTest {
    private val now = 1_790_000_000_000L
    private fun reading(hoursAgo: Double) = GlucoseReading(now - (hoursAgo * 3_600_000L).toLong(), 120, Trend.Flat)

    @Test fun `the watch default still keeps one day`() {
        val kept = SourceSync.merge(listOf(reading(30.0), reading(20.0)), listOf(reading(1.0)), now)
        assertEquals(2, kept.size)
    }

    @Test fun `a longer retention keeps what the phone has built up`() {
        val twoWeeks = 14 * SourceSync.DAY_MS
        val old = listOf(reading(13 * 24.0), reading(3 * 24.0), reading(30.0))
        val kept = SourceSync.merge(old, listOf(reading(0.5)), now, twoWeeks)
        assertEquals(4, kept.size)
        assertTrue(kept.zipWithNext().all { (a, b) -> a.timeMillis < b.timeMillis })
    }

    @Test fun `nothing older than the retention survives`() {
        val kept = SourceSync.merge(listOf(reading(15 * 24.0)), emptyList(), now, 14 * SourceSync.DAY_MS)
        assertEquals(0, kept.size)
    }

    @Test fun `a retention shorter than Dexcom's day is refused`() {
        val cache = object : SyncCache {
            override fun get(key: String): String? = null
            override fun put(values: Map<String, String?>) = Unit
        }
        assertFailsWith<IllegalArgumentException> { SourceSync(cache, retainMs = 3_600_000L) }
    }

    @Test fun `demo heart rate is one sample a minute and plausible`() {
        val samples = DemoData.heartRate(now, hours = 6)
        assertEquals(360, samples.size)
        assertTrue(samples.all { it.bpm in 48..150 })
        assertTrue(samples.zipWithNext().all { (a, b) -> b.timeMillis - a.timeMillis == 60_000L })
        assertEquals(samples, DemoData.heartRate(now, hours = 6), "deterministic")
    }
}
