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
                if(intent.getBooleanExtra("release_checks",false)) {
                    ReleaseCheckData.verifyFolders(this)
                    val fixture=ReleaseCheckData.fixture()
                    val response=InsulinResponse.analyse(fixture.events,fixture.readings)
                    check(response.estimates.any {it.isf!=null} && response.estimates.any {it.icr!=null})
                    cacheDir.resolve("response-points-synthetic.pdf").writeBytes(ReportPdf.render(fixture))
                    cacheDir.resolve("response-checks.txt").writeText("Folders created/recreated/idempotent; ${response.estimates.size} response points; PDF rendered; synthetic data only.")
                    getSharedPreferences("export-browser",MODE_PRIVATE).edit().putBoolean("external.CARELINK",true).putBoolean("external.CLARITY",true).apply()
                    runOnUiThread {label.text="Folder and response checks passed."}
                    return@Thread
                }
                if(intent.getBooleanExtra("archive_matrix",false)) {
                    val store=WorkoutStore(this)
                    val checks=cacheDir.resolve("workout-cases").listFiles().orEmpty().filter { it.extension=="fit" }.sortedBy { it.name }.map { file->
                        val bytes=file.readBytes();val decoded=FitWorkoutReader.read(bytes,file.name)
                        val first=store.import(bytes,file.name);val again=store.import(bytes,file.name)
                        check(!again.second&&first.first.id==again.first.id)
                        "${file.name}: ${java.time.Instant.ofEpochMilli(decoded.startMillis)} to ${java.time.Instant.ofEpochMilli(decoded.endMillis)}; ${decoded.id.take(12)}; duplicate bytes reused; original bytes archived"
                    }
                    check(checks.size==8)
                    cacheDir.resolve("native-archive-check.txt").writeText(checks.joinToString("\n"))
                    runOnUiThread { label.text="Eight native archives verified, including browser suffixes." }
                    return@Thread
                }
                if (intent.getBooleanExtra("layout_review", false)) {
                    val zone=java.time.ZoneId.of("Europe/Bucharest")
                    fun source(key: String, portal: ExportPortal): List<ExportRow> {
                        val uri=android.net.Uri.parse(requireNotNull(intent.getStringExtra(key)))
                        val bytes=contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        val text=bytes.toString(Charsets.UTF_8)
                        val order=HistoricalCsv.inferDateOrder(text) ?: error("Explicit date order required")
                        ReportFiles.archiveCsv(this,portal,bytes,order=order)
                        return HistoricalCsv.parse(text,portal.source,zone,order)
                    }
                    WorkoutStore(this).setSwimPumpDisconnected(true)
                    val rows=source("sensor_uri",ExportPortal.CLARITY)+source("pump_uri",ExportPortal.CARELINK)
                    val first=java.time.LocalDate.parse("2026-09-20");val last=java.time.LocalDate.parse("2026-10-18")
                    val selected=rows.filter { java.time.Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() in first..last }
                    val workouts=cacheDir.resolve("workout-cases").listFiles().orEmpty()
                        .filter { it.extension.equals("fit",true) }.map { FitWorkoutReader.read(it.readBytes(),it.name).let { w->w.copy(pumpDisconnected=w.isSwim) } }
                    val window=ExportWindow(LatestDevice.MINIMED_PUMP,first,first,last,first,allowIncomplete=true)
                    val treatments=selected.filter { it.source==ExportRow.SOURCE_CARELINK }.mapNotNull { r->when(r.kind){
                        ExportRow.KIND_BOLUS->r.insulinU?.let { Treatment(r.timeMillis,insulin=it) }
                        ExportRow.KIND_BASAL_DELIVERY->r.insulinU?.let { Treatment(r.timeMillis,insulin=it,insulinKind=InsulinKind.BASAL) }
                        ExportRow.KIND_TEMP_BASAL->Treatment(r.timeMillis,insulinKind=InsulinKind.BASAL,basalRate=r.basalRateUPerH,basalPercent=r.basalPercent,durationMinutes=r.durationMin)
                        else->null
                    } }
                    val report=GlucoseReport.build(selected.filter { it.source==ExportRow.SOURCE_DEXCOM_CLARITY }.mapNotNull(ExportRow::toReading),
                        treatments,"Dexcom Clarity",zone,window=window,events=selected)
                    val general=ReportPdf.render(report,workouts=workouts)
                    val workoutFirst=java.time.LocalDate.parse("2026-10-15")
                    val activityWorkouts=workouts.filter { it.name in setOf("Afternoon_Swim","Evening_Run") }
                    val activityContext=WorkoutAnnotations.context(selected,workouts,activityWorkouts)
                    val activity=WorkoutReportPdf.render(activityWorkouts,report.readings,activityContext,zone,workoutFirst,last)
                    val files=listOf(ExportNames.report(first,last,ExportContent.SENSOR_AND_PUMP,workouts.size) to general,
                        (ExportNames.report(workoutFirst,last,ExportContent.SENSOR_AND_PUMP,activityWorkouts.size)+"-activity-report") to activity)
                    val uris=files.map { (name,bytes)->cacheDir.resolve("$name.pdf").writeBytes(bytes)
                        "$name.pdf="+ReportFiles.publish(this,"$name.pdf","application/pdf",bytes,ReportFiles.exports("application/pdf")) }
                    val from=workoutFirst.atStartOfDay(zone).toInstant().toEpochMilli()
                    val until=last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val csvName=ExportNames.report(workoutFirst,last,ExportContent.SENSOR_AND_PUMP,activityWorkouts.size)+"-merged-sample.csv"
                    val merged=MergedHealthExport.rows(activityContext,report.readings,activityWorkouts,from,until)
                    val csv=ExportCsv.bytes(ExportCsv.write(merged,zone))
                    cacheDir.resolve(csvName).writeBytes(csv)
                    val csvUri=ReportFiles.publish(this,csvName,"text/csv",csv,ReportFiles.exports("text/csv"))
                    val annotated=WorkoutAnnotations.add(selected,workouts)
                    val response=InsulinResponse.analyse(annotated,report.readings)
                    InsulinAnalysisStore(this).save(annotated,report.readings,zone,until,PhoneSettingsStore(this).load())
                    cacheDir.resolve("response-check.txt").writeText("Native settings: "+selected.count { it.pumpIsfMgdlPerU!=null||it.pumpIcrGPerU!=null }+"; conditional estimates: "+response.estimates.size+"; absorption points: "+response.appearance.size+"; excluded meals: "+response.excluded+"; unresolved bolus components: "+BolusLedger.records(selected).count { it.insulinU==null }+"; CSV rows: "+merged.size)
                    cacheDir.resolve("layout-review.txt").writeText((uris+"$csvName=$csvUri").joinToString("\n"))
                    runOnUiThread { label.text="Native layout review ready: ${uris.joinToString("\n")}" }
                    return@Thread
                }
                if (intent.getBooleanExtra("activity_matrix", false)) {
                    val zone = java.time.ZoneId.of(intent.getStringExtra("zone") ?: "Europe/Bucharest")
                    val inputs = cacheDir.resolve("workout-cases").listFiles().orEmpty().filter { it.extension.equals("fit", true) }
                    check(inputs.isNotEmpty()) { "Explicit local FIT fixtures are required" }
                    val workouts = inputs.sortedBy { it.name }.map { FitWorkoutReader.read(it.readBytes(), it.name) }
                    val range = WorkoutReportRange.covering(workouts, zone)
                    cacheDir.resolve("activity-matrix.pdf").writeBytes(WorkoutReportPdf.render(workouts, emptyList(), emptyList(), zone, range.first, range.last))
                    val window = ExportWindow(LatestDevice.MINIMED_PUMP, range.first, range.first, range.last, range.first, allowIncomplete = true)
                    val report = GlucoseReport.build(emptyList(), emptyList(), "No sensor fixture supplied", zone, window = window)
                    cacheDir.resolve("activity-matrix-general.pdf").writeBytes(ReportPdf.render(report, workouts = workouts))
                    val edgeStart = java.time.Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()
                    val base = Workout("b".repeat(64), "Synthetic activity", "Swim", edgeStart, edgeStart + 300_000,
                        300.0, 240.0, 100.0, null, null, emptyList())
                    val edges = listOf(base, base.copy(id="c".repeat(64), name="Pool lengths only", poolLengthM=25.0,
                        lengths=listOf(WorkoutLength(edgeStart,edgeStart+60_000,60.0,null,true))),
                        base.copy(id="d".repeat(64), name="Open water without HR", samples=(0..300).map { i -> WorkoutSample(edgeStart+i*1000L,speedMps=.6,distanceM=i*.6) }),
                        base.copy(id="e".repeat(64), name="Stationary training", sport="Exercise"),
                        base.copy(id="f".repeat(64), name="Ride without elevation", sport="Ride", distanceM=1500.0, timerSeconds=300.0, samples=(0..300).map { i -> WorkoutSample(edgeStart+i*1000L,heartRate=130,speedMps=5.0,distanceM=i*5.0) }))
                    val edgeRange = WorkoutReportRange.covering(edges, zone)
                    cacheDir.resolve("activity-edge-cases.pdf").writeBytes(WorkoutReportPdf.render(edges, emptyList(), emptyList(), zone, edgeRange.first, edgeRange.last))
                    cacheDir.resolve("activity-matrix-check.txt").writeText(workouts.joinToString("\n") { workout ->
                        val analysis = WorkoutAnalysis.analyse(workout)
                        "${workout.name}: ${workout.sport}; observations=${workout.samples.size}; outside=${workout.excludedSampleCount}; lengths=${workout.lengths.size}; motion=${analysis.motionTitle} / ${analysis.motionUnit}; GAP=${analysis.gap.isNotEmpty()}"
                    })
                    runOnUiThread { label.text = "Native activity matrix and missing-field reports ready." }
                    return@Thread
                }
                if (intent.getBooleanExtra("timeline_fixture", false)) {
                    // Explicit private inputs; no account access or files bundled in the APK.
                    val zone = java.time.ZoneId.of(intent.getStringExtra("zone") ?: "UTC")
                    fun source(name: String, kind: String) = cacheDir.resolve(name).takeIf { it.isFile }?.let {
                        HistoricalCsv.parse(it.readText(), kind, zone)
                    }.orEmpty()
                    val glucose = source("sensor-fixture.csv", ExportRow.SOURCE_DEXCOM_CLARITY) + source("context-sensor-fixture.csv", ExportRow.SOURCE_DEXCOM_CLARITY)
                    val pump = source("pump-fixture.csv", ExportRow.SOURCE_CARELINK) + source("context-pump-fixture.csv", ExportRow.SOURCE_CARELINK)
                    val workout = FitWorkoutReader.read(cacheDir.resolve("workout-fixture.fit").readBytes(), "Imported activity")
                    val end = java.time.Instant.ofEpochMilli(workout.endMillis).atZone(zone).toLocalDate()
                    val first = end.minusDays(6)
                    val window = ExportWindow(LatestDevice.MINIMED_PUMP, first, first, end, first, allowIncomplete = true)
                    val all = (glucose + pump).distinctBy { it.source + it.identity() }
                    val readings = glucose.mapNotNull(ExportRow::toReading)
                    val treatments = pump.mapNotNull { row -> when (row.kind) {
                        ExportRow.KIND_BOLUS -> Treatment(row.timeMillis, insulin = row.insulinU ?: 0.0)
                        ExportRow.KIND_BASAL_DELIVERY -> Treatment(row.timeMillis, insulin = row.insulinU ?: 0.0, insulinKind = InsulinKind.BASAL)
                        ExportRow.KIND_MEAL -> Treatment(row.timeMillis, carbs = row.carbsG ?: 0.0)
                        else -> null
                    } }
                    val report = GlucoseReport.build(readings, treatments, "Dexcom Clarity", zone, window = window, events = all)
                    cacheDir.resolve("timeline-fixture.pdf").writeBytes(ReportPdf.render(report, workouts = listOf(workout)))
                    cacheDir.resolve("timeline-fixture-check.txt").writeText("Window: $first to $end; sensor days: ${report.summary.daysWithData}; workout: ${workout.startMillis} to ${workout.endMillis}; meals: ${ReportMeal.read(report.events).size}")
                    runOnUiThread { label.text = "Private general timeline report ready." }
                    return@Thread
                }
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
