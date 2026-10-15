package glucowatch.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class HistoricalDateOrderTest {
    private fun csv(dates: List<String>) = "Date,Time,Bolus Volume Delivered (U)\n" + dates.joinToString("\n") { "$it,12:00:00,1.5" }
    @Test fun scansLongFileAcrossMonthsAndLeapDayInBothFormats() {
        val start = LocalDate.of(2024, 1, 1)
        val days = (0L..89L).map(start::plusDays)
        for (order in HistoricalCsv.DateOrder.entries) {
            val text = csv(days.map { if (order == HistoricalCsv.DateOrder.DAY_FIRST) "${it.dayOfMonth}/${it.monthValue}/${it.year}" else "${it.monthValue}/${it.dayOfMonth}/${it.year}" })
            assertEquals(order, HistoricalCsv.inferDateOrder(text))
            assertEquals(90, HistoricalCsv.parse(text, ExportRow.SOURCE_CARELINK, ZoneOffset.UTC, order).size)
            val dashed = text.replace('/', '-')
            assertEquals(order, HistoricalCsv.inferDateOrder(dashed))
            assertEquals(90, HistoricalCsv.parse(dashed, ExportRow.SOURCE_CARELINK, ZoneOffset.UTC, order).size)
        }
    }
    @Test fun decisiveDateCanBeOutsideSelectedIntervalAndAfterAmbiguousRows() {
        assertEquals(HistoricalCsv.DateOrder.MONTH_FIRST, HistoricalCsv.inferDateOrder(csv(List(100) { "10/01/2026" } + "10/14/2026"), LocalDate.of(2026,10,1),LocalDate.of(2026,10,2)))
    }
    @Test fun selectedRangeDisambiguatesShortFilesWithoutGuessing() {
        assertEquals(HistoricalCsv.DateOrder.DAY_FIRST, HistoricalCsv.inferDateOrder(csv(listOf("01/10/2026", "02/10/2026")), LocalDate.of(2026,10,1),LocalDate.of(2026,10,2)))
        assertNull(HistoricalCsv.inferDateOrder(csv(listOf("01/02/2026"))))
        assertEquals(HistoricalCsv.DateOrder.DAY_FIRST, HistoricalCsv.inferDateOrder(csv(listOf("02/02/2026"))))
        assertFalse(HistoricalCsv.inspectDateOrder(csv(listOf("02/02/2026"))).remember)
    }
    @Test fun mixedOrImpossibleDatesAreRefusedAndSourceComesFromHeaders() {
        assertFailsWith<IllegalArgumentException> { HistoricalCsv.inferDateOrder(csv(listOf("14/10/2026", "10/14/2026"))) }
        assertFailsWith<IllegalArgumentException> { HistoricalCsv.inferDateOrder(csv(listOf("31/02/2026"))) }
        assertEquals(ExportRow.SOURCE_CARELINK, HistoricalCsv.source(csv(listOf("14/10/2026"))))
        assertEquals(ExportRow.SOURCE_DEXCOM_CLARITY, HistoricalCsv.source("Timestamp (YYYY-MM-DDThh:mm:ss),Glucose Value (mg/dL)\n2026-10-14T12:00:00,125"))
    }
}
