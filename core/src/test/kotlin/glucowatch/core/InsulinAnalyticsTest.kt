package glucowatch.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class InsulinAnalyticsTest {
    private val zone=ZoneOffset.UTC
    private val start=LocalDate.of(2026,1,1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val end=start+InsulinAnalytics.DAY
    private fun rows()=listOf(
        ExportRow(start,ExportRow.SOURCE_CARELINK,ExportRow.KIND_REPORTED_BASAL,basalRateUPerH=1.0),
        ExportRow(end-60_000,ExportRow.SOURCE_CARELINK,ExportRow.KIND_REPORTED_BASAL,basalRateUPerH=1.0),
        ExportRow(start+3_600_000,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,insulinU=12.0),
        ExportRow(start+3_600_000,ExportRow.SOURCE_CARELINK,ExportRow.KIND_MEAL,carbsG=60.0))
    @Test fun `references use basal plus delivered bolus without adjusting for carbs or IOB`() {
        val rows=rows()
        val point=InsulinAnalytics.at(rows,zone,end)
        assertEquals(36.0,point.total24h);assertEquals(50.0,point.isf)
        assertEquals(450.0/36,point.icr);assertEquals(60.0,point.carbs24h);assertTrue(point.estimatedBasal)
        val iob=ExportRow(end-60_000,ExportRow.SOURCE_CARELINK,ExportRow.KIND_IOB,iobU=3.0)
        val second=InsulinAnalytics.at(rows+rows.last().copy(source=ExportRow.SOURCE_DEXCOM_CLARITY)+iob,zone,end)
        assertEquals(point.isf,second.isf);assertEquals(point.icr,second.icr);assertEquals(60.0,second.carbs24h)
        assertEquals(3.0,second.iob)
        assertNull(InsulinAnalytics.at(rows+iob.copy(timeMillis=end-901_000),zone,end).iob)
    }
    @Test fun `partial unknown and zero totals do not become dosing factors`() {
        assertNull(InsulinAnalytics.at(rows().drop(1),zone,end).isf)
        val unknown=ExportRow(start+7_200_000,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,programmedFastU=2.0)
        assertNull(InsulinAnalytics.at(rows()+unknown,zone,end).icr)
        assertNull(InsulinAnalytics.at(emptyList(),zone,end).total24h)
        val zero=rows().filter { it.kind!=ExportRow.KIND_BOLUS }.map { if(it.basalRateUPerH!=null)it.copy(basalRateUPerH=0.0)else it }
        assertNull(InsulinAnalytics.at(zero,zone,end).isf)
    }
    @Test fun `extended and immediate bolus components remain separate even at same timestamp`() {
        val csv="""Index,Date,Time,Bolus Type,Bolus Volume Selected (U),Bolus Volume Delivered (U),Bolus Duration (h:mm:ss)
1,2026-01-01,12:00:00,Dual (normal part),2,2,
2,2026-01-01,12:00:00,Dual (square part),3,,00:30:00
"""
        val parsed=HistoricalCsv.parse(csv,ExportRow.SOURCE_CARELINK,zone)
        assertEquals(2,parsed.size)
        val parts=ReportBolus.read(parsed)
        assertEquals(1,parts.size)
        assertEquals(2.0,parts[0].immediate);assertEquals(3.0,parts[0].extended);assertFalse(parts[0].extensionDelivered)
        assertEquals(1_800_000,parts[0].end!!-parts[0].start)
        assertEquals(2.0,parts[0].cumulative(parts[0].start))
        assertEquals(3.5,parts[0].cumulative(parts[0].start+900_000))
        assertEquals(5.0,parts[0].cumulative(parts[0].end!!))
        assertEquals(2.0,parsed.sumOf { it.insulinU?:0.0 })
        assertNull(ReportBolus.read(parsed.map { it.copy(durationMin=null) })[0].end)
    }
}
