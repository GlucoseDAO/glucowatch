package glucowatch.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class WorkoutReportRangeTest {
    private fun activity(id: String, start: String, end: String) = Workout(id, "Fixture", "Swim",
        Instant.parse(start).toEpochMilli(), Instant.parse(end).toEpochMilli(), null, null, null, null, null, emptyList())

    @Test fun coversEveryActivityInLocalTimeIncludingMidnightAndDst() {
        val workouts = listOf(activity("a", "2026-10-24T20:00:00Z", "2026-10-24T21:30:00Z"),
            activity("b", "2026-10-25T00:00:00Z", "2026-10-25T03:30:00Z"))
        val range = WorkoutReportRange.covering(workouts.reversed() + workouts.first(), ZoneId.of("Europe/Bucharest"))
        assertEquals(LocalDate.parse("2026-10-24"), range.first)
        assertEquals(LocalDate.parse("2026-10-25"), range.last)
        range.validate(range.first.minusDays(1), range.last.plusDays(1))
        assertFailsWith<IllegalArgumentException> { range.validate(range.first.plusDays(1), range.last) }
        assertFailsWith<IllegalArgumentException> { range.validate(range.first, range.last.minusDays(1)) }
    }
    @Test fun rejectsEmptySelectionsAndWindowsLongerThanNinetyDays() {
        assertFailsWith<IllegalArgumentException> { WorkoutReportRange.covering(emptyList(), ZoneId.of("UTC")) }
        val one = activity("a", "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z")
        val far = activity("b", "2026-04-01T10:00:00Z", "2026-04-01T11:00:00Z")
        assertFailsWith<IllegalArgumentException> { WorkoutReportRange.covering(listOf(one, far), ZoneId.of("UTC")) }
        val range = WorkoutReportRange.covering(listOf(one), ZoneId.of("UTC"))
        range.validate(range.first, range.first.plusDays(89))
        assertFailsWith<IllegalArgumentException> { range.validate(range.first, range.first.plusDays(90)) }
    }
}
