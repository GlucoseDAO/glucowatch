package glucowatch.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class ReportPresentationTest {
    private val day = LocalDate.of(2026, 1, 1)
    private val zone = ZoneOffset.UTC
    private fun at(d: Int, hour: Int = 0, minute: Int = 0, second: Int = 0) = day.plusDays(d.toLong()).atTime(hour, minute, second).toInstant(zone).toEpochMilli()
    private fun rate(d: Int, hour: Int, value: Double, minute: Int = 0, state: String? = null) = ExportRow(at(d, hour, minute), ExportRow.SOURCE_CARELINK, ExportRow.KIND_REPORTED_BASAL,
        basalRateUPerH = value, detail = "{\"Date\":\"2026/01/01\",\"Time\":\"00:00:00\"" + (state?.let { ",\"Suspend\":\"$it\"" } ?: "") + "}")
    private fun rows() = (0..2).flatMap { d -> listOf(rate(d, 0, 1.0), rate(d, 6, .8), rate(d, 12, 1.2)) }
    private fun build(rows: List<ExportRow>, days: Int = 3): ReportPresentation {
        val window = (ExportWindow.resolve(LatestDevice.MINIMED_PUMP, day, day.plusDays(days - 1L), days, day.plusDays(10)) as ExportRequest.Ready).window
        val readings = listOf(GlucoseReading(at(0, 1), 100, Trend.Flat), GlucoseReading(at(2, 1), 120, Trend.Flat))
        return ReportPresentation.build(GlucoseReport.build(readings, emptyList(), "Fixture", zone, at(10), window, rows))
    }
    @Test fun `repeated native profile and percentage temp follow schedule changes`() {
        val temp = ExportRow(at(1, 5, 35), ExportRow.SOURCE_CARELINK, ExportRow.KIND_TEMP_BASAL, basalPercent = 200.0, durationMin = 120.0)
        val m = build(rows() + temp)
        assertEquals(listOf(0, 360, 720), m.schedule.map { it.minuteOfDay })
        assertEquals(25.2, m.scheduledDailyUnits, 1e-9)
        fun value(h: Int, minute: Int) = m.basalTimeline.single { at(1, h, minute) in it.start until it.end }.programmedRate
        assertEquals(2.0, value(5, 45)); assertEquals(1.6, value(6, 30)); assertEquals(.8, value(7, 40))
        assertEquals(200.0, temp.basalPercent); assertNull(temp.insulinU)
    }
    @Test fun `suspend state survives a native row that also carries a basal rate`() {
        val m = build(rows() + rate(1, 7, 0.0, state = "USER_SUSPEND") + rate(1, 8, .8, state = "NORMAL_PUMPING"))
        assertEquals(ReportBasalChange.SUSPENDED, m.basalTimeline.single { at(1, 7, 30) in it.start until it.end }.change)
        assertEquals(0.0, m.basalTimeline.single { at(1, 7, 30) in it.start until it.end }.programmedRate)
        assertEquals(.8, m.basalTimeline.single { at(1, 8, 30) in it.start until it.end }.programmedRate)
    }
    @Test fun `native rate duplicates cannot discard suspend and resume states`() {
        val csv = "Date,Time,Basal Rate (U/h),Suspend\n2026/01/02,07:00:00,0,\n2026/01/02,07:00:00,0,USER_SUSPEND\n2026/01/02,08:00:00,0.8,NORMAL_PUMPING"
        val imported = HistoricalCsv.parse(csv, ExportRow.SOURCE_CARELINK, zone)
        assertEquals(2, imported.count { it.kind == ExportRow.KIND_SUSPEND })
        val m = build(rows() + imported)
        assertEquals(ReportBasalChange.SUSPENDED, m.basalTimeline.single { at(1, 7, 30) in it.start until it.end }.change)
        assertEquals(.8, m.basalTimeline.single { at(1, 8, 30) in it.start until it.end }.programmedRate)
    }
    @Test fun `five minute display does not quantize dose or program calculations`() {
        val exact = at(1, 5, 32, 20)
        val m = build(rows() + ExportRow(exact, ExportRow.SOURCE_CARELINK, ExportRow.KIND_TEMP_BASAL, basalRateUPerH = 2.0, durationMin = 17.0))
        val source = m.basalTimeline.first { it.change == ReportBasalChange.TEMPORARY }
        assertEquals(exact, source.start)
        assertTrue(m.displayBasal(day.plusDays(1)).all { (it.start - at(1)) % 300_000 == 0L && (it.end - at(1)) % 300_000 == 0L })
        assertEquals(at(1, 5, 30), m.displayBasal(day.plusDays(1)).first { it.change == ReportBasalChange.TEMPORARY }.start)
        assertEquals(17.0 / 60 * 2, m.programmedUnits(exact, exact + 17 * 60_000)!!, 1e-9)
    }
    @Test fun `a sparse live rate or ambiguous profile never supplies a full day schedule`() {
        val live = ExportRow(at(0), ExportRow.SOURCE_CARELINK, ExportRow.KIND_REPORTED_BASAL, basalRateUPerH = 1.0)
        assertTrue(build(listOf(live)).schedule.isEmpty())
        assertNull(build(listOf(live)).programmedUnits(at(0), at(1)))
        assertTrue(build(listOf(live)).basalProfile.all { it == null })
        assertTrue(build(rows() + (0..2).map { rate(it, 0, 2.0) }).schedule.isEmpty())
    }
    @Test fun `scheduled hourly units integrate changes inside the hour`() {
        val m = build(rows() + (0..2).map { rate(it, 6, 1.2, minute = 30) })
        assertEquals(1.0, m.scheduledUnits(6)!!, 1e-9)
        val display = m.displayBasal(day.plusDays(1)).filter { it.start >= at(1, 6) && it.end <= at(1, 7) }
        assertEquals(listOf(.8, 1.2), display.map { it.scheduledRate })
        assertNull(m.programmedUnits(at(5), at(6)))
    }
    @Test fun `an uncommon winning rate does not establish a recurring profile`() {
        val records = (0..4).flatMap { d -> listOf(rate(d, 0, if (d < 2) 1.0 else d.toDouble()), rate(d, 6, .8)) }
        assertTrue(build(records, days = 5).schedule.isEmpty())
    }
    @Test fun `hourly ranges include observed zeros but omit missing days and partial hours`() {
        val dose = ExportRow(at(0, 1, 10), ExportRow.SOURCE_CARELINK, ExportRow.KIND_BOLUS, insulinU = 2.0)
        val selected = dose.copy(timeMillis = at(0, 1, 5), insulinU = null, programmedFastU = 2.0)
        val m = build(listOf(rate(0, 0, 1.0), rate(2, 0, 1.0), rate(2, 10, 1.0), dose, selected))
        assertEquals(2, m.bolusProfile[1]!!.count)
        assertEquals(ReportRange(0.0, 1.0, 2.0, 2), m.bolusProfile[1])
        assertEquals(1, m.bolusProfile[10]!!.count)
        assertEquals(1, m.boluses.size)
        assertEquals(ReportCoverage(2, 2, 2, 3), m.coverage)
    }
}
