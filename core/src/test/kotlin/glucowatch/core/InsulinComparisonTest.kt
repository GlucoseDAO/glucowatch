package glucowatch.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class InsulinComparisonTest {
    private val zone = ZoneOffset.UTC
    private val from = LocalDate.of(2026, 10, 19).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun setting(hour: Int, isf: Double) = ExportRow(from + hour * InsulinResponse.HOUR, ExportRow.SOURCE_CARELINK,
        "pump_therapy_settings", pumpIsfMgdlPerU = isf, pumpIcrGPerU = 10.0)
    @Test fun settingsHoldForwardOnlyAndStopAtTwentyFourHoursOrConflicts() {
        val rows = listOf(setting(2, 55.0), setting(4, 60.0), setting(7, 65.0), setting(7, 70.0))
        val segments = InsulinComparison.segments(rows, InsulinComparison.Parameter.ISF, from, from + 3 * (24 * InsulinResponse.HOUR))
        assertEquals(listOf(2L to 4L, 4L to 7L), segments.map { (it.start-from)/InsulinResponse.HOUR to (it.end-from)/InsulinResponse.HOUR })
        val held = InsulinComparison.segments(listOf(setting(2,55.0)), InsulinComparison.Parameter.ISF, from, from + 3 * (24 * InsulinResponse.HOUR))
        assertEquals((24 * InsulinResponse.HOUR), held.single().end - held.single().start)
    }
    @Test fun hourlyComparisonRetainsZeroMissingnessAndActualResponseRanges() {
        val response = InsulinResponse.Result(listOf(
            InsulinResponse.Estimate(from + 4 * InsulinResponse.HOUR, isf=45.0, icr=null),
            InsulinResponse.Estimate(from + 4 * InsulinResponse.HOUR + 300_000, isf=65.0, icr=null)), emptyList(), 0)
        val hours = InsulinComparison.hourly(listOf(setting(2,55.0)), response, zone, from, from+(24 * InsulinResponse.HOUR), InsulinComparison.Parameter.ISF)
        assertNull(hours[0].pump); assertNull(hours[0].response)
        assertEquals(ReportRange(45.0,55.0,65.0,2),hours[4].response)
        assertEquals(55.0,hours[4].pump!!.median)
        assertNull(InsulinComparison.hourly(emptyList(),response,zone,from,from+(24 * InsulinResponse.HOUR),InsulinComparison.Parameter.ICR)[4].response)
    }
    @Test fun totalAddsDeliveredBolusToIntegratedBasalWithoutCallingRatesDoses() {
        val rows = listOf(ExportRow(from,ExportRow.SOURCE_CARELINK,ExportRow.KIND_REPORTED_BASAL,basalRateUPerH=1.0),
            ExportRow(from+6*InsulinResponse.HOUR,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,insulinU=3.0),
            ExportRow(from+12*InsulinResponse.HOUR,ExportRow.SOURCE_CARELINK,ExportRow.KIND_TEMP_BASAL,basalPercent=50.0,durationMin=120.0))
        val total = ReportInsulinTotal.build(rows+rows,zone,from,from+(24 * InsulinResponse.HOUR))
        assertEquals(3.0,total.bolus); assertEquals(23.0,total.basal!!,1e-9)
        assertEquals(26.0,total.total!!,1e-9); assertTrue(total.estimatedBasal); assertFalse(total.partial)
        val bolusOnly = ReportInsulinTotal.build(rows.filter {it.kind==ExportRow.KIND_BOLUS},zone,from,from+(24 * InsulinResponse.HOUR))
        assertNull(bolusOnly.total); assertEquals(3.0,bolusOnly.bolus)
    }
    @Test fun missingDaysStayPartialAndRecordedBasalTakesPriorityOverRates() {
        val rate = ExportRow(from,ExportRow.SOURCE_CARELINK,ExportRow.KIND_REPORTED_BASAL,basalRateUPerH=1.0)
        val missingDay = ReportInsulinTotal.build(listOf(rate),zone,from,from+48*InsulinResponse.HOUR)
        assertEquals(24.0,missingDay.total!!,1e-9); assertTrue(missingDay.partial)
        val zeroDose = ExportRow(from,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BASAL_DELIVERY,insulinU=0.0)
        val measured = ReportInsulinTotal.build(listOf(rate,zeroDose),zone,from,from+24*InsulinResponse.HOUR)
        assertEquals(0.0,measured.basal); assertFalse(measured.estimatedBasal); assertTrue(measured.partial)
    }
}
