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
        assertEquals(10.0,result.estimates.single().icr!!,.1)
        assertEquals(30.0,result.appearance.single { it.time==start+7_200_000 }.apparentG,.3)
        val exercise=ExportRow(start+3_600_000,"strava_fit","workout_start",durationMin=60.0)
        assertTrue(InsulinResponse.analyse(rows+exercise,glucose).estimates.isEmpty())
    }
    @Test fun isolatedCorrectionRecoversSensitivityWithoutUsingMealICR() {
        val rows=listOf(settings,ExportRow(start,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,insulinU=2.0))
        val glucose=(-72..48).map { i->GlucoseReading(start+i*300_000L,(250-100*InsulinResponse.effect(i*5.0)).toInt(),Trend.None) }
        assertEquals(50.0,InsulinResponse.analyse(rows,glucose).estimates.single().isf!!,1.0)
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
