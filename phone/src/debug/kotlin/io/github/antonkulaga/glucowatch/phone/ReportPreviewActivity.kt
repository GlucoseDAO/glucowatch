package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import glucowatch.core.*

/** Local synthetic smoke-test fixture: no account, network, or health data. Debug APKs only. */
class ReportPreviewActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val label = TextView(this).apply { text = "Rendering synthetic report previews…" }
        setContentView(label)
        Thread {
            try {
                if (intent.getBooleanExtra("reviewed_fixture", false)) {
                    // Explicitly supplied private files only; no personal fixture enters git or release.
                    val zone=java.time.ZoneId.of(intent.getStringExtra("zone") ?: "UTC")
                    val glucose=HistoricalCsv.parse(cacheDir.resolve("sensor-fixture.csv").readText(),ExportRow.SOURCE_DEXCOM_CLARITY,zone)
                    val pump=HistoricalCsv.parse(cacheDir.resolve("pump-fixture.csv").readText(),ExportRow.SOURCE_CARELINK,zone)
                    val readings=glucose.filter { it.kind==ExportRow.KIND_GLUCOSE&&it.glucoseMgdl!=null }
                        .map { GlucoseReading(it.timeMillis,it.glucoseMgdl!!,Trend.Flat) }
                    val workout=FitWorkoutReader.read(cacheDir.resolve("workout-fixture.fit").readBytes(),"Local activity")
                    cacheDir.resolve("reviewed-fixture.pdf").writeBytes(WorkoutReportPdf.render(workout,readings,glucose+pump,zone))
                    val analysis=WorkoutAnalysis.analyse(workout)
                    cacheDir.resolve("reviewed-fixture-check.txt").writeText("Mean GAP: ${analysis.gapMean}; zones: ${analysis.gapZones}; source samples: ${readings.size}")
                    runOnUiThread { label.text="Private reviewed fixture ready." }
                    return@Thread
                }
                if (intent.getBooleanExtra("workouts", false)) {
                    val base = ReportPreviewData.build(1, ExportContent.SENSOR_AND_PUMP)
                    val rows = base.events + listOf(
                        ExportRow(base.days.single().date.atTime(10,45).toInstant(java.time.ZoneOffset.UTC).toEpochMilli(), "carelink", ExportRow.KIND_BOLUS, insulinU = .4),
                        ExportRow(base.days.single().date.atTime(11,0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli(), "carelink", ExportRow.KIND_MEAL, carbsG = 20.0),
                    ) + (0..17).map { i -> ExportRow(base.days.single().date.atTime(10,30).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() + i * 300_000L,
                        "carelink", ExportRow.KIND_BASAL_DELIVERY, insulinU = .04) }
                    val treatments = rows.mapNotNull { r -> when (r.kind) {
                        ExportRow.KIND_BOLUS -> Treatment(r.timeMillis, insulin = r.insulinU ?: 0.0)
                        ExportRow.KIND_BASAL_DELIVERY -> Treatment(r.timeMillis, insulin = r.insulinU ?: 0.0, insulinKind = InsulinKind.BASAL)
                        ExportRow.KIND_MEAL -> Treatment(r.timeMillis, carbs = r.carbsG ?: 0.0)
                        else -> null
                    } }
                    val report = GlucoseReport.build(base.readings, treatments, base.glucoseSource, java.time.ZoneId.of(base.zoneId),
                        window = base.window, events = rows)
                    val zone = java.time.ZoneId.of(report.zoneId)
                    val start = report.days.single().date.atTime(10, 30).atZone(zone).toInstant().toEpochMilli()
                    val workout = Workout("a".repeat(64), "Synthetic mountain run", "Run", start, start + 90 * 60_000,
                        5400.0, 5300.0, 8500.0, 320.0, 290.0,
                        (0..5400).map { s -> WorkoutSample(start + s * 1000L,
                            if (s in 1800..1860) null else (135 + 25 * kotlin.math.sin(s / 400.0)).toInt(),
                            if (s in 1800..1860) 0.0 else 1.6 + .6 * kotlin.math.sin(s / 300.0),
                            950 + 100 * kotlin.math.sin(s / 1200.0), s * 1.57) })
                    cacheDir.resolve("workout-synthetic.pdf").writeBytes(WorkoutReportPdf.render(workout, report.readings, report.events, zone, listOf(110,130,150,170)))
                    cacheDir.resolve("workout-in-daily-synthetic.pdf").writeBytes(ReportPdf.render(report, workouts = listOf(workout), zoneBoundaries = listOf(110,130,150,170)))
                    runOnUiThread { label.text = "Synthetic workout reports ready." }
                    return@Thread
                }
                if (intent.getBooleanExtra("inspect_fit", false)) {
                    // Explicit local fixture only. No production entry point or path in the release APK.
                    val file = cacheDir.resolve("workout-fixture.fit")
                    val bytes = file.readBytes()
                    val before = WorkoutStore(this).entries().size
                    val first = WorkoutStore(this).import(bytes, "Local workout.fit")
                    val second = WorkoutStore(this).import(bytes, "Local workout.fit")
                    val decoded = WorkoutStore(this).load(first.first.id)
                    check(!second.second && first.first.id == second.first.id)
                    check(WorkoutStore(this).entries().size == before + if (first.second) 1 else 0)
                    cacheDir.resolve("workout-inspection.txt").writeText("Imported: ${first.second}; duplicate suppressed: ${!second.second}; filename: ${first.first.file}; samples: ${decoded.samples.size}; distance: ${decoded.distanceM}; public copy: ${first.first.uri != null}")
                    runOnUiThread { label.text = "FIT archive checks passed." }
                    return@Thread
                }
                val chosen = intent.getStringExtra("content")?.let { ExportContent.valueOf(it) }
                val cases = if (intent.getBooleanExtra("all_cases", false)) ReportPreviewData.cases else
                    (chosen?.let { listOf(it) } ?: ExportContent.entries).map { intent.getIntExtra("days", 90) to it }
                cases.forEach { (days, content) ->
                    val report = ReportPreviewData.build(days, content)
                    cacheDir.resolve("standard-${days}-${content.name.lowercase()}.pdf").writeBytes(ReportPdf.render(report))
                }
                runOnUiThread { label.text = "Synthetic reports ready in private cache." }
            } catch (e: Exception) {
                cacheDir.resolve("report-preview-error.txt").writeText(e.stackTraceToString())
                runOnUiThread { label.text = "Report preview failed: ${e.javaClass.simpleName}: ${e.message}" }
            }
        }.start()
    }
}
