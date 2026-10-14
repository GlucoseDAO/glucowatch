package glucowatch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthExportTest {
    private val now = NightscoutClient.parseTime("2026-01-02T00:00:00Z")!!

    private fun patient() = Json.parseToJsonElement("""
        {
          "lastConduitDateTime":"2026-01-01T00:00:00.000Z",
          "lastConduitUpdateServerDateTime":"2026-01-01T00:00:00.000Z",
          "pumpModelNumber":"MMT-1880",
          "reservoirRemainingUnits":"42.5",
          "sgs":[
            {"sg":0,"timestamp":"2026-01-01T00:05:00.000Z","sensorState":"WARM_UP"},
            {"sg":110,"timestamp":"2026-01-01T00:10:00.000Z"}
          ],
          "markers":[
            {"type":"INSULIN","timestamp":"2026-01-01T00:20:00.000Z","data":{"dataValues":{
              "deliveredFastAmount":"2.5","programmedFastAmount":"3","deliveredExtendedAmount":"0.5",
              "programmedExtendedAmount":"1","activationType":"RECOMMENDED","bolusType":"DUAL","id":"bolus-1"}}},
            {"type":"CALIBRATION","timestamp":"2026-01-01T00:30:00.000Z","data":{"dataValues":{"amount":"108","unit":"mg/dL"}}},
            {"type":"SITE_CHANGE","timestamp":"2026-01-01T00:40:00.000Z","data":{"dataValues":{"note":"left arm, ok"}}},
            {"type":"AUTO_BASAL_DELIVERY","timestamp":"2026-01-01T00:45:00.000Z","data":{"dataValues":{"bolusAmount":"0.025"}}}
          ],
          "activeInsulin":{"amount":1.2,"datetime":"2026-01-01T00:10:00.000Z"},
          "basal":{"basalRate":0.8}
        }
    """.trimIndent()) as JsonObject

    @Test
    fun `carelink rows keep every extracted field and the same clock as the chart`() {
        val data = CareLinkClient.parse(patient(), now)
        val rows = data.exportRows
        val gap = rows.single { it.kind == ExportRow.KIND_GLUCOSE && it.glucoseMgdl == null }
        assertEquals(0.0, gap.value)
        assertEquals("WARM_UP", gap.sensorState)
        assertEquals(110, rows.single { it.glucoseMgdl == 110 }.glucoseMgdl)
        val bolus = rows.single { it.kind == ExportRow.KIND_BOLUS }
        assertEquals(data.treatments.single { it.isBolus }.timeMillis, bolus.timeMillis)
        assertEquals(3.0, bolus.insulinU)
        assertEquals(3.0, bolus.programmedFastU)
        assertEquals(2.5, bolus.deliveredFastU)
        assertEquals(1.0, bolus.programmedExtendedU)
        assertEquals(0.5, bolus.deliveredExtendedU)
        assertEquals(false, bolus.automatic)
        assertEquals("RECOMMENDED", bolus.activationType)
        assertEquals("DUAL", bolus.bolusType)
        assertTrue(bolus.detail!!.contains("bolus-1"))
        val calibration = rows.single { it.kind == ExportRow.KIND_CALIBRATION }
        assertEquals(108.0, calibration.value)
        assertEquals("mg/dL", calibration.valueUnit)
        val other = rows.single { it.kind == ExportRow.KIND_OTHER }
        assertEquals("SITE_CHANGE", other.markerType)
        assertTrue(other.detail!!.contains("left arm, ok"))
        assertEquals(0.025, rows.single { it.kind == ExportRow.KIND_BASAL_DELIVERY }.insulinU)
        assertEquals(1.2, rows.single { it.kind == ExportRow.KIND_IOB }.iobU)
        assertEquals(0.8, rows.single { it.kind == ExportRow.KIND_REPORTED_BASAL }.basalRateUPerH)
        assertTrue(rows.single { it.kind == ExportRow.KIND_DEVICE }.detail!!.contains("MMT-1880"))
        assertTrue(rows.single { it.kind == ExportRow.KIND_DEVICE }.detail!!.contains("42.5"))
    }

    @Test
    fun `csv quotes commas and leaves a rate distinct from a dose`() {
        val text = ExportCsv.write(CareLinkClient.parse(patient(), now).exportRows, ZoneOffset.UTC)
        assertTrue(text.startsWith(ExportCsv.COLUMNS.joinToString(",")))
        assertTrue(text.contains("\"{\"\"note\"\":\"\"left arm, ok\"\"}\""))
        val basal = text.lines().single { it.contains(",reported_basal,") }
        assertTrue(basal.contains(",0.8,"))
        assertTrue(!basal.contains(",0.8,BOLUS,"))
    }

    @Test
    fun `a fresh payload replaces the last day and the chart cache fills older holes`() {
        val older = ExportRow(now - 2 * SourceSync.DAY_MS, ExportRow.SOURCE_CARELINK, ExportRow.KIND_BOLUS, insulinU = 1.0, markerType = "INSULIN")
        val recent = ExportRow(now - 3_600_000L, ExportRow.SOURCE_CARELINK, ExportRow.KIND_BOLUS, insulinU = 2.0, markerType = "INSULIN")
        val fresh = ExportRow(now - 3_600_000L, ExportRow.SOURCE_CARELINK, ExportRow.KIND_BOLUS, insulinU = 4.0,
            programmedFastU = 4.0, markerType = "INSULIN")
        val device = ExportRow(now, ExportRow.SOURCE_CARELINK, ExportRow.KIND_DEVICE, detail = "{}")
        val rolled = ExportLog.roll(listOf(older, recent, device), listOf(fresh, device), now, retainMs = 14L * 24 * 3_600_000L)
        assertEquals(listOf(1.0, 4.0), rolled.map { it.insulinU })
        assertTrue(rolled.none { it.kind == ExportRow.KIND_DEVICE })
        val fallback = ExportRow.fromCareLinkCache(
            listOf(GlucoseReading(500L, 99, Trend.Flat)),
            listOf(Treatment(1_000L, insulin = 1.0)),
            null,
        )
        val merged = ExportLog.prefer(listOf(fresh), fallback)
        assertEquals(4.0, merged.single { it.timeMillis == fresh.timeMillis }.insulinU)
        assertEquals(99, merged.single { it.kind == ExportRow.KIND_GLUCOSE }.glucoseMgdl)
        assertEquals(1.0, merged.single { it.timeMillis == 1_000L }.insulinU)
    }
}

