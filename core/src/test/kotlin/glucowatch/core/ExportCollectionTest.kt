package glucowatch.core

import kotlin.test.*

class ExportCollectionTest {
    @Test fun ninetyDaysCollectsThreeDistinctPumpIntervalsBeforeClarity() {
        val end = java.time.LocalDate.of(2026, 10, 20)
        val window = (ExportWindow.resolve(LatestDevice.DEXCOM_SENSOR, end.minusDays(100), end, 90, end) as ExportRequest.Ready).window
        val c = ExportCollection(window.content, window.device, window)
        assertEquals(ExportPortal.CARELINK, c.next)
        val expected = listOf(end.minusDays(29) to end, end.minusDays(59) to end.minusDays(30), end.minusDays(89) to end.minusDays(60))
        expected.forEachIndexed { index, (start, until) ->
            assertEquals(ExportDates(start, until), c.target(ExportPortal.CARELINK))
            assertEquals(ExportPortal.CARELINK, c.next)
            assertTrue(c.accept(ExportPortal.CARELINK, index))
            assertFalse(c.accept(ExportPortal.CARELINK, index))
        }
        assertEquals(ExportPortal.CLARITY, c.next)
        assertEquals(ExportDates(window.start, window.end), c.target(ExportPortal.CLARITY))
        c.accept(ExportPortal.CLARITY); assertTrue(c.ready)
    }
    @Test fun restoredAndPartialIntervalsKeepTheirPlace() {
        val end = java.time.LocalDate.of(2026, 3, 1)
        val window = (ExportWindow.resolve(LatestDevice.MINIMED_PUMP, end.minusDays(100), end, 31, end, ExportContent.PUMP) as ExportRequest.Ready).window
        val c = ExportCollection(window.content, window.device, window)
        c.accept(ExportPortal.CARELINK, 1)
        assertEquals(30, c.target(ExportPortal.CARELINK)!!.days)
        assertFalse(c.ready)
        c.accept(ExportPortal.CARELINK, 0); assertTrue(c.ready)
        assertFailsWith<IllegalArgumentException> { c.accept(ExportPortal.CARELINK, 2) }
    }
    @Test fun filesAdvanceByInternalEventsRatherThanNameAndRepeatedChunksDoNotCount() {
        val end = java.time.LocalDate.of(2026, 10, 20); val zone = java.time.ZoneOffset.UTC
        val window = (ExportWindow.resolve(LatestDevice.MINIMED_PUMP, end.minusDays(100), end, 90, end) as ExportRequest.Ready).window
        val c = ExportCollection(window.content, window.device, window)
        fun event(day: java.time.LocalDate, units: Double=1.0) = ExportRow(day.atTime(12,0).toInstant(zone).toEpochMilli(),ExportRow.SOURCE_CARELINK,ExportRow.KIND_BOLUS,insulinU=units)
        val first = listOf(event(end))
        assertEquals(0,c.validate(ExportPortal.CARELINK,first,emptyList(),zone)); c.accept(ExportPortal.CARELINK,0)
        assertFailsWith<IllegalArgumentException> { c.validate(ExportPortal.CARELINK,first,first,zone) }
        val next = listOf(event(end.minusDays(30)))
        assertEquals(1,c.validate(ExportPortal.CARELINK,next,first,zone))
        // Already imported overlap in the target plus new events outside it cannot count as a new chunk.
        assertFailsWith<IllegalArgumentException> { c.validate(ExportPortal.CARELINK,next + event(end,2.0),first+next,zone) }
        assertFalse(c.ready)
    }
    @Test fun combinedNeedsBothAndDuplicatePumpDoesNotReopenIt() {
        val collection = ExportCollection(ExportContent.SENSOR_AND_PUMP, LatestDevice.MINIMED_PUMP)
        assertEquals(ExportPortal.CARELINK, collection.next)
        assertTrue(collection.accept(ExportPortal.CARELINK))
        assertFalse(collection.ready)
        assertEquals(ExportPortal.CLARITY, collection.next)
        assertFalse(collection.accept(ExportPortal.CARELINK))
        assertEquals(ExportPortal.CLARITY, collection.next)
        collection.accept(ExportPortal.CLARITY)
        assertTrue(collection.ready); assertNull(collection.next)
    }
    @Test fun sourceIdentityAllowsOutOfOrderFilesAndRejectsUnselectedSources() {
        val collection = ExportCollection(ExportContent.SENSOR_AND_PUMP, LatestDevice.MINIMED_PUMP)
        collection.accept(ExportPortal.CLARITY)
        assertEquals(ExportPortal.CARELINK, collection.next)
        val sensor = ExportCollection(ExportContent.SENSOR, LatestDevice.MINIMED_PUMP)
        assertFailsWith<IllegalArgumentException> { sensor.accept(ExportPortal.CARELINK) }
        sensor.accept(ExportPortal.CLARITY); assertTrue(sensor.ready)
    }
}
