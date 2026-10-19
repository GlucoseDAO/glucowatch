package glucowatch.core

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Explicit rate-time reconstruction. It never creates normalized delivery events. */
data class ReportBasalEstimate(val intervals: List<ReportBasalInterval>, val assumptions: List<String>, val from: Long, val until: Long) {
    val knownMillis get() = intervals.sumOf { it.end - it.start }
    val complete get() = knownMillis == until - from && until > from
    val units get() = intervals.takeIf { it.isNotEmpty() }?.sumOf { it.programmedRate * (it.end - it.start) / 3_600_000.0 }

    companion object {
        fun build(rows: List<ExportRow>, zone: ZoneId, from: Long, until: Long): ReportBasalEstimate {
            require(until > from) { "Choose a non-empty basal estimate interval." }
            val result = mutableListOf<ReportBasalInterval>(); val notes = linkedSetOf<String>()
            val sorted = rows.distinctBy { it.identity() }.sortedBy { it.timeMillis }
            fun date(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
            fun state(row: ExportRow): String? = runCatching {
                ((Json.parseToJsonElement(row.detail ?: "{}") as? JsonObject)?.get("Suspend") as? JsonPrimitive)?.content
            }.getOrNull()
            var day = date(from)
            while (day <= date(until - 1)) {
                val a = maxOf(from, day.atStartOfDay(zone).toInstant().toEpochMilli())
                val b = minOf(until, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
                val native = sorted.filter { date(it.timeMillis) == day }
                val grouped = native.filter { it.kind == ExportRow.KIND_REPORTED_BASAL && it.basalRateUPerH != null }
                    .groupBy { it.timeMillis }.toSortedMap()
                val rates = grouped.mapNotNull { (time, group) ->
                    val values = group.mapNotNull { it.basalRateUPerH }.distinct()
                    if (values.size != 1) { notes += "Conflicting rate rows: use the next unambiguous setting before the first known rate, otherwise keep the previous setting."; null }
                    else time to values.single()
                }
                if (rates.isNotEmpty()) {
                    notes += "Rate settings are held between changes and to the end of each day with rate data; the first unambiguous rate covers an earlier day boundary."
                    val temps = sorted.filter { it.kind == ExportRow.KIND_TEMP_BASAL && it.durationMin != null &&
                        it.timeMillis < b && it.timeMillis+(it.durationMin!!*60_000).toLong() > a }
                    val states = sorted.filter { it.timeMillis < b }.mapNotNull { row -> state(row)?.let { row.timeMillis to it } }.distinct()
                    val cuts = (listOf(a,b) + rates.map { it.first } + temps.flatMap { listOf(it.timeMillis,it.timeMillis+(it.durationMin!!*60_000).toLong()) } + states.map { it.first })
                        .filter { it in a..b }.distinct().sorted()
                    cuts.zipWithNext().forEach { (start,end) ->
                        val baseline = (rates.lastOrNull { it.first <= start } ?: rates.first()).second
                        var programmed = baseline; var change = ReportBasalChange.SCHEDULED
                        temps.lastOrNull { it.timeMillis <= start }?.let { temp ->
                            if (start < temp.timeMillis+(temp.durationMin!!*60_000).toLong()) {
                                (temp.basalPercent?.let { baseline*it/100 } ?: temp.basalRateUPerH)?.let { programmed=it;change=ReportBasalChange.TEMPORARY }
                            }
                        }
                        states.lastOrNull { it.first <= start }?.let { if (it.second != "NORMAL_PUMPING") { programmed=0.0;change=ReportBasalChange.SUSPENDED } }
                        result += ReportBasalInterval(start,end,baseline,programmed,change)
                    }
                }
                day = day.plusDays(1)
            }
            return ReportBasalEstimate(result,notes.toList(),from,until)
        }
    }
}
