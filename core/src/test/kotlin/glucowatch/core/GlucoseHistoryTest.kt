package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlucoseHistoryTest {
    private val now = 1_790_000_000_000L
    private fun reading(minutesAgo: Int, mgdl: Int = 100) = GlucoseReading(now - minutesAgo * 60_000L, mgdl, Trend.Flat)

    @Test fun `thirty minute change chooses thirty minutes instead of the first twenty five`() {
        val readings = listOf(reading(35, 90), reading(30, 100), reading(25, 110), reading(20), reading(15), reading(10), reading(5), reading(0, 130))
        val change = GlucoseHistory.change30Minutes(readings, now)!!
        assertEquals(30.0, change.mgdl)
        assertEquals(30 * 60_000L, change.elapsedMillis)
        assertFalse(change.hasGap)
    }

    @Test fun `missing baseline and stale current readings are not a thirty minute change`() {
        assertNull(GlucoseHistory.change30Minutes(listOf(reading(120), reading(20), reading(0)), now))
        assertNull(GlucoseHistory.change30Minutes(listOf(reading(45), reading(15)), now))
        assertNull(GlucoseHistory.change30Minutes(listOf(reading(0)), now))
        assertNull(GlucoseHistory.change30Minutes(emptyList(), now))
    }

    @Test fun `endpoint difference across missing samples remains available but is flagged`() {
        val change = GlucoseHistory.change30Minutes(listOf(reading(30, 150), reading(5, 110), reading(0, 100)), now)!!
        assertEquals(-50.0, change.mgdl)
        assertTrue(change.hasGap)
    }

    @Test fun `jittered sample time and flat glucose are supported`() {
        val readings = (6 downTo 0).map { reading(it * 5).copy(timeMillis = now - it * 5 * 60_000L - it * 10_000L) }
        val change = GlucoseHistory.change30Minutes(readings, now)!!
        assertEquals(0.0, change.mgdl)
        assertEquals(31 * 60_000L, change.elapsedMillis)
        assertFalse(change.hasGap)
    }

    @Test fun `gap starts at missed sample and ends when readings resume`() {
        val gap = GlucoseHistory.gaps(listOf(reading(30), reading(25), reading(10), reading(5), reading(0)), now).single()
        assertEquals(now - 20 * 60_000L, gap.startMillis)
        assertEquals(now - 10 * 60_000L, gap.endMillis)
    }

    @Test fun `ongoing loss is marked through now even with one old reading`() {
        assertEquals(listOf(GlucoseGap(now - 15 * 60_000L, now)), GlucoseHistory.gaps(listOf(reading(20)), now))
        assertTrue(GlucoseHistory.gaps(listOf(reading(5)), now).isEmpty())
        assertTrue(GlucoseHistory.gaps(emptyList(), now).isEmpty())
    }

    @Test fun `one missing five minute sample is visible but normal sampling jitter is not`() {
        assertEquals(1, GlucoseHistory.gaps(listOf(reading(10), reading(0)), now).size)
        assertTrue(GlucoseHistory.gaps(listOf(reading(7), reading(0)), now).isEmpty())
    }

    @Test fun `gaps respect historical window end and ignore future values and duplicate timestamps`() {
        val history = listOf(reading(0), reading(30), reading(30), reading(-20))
        assertEquals(listOf(GlucoseGap(now - 25 * 60_000L, now - 15 * 60_000L)),
            GlucoseHistory.gaps(history, now - 15 * 60_000L))
        assertEquals(100.0, GlucoseHistory.change30Minutes(listOf(reading(30, 100), reading(0, 200), reading(-20, 250)), now)?.mgdl)
    }
}
