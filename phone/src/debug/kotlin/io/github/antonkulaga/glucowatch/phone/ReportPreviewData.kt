package io.github.antonkulaga.glucowatch.phone

import glucowatch.core.*
import java.time.LocalDate
import java.time.ZoneOffset

/** Entirely synthetic historical snapshots and doses; no accounts, network or personal data. */
internal object ReportPreviewData {
    val cases = ExportContent.entries.map { 14 to it } + listOf(1 to ExportContent.SENSOR_AND_PUMP, 90 to ExportContent.SENSOR_AND_PUMP)
    fun build(days: Int, content: ExportContent): GlucoseReport {
        val today = LocalDate.of(2026, 10, 13)
        val zone = ZoneOffset.UTC
        val now = today.atTime(23, 59, 59).toInstant(zone).toEpochMilli()
        val readings = DemoData.readings(now, days * 24)
        val treatments = DemoData.treatments(now, days * 24).filter { it.durationMinutes == null }.toMutableList()
        val rows = ExportRow.fromCareLinkCache(emptyList(), treatments, null).toMutableList()
        repeat(days) { d ->
            val day = today.minusDays(d.toLong())
            fun time(hour: Int, minute: Int = 0) = day.atTime(hour, minute).toInstant(zone).toEpochMilli()
            for ((minute, rate) in listOf(0 to .8, 180 to .7, 420 to 1.0, 750 to 1.2, 1260 to .9)) {
                rows += ExportRow(time(minute / 60, minute % 60), ExportRow.SOURCE_CARELINK, ExportRow.KIND_REPORTED_BASAL,
                    basalRateUPerH = rate, detail = "{\"Date\":\"$day\",\"Time\":\"${minute / 60}:${minute % 60}:00\"}")
            }
            rows += ExportRow(time(15, 32), ExportRow.SOURCE_CARELINK, ExportRow.KIND_TEMP_BASAL,
                basalPercent = if (d % 2 == 0) 160.0 else 60.0, durationMin = 95.0)
            rows += ExportRow(time(19, 5), ExportRow.SOURCE_CARELINK, ExportRow.KIND_SUSPEND, detail = "{\"Suspend\":\"USER_SUSPEND\"}")
            rows += ExportRow(time(19, 35), ExportRow.SOURCE_CARELINK, ExportRow.KIND_SUSPEND, detail = "{\"Suspend\":\"NORMAL_PUMPING\"}")
            rows += ExportRow(time(23, 59), ExportRow.SOURCE_CARELINK, ExportRow.KIND_OTHER, markerType = "Preview boundary")
            // Exercise continuation tables with more than eighteen native events on a day.
            if (d == 0) repeat(22) { i ->
                rows += ExportRow(time(i, 10), ExportRow.SOURCE_CARELINK, ExportRow.KIND_MEAL, carbsG = 5.0)
                treatments += Treatment(time(i, 10), carbs = 5.0)
            }
        }
        val window = (ExportWindow.resolve(LatestDevice.MINIMED_PUMP, today.minusDays(100), today, days, today, content) as ExportRequest.Ready).window
        return GlucoseReport.build(if (content.sensor) readings else emptyList(), if (content.pump) treatments else emptyList(),
            "Synthetic demo", zone, now, window, rows.filter(content::includes))
    }
}
