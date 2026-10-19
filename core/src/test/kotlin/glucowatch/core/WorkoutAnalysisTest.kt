package glucowatch.core

import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class WorkoutAnalysisTest {
    private val start=Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    private fun workout(grade: Double=0.0)=Workout("a".repeat(64),"Fixture","Run",start,start+300_000,300.0,300.0,450.0,0.0,0.0,
        (0..300).map { i->WorkoutSample(start+i*1000L,150,0.0,100+grade*1.5*i,1.5*i) })

    @Test fun `flat uphill and downhill match the published energy cost ratio`() {
        listOf(0.0,.1,-.1).forEach { grade ->
            val result=WorkoutAnalysis.analyse(workout(grade))
            val expected=1000/(1.5*WorkoutAnalysis.runningCost(grade)/3.6)/60
            assertEquals(expected,result.gapMean!!,1e-9)
            assertEquals(300.0,result.gapZones.sum())
            assertEquals(1,result.gap.size)
        }
        assertEquals(3.6,WorkoutAnalysis.runningCost(0.0))
        assertTrue(WorkoutAnalysis.runningCost(.1)>3.6)
        assertTrue(WorkoutAnalysis.runningCost(-.1)<3.6)
    }
    @Test fun `zero speed artefacts use distance progress and true source gaps remain gaps`() {
        val w=workout().copy(samples=workout().samples.filterNot { it.timeMillis in start+100_000..start+140_000 })
        val result=WorkoutAnalysis.analyse(w)
        assertEquals(2,result.gap.size)
        assertTrue(result.gapZones.sum()<300)
        assertEquals(listOf(start+99_000 to start+141_000),result.heartGaps)
        assertTrue(result.gap.flatten().all { it.value<30 })
    }
    @Test fun `stationary observations are slow pace instead of absent samples`() {
        val w=workout().copy(samples=workout().samples.mapIndexed { i,s->s.copy(distanceM=if(i in 100..200)150.0 else if(i>200)150.0+(i-200)*1.5 else i*1.5) })
        val result=WorkoutAnalysis.analyse(w)
        assertEquals(300.0,result.gapZones.sum())
        assertTrue(result.gap.flatten().any { it.value==30.0 })
    }
    @Test fun `private real FIT agrees with the reviewed calculation when selected explicitly`() {
        val file=System.getenv("GLUCOWATCH_WORKOUT_FIXTURE")?.let(::File)?:return
        val w=FitWorkoutReader.read(file.readBytes(),file.name)
        val result=WorkoutAnalysis.analyse(w)
        if(w.distanceM?.let { kotlin.math.abs(it-21882.65)<.1 }==true){
            assertEquals(12.8789139441,result.gapMean!!,1e-6)
            assertEquals(listOf(14303.0,2831.0,1669.0,712.0,449.0,743.0),result.gapZones)
        }
    }
    @Test fun `day specific basal reconstruction handles conflicts temps and suspension`() {
        val zone=ZoneId.of("UTC")
        fun rate(offset: Long,value: Double)=ExportRow(start+offset,ExportRow.SOURCE_CARELINK,ExportRow.KIND_REPORTED_BASAL,basalRateUPerH=value)
        val rows=listOf(rate(0,1.55),rate(0,.775),rate(45_000,.775),rate(3*3_600_000L,.7),rate(6*3_600_000L,.8),rate(7*3_600_000L,.9),rate(11*3_600_000L,1.0),rate(21*3_600_000L,.8))
        val day=ReportBasalEstimate.build(rows,zone,start,start+86_400_000)
        assertEquals(21.225,day.units!!,1e-9);assertTrue(day.complete)
        assertTrue(day.assumptions.any { it.contains("Conflicting") })
        val temp=ExportRow(start+5*3_600_000L,ExportRow.SOURCE_CARELINK,ExportRow.KIND_TEMP_BASAL,basalPercent=200.0,durationMin=120.0)
        val adjusted=ReportBasalEstimate.build(rows+temp,zone,start,start+86_400_000)
        assertEquals(day.units!!+1.5,adjusted.units!!,1e-9)
        val suspended=ExportRow(start+10*3_600_000L,ExportRow.SOURCE_CARELINK,ExportRow.KIND_SUSPEND,detail="{\"Suspend\":\"USER_SUSPEND\"}")
        val resume=suspended.copy(timeMillis=start+12*3_600_000L,detail="{\"Suspend\":\"NORMAL_PUMPING\"}")
        assertEquals(day.units!!-1.9,ReportBasalEstimate.build(rows+listOf(suspended,resume),zone,start,start+86_400_000).units!!,1e-9)
        assertNull(ReportBasalEstimate.build(emptyList(),zone,start,start+86_400_000).units)
    }
    @Test fun `temporary rates and suspend states cross midnight without filling missing days`() {
        val day=86_400_000L
        val rate=ExportRow(start+day,ExportRow.SOURCE_CARELINK,ExportRow.KIND_REPORTED_BASAL,basalRateUPerH=.8)
        val temp=ExportRow(start+day-30*60_000,ExportRow.SOURCE_CARELINK,ExportRow.KIND_TEMP_BASAL,basalPercent=200.0,durationMin=120.0)
        val suspend=ExportRow(start+day-60*60_000,ExportRow.SOURCE_CARELINK,ExportRow.KIND_SUSPEND,detail="{\"Suspend\":\"USER_SUSPEND\"}")
        val resume=suspend.copy(timeMillis=start+day+60*60_000,detail="{\"Suspend\":\"NORMAL_PUMPING\"}")
        assertEquals(20.4,ReportBasalEstimate.build(listOf(rate,temp),ZoneId.of("UTC"),start+day,start+day*2).units!!,1e-9)
        assertEquals(18.8,ReportBasalEstimate.build(listOf(rate,temp,suspend,resume),ZoneId.of("UTC"),start+day,start+day*2).units!!,1e-9)
        val missing=ReportBasalEstimate.build(listOf(rate,temp),ZoneId.of("UTC"),start+day,start+day*3)
        assertFalse(missing.complete)
        assertEquals(day,missing.knownMillis)
    }
}
