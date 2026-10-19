package io.github.antonkulaga.glucowatch.phone

import android.graphics.Canvas
import glucowatch.core.*
import java.time.Instant
import java.time.ZoneId

/** Whole calendar-day context first, then the exact activity interval. */
object WorkoutReportPdf {
    fun render(workout: Workout, glucose: List<GlucoseReading>, events: List<ExportRow>, zone: ZoneId,
               boundaries: List<Int> = WorkoutAnalysis.defaultHeartBoundaries,
               paceBoundaries: List<Int> = WorkoutAnalysis.defaultPaceBoundaries): ByteArray {
        val first=Instant.ofEpochMilli(workout.startMillis).atZone(zone).toLocalDate()
        val last=Instant.ofEpochMilli(workout.endMillis).atZone(zone).toLocalDate()
        val window=ExportWindow(LatestDevice.MINIMED_PUMP,first,first,last,first,ExportContent.SENSOR_AND_PUMP,allowIncomplete=true)
        val report=GlucoseReport.build(glucose,emptyList(),"Imported sensor",zone,window=window,events=events)
        return ReviewedReportPdf.render(report,listOf(workout),boundaries,System.currentTimeMillis(),paceBoundaries)
    }

    fun pages(workout: Workout, glucose: List<GlucoseReading>, events: List<ExportRow>, zone: ZoneId,
              boundaries: List<Int> = WorkoutAnalysis.defaultHeartBoundaries,
              from: Long = workout.startMillis, until: Long = workout.endMillis,
              paceBoundaries: List<Int> = WorkoutAnalysis.defaultPaceBoundaries): List<(Canvas)->Unit> =
        ReviewedReportPdf.activityPages(workout,glucose,events,zone,boundaries,from,until,paceBoundaries)
}
