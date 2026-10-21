package glucowatch.core

import java.time.Instant
import java.time.ZoneId

/** Native wizard settings are observations of the programmed parameters, not a full schedule export. */
object InsulinComparison {
    enum class Parameter(val label: String, val unit: String) {
        ISF("ISF", "mg/dL/U"), ICR("ICR", "g/U");
        fun native(row: ExportRow) = (if (this == ISF) row.pumpIsfMgdlPerU else row.pumpIcrGPerU)?.takeIf { it.isFinite() && it > 0 }
        fun response(point: InsulinResponse.Estimate) = (if (this == ISF) point.isf else point.icr)?.takeIf { it.isFinite() && it > 0 }
    }
    data class Segment(val start: Long, val end: Long, val value: Double)
    data class Hour(val pump: ReportRange?, val response: ReportRange?)

    /** Hold only forward from a known setting, until the next setting or 24 h. Conflicts break the curve. */
    fun segments(rows: List<ExportRow>, parameter: Parameter, from: Long, until: Long): List<Segment> {
        require(until > from)
        val samples = rows.filter { it.source == ExportRow.SOURCE_CARELINK && it.timeMillis < until && parameter.native(it) != null }
            .groupBy { it.timeMillis }.toSortedMap().entries.toList()
        return samples.mapIndexedNotNull { i, entry ->
            val values = entry.value.mapNotNull(parameter::native).distinct()
            val a = maxOf(from, entry.key)
            val b = minOf(until, entry.key + 86_400_000L, samples.getOrNull(i + 1)?.key ?: until)
            if (values.size == 1 && b > a) Segment(a, b, values.single()) else null
        }
    }

    fun hourly(rows: List<ExportRow>, response: InsulinResponse.Result, zone: ZoneId, from: Long, until: Long,
               parameter: Parameter): List<Hour> {
        val native = segments(rows, parameter, from, until)
        val pumpValues = List(24) { mutableListOf<Double>() }
        // Split by actual calendar hours, including DST transitions; unknown spans stay unknown.
        native.forEach { segment ->
            var time = segment.start
            while (time < segment.end) {
                val local = Instant.ofEpochMilli(time).atZone(zone)
                pumpValues[local.hour] += segment.value
                time = minOf(segment.end, local.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli())
            }
        }
        val estimates = response.estimates.filter { it.time in from until until }.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).hour }
        return (0..23).map { hour -> Hour(ReportRange.of(pumpValues[hour]), ReportRange.of(estimates[hour].orEmpty().mapNotNull(parameter::response))) }
    }
}

/** Summation keeps measured basal doses distinct from a rate x duration reconstruction. */
data class ReportInsulinTotal(val bolus: Double, val basal: Double?, val estimatedBasal: Boolean,
                              val knownBasalMillis: Long, val requestedMillis: Long) {
    val total get() = basal?.plus(bolus)
    val partial get() = knownBasalMillis < requestedMillis
    companion object {
        fun build(rows: List<ExportRow>, zone: ZoneId, from: Long, until: Long): ReportInsulinTotal {
            require(until > from)
            val native = rows.distinctBy { it.source + it.identity() }
            val pump = native.filter { it.source == ExportRow.SOURCE_CARELINK }
            val bolus = BolusLedger.unitsBetween(BolusLedger.records(pump), from, until)
            val pulses = pump.filter { it.kind == ExportRow.KIND_BASAL_DELIVERY && it.timeMillis in from until until && it.insulinU != null }
            if (pulses.isNotEmpty()) {
                // Sparse pulses do not establish complete interval coverage.
                val first = pump.minOfOrNull { it.timeMillis } ?: until
                val last = pump.maxOfOrNull { it.timeMillis } ?: from
                return ReportInsulinTotal(bolus, pulses.sumOf { it.insulinU!! }, false,
                    (minOf(until, last) - maxOf(from, first)).coerceAtLeast(0), until - from)
            }
            val basal = ReportBasalEstimate.build(native, zone, from, until)
            return ReportInsulinTotal(bolus, basal.units, true, basal.knownMillis, until - from)
        }
    }
}
