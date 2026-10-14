package glucowatch.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExportDatesTest {
    @Test fun bothDatesAreIncludedAndLeapDaysAreCounted() {
        val dates = ExportDates(LocalDate.parse("2024-02-28"), LocalDate.parse("2024-03-01"))
        assertEquals(3, dates.days)
        assertEquals(1, dates.withStart(dates.end).days)
    }

    @Test fun eitherCalendarUpdatesTheDayCount() {
        val dates = ExportDates.endingOn(LocalDate.parse("2026-10-14"), 14)
        assertEquals(7, dates.withStart(LocalDate.parse("2026-10-08")).days)
        assertEquals(10, dates.withEnd(LocalDate.parse("2026-10-10")).days)
    }

    @Test fun editingDaysPreservesEndDate() {
        val end = LocalDate.parse("2026-10-14")
        val dates = ExportDates.endingOn(end, 14).withDays(30)
        assertEquals(end, dates.end)
        assertEquals(LocalDate.parse("2026-09-15"), dates.start)
        assertEquals(30, dates.days)
    }

    @Test fun invalidIntervalsAreRefused() {
        val day = LocalDate.parse("2026-10-14")
        assertFailsWith<IllegalArgumentException> { ExportDates(day, day.minusDays(1)) }
        assertFailsWith<IllegalArgumentException> { ExportDates.endingOn(day, 0) }
        assertFailsWith<IllegalArgumentException> { ExportDates.endingOn(day, 91) }
        assertEquals(90, ExportDates.endingOn(day, 90).days)
    }

    @Test fun dayCountCanKeepTheStartDateChosenOnItsCalendar() {
        val dates = ExportDates.endingOn(LocalDate.parse("2026-10-14"), 30)
            .withStart(LocalDate.parse("2026-09-20")).withDays(10, keepStart = true)
        assertEquals(LocalDate.parse("2026-09-20"), dates.start)
        assertEquals(LocalDate.parse("2026-09-29"), dates.end)
        assertEquals(10, dates.days)
        assertEquals(LocalDate.parse("2026-09-29"), dates.withDays(7).end)
    }
}
