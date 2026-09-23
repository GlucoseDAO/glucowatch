package glucowatch.core

import kotlin.math.PI
import kotlin.math.sin

/**
 * Deterministic synthetic CGM trace so the emulator shows a realistic chart without an account.
 * The same wall-clock time always yields the same value, so refreshes stay consistent.
 */
object DemoData {
    private const val STEP_MS = 5 * 60_000L

    fun valueAt(timeMillis: Long): Double {
        val minutes = timeMillis / 60_000.0
        return 125 +
            55 * sin(2 * PI * minutes / 210) +
            18 * sin(2 * PI * minutes / 47 + 1.3) +
            6 * sin(2 * PI * minutes / 13 + 0.4)
    }

    fun readings(nowMillis: Long, hours: Int = 24): List<GlucoseReading> {
        val last = nowMillis - nowMillis % STEP_MS
        val count = hours * 12
        return (count - 1 downTo 0).map { i ->
            val t = last - i * STEP_MS
            val v = valueAt(t)
            val rate = (v - valueAt(t - STEP_MS)) / 5.0
            GlucoseReading(t, v.toInt().coerceIn(40, 400), Trend.fromRate(rate))
        }
    }
}
