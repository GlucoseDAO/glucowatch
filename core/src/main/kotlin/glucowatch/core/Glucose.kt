package glucowatch.core

import kotlin.math.roundToInt

/** One CGM reading as reported by Dexcom Share. */
data class GlucoseReading(
    val timeMillis: Long,
    val mgdl: Int,
    val trend: Trend,
)

/** Trend directions as returned by Dexcom Share, in the order of their legacy numeric codes. */
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
        fun parse(value: String): Trend =
            entries.firstOrNull { it.name == value }
                ?: value.toIntOrNull()?.let { entries.getOrNull(it) }
                ?: None

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

/** Change between the two most recent readings, if they are roughly 5 minutes apart. */
fun List<GlucoseReading>.lastDelta(): Double? {
    if (size < 2) return null
    val (prev, last) = takeLast(2)
    val minutes = (last.timeMillis - prev.timeMillis) / 60_000.0
    if (minutes <= 0 || minutes > 12) return null
    return (last.mgdl - prev.mgdl).toDouble()
}
