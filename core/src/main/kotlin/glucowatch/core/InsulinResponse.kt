package glucowatch.core

import kotlin.math.abs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Retrospective, conditional glucose-balance estimates. Never changes therapy settings.
 * Carb appearance: ICE * ICR / ISF (LoopDocs, Algorithm/Prediction).
 * The action kernel is our explicit triangular approximation: 10 min delay, 75 min peak,
 * 360 min end, using the rapid-acting adult time scale documented by LoopKit. It is not
 * a measurement of insulin absorption and is independent of the pump's SmartGuard AIT.
 */
object InsulinResponse {
    data class Estimate(val time: Long,val isf: Double?=null,val icr: Double?=null,val count: Int=1)
    data class Appearance(val time: Long,val mealTime: Long,val loggedG: Double,val apparentG: Double,
                          val effectiveU: Double,val glucoseChange: Double,val baselineIsf: Double,val baselineIcr: Double)
    data class Result(val estimates: List<Estimate>,val appearance: List<Appearance>,val excluded: Int) {
        fun exportRows()=estimates.map { e->ExportRow(e.time,"response_model",if(e.isf!=null)"isf_response_estimate"else"icr_response_estimate",
            value=e.isf?:e.icr,valueUnit=if(e.isf!=null)"mg/dL/U"else"g/U",detail="Conditional 4-hour glucose-balance estimate; not a prescribed setting.") }+
            appearance.map { a->ExportRow(a.time,"response_model","apparent_carb_absorption",value=a.apparentG,valueUnit="g",
                detail=buildJsonObject { put("meal_time_millis",a.mealTime);put("logged_carbs_g",a.loggedG);put("model_effective_insulin_u",a.effectiveU)
                    put("glucose_change_mgdl",a.glucoseChange);put("baseline_isf_mgdl_per_u",a.baselineIsf);put("baseline_icr_g_per_u",a.baselineIcr)
                    put("method","ICE * ICR / ISF; triangular 6h action model; neutral basal assumption; apparent glucose appearance, not measured absorption") }.toString()) }
    }
    fun effect(minutes: Double): Double {
        val t=minutes-10;val peak=65.0;val duration=350.0
        return when { t<=0->0.0;t>=duration->1.0;t<=peak->t*t/(peak*duration)
            else->1-(duration-t)*(duration-t)/((duration-peak)*duration) }
    }
    fun analyse(rows: List<ExportRow>, readings: List<GlucoseReading>): Result {
        val glucose=readings.distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
        if(glucose.isEmpty())return Result(emptyList(),emptyList(),0)
        val records=BolusLedger.records(rows)
        val pulses=mutableListOf<Pair<Long,Double>>()
        records.filter { it.insulinU!=null }.forEach { r->
            if(BolusLedger.extended(r)&&r.durationMin!=null&&r.durationMin>0) {
                val n=kotlin.math.ceil(r.durationMin/5).toInt().coerceIn(1,288)
                repeat(n) { i->pulses+=(r.timeMillis+((i+.5)*r.durationMin*60_000/n).toLong()) to (r.insulinU!!/n) }
            }else pulses+=r.timeMillis to r.insulinU!!
        }
        val meals=ReportMeal.read(rows)
        val estimates=mutableListOf<Estimate>();val appearances=mutableListOf<Appearance>();var excluded=0
        fun nearest(t: Long)=glucose.minByOrNull { abs(it.timeMillis-t) }?.takeIf { abs(it.timeMillis-t)<=450_000 }
        fun settings(t: Long)=rows.filter { it.timeMillis in (t-InsulinAnalytics.DAY)..t }.sortedBy { it.timeMillis }
        fun effective(a: Long,b: Long)=pulses.filter { it.first<b&&it.first>a-InsulinAnalytics.DAY }.sumOf { (t,u)->u*(effect((b-t)/60_000.0)-effect((a-t)/60_000.0)) }
        fun clear(a: Long,b: Long): Boolean {
            if(glucose.first().timeMillis>a-21_600_000||glucose.last().timeMillis<b)return false
            if(WorkoutAnnotations.disconnected(rows,a-21_600_000,b))return false
            if(rows.any { (it.kind=="workout_start"||it.kind==ExportRow.KIND_TEMP_BASAL)&&it.durationMin!=null&&
                it.timeMillis<b+7_200_000&&it.timeMillis+(it.durationMin!!*60_000).toLong()>a-21_600_000 })return false
            if(rows.any { it.kind==ExportRow.KIND_SUSPEND&&it.timeMillis in (a-21_600_000)..b })return false
            if(records.any { it.insulinU==null&&it.timeMillis in (a-21_600_000)..b })return false
            val samples=glucose.filter { it.timeMillis in a..b }
            return samples.size>=(b-a)/300_000*.8 && samples.zipWithNext().all { it.second.timeMillis-it.first.timeMillis<=900_000 }
        }
        meals.forEach { meal->
            val a=meal.timeMillis;val b=a+14_400_000
            val others=meals.any { it!=meal&&it.timeMillis in (a-10_800_000)..b }
            val s=settings(a);val isf=s.lastOrNull { it.pumpIsfMgdlPerU!=null }?.pumpIsfMgdlPerU
            val icr=s.lastOrNull { it.pumpIcrGPerU!=null }?.pumpIcrGPerU
            val before=nearest(a);val after=nearest(b)
            if(others||isf==null||icr==null||isf<=0||icr<=0||before==null||after==null||!clear(a,b)){excluded++;return@forEach}
            val used=effective(a,b);val denominator=used+(after.mgdl-before.mgdl)/isf
            val apparentRatio=meal.grams/denominator
            if(denominator>0&&apparentRatio in 1.0..200.0)estimates+=Estimate(a,icr=apparentRatio)
            for(m in 15..240 step 15) {
                val end=a+m*60_000L;val point=nearest(end)?:continue;val consumed=effective(a,end)
                val delta=(point.mgdl-before.mgdl).toDouble()
                appearances+=Appearance(end,a,meal.grams,icr*(delta/isf+consumed),consumed,delta,isf,icr)
            }
        }
        records.filter { it.insulinU!=null&&it.insulinU>0 }.forEach { dose->
            val a=dose.timeMillis;val b=a+14_400_000
            if(meals.any { it.timeMillis in (a-21_600_000)..b }||!clear(a,b))return@forEach
            val before=nearest(a)?:return@forEach;val after=nearest(b)?:return@forEach
            val used=effective(a,b);val ratio=(before.mgdl-after.mgdl)/used
            if(used>.25&&ratio in 5.0..400.0)estimates+=Estimate(a,isf=ratio)
        }
        return Result(estimates.sortedBy { it.time },appearances,excluded)
    }
}
