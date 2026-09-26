package glucowatch.core

import kotlin.math.roundToInt

/** One CGM reading as reported by Dexcom Share or Nightscout. */
data class GlucoseReading(
    val timeMillis: Long,
    val mgdl: Int,
    val trend: Trend,
)

/**
 * Trend directions as returned by Dexcom Share, in the order of their legacy numeric codes.
 * Nightscout uses the same names and codes, spelled `NOT COMPUTABLE`, `RATE OUT OF RANGE`, `NONE`.
 */
enum class Trend(val arrow: String, val description: String) {
    None("", ""),
    DoubleUp("⇈", "rising quickly"),
    SingleUp("↑", "rising"),
    FortyFiveUp("↗", "rising slightly"),
    Flat("→", "steady"),
    FortyFiveDown("↘", "falling slightly"),
    SingleDown("↓", "falling"),
    DoubleDown("⇊", "falling quickly"),
    NotComputable("?", "unable to determine trend"),
    RateOutOfRange("-", "trend unavailable");

    companion object {
        fun parse(value: String): Trend {
            val key = value.filter { it.isLetterOrDigit() }.lowercase()
            return entries.firstOrNull { it.name.lowercase() == key }
                ?: value.trim().toIntOrNull()?.let { entries.getOrNull(it) }
                ?: None
        }

        /** Dexcom-style trend from a rate of change in mg/dL per minute. */
        fun fromRate(mgdlPerMinute: Double): Trend = when {
            mgdlPerMinute > 3 -> DoubleUp
            mgdlPerMinute > 2 -> SingleUp
            mgdlPerMinute > 1 -> FortyFiveUp
            mgdlPerMinute >= -1 -> Flat
            mgdlPerMinute >= -2 -> FortyFiveDown
            mgdlPerMinute >= -3 -> SingleDown
            else -> DoubleDown
        }
    }
}

enum class GlucoseUnit(val label: String) {
    MMOL("mmol/L"),
    MGDL("mg/dL");

    fun convert(mgdl: Double): Double = if (this == MMOL) mgdl / MGDL_PER_MMOL else mgdl

    fun format(mgdl: Double): String = when (this) {
        MMOL -> "%.1f".format(java.util.Locale.ROOT, mgdl / MGDL_PER_MMOL)
        MGDL -> mgdl.roundToInt().toString()
    }

    fun formatDelta(deltaMgdl: Double): String {
        val sign = if (deltaMgdl >= 0) "+" else "-"
        return sign + format(kotlin.math.abs(deltaMgdl))
    }

    companion object {
        const val MGDL_PER_MMOL = 18.016

        /** Accepts `mmol`, `mmol/l`, `mgdl`, `mg/dl` in any case. */
        fun parse(value: String): GlucoseUnit = when (value.trim().lowercase()) {
            "mmol", "mmol/l" -> MMOL
            "mgdl", "mg/dl" -> MGDL
            else -> throw IllegalArgumentException("Unknown glucose unit '$value': use mmol or mgdl")
        }
    }
}

/**
 * Change since the reading closest to 5 minutes before the latest one, looking back at most 12 minutes.
 * With 5-minute sensors that is the previous reading; with 1-minute uploads it is the one 5 readings back.
 */
fun List<GlucoseReading>.lastDelta(): Double? {
    val last = lastOrNull() ?: return null
    val prev = asReversed().asSequence().drop(1)
        .takeWhile { last.timeMillis - it.timeMillis <= 12 * 60_000L }
        .filter { it.timeMillis < last.timeMillis }
        .minByOrNull { kotlin.math.abs(last.timeMillis - it.timeMillis - 5 * 60_000L) }
        ?: return null
    return (last.mgdl - prev.mgdl).toDouble()
}

/** One heart-rate sample, from the phone's Health Connect or from [DemoData]. */
data class HeartSample(val timeMillis: Long, val bpm: Int)
