package glucowatch.core

import java.time.ZoneId

/** Recorded Bolus Wizard settings take precedence over retrospective TDD reference rules.
 * Medtronic 780G Therapy Management Protocol: ISF=1800/TDD, ICR=450/TDD (starting rules).
 * https://www.medtronic.com/content/dam/medtronic-wide/public/united-states/products/diabetes/mm780g-therapy-management-protocol.pdf
 */
object InsulinAnalytics {
    const val DAY = 86_400_000L
    data class Point(val time: Long, val total24h: Double?, val estimatedBasal: Boolean,
                     val carbs24h: Double, val bolus24h: Double, val iob: Double?, val unavailableReason: String? = null,
                     val recordedIsf: Double? = null, val recordedIcr: Double? = null) {
        val referenceIsf get() = total24h?.let { 1800.0 / it }
        val referenceIcr get() = total24h?.let { 450.0 / it }
        val isf get() = recordedIsf ?: referenceIsf
        val icr get() = recordedIcr ?: referenceIcr
    }

    fun at(rows: List<ExportRow>, zone: ZoneId, time: Long): Point = series(rows,zone,listOf(time)).single()

    fun series(rows: List<ExportRow>, zone: ZoneId, times: List<Long>): List<Point> {
        if(times.isEmpty())return emptyList()
        val first=times.min()-DAY;val last=times.max()
        val pump = rows.filter { it.source == ExportRow.SOURCE_CARELINK && it.timeMillis in (first-DAY)..(last+DAY) &&
            it.kind in setOf(ExportRow.KIND_REPORTED_BASAL,ExportRow.KIND_TEMP_BASAL,ExportRow.KIND_SUSPEND,
                ExportRow.KIND_BASAL_DELIVERY,ExportRow.KIND_BOLUS,ExportRow.KIND_IOB,"pump_therapy_settings") }.distinctBy { it.identity() }
        val bolusRecords=BolusLedger.records(pump)
        val estimate=ReportBasalEstimate.build(pump+rows.filter { it.kind=="pump_disconnected" },zone,first,last)
        val meals=ReportMeal.read(rows)
        val earliest=pump.minOfOrNull { it.timeMillis };val latest=pump.maxOfOrNull { it.timeMillis }
        return times.map { time->
        val from = time-DAY
        val selected = pump.filter { it.timeMillis in from until time }
        val selectedBoluses=bolusRecords.filter { it.timeMillis in from until time }
        val bolus = BolusLedger.unitsBetween(bolusRecords,from,time)
        val intervals=estimate.intervals.filter { it.start<time&&it.end>from }
        val complete=intervals.sumOf { minOf(time,it.end)-maxOf(from,it.start) }==DAY
        val doses = selected.filter { it.kind == ExportRow.KIND_BASAL_DELIVERY && it.insulinU != null }
        // Event bounds are only a coverage proxy; settings-based basal is explicitly identified.
        // Never extrapolate a partial day into a 24-hour total or count programmed-only boluses.
        val bounded = earliest?.let { it <= from + 300_000 } == true && latest?.let { it >= time - 300_000 } == true
        val basal = when {
            doses.isNotEmpty() && bounded -> doses.sumOf { it.insulinU!! }
            doses.isEmpty() && complete && bounded -> intervals.sumOf { it.programmedRate*(minOf(time,it.end)-maxOf(from,it.start))/3_600_000.0 }
            else -> null
        }
        val pending = selectedBoluses.any { it.insulinU == null &&
            ((it.programmedFastU ?: 0.0) + (it.programmedExtendedU ?: 0.0)) > 0 }
        val total = basal?.plus(bolus)?.takeIf { it.isFinite() && it > 0 && !pending }
        val iob = pump.filter { it.iobU != null && it.timeMillis in (time - 900_000)..time }
            .maxByOrNull { it.timeMillis }?.iobU
        val reason=when { pending->"Bolus delivery is unconfirmed";!bounded->"Incomplete 24-hour pump history";
            basal==null->"Basal coverage is incomplete";total==null->"Total insulin is zero or unknown";else->null }
        val settings=pump.filter { it.timeMillis in (time-DAY)..time }.sortedBy { it.timeMillis }
        Point(time, total, doses.isEmpty(), meals.filter { it.timeMillis in from until time }.sumOf { it.grams }, bolus, iob, reason,
            settings.lastOrNull { (it.pumpIsfMgdlPerU?:0.0)>0 }?.pumpIsfMgdlPerU,
            settings.lastOrNull { (it.pumpIcrGPerU?:0.0)>0 }?.pumpIcrGPerU)
        }
    }
}
