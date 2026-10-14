package glucowatch.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExportWindowTest {
    private val today = LocalDate.of(2026, 10, 13)
    private val pumpSince = LocalDate.of(2026, 9, 1)

    @Test
    fun `an interval inside the device life is kept`() {
        val ready = ExportWindow.resolve(LatestDevice.MINIMED_PUMP, pumpSince, today, 14, today)
        val window = assertIs<ExportRequest.Ready>(ready).window
        assertEquals(LocalDate.of(2026, 9, 30), window.start)
        assertEquals(today, window.end)
        assertFalse(window.clipped)
    }

    @Test
    fun `days before the device started are clipped to that start`() {
        val ready = ExportWindow.resolve(LatestDevice.MINIMED_PUMP, pumpSince, today, 90, today)
        val window = assertIs<ExportRequest.Ready>(ready).window
        assertEquals(pumpSince, window.start)
        assertEquals(today, window.end)
        assertEquals(LocalDate.of(2026, 7, 16), window.requestedStart)
        assertTrue(window.clipped)
    }

    @Test
    fun `an end date before the device start refuses the export`() {
        val end = LocalDate.of(2026, 8, 1)
        val refused = ExportWindow.resolve(LatestDevice.DEXCOM_SENSOR, pumpSince, end, 30, today)
        assertEquals(
            "No export. The interval ends on 2026-08-01, which is before the Dexcom sensor start on 2026-09-01.",
            assertIs<ExportRequest.Refused>(refused).message,
        )
    }

    @Test
    fun `the device start day itself is a one-day export when the request is longer`() {
        val ready = ExportWindow.resolve(LatestDevice.MINIMED_PUMP, pumpSince, pumpSince, 90, today)
        val window = assertIs<ExportRequest.Ready>(ready).window
        assertEquals(pumpSince, window.start)
        assertEquals(pumpSince, window.end)
        assertTrue(window.clipped)
    }

    @Test
    fun `day counts outside 1 to 90 and a future end are refused`() {
        assertIs<ExportRequest.Refused>(ExportWindow.resolve(LatestDevice.MINIMED_PUMP, pumpSince, today, 0, today))
        assertIs<ExportRequest.Refused>(ExportWindow.resolve(LatestDevice.MINIMED_PUMP, pumpSince, today, 91, today))
        assertIs<ExportRequest.Refused>(
            ExportWindow.resolve(LatestDevice.MINIMED_PUMP, pumpSince, today.plusDays(1), 7, today),
        )
    }

    @Test
    fun `contains is inclusive of the start and end days in the given zone`() {
        val window = (ExportWindow.resolve(LatestDevice.MINIMED_PUMP, pumpSince, today, 14, today) as ExportRequest.Ready).window
        val zone = ZoneOffset.UTC
        val start = window.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val before = window.start.minusDays(1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        val end = window.end.atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        assertTrue(window.contains(start, zone))
        assertFalse(window.contains(before, zone))
        assertTrue(window.contains(end, zone))
    }
}
