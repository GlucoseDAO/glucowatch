package io.github.antonkulaga.glucowatch.phone

import android.graphics.Canvas
import glucowatch.core.*
import java.time.Instant
import java.time.ZoneId

/** Whole calendar-day context first, then the exact activity interval. */
object WorkoutReportPdf {
    fun render(workouts: List<Workout>, glucose: List<GlucoseReading>, events: List<ExportRow>, zone: ZoneId,
               start: java.time.LocalDate, end: java.time.LocalDate,
               boundaries: List<Int> = WorkoutAnalysis.defaultHeartBoundaries,
               paceBoundaries: List<Int> = WorkoutAnalysis.defaultPaceBoundaries): ByteArray {
        WorkoutReportRange.covering(workouts, zone).validate(start, end)
        val window = ExportWindow(LatestDevice.MINIMED_PUMP, start, start, end, start, ExportContent.SENSOR_AND_PUMP, allowIncomplete = true)
        val report = GlucoseReport.build(glucose, emptyList(), "Imported sensor", zone, window = window, events = WorkoutAnnotations.add(events,workouts))
        return ReviewedReportPdf.render(report, workouts, boundaries, System.currentTimeMillis(), paceBoundaries)
    }

    fun render(workout: Workout, glucose: List<GlucoseReading>, events: List<ExportRow>, zone: ZoneId,
               boundaries: List<Int> = WorkoutAnalysis.defaultHeartBoundaries,
               paceBoundaries: List<Int> = WorkoutAnalysis.defaultPaceBoundaries): ByteArray {
        val first=Instant.ofEpochMilli(workout.startMillis).atZone(zone).toLocalDate()
        val last=Instant.ofEpochMilli(workout.endMillis).atZone(zone).toLocalDate()
        val window=ExportWindow(LatestDevice.MINIMED_PUMP,first,first,last,first,ExportContent.SENSOR_AND_PUMP,allowIncomplete=true)
        val report=GlucoseReport.build(glucose,emptyList(),"Imported sensor",zone,window=window,events=WorkoutAnnotations.add(events,listOf(workout)))
        return ReviewedReportPdf.render(report,listOf(workout),boundaries,System.currentTimeMillis(),paceBoundaries)
    }

    fun pages(workout: Workout, glucose: List<GlucoseReading>, events: List<ExportRow>, zone: ZoneId,
              boundaries: List<Int> = WorkoutAnalysis.defaultHeartBoundaries,
              from: Long = workout.startMillis, until: Long = workout.endMillis,
              paceBoundaries: List<Int> = WorkoutAnalysis.defaultPaceBoundaries): List<(Canvas)->Unit> =
        ReviewedReportPdf.activityPages(workout,glucose,WorkoutAnnotations.add(events,listOf(workout)),zone,boundaries,from,until,paceBoundaries)
}
