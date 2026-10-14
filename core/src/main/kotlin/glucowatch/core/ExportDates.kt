package glucowatch.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Inclusive calendar dates; the most recently selected endpoint can anchor day-count edits. */
data class ExportDates(val start: LocalDate, val end: LocalDate) {
    val days: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()

    init {
        require(!start.isAfter(end)) { "Start date must be on or before end date." }
        require(ChronoUnit.DAYS.between(start, end) < ExportWindow.MAX_DAYS) { "Choose at most 90 days." }
    }

    fun withStart(day: LocalDate) = ExportDates(day, end)
    fun withEnd(day: LocalDate) = ExportDates(start, day)
    fun withDays(count: Int, keepStart: Boolean = false): ExportDates {
        require(count in ExportWindow.MIN_DAYS..ExportWindow.MAX_DAYS) { "Choose 1 to 90 days." }
        return if (keepStart) ExportDates(start, start.plusDays(count - 1L)) else endingOn(end, count)
    }

    companion object {
        fun endingOn(end: LocalDate, days: Int): ExportDates {
            require(days in ExportWindow.MIN_DAYS..ExportWindow.MAX_DAYS) { "Choose 1 to 90 days." }
            return ExportDates(end.minusDays(days - 1L), end)
        }
    }
}
