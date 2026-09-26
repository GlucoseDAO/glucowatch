package glucowatch.core

import kotlin.math.PI
import kotlin.math.sin

/**
 * Deterministic synthetic CGM trace, meals and loop status so the emulator shows a realistic
 * chart without an account. The same wall-clock time always yields the same value, so refreshes
 * stay consistent.
 */
object DemoData {
    private const val STEP_MS = 5 * 60_000L
    private const val PERIOD_MS = 210 * 60_000L

    /** Meals sit at the low point of the main wave, so glucose rises after each one. */
    private const val MEAL_OFFSET_MS = 157 * 60_000L

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

    /** A 45 g meal with a 4.5 U bolus every 3.5 hours, followed by two small automatic boluses. */
    fun treatments(nowMillis: Long, hours: Int = 24): List<Treatment> {
        val start = nowMillis - hours * 3_600_000L
        var meal = start - Math.floorMod(start - MEAL_OFFSET_MS, PERIOD_MS)
        val result = mutableListOf<Treatment>()
        while (meal <= nowMillis) {
            result += Treatment(meal, insulin = 4.5, carbs = 45.0)
            result += Treatment(meal + 25 * 60_000L, insulin = 0.4, automatic = true)
            result += Treatment(meal + 50 * 60_000L, insulin = 0.3, automatic = true)
            meal += PERIOD_MS
        }
        return result.filter { it.timeMillis in start..nowMillis }
    }

    /**
     * Synthetic heart rate, one sample a minute: a resting rate that drifts slowly, rises for a while
     * after each demo meal, and never leaves 48–150 bpm. Deterministic, like [valueAt].
     */
    fun heartRate(nowMillis: Long, hours: Int = 24): List<HeartSample> {
        val last = nowMillis - nowMillis % 60_000L
        return (hours * 60 - 1 downTo 0).map { i ->
            val t = last - i * 60_000L
            val minutes = t / 60_000.0
            val afterMeal = Math.floorMod(t - MEAL_OFFSET_MS, PERIOD_MS) / 60_000.0
            val digestion = if (afterMeal < 60) 9 * sin(PI * afterMeal / 60) else 0.0
            val bpm = 64 + 6 * sin(2 * PI * minutes / 190 + 0.7) + 3 * sin(2 * PI * minutes / 23) + digestion
            HeartSample(t, bpm.toInt().coerceIn(48, 150))
        }
    }

    /** IOB and COB from [treatments], decaying linearly over 4 h and 3 h. No loop forecast. */
    fun loopStatus(nowMillis: Long): LoopStatus {
        val t = nowMillis - nowMillis % STEP_MS
        val recent = treatments(t, hours = 5)
        fun left(since: Long, minutes: Int) = (1 - (t - since) / (minutes * 60_000.0)).coerceAtLeast(0.0)
        val iob = recent.sumOf { it.insulin * left(it.timeMillis, 240) }
        val cob = recent.sumOf { it.carbs * left(it.timeMillis, 180) }
        return LoopStatus(t, iob = Math.round(iob * 100) / 100.0, cob = Math.round(cob).toDouble())
    }
}
