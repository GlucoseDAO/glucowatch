package glucowatch.core

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.*

class HistoricalExportTest {
    private val today = LocalDate.of(2026, 10, 13)
    private val since = LocalDate.of(2026, 9, 1)
    private fun window(content: ExportContent = ExportContent.SENSOR_AND_PUMP, incomplete: Boolean = false) =
        assertIs<ExportRequest.Ready>(ExportWindow.resolve(LatestDevice.MINIMED_PUMP, since, today, 90, today, content, incomplete)).window

    @Test fun `incomplete and single device exports preserve earlier requested dates`() {
        assertEquals(LocalDate.of(2026, 7, 16), window(incomplete = true).start)
        assertEquals(LocalDate.of(2026, 7, 16), window(ExportContent.SENSOR).start)
        assertEquals(LocalDate.of(2026, 7, 16), window(ExportContent.PUMP).start)
        assertEquals(since, window().start)
        assertIs<ExportRequest.Ready>(ExportWindow.resolve(LatestDevice.MINIMED_PUMP, since, since.minusDays(1), 5, today, allowIncomplete = true))
        assertIs<ExportRequest.Refused>(ExportWindow.resolve(LatestDevice.MINIMED_PUMP, since, today.plusDays(1), 1, today, allowIncomplete = true))
    }

    @Test fun `CSV quoting and units and explicit time zones are retained`() {
        val csv = """Index,Timestamp (YYYY-MM-DDThh:mm:ss),Event Type,Glucose Value (mmol/L),Insulin Value (u),Carb Value (grams),Note
1,2026-10-12T12:00:00,EGV,6.0,,,"quoted, note"
2,2026-10-12T12:05:00,Insulin,,2.5,,dose
3,2026-10-12T12:10:00,Carbs,,,25,meal
"""
        val rows = HistoricalCsv.parse(csv, ExportRow.SOURCE_DEXCOM_CLARITY, ZoneOffset.UTC)
        assertEquals(108, rows.first().glucoseMgdl)
        assertTrue(rows.first().detail!!.contains("quoted, note"))
        assertEquals(2.5, rows[1].insulinU)
        assertEquals(25.0, rows[2].carbsG)
        assertEquals(1, rows.count(ExportContent.SENSOR::includes))
        assertEquals(0, rows.count(ExportContent.PUMP::includes))
    }

    @Test fun `carelink rates are not converted into delivered doses`() {
        val csv = """Device info
Index,Date,Time,Sensor Glucose (mg/dL),Bolus Volume Delivered (U),Basal Rate (U/h),BG Reading (mg/dL),Raw-Type
1,12/10/2026,12:00:00,120,2.5,0.8,,Bolus
2,12/10/2026,12:05:00,,,,125,Meter
"""
        val rows = HistoricalCsv.parse(csv, ExportRow.SOURCE_CARELINK, ZoneOffset.UTC)
        assertEquals(4, rows.size)
        val basal = rows.single { it.kind == ExportRow.KIND_REPORTED_BASAL }
        assertEquals(0.8, basal.basalRateUPerH)
        assertNull(basal.insulinU)
        assertEquals(2, rows.count(ExportContent.SENSOR::includes))
        assertEquals(2, rows.count(ExportContent.PUMP::includes))
        assertEquals(2.5, rows.single { it.kind == ExportRow.KIND_BOLUS }.insulinU)
    }

    @Test fun `bad timestamps refuse the entire file rather than silently losing events`() {
        assertFailsWith<IllegalArgumentException> {
            HistoricalCsv.parse("Date,Time,Sensor Glucose (mg/dL)\n31/02/2026,12:00:00,120", ExportRow.SOURCE_CARELINK, ZoneOffset.UTC)
        }
        assertFailsWith<IllegalArgumentException> { HistoricalCsv.records("a,\"unfinished") }
    }

