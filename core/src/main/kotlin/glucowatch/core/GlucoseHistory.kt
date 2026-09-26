package glucowatch.core

import kotlin.math.abs

data class GlucoseGap(val startMillis: Long, val endMillis: Long)
data class GlucoseChange(val mgdl: Double, val elapsedMillis: Long, val hasGap: Boolean)

/** Missing measurements are not evidence of a particular sensor or network failure. */
object GlucoseHistory {
    const val SAMPLE_MS = 5 * 60_000L
    // Allow notification/transport jitter, but show even one missing five-minute sample.
    const val GAP_MS = SAMPLE_MS + SAMPLE_MS / 2
    const val FRESH_MS = 10 * 60_000L

    /** Shade from the first expected missing sample. Do not call time before collection a dropout. */
    fun gaps(readings: List<GlucoseReading>, until: Long): List<GlucoseGap> {
        val times = readings.map { it.timeMillis }.filter { it <= until }.distinct().sorted()
        if (times.isEmpty()) return emptyList()
        return (times + until).zipWithNext().mapNotNull { (from, to) ->
            if (to - from > GAP_MS) GlucoseGap(from + SAMPLE_MS, to) else null
        }
    }

    /** Compare actual endpoints near thirty minutes apart, without interpolating across missing data. */
    fun change30Minutes(readings: List<GlucoseReading>, now: Long): GlucoseChange? {
        val samples = readings.filter { it.timeMillis <= now }.sortedBy { it.timeMillis }
        val last = samples.lastOrNull() ?: return null
        if (now - last.timeMillis > FRESH_MS) return null
        val target = last.timeMillis - 30 * 60_000L
        val before = samples.minByOrNull { abs(it.timeMillis - target) }
            ?.takeIf { abs(it.timeMillis - target) <= SAMPLE_MS } ?: return null
        val interval = samples.filter { it.timeMillis in before.timeMillis..last.timeMillis }
        return GlucoseChange(
            (last.mgdl - before.mgdl).toDouble(), last.timeMillis - before.timeMillis,
            interval.zipWithNext().any { (a, b) -> b.timeMillis - a.timeMillis > GAP_MS },
        )
    }
}