class GlucoseReportTest {
    @Test
    fun `bins do not overlap and gmi follows the published line`() {
        val start = NightscoutClient.parseTime("2026-01-05T00:00:00Z")!!
        val readings = listOf(50, 60, 100, 100, 200, 300).mapIndexed { index, mgdl ->
            GlucoseReading(start + index * 3_600_000L, mgdl, Trend.Flat)
        }
        val pump = listOf(
            Treatment(start, insulin = 2.0),
            Treatment(start + 3_600_000L, insulin = 1.0, automatic = true),
            Treatment(start, insulin = 0.5, insulinKind = InsulinKind.BASAL, automatic = true),
            Treatment(start, carbs = 40.0),
        )
        val report = GlucoseReport.build(readings, pump, "Dexcom Share", ZoneOffset.UTC, now = start + 10 * 3_600_000L)
        val ranges = report.summary.ranges
        assertEquals(1, ranges.veryLow)
        assertEquals(1, ranges.low)
        assertEquals(2, ranges.inRange)
        assertEquals(1, ranges.high)
        assertEquals(1, ranges.veryHigh)
        assertEquals(135.0, report.summary.mean)
        assertEquals(100.0 / 3.0, report.summary.automaticBolusPercent!!, 0.0001)
        assertEquals(6.5392, report.summary.gmi!!, 0.0001)
        assertEquals(3.0, report.summary.bolusPerDay)
        assertEquals(0.5, report.summary.basalPerDay)
        assertEquals(40.0, report.summary.carbsPerDay)
        assertEquals(3.0, GlucoseReport.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0.5))
        assertNull(GlucoseReport.percentile(emptyList(), 0.5))
        val monday = report.weekdays.first { it.label == "Mon" }
        assertEquals(6, monday.n)
    }
}

class CareLinkExportCacheTest {
    private fun jwt(sub: String) = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub"}""".toByteArray()) + ".sig"

    private class MemoryLogin(var token: CareLinkToken?) : CareLinkLogin {
        override fun load() = token
        override fun replace(old: CareLinkToken, new: CareLinkToken) {
            if (token?.refreshToken == old.refreshToken) token = new
        }
    }

    private class MapCache : SyncCache {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(values: Map<String, String?>) {
            values.forEach { (key, value) -> if (value == null) map.remove(key) else map[key] = value }
        }
    }

    @Test
    fun `only a history longer than a day stores the export log`() {
        val now = NightscoutClient.parseTime("2026-01-02T00:00:00Z")!!
        val body = """{"patientData":{
          "lastConduitDateTime":"2026-01-01T00:00:00.000Z","lastConduitUpdateServerDateTime":"2026-01-01T00:00:00.000Z",
          "sgs":[{"sg":120,"timestamp":"2026-01-01T12:00:00.000Z"}],
          "markers":[{"type":"INSULIN","timestamp":"2026-01-01T12:05:00.000Z","data":{"dataValues":{"deliveredFastAmount":"1.5","programmedFastAmount":"2"}}}]
        }}"""
        val disco = """{"supportedCountries":[{"DE":{"region":"EU"}}],"CP":[{"region":"EU","UseSSOConfiguration":"Auth0SSOConfiguration",
          "SSOConfiguration":"https://old/sso.json","Auth0SSOConfiguration":"https://cfg.example/auth0.json",
          "baseUrlCareLink":"https://cl.example/api/carepartner/v2","baseUrlCumulus":"https://cloud.example/connect/carepartner/v13"}]}"""
        val sso = """{"server":{"hostname":"login.example","port":443,"prefix":""},"system_endpoints":{"token_endpoint_path":"/oauth/token"}}"""
        val transport = HttpTransport { request ->
            when {
                request.url.startsWith(CareLinkClient.DISCOVERY_URL) -> HttpResponse(200, disco)
                request.url == "https://cfg.example/auth0.json" -> HttpResponse(200, sso)
                request.url.endsWith("/users/me") -> HttpResponse(200, """{"role":"PATIENT","username":"person"}""")
                request.url.endsWith("/display/message") -> HttpResponse(200, body)
                else -> HttpResponse(404, "")
            }
        }
        val token = CareLinkToken("DE", "client", jwt("auth0|abc"), "refresh", Long.MAX_VALUE)
        val phone = MapCache()
        SourceSync(phone, transport, retainMs = 2 * SourceSync.DAY_MS).fetch(SourceAccount.CareLink(MemoryLogin(token)), 6, now)
        val rows = ExportLog.decode(phone.map.getValue(SourceSync.CARELINK_EXPORT))
        assertEquals(1.5, rows.single { it.kind == ExportRow.KIND_BOLUS }.deliveredFastU)
        assertEquals(2.0, rows.single { it.kind == ExportRow.KIND_BOLUS }.programmedFastU)
        val watch = MapCache()
        SourceSync(watch, transport, retainMs = SourceSync.DAY_MS).fetch(SourceAccount.CareLink(MemoryLogin(token)), 6, now)
        assertNull(watch.map[SourceSync.CARELINK_EXPORT])
    }
}
