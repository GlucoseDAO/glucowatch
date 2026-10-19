package glucowatch.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class ExportNamesTest {
    @Test fun `historical defaults use yesterday even on a month boundary`() {
        assertEquals(LocalDate.of(2026,9,30),ExportNames.lastCompletedDay(LocalDate.of(2026,10,1)))
        assertEquals("glucophone-2026-10-01_to_2026-10-18-cgm-pump-workouts",
            ExportNames.report(LocalDate.of(2026,10,1),LocalDate.of(2026,10,18),ExportContent.SENSOR_AND_PUMP,2))
        val rows=listOf(ExportRow(86_400_000L,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS),ExportRow(0L,ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS))
        assertEquals("1970-01-01_to_1970-01-02",ExportNames.interval(rows,ZoneOffset.UTC))
        assertNull(ExportNames.interval(emptyList(),ZoneOffset.UTC))
    }
}