    @Test fun `CareLink year first slash dates and repeated device headings are imported`() {
        val header = "Index;Date;Time;Bolus Volume Delivered (U);Sensor Glucose (mg/dL);Raw-Type"
        val csv = "Synthetic metadata\n$header\n1,00000;2026/10/13;12:00:00;1.5;;Bolus\n\n" +
            "Synthetic device;Synthetic model;Synthetic serial;Synthetic firmware;Synthetic type\n" +
            "$header\n2,00000;2026/10/13;12:05:00;;125;Sensor\n"
        assertEquals(ExportRow.SOURCE_CARELINK, HistoricalCsv.source(csv))
        assertFalse(HistoricalCsv.inspectDateOrder(csv).remember)
        for (order in HistoricalCsv.DateOrder.entries) {
            val rows = HistoricalCsv.parse(csv, ExportRow.SOURCE_CARELINK, ZoneOffset.UTC, order)
            assertEquals(2, rows.size)
            assertEquals(1.5, rows.first().insulinU)
            assertEquals(125, rows.last().glucoseMgdl)
            assertEquals(LocalDate.of(2026, 10, 13).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), rows.first().timeMillis)
        }
    }

    @Test fun `invalid indexed CareLink event before repeated header is never treated as metadata`() {
        val header = "Index;Date;Time;Bolus Volume Delivered (U);Sensor Glucose (mg/dL);Raw-Type"
        assertFailsWith<IllegalArgumentException> {
            HistoricalCsv.parse("$header\n1,00000;2026/02/31;12:00:00\n$header\n2,00000;2026/10/13;12:05:00;;125;Sensor", ExportRow.SOURCE_CARELINK, ZoneOffset.UTC)
        }
    }

    @Test fun `report denominators and empty days use requested window including pump only`() {
        val w = window(ExportContent.PUMP)
        val time = since.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val report = GlucoseReport.build(emptyList(), listOf(Treatment(time, insulin = 9.0)), "CareLink", ZoneOffset.UTC,
            today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), window = w)
        assertEquals(90, report.summary.spanDays)
        assertEquals(90, report.days.size)
        assertEquals(0.1, report.summary.bolusPerDay, 0.0001)
        assertNull(report.summary.sensorPercent)
    }

    @Test fun `cached insulin and carbs at the same time both survive export`() {
        val rows = ExportRow.fromCareLinkCache(emptyList(), listOf(Treatment(1_000, insulin = 2.5, carbs = 30.0)), null)
        assertEquals(2.5, rows.single { it.kind == ExportRow.KIND_BOLUS }.insulinU)
        assertEquals(30.0, rows.single { it.kind == ExportRow.KIND_MEAL }.carbsG)
    }

    @Test fun `quoted semicolon CareLink headers are found after a long preamble`() {
        val csv = "Metadata\n".repeat(40) + "\"Index\";\"Date\";\"Time\";\"Sensor Glucose (mg/dL)\"\n1;12/10/2026;12:00:00;120\n"
        val rows = HistoricalCsv.parse(csv, ExportRow.SOURCE_CARELINK, ZoneOffset.UTC)
        assertEquals(120, rows.single().glucoseMgdl)
    }

    @Test fun `malformed Clarity responses cannot expose tokens in error messages`() {
        val client = ClarityClient(Region.US, HttpTransport { HttpResponse(200, "{\"accessToken\":secret-never-print") })
        val error = assertFailsWith<IllegalStateException> { client.csv("ABCDEFGH", window()) }
        assertFalse(error.message.orEmpty().contains("secret-never-print"))
    }

    @Test fun `Clarity download uses inclusive requested interval and never Share credentials`() {
        val requests = mutableListOf<HttpRequest>()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"subjectId\":\"subject123\"}".toByteArray())
        val client = ClarityClient(Region.OUS, HttpTransport { request ->
            requests += request
            when {
                request.url.contains("/redeem?") -> HttpResponse(200, "{\"accessToken\":\"header.$payload.signature\"}")
                request.url.endsWith("/user/sharing") -> HttpResponse(302, "", mapOf("Set-Cookie" to listOf("_rogue_material_session=synthetic; Secure; HttpOnly")))
                request.url.endsWith("/subject_info") -> HttpResponse(200, "{\"first_name\":\"Demo\",\"last_name\":\"Person\"}")
                else -> HttpResponse(200, "csv")
            }
        })
        assertEquals("csv", client.csv("ABCD-EFGH", window()))
        assertTrue(requests.all { it.url.startsWith("https://clarity.dexcom.eu/") })
        assertTrue(requests.last().body!!.contains("2026-09-01%2F2026-10-13"))
        assertEquals("_rogue_material_session=synthetic", requests.last().headers["Cookie"])
        assertEquals("header.$payload.signature", requests.last().headers["Access-Token"])
        assertTrue(requests.last().body!!.contains("firstName=Demo"))
        assertEquals(4, requests.size)
        assertFalse(requests.last().url.contains("accessToken"))
        assertFalse(requests.any { "Password" in it.url })
        val denied = ClarityClient(Region.US, HttpTransport { HttpResponse(403, "secret-server-response") })
        assertFalse(assertFailsWith<IllegalArgumentException> { denied.csv("ABCDEFGH", window()) }.message!!.contains("secret-server-response"))
    }
}
