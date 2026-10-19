package glucowatch.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Calendar dates for the actual selected activities, independent of their download dates. */
data class WorkoutReportRange(val first: LocalDate, val last: LocalDate) {
    fun validate(start: LocalDate, end: LocalDate) {
        require(!start.isAfter(first) && !end.isBefore(last)) { "The report dates must cover every selected activity ($first to $last)." }
        require(!end.isBefore(start) && ChronoUnit.DAYS.between(start, end) < 90) { "Choose a covering interval of 1 to 90 calendar days." }
    }
    companion object {
        fun covering(workouts: List<Workout>, zone: ZoneId): WorkoutReportRange {
            require(workouts.isNotEmpty() && workouts.distinctBy { it.id }.size <= 50) { "Select 1 to 50 activities." }
            val range = WorkoutReportRange(
                Instant.ofEpochMilli(workouts.minOf { it.startMillis }).atZone(zone).toLocalDate(),
                Instant.ofEpochMilli(workouts.maxOf { it.endMillis }).atZone(zone).toLocalDate())
            range.validate(range.first, range.last)
            return range
        }
    }
}
