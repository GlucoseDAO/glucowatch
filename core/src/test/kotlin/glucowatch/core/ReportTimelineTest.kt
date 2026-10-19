package glucowatch.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class ReportTimelineTest {
    private val zone = ZoneOffset.UTC
    private val date = LocalDate.of(2026, 1, 1)
    private fun at(d: Int, h: Int = 0, m: Int = 0) = date.plusDays(d.toLong()).atTime(h, m).toInstant(zone).toEpochMilli()
    private fun row(d: Int, h: Int, kind: String = ExportRow.KIND_REPORTED_BASAL, rate: Double? = 1.0) = ExportRow(at(d,h),ExportRow.SOURCE_CARELINK,kind,basalRateUPerH=rate)
    private fun report(rows: List<ExportRow>): GlucoseReport {
        val window = ExportWindow(LatestDevice.MINIMED_PUMP,date,date,date.plusDays(2),date)
        return GlucoseReport.build(emptyList(),emptyList(),"fixture",zone,at(4),window,rows)
    }
    @Test fun `food joins identical logs but retains both origins and distinct sensor meals`() {
        val p = ExportRow(at(0,12),ExportRow.SOURCE_CARELINK,ExportRow.KIND_MEAL,carbsG=30.0)
        val s = p.copy(source=ExportRow.SOURCE_DEXCOM_CLARITY)
        val other = s.copy(timeMillis=at(0,14),carbsG=15.0)
        val meals=ReportMeal.read(listOf(p,p.copy(markerType="duplicate"),s,other,other.copy(carbsG=0.0)))
        assertEquals(2,meals.size);assertEquals("P+S",meals[0].sourceLabel);assertEquals("S",meals[1].sourceLabel)
        val r=report(listOf(p,s,other))
        assertEquals(45.0,r.days.first().carbsG);assertEquals(15.0,r.summary.carbsPerDay)
    }
    @Test fun `sensor selection retains Clarity food without admitting insulin or pump meals`() {
        val s=ExportRow(at(0,12),ExportRow.SOURCE_DEXCOM_CLARITY,ExportRow.KIND_MEAL,carbsG=18.0)
        val loggedDose=s.copy(kind=ExportRow.KIND_OTHER,carbsG=null,insulinU=2.0)
        val p=s.copy(source=ExportRow.SOURCE_CARELINK)
        val selected=listOf(s,loggedDose,p).filter(ExportContent.SENSOR::includes)
        assertEquals(listOf(s),selected)
        assertEquals(18.0,ReportMeal.read(selected).single().grams)
        assertTrue(ExportContent.SENSOR_AND_PUMP.includes(s))
        assertFalse(ExportContent.PUMP.includes(s))
    }
    @Test fun `missing pump days and partial hours stay absent in overlays`() {
        val dose=row(0,1,ExportRow.KIND_BOLUS,null).copy(insulinU=2.0)
        val r=report(listOf(row(0,0),dose,row(0,23),row(2,0),row(2,12)))
        val a=ReportDayTimeline.build(r,date,at(4));val missing=ReportDayTimeline.build(r,date.plusDays(1),at(4));val last=ReportDayTimeline.build(r,date.plusDays(2),at(4))
        assertEquals(0.0,a.hours[0].bolus);assertEquals(2.0,a.hours[1].bolus)
        assertTrue(missing.hours.all { it.bolus==null && it.basal==null })
        assertNull(last.hours[12].bolus);assertEquals(0.0,last.hours[11].bolus)
    }
    @Test fun `an hourly programmed amount integrates exact temporary intervals`() {
        val temp=ExportRow(at(0,5,30),ExportRow.SOURCE_CARELINK,ExportRow.KIND_TEMP_BASAL,basalPercent=200.0,durationMin=30.0)
        val r=report(listOf(row(0,0),temp,row(0,23)))
        val d=ReportDayTimeline.build(r,date,at(4))
        assertEquals(1.0,d.hours[5].scheduled);assertEquals(1.5,d.hours[5].basal)
        assertEquals(1.0,d.hours[6].basal);assertFalse(d.basalMeasured)
        assertNull(temp.insulinU)
    }
    @Test fun `recorded basal pulses never get doubled with a rate estimate`() {
        val pulse=row(0,5,ExportRow.KIND_BASAL_DELIVERY,null).copy(insulinU=.05)
        val d=ReportDayTimeline.build(report(listOf(row(0,0),pulse,row(0,23))),date,at(4))
        assertTrue(d.basalMeasured);assertEquals(.05,d.hours[5].basal);assertNull(d.hours[5].scheduled)
    }
    @Test fun `exercise clips across midnight excludes out of range and unions overlapping imports`() {
        fun w(id: String,from: Long,until: Long)=Workout(id,"fixture","Run",from,until,null,null,null,null,null,emptyList())
        val a=w("a",at(0,23),at(1,1));val b=w("b",at(1,0),at(1,2));val outside=w("c",at(2,4),at(2,5))
        assertEquals(listOf(a,b),ReportExercise.onDay(listOf(b,a,a,outside),date.plusDays(1),zone))
        assertEquals(listOf(at(1) to at(1,2)),ReportExercise.spans(listOf(a,b),at(1),at(2)))
        assertTrue(ReportExercise.onDay(listOf(outside),date,zone).isEmpty())
    }
}
