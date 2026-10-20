package glucowatch.core

import kotlin.math.abs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Conditional observed-response points, recomputed as CGM arrives. These are not therapy
 * settings. The explicit 10/75/360-minute triangular action approximation and neutral-basal
 * assumption cannot identify physiological absorption or both ISF and ICR from a mixed meal.
 * See https://loopkit.github.io/loopdocs/operation/algorithm/prediction/ for the balance model.
 */
object InsulinResponse {
    const val HOUR = 3_600_000L
    data class Estimate(val time: Long, val isf: Double? = null, val icr: Double? = null, val count: Int = 1,
        val windowStart: Long = time, val effectiveU: Double = 0.0, val glucoseChange: Double = 0.0,
        val carbsG: Double? = null, val method: String = "response", val provisional: Boolean = true) {
        val minutes get() = (time - windowStart) / 60_000
    }
    data class Appearance(val time: Long, val mealTime: Long, val loggedG: Double, val apparentG: Double,
        val effectiveU: Double, val glucoseChange: Double, val baselineIsf: Double, val baselineIcr: Double)
    data class Result(val estimates: List<Estimate>, val appearance: List<Appearance>, val excluded: Int,
        val noPointsReason: String? = null) {
        fun exportRows() = estimates.map { e -> ExportRow(e.time, "response_model",
            if (e.isf != null) "isf_response_estimate" else "icr_response_estimate",
            value = e.isf ?: e.icr, valueUnit = if (e.isf != null) "mg/dL/U" else "g/U",
            detail = buildJsonObject {
                put("window_start_millis", e.windowStart); put("observed_at_millis", e.time)
                put("model_effective_insulin_u", e.effectiveU); put("glucose_change_mgdl", e.glucoseChange)
                e.carbsG?.let { put("logged_carbs_g", it) }; put("method", e.method); put("provisional", e.provisional)
                put("assumptions", "Triangular 6h insulin action; neutral basal; logged meals complete; not a dose recommendation")
            }.toString()) } + appearance.map { a -> ExportRow(a.time, "response_model", "apparent_carb_absorption",
            value = a.apparentG, valueUnit = "g", detail = buildJsonObject {
                put("meal_time_millis", a.mealTime); put("logged_carbs_g", a.loggedG); put("model_effective_insulin_u", a.effectiveU)
                put("glucose_change_mgdl", a.glucoseChange); put("baseline_isf_mgdl_per_u", a.baselineIsf); put("baseline_icr_g_per_u", a.baselineIcr)
                put("method", "ICE * ICR / ISF; triangular 6h action model; neutral basal; apparent appearance, not measured absorption")
            }.toString()) }
    }
    fun effect(minutes: Double): Double {
        val t = minutes - 10; val peak = 65.0; val duration = 350.0
        return when { t <= 0 -> 0.0; t >= duration -> 1.0; t <= peak -> t*t/(peak*duration)
            else -> 1 - (duration-t)*(duration-t)/((duration-peak)*duration) }
    }
    private fun <T> between(items: List<T>, from: Long, until: Long, time: (T)->Long): List<T> {
        fun bound(target: Long, inclusive: Boolean): Int {
            var lo=0; var hi=items.size
            while(lo<hi) { val mid=(lo+hi)/2; val t=time(items[mid]); if(t<target || inclusive&&t==target) lo=mid+1 else hi=mid }
            return lo
        }
        return items.subList(bound(from,false),bound(until,true))
    }
    fun analyse(rows: List<ExportRow>, readings: List<GlucoseReading>, nowMillis: Long = Long.MAX_VALUE): Result {
        val glucose = readings.filter { it.timeMillis <= nowMillis }.distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
        if (glucose.isEmpty()) return Result(emptyList(), emptyList(), 0, "Waiting for sensor readings.")
        val cutoff = minOf(nowMillis, glucose.last().timeMillis)
        val events = rows.filter { it.timeMillis <= cutoff }
        val records = BolusLedger.records(events)
        val nativeDeliveries = events.filter { it.kind == ExportRow.KIND_BOLUS && it.insulinU != null }
            .groupBy { it.bolusId to BolusLedger.extended(it) }
        fun confirmedAt(r: ExportRow): Long = if (r.bolusId == null) r.timeMillis else
            nativeDeliveries[r.bolusId to BolusLedger.extended(r)].orEmpty()
                .filter { it.timeMillis - r.timeMillis in 0..43_200_000L }.minOfOrNull { it.timeMillis } ?: r.timeMillis
        data class Pulse(val time: Long, val units: Double, val knownAt: Long)
        val pulses = records.filter { it.insulinU != null && it.insulinU > 0 }.flatMap { r ->
            if (BolusLedger.extended(r) && r.durationMin != null && r.durationMin > 0) {
                val n = kotlin.math.ceil(r.durationMin/5).toInt().coerceIn(1,288)
                (0 until n).map { i -> Pulse(r.timeMillis + ((i+.5)*r.durationMin*60_000/n).toLong(), r.insulinU!!/n, confirmedAt(r)) }
            } else listOf(Pulse(r.timeMillis, r.insulinU!!, confirmedAt(r)))
        }
        val meals = ReportMeal.read(events)
        val confounds = events.filter { it.kind in setOf("workout_start", ExportRow.KIND_TEMP_BASAL, ExportRow.KIND_SUSPEND, "pump_disconnected") }
        val settings = events.filter { (it.pumpIsfMgdlPerU ?: 0.0)>0 || (it.pumpIcrGPerU ?: 0.0)>0 }.sortedBy { it.timeMillis }
        val basalRates = events.filter { it.kind==ExportRow.KIND_REPORTED_BASAL && it.basalRateUPerH!=null }.sortedBy { it.timeMillis }
        val estimates = mutableListOf<Estimate>(); val appearances = mutableListOf<Appearance>(); var excluded = 0
        fun nearest(t: Long) = between(glucose,t-450_000,t+450_000) { it.timeMillis }.minByOrNull { abs(it.timeMillis-t) }
        fun effective(a: Long, b: Long) = pulses.filter { it.knownAt <= b && it.time < b && it.time > a-6*HOUR }
            .sumOf { it.units*(effect((b-it.time)/60_000.0)-effect((a-it.time)/60_000.0)) }
        fun clear(a: Long, b: Long): Boolean {
            if (glucose.first().timeMillis > a-6*HOUR+450_000) return false
            val known = confounds.filter { it.timeMillis <= b }
            if (between(basalRates,a-6*HOUR,b) {it.timeMillis}.map {it.basalRateUPerH}.distinct().size>1) return false
            if (WorkoutAnnotations.disconnected(known, a-6*HOUR, b)) return false
            if (known.any { (it.kind == "workout_start" || it.kind == ExportRow.KIND_TEMP_BASAL) &&
                it.timeMillis <= b && (it.durationMin?.let { d -> it.timeMillis+(d*60_000).toLong() } ?: b) >= a-6*HOUR }) return false
            if (known.any { it.kind == ExportRow.KIND_SUSPEND && it.timeMillis in (a-6*HOUR)..b }) return false
            if (between(records,a-6*HOUR,b) { it.timeMillis }.any { it.insulinU == null || confirmedAt(it) > b }) return false
            val samples = between(glucose,a,b) { it.timeMillis }
            return samples.size >= ((b-a)/300_000.0+1)*.8 && samples.zipWithNext().all { it.second.timeMillis-it.first.timeMillis <= 900_000 }
        }
        // Each point ends at a real CGM timestamp; no future CGM, delivery or setting is used.
        records.filter { it.insulinU != null && it.insulinU > 0 }.forEach { dose ->
            val a = dose.timeMillis
            val before = nearest(a) ?: return@forEach
            between(glucose,a+90*60_000,a+4*HOUR) { it.timeMillis }.forEach observation@ { after ->
                val b = after.timeMillis
                if (meals.any { it.timeMillis in (a-6*HOUR)..b } || !clear(a,b)) return@observation
                val used = effective(a,b); val delta = (after.mgdl-before.mgdl).toDouble(); val ratio = -delta/used
                if (used > .25 && ratio in 5.0..400.0) estimates += Estimate(b, isf=ratio, windowStart=a,
                    effectiveU=used, glucoseChange=delta, method="isolated_correction_response", provisional=b-a<4*HOUR)
            }
        }
        meals.forEach { meal ->
            val a = meal.timeMillis; val before = nearest(a) ?: return@forEach
            val nativeIsf = between(settings,a-InsulinAnalytics.DAY,a) { it.timeMillis }.lastOrNull { (it.pumpIsfMgdlPerU ?: 0.0)>0 }?.pumpIsfMgdlPerU
            val observedIsf = estimates.filter { it.isf != null && it.time <= a && it.time >= a-14*InsulinAnalytics.DAY }
                .maxByOrNull { it.time }?.isf
            val isf = observedIsf ?: nativeIsf
            val priorCount = estimates.size
            between(glucose,a+3*HOUR,a+6*HOUR) { it.timeMillis }.forEach observation@ { after ->
                val b = after.timeMillis
                if (meals.any { it != meal && it.timeMillis in (a-6*HOUR)..b } || !clear(a,b)) return@observation
                val used = effective(a,b); val delta = (after.mgdl-before.mgdl).toDouble()
                val denominator = if (isf != null && isf > 0) used+delta/isf else if (abs(delta)<=15) used else return@observation
                val ratio = meal.grams/denominator
                if (used > .25 && denominator > 0 && ratio in 1.0..200.0) estimates += Estimate(b, icr=ratio, windowStart=a,
                    effectiveU=used, glucoseChange=delta, carbsG=meal.grams,
                    method=when { observedIsf!=null -> "meal_balance_observed_isf"; nativeIsf!=null -> "meal_balance_recorded_isf"; else -> "meal_return_to_baseline" },
                    provisional=b-a<6*HOUR)
            }
            if (estimates.size == priorCount) excluded++
            // Appearance is separate: it needs independently recorded baseline settings.
            val nativeIcr = between(settings,a-InsulinAnalytics.DAY,a) { it.timeMillis }.lastOrNull { (it.pumpIcrGPerU ?: 0.0)>0 }?.pumpIcrGPerU
            if (nativeIsf != null && nativeIsf > 0 && nativeIcr != null && nativeIcr > 0) for (m in 15..240 step 15) {
                val end = a+m*60_000L
                val point = nearest(end)?.takeIf { it.timeMillis <= cutoff && it.timeMillis >= end-450_000 } ?: continue
                if (end > cutoff || !clear(a,point.timeMillis) || meals.any { it!=meal && it.timeMillis in (a-6*HOUR)..point.timeMillis }) continue
                val consumed = effective(a,point.timeMillis); val delta = (point.mgdl-before.mgdl).toDouble()
                appearances += Appearance(point.timeMillis,a,meal.grams,nativeIcr*(delta/nativeIsf+consumed),consumed,delta,nativeIsf,nativeIcr)
            }
        }
        val points = estimates.distinctBy { Triple(it.time,it.isf!=null,it.windowStart) }.sortedBy { it.time }
        val reason = if (points.isNotEmpty()) null else if (records.none { (it.insulinU ?: 0.0)>0 }) "Waiting for confirmed pump boluses."
            else "Waiting for an eligible response window: correction 90 min, meal 3 h, with CGM coverage and no overlapping meals, exercise or basal changes."
        return Result(points, appearances, excluded, reason)
    }
}
