package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExportPortalTest {
    @Test fun captureBridgeIsConfinedToCsvProviderOrigins() {
        assertEquals(setOf("https://clarity.dexcom.eu"), ExportPortal.CLARITY.captureOrigins("RO", Region.OUS))
        assertEquals(setOf("https://clarity.dexcom.com"), ExportPortal.CLARITY.captureOrigins("US", Region.US))
        assertEquals(setOf("https://clarity.dexcom.jp"), ExportPortal.CLARITY.captureOrigins("JP", Region.JP))
        val origins = ExportPortal.CARELINK.captureOrigins("RO", Region.OUS)
        assertTrue("https://*.minimed.eu" in origins)
        assertFalse("*" in origins)
        assertTrue(origins.all { it.startsWith("https://") })
    }

    @Test fun latestSelectedDeviceOpensFirst() {
        assertEquals(listOf(ExportPortal.CARELINK, ExportPortal.CLARITY), ExportPortal.sequence(ExportContent.SENSOR_AND_PUMP, LatestDevice.MINIMED_PUMP))
        assertEquals(listOf(ExportPortal.CLARITY, ExportPortal.CARELINK), ExportPortal.sequence(ExportContent.SENSOR_AND_PUMP, LatestDevice.DEXCOM_SENSOR))
        assertEquals(listOf(ExportPortal.CARELINK), ExportPortal.sequence(ExportContent.PUMP, LatestDevice.DEXCOM_SENSOR))
        assertEquals(listOf(ExportPortal.CLARITY), ExportPortal.sequence(ExportContent.SENSOR, LatestDevice.MINIMED_PUMP))
    }

    @Test fun opensTheSelectedRegionalServer() {
        assertEquals("https://clarity.dexcom.com", ExportPortal.CLARITY.url("RO", Region.US))
        assertEquals("https://clarity.dexcom.eu", ExportPortal.CLARITY.url("RO", Region.OUS))
        assertEquals("https://clarity.dexcom.jp", ExportPortal.CLARITY.url("JP", Region.JP))
        assertEquals("https://carelink.minimed.com", ExportPortal.CARELINK.url("us", Region.OUS))
        assertEquals("https://carelink.minimed.eu", ExportPortal.CARELINK.url("RO", Region.OUS))
    }

    @Test fun downloadsCannotCrossClarityRegionsOrUseLookalikeHosts() {
        assertTrue(ExportPortal.CLARITY.permits("https://clarity.dexcom.eu/export", "RO", Region.OUS, true))
        assertFalse(ExportPortal.CLARITY.permits("https://clarity.dexcom.com/export", "RO", Region.OUS, true))
        assertTrue(ExportPortal.CLARITY.permits("https://uam2.dexcom.com/login", "RO", Region.OUS))
        for (url in listOf("https://minimed.eu.evil.com/export", "https://evilminimed.eu/export", "http://carelink.minimed.eu/export", "https://user@carelink.minimed.eu/export", "https://carelink.minimed.eu:8443/export", "file:///tmp/data.csv")) {
            assertFalse(ExportPortal.CARELINK.permits(url, "RO", Region.OUS, true))
        }
    }

    @Test fun aDownloadedCsvMustMatchItsSelectedService() {
        assertFailsWith<IllegalArgumentException> {
            HistoricalCsv.parse("Date,Time,Bolus Volume Delivered (U)\n13/10/2026,12:00:00,2\n",
                ExportRow.SOURCE_DEXCOM_CLARITY, java.time.ZoneId.of("UTC"))
        }
        assertFailsWith<IllegalArgumentException> {
            HistoricalCsv.parse("Timestamp (YYYY-MM-DDThh:mm:ss),Glucose Value (mg/dL)\n2026-10-13T12:00:00,125\n",
                ExportRow.SOURCE_CARELINK, java.time.ZoneId.of("UTC"))
        }
    }

    @Test fun carelinkDatesAcceptSingleDigitDaysAndMonthsWithExplicitOrder() {
        val csv = "Date,Time,Bolus Volume Delivered (U)\n1/2/2026,12:00:00,2\n"
        val zone = java.time.ZoneId.of("UTC")
        val dayFirst = HistoricalCsv.parse(csv, ExportRow.SOURCE_CARELINK, zone, HistoricalCsv.DateOrder.DAY_FIRST).single()
        val monthFirst = HistoricalCsv.parse(csv, ExportRow.SOURCE_CARELINK, zone, HistoricalCsv.DateOrder.MONTH_FIRST).single()
        assertEquals(java.time.Instant.parse("2026-02-01T12:00:00Z").toEpochMilli(), dayFirst.timeMillis)
        assertEquals(java.time.Instant.parse("2026-01-02T12:00:00Z").toEpochMilli(), monthFirst.timeMillis)
    }
}
