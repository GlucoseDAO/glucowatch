package glucowatch.core

import java.time.ZoneOffset
import kotlin.test.*

class InsulinResponseTest {
    private val start=1_767_225_600_000L
    private val settings=ExportRow(start,ExportRow.SOURCE_CARELINK,"pump_therapy_settings",pumpIsfMgdlPerU=50.0,pumpIcrGPerU=10.0)
    @Test fun nativeSettingsAndBolusNumberResolveProgramAndDelivery() {
        val text="""Index,Date,Time,Bolus Type,Bolus Volume Selected (U),Bolus Volume Delivered (U),Bolus Number,BWZ Carb Ratio (g/U),BWZ Insulin Sensitivity (mg/dL/U)
1,2026-01-01,12:00:00,Normal,2,,45,10,50
2,2026-01-01,12:01:00,Normal,2,2,45,,
"""
        val rows=HistoricalCsv.parse(text,ExportRow.SOURCE_CARELINK,ZoneOffset.UTC)
        assertEquals(2,rows.count { it.kind==ExportRow.KIND_BOLUS })
        val dose=BolusLedger.records(rows).single()
        assertEquals(2.0,dose.insulinU);assertEquals("45",dose.bolusId)
        assertEquals(rows.minOf { it.timeMillis },dose.timeMillis)
        val point=InsulinAnalytics.at(rows,ZoneOffset.UTC,rows.maxOf { it.timeMillis })
        assertEquals(50.0,point.isf);assertEquals(10.0,point.icr)
        assertEquals(2,BolusLedger.records(rows.map { if(it.insulinU!=null)it.copy(bolusId="46")else it }).size)
        val decoded=ExportRow.decodeJson(ExportRow.encodeJson(rows))
        assertEquals(rows,decoded)
        val csv=ExportCsv.write(rows,ZoneOffset.UTC)
        assertTrue(csv.contains("pump_isf_mgdl_per_u,pump_icr_g_per_u,bolus_id"))
    }
    @Test fun actionKernelIsMonotonicAndConservesUnitEffect() {
        assertEquals(0.0,InsulinResponse.effect(0.0));assertEquals(1.0,InsulinResponse.effect(360.0))
        val values=(0..400).map {InsulinResponse.effect(it.toDouble())}
        assertTrue(values.zipWithNext().all { it.first<=it.second })
    }
    @Test fun syntheticMealRecoversConditionalRatioAndApparentCarbCurve() {
        val rows=listOf(settings,ExportRow(start,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,insulinU=6.0),
            ExportRow(start,ExportRow.SOURCE_CARELINK,ExportRow.KIND_MEAL,carbsG=60.0))
        val glucose=(-72..48).map { i->val m=i*5.0;GlucoseReading(start+i*300_000L,(100+300*m.coerceAtLeast(0.0)/240-300*InsulinResponse.effect(m)).toInt(),Trend.None) }
        val result=InsulinResponse.analyse(rows,glucose)
        assertEquals(10.0,result.estimates.last().icr!!,.1)
        assertTrue(result.estimates.all { it.time >= start+3*3_600_000 && it.windowStart==start })
        assertEquals(30.0,result.appearance.single { it.time==start+7_200_000 }.apparentG,.3)
        val exercise=ExportRow(start+3_600_000,"strava_fit","workout_start",durationMin=60.0)
        assertTrue(InsulinResponse.analyse(rows+exercise,glucose).estimates.isEmpty())
    }
    @Test fun isolatedCorrectionRecoversSensitivityWithoutUsingMealICR() {
        val rows=listOf(settings,ExportRow(start,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,insulinU=2.0))
        val glucose=(-72..48).map { i->GlucoseReading(start+i*300_000L,(250-100*InsulinResponse.effect(i*5.0)).toInt(),Trend.None) }
        assertEquals(50.0,InsulinResponse.analyse(rows,glucose).estimates.last().isf!!,1.0)
    }
    @Test fun liveCorrectionPointsAreCausalAndAppearBeforeFourHours() {
        val rows=listOf(ExportRow(start,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,insulinU=2.0))
        val glucose=(-72..72).map { i->GlucoseReading(start+i*300_000L,(250-100*InsulinResponse.effect(i*5.0)).toInt(),Trend.None) }
        val now=start+2*3_600_000
        val live=InsulinResponse.analyse(rows,glucose,now)
        assertTrue(live.estimates.isNotEmpty())
        assertTrue(live.estimates.all { it.time<=now && it.provisional && it.isf!=null })
        assertEquals(50.0,live.estimates.last().isf!!,1.0)
        val future=rows+ExportRow(start+3*3_600_000,ExportRow.SOURCE_CARELINK,ExportRow.KIND_MEAL,carbsG=100.0)
        assertEquals(live,InsulinResponse.analyse(future,glucose,now))
        assertEquals(live.estimates,InsulinResponse.analyse(rows,glucose.filter {it.timeMillis<=now}).estimates)
        assertTrue(InsulinResponse.analyse(rows,glucose,start+60*60_000).estimates.isEmpty())
        assertTrue(live.exportRows().all { it.timeMillis<=now && it.detail!!.contains("observed_at_millis") })
    }
    @Test fun mealNearBaselineDoesNotNeedConfiguredIsfOrIcr() {
        val rows=listOf(ExportRow(start,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,insulinU=6.0),
            ExportRow(start,ExportRow.SOURCE_CARELINK,ExportRow.KIND_MEAL,carbsG=60.0))
        val glucose=(-72..72).map { GlucoseReading(start+it*300_000L,100,Trend.None) }
        val result=InsulinResponse.analyse(rows,glucose)
        assertEquals(10.0,result.estimates.last().icr!!,.01)
        assertEquals("meal_return_to_baseline",result.estimates.last().method)
        assertFalse(result.estimates.last().provisional)
        val elevated=glucose.map { if(it.timeMillis>=start)it.copy(mgdl=150)else it }
        // A sustained rise cannot identify both unknown factors from a meal alone.
        val risen=elevated.map { if(it.timeMillis==start)it.copy(mgdl=100)else it }
        assertTrue(InsulinResponse.analyse(rows,risen).estimates.isEmpty())
    }
    @Test fun missingCgmAndExerciseNeverProduceResponsePoints() {
        val rows=listOf(ExportRow(start,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,insulinU=2.0))
        val glucose=(-72..48).map { i->GlucoseReading(start+i*300_000L,(250-100*InsulinResponse.effect(i*5.0)).toInt(),Trend.None) }
        assertTrue(InsulinResponse.analyse(rows,glucose.filterNot { it.timeMillis in (start+20*60_000)..(start+100*60_000) }).estimates.isEmpty())
        assertTrue(InsulinResponse.analyse(rows+ExportRow(start,"strava_fit","workout_start",durationMin=30.0),glucose).estimates.isEmpty())
        val basal=listOf(ExportRow(start-3_600_000,ExportRow.SOURCE_CARELINK,ExportRow.KIND_REPORTED_BASAL,basalRateUPerH=.8),
            ExportRow(start+3_600_000,ExportRow.SOURCE_CARELINK,ExportRow.KIND_REPORTED_BASAL,basalRateUPerH=1.2))
        assertTrue(InsulinResponse.analyse(rows+basal,glucose).estimates.isEmpty())
    }
    @Test fun lateDeliveryConfirmationCannotRewriteEarlierObservationPoints() {
        val programmed=ExportRow(start,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,programmedFastU=2.0,bolusId="42")
        val delivered=programmed.copy(timeMillis=start+3*3_600_000,insulinU=2.0,deliveredFastU=2.0)
        val glucose=(-72..48).map { i->GlucoseReading(start+i*300_000L,(250-100*InsulinResponse.effect(i*5.0)).toInt(),Trend.None) }
        val result=InsulinResponse.analyse(listOf(programmed,delivered),glucose)
        assertTrue(result.estimates.isNotEmpty())
        assertTrue(result.estimates.all { it.time>=delivered.timeMillis })
        assertTrue(InsulinResponse.analyse(listOf(programmed,delivered),glucose,start+2*3_600_000).estimates.isEmpty())
    }
    @Test fun swimDisconnectionHasProvenanceAndZeroEstimatedBasalWithoutChangingNativeRates() {
        val swim=Workout("a".repeat(64),"swim","Swim",start+3_600_000,start+7_200_000,null,null,null,null,null,emptyList(),pumpDisconnected=true)
        val raw=ExportRow(start,ExportRow.SOURCE_CARELINK,ExportRow.KIND_REPORTED_BASAL,basalRateUPerH=1.0)
        val events=WorkoutAnnotations.add(listOf(raw),listOf(swim))
        val estimate=ReportBasalEstimate.build(events,ZoneOffset.UTC,start,start+InsulinAnalytics.DAY)
        assertEquals(23.0,estimate.units);assertEquals(1.0,raw.basalRateUPerH)
        assertTrue(swim.exportRows(start,start+InsulinAnalytics.DAY).any { it.source=="user_reported"&&it.kind=="pump_disconnected" })
        val clipped=swim.exportRows(start,start+5_400_000)
        assertFalse(clipped.any {it.kind=="pump_reconnected"})
        assertTrue(clipped.all {it.timeMillis<start+5_400_000})
        val hidden=WorkoutAnnotations.context(emptyList(),listOf(swim),emptyList())
        assertTrue(hidden.any {it.source=="activity_context"&&it.kind=="workout_start"})
    }
}
