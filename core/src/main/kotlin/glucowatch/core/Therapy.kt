package glucowatch.core

import kotlin.math.abs

/**
 * Insulin or carbs logged in Nightscout (Careportal, AAPS, Trio, iAPS, Loop, xDrip+).
 * Basal delivery is distinct from boluses, even when a pump calls a basal pulse a bolus.
 * [insulin] is a delivered/logged dose in U, never a rate. Temp basal settings retain their
 * rate and duration; they are not converted into an assumed delivered dose.
 */
data class Treatment(
    val timeMillis: Long,
    val insulin: Double = 0.0,
    val carbs: Double = 0.0,
    val automatic: Boolean = false,
    val insulinKind: InsulinKind = InsulinKind.BOLUS,
    val basalRate: Double? = null,
    /** Nightscout percentage adjustment: 0 means unchanged, -100 means suspended. */
    val basalPercent: Double? = null,
    val durationMinutes: Double? = null,
) {
    val isBasal get() = insulinKind == InsulinKind.BASAL
    val isBolus get() = !isBasal && insulin > 0

    fun basalValue(): String = when {
        durationMinutes == 0.0 -> "ended"
        basalRate != null -> "${formatAmount(basalRate)} U/h"
        basalPercent != null -> "${if (basalPercent > 0) "+" else ""}${formatAmount(basalPercent)}%"
        else -> "${formatAmount(insulin)} U"
    }

    /** Explicit units prevent a pump rate being read as a dose. A zero duration ends a temp. */
    fun basalDescription(): String = (when {
        durationMinutes != null -> "Temp basal "
        basalRate != null -> "Reported basal "
        else -> "Basal "
    }) + basalValue() +
        (durationMinutes?.takeIf { it > 0 }?.let { " for ${formatAmount(it)} min" } ?: "")
}

enum class InsulinKind { BOLUS, BASAL }

/** Insulin units or carb grams for display: 3.6, 12, 0.25 → "3.6", "12", "0.25". */
fun formatAmount(value: Double, decimals: Int = 2): String =
    java.math.BigDecimal(value).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** 35 min → "35m", 206 min → "3h 26m", like the watch face's own counters, for labels rebuilt on every refresh. */
fun formatAge(minutes: Long): String {
    val m = minutes.coerceAtLeast(0)
    return when {
        m < 60 -> "${m}m"
        m < 24 * 60 -> "${m / 60}h" + if (m % 60 > 0) " ${m % 60}m" else ""
        else -> "${m / (24 * 60)}d" + if (m / 60 % 24 > 0) " ${m / 60 % 24}h" else ""
    }
}

/**
 * Treatments of several sources in one list, oldest first. Matching boluses/carbs within two
 * minutes are kept once. Basal events require an exact timestamp so nearby delivered pulses
 * are never collapsed. Kinds, automation, rates and durations must agree. The first list wins.
 */
fun mergeTreatments(sources: List<List<Treatment>>): List<Treatment> {
    val kept = mutableListOf<Treatment>()
    for (t in sources.flatten()) {
        val twin = kept.any {
            it.insulinKind == t.insulinKind && it.automatic == t.automatic &&
                it.basalRate == t.basalRate && it.basalPercent == t.basalPercent && it.durationMinutes == t.durationMinutes &&
                abs(it.timeMillis - t.timeMillis) <= (if (t.isBasal) 0L else 2 * 60_000L) &&
                abs(it.insulin - t.insulin) < 0.005 && abs(it.carbs - t.carbs) < 0.5
        }
        if (!twin) kept += t
    }
    return kept.sortedBy { it.timeMillis }
}

fun List<Treatment>.lastManualBolus(now: Long = System.currentTimeMillis()): Treatment? =
    lastOrNull { it.isBolus && !it.automatic && it.timeMillis <= now }

fun List<Treatment>.lastBasal(now: Long = System.currentTimeMillis()): Treatment? =
    lastOrNull { it.isBasal && it.timeMillis <= now }

fun List<Treatment>.lastCarbs(now: Long = System.currentTimeMillis()): Treatment? =
    lastOrNull { it.carbs > 0 && it.timeMillis <= now }

/**
 * What a closed loop (AAPS, Trio, iAPS, OpenAPS, Loop) last reported to Nightscout.
 * [timeMillis] is when the loop computed [iob] and [cob]. [forecast] is the loop's own
 * prediction in mg/dL, one point every 5 minutes, and [forecastName] says which curve it is
 * (`COB`, `UAM`, `IOB`, `ZT` from oref, `Loop` from Loop).
 */
data class LoopStatus(
    val timeMillis: Long,
    val iob: Double? = null,
    val cob: Double? = null,
    val eventualMgdl: Double? = null,
    val forecast: List<PredictedPoint> = emptyList(),
    val forecastName: String? = null,
) {
    fun ageMinutes(now: Long = System.currentTimeMillis()) = (now - timeMillis) / 60_000

    /** Nightscout stops showing a loop's IOB and COB after 30 minutes; so does the watch. */
    fun isStale(now: Long = System.currentTimeMillis()) = now - timeMillis > STALE_MINUTES * 60_000L

    /**
     * The forecast as a [Prediction] from the latest reading up to [horizonMinutes], or null if it is too old.
     * Loops start their curve at the current glucose, so points within 2.5 minutes of the reading are dropped.
     */
    fun prediction(lastReadingMillis: Long, horizonMinutes: Int, now: Long = System.currentTimeMillis()): Prediction? {
        val start = forecast.firstOrNull()?.timeMillis ?: return null
        if (now - start > FORECAST_STALE_MINUTES * 60_000L) return null
        val end = lastReadingMillis + horizonMinutes * 60_000L + 150_000L
        val points = forecast.filter { it.timeMillis in (lastReadingMillis + 150_000L)..end }
        return if (points.isEmpty()) null else Prediction(MODEL_ID, points)
    }

    /** Newer values win; a report that lacks a value (iAPS splits each cycle in two) keeps the older one. */
    fun mergedOnto(older: LoopStatus?): LoopStatus {
        if (older == null || older.timeMillis > timeMillis) return older?.mergedOnto(this) ?: this
        val keepOld = timeMillis - older.timeMillis <= STALE_MINUTES * 60_000L
        return copy(
            iob = iob ?: older.iob.takeIf { keepOld },
            cob = cob ?: older.cob.takeIf { keepOld },
            eventualMgdl = eventualMgdl ?: older.eventualMgdl.takeIf { keepOld },
            forecast = forecast.ifEmpty { if (keepOld) older.forecast else emptyList() },
            forecastName = if (forecast.isNotEmpty()) forecastName else older.forecastName.takeIf { keepOld },
        )
    }

    companion object {
        const val STALE_MINUTES = 30
        const val FORECAST_STALE_MINUTES = 15

        /** [Prediction.modelId] of a forecast taken from the loop; also the setting that selects it. */
        const val MODEL_ID = "loop"
    }
}
