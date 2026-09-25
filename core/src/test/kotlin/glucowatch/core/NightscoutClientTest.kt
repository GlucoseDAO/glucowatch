package glucowatch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightscoutClientTest {
    private class FakeTransport(val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun HttpRequest.query(name: String): String? =
        url.substringAfter('?', "").split('&').firstOrNull { URLDecoder.decode(it.substringBefore('='), "UTF-8") == name }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }

    // Trimmed from a Nightscout 15.0.3 site fed by iAPS and an Ottai sensor.
    private val entriesJson = """
        [{"_id":"6ab655d2","date":1790334414821,"dateString":"2026-09-25T11:06:54.000Z","device":"Ottai","direction":"FortyFiveDown","sgv":207,"type":"sgv","mills":1790334414000},
         {"_id":"6ab654a6","date":1790334114821,"dateString":"2026-09-25T11:01:54.000Z","direction":"NOT COMPUTABLE","sgv":212,"type":"sgv"},
         {"_id":"6ab6537b","date":1790333814821,"sgv":"218","trend":4,"type":"sgv"},
         {"_id":"err","date":1790333514821,"sgv":5,"type":"sgv"}]
    """.trimIndent()

    private val treatmentsJson = """
        [{"eventType":"Temp Basal","created_at":"2026-09-25T10:07:47.871Z","absolute":1.6,"rate":1.6,"duration":30,"carbs":null,"insulin":null},
         {"eventType":"Bolus","insulin":3.6,"created_at":"2026-09-25T08:07:29.000Z","bolus":{"isSMB":false,"amount":3.6},"carbs":null},
         {"eventType":"Carb Correction","carbs":61,"created_at":"2026-09-25T05:57:00.000Z","insulin":null},
         {"eventType":"SMB","insulin":0.2,"created_at":"2026-09-24T16:22:42.200Z","carbs":null},
         {"eventType":"Correction Bolus","insulin":0.3,"date":1790330000000,"isSMB":true,"type":"SMB"},
         {"eventType":"Correction Bolus","insulin":0.7,"date":1790330100000,"type":"PRIMING"},
         {"eventType":"Meal Bolus","insulin":2.0,"carbs":20,"date":1790330200000,"isValid":false}]
    """.trimIndent()

    // iAPS uploads two documents per loop cycle; only one of them carries the forecast.
    private val devicestatusJson = """
        [{"created_at":"2026-09-25T10:17:34.516Z","device":"iAPS","openaps":{
            "enacted":{"IOB":1.377,"COB":8,"bg":202,"eventualBG":146,"timestamp":"2026-09-25T10:17:34.465Z",
                       "predBGs":{"IOB":[202,204,206,207],"ZT":[202,189,177,165],"COB":[202,205,207,209,211,212,213],"UAM":[202,205,207,209]}},
            "iob":{"iob":1.377,"basaliob":0.082,"time":"2026-09-25T10:17:33.706Z"}}},
         {"created_at":"2026-09-25T10:17:34.098Z","device":"iAPS","openaps":{
            "enacted":{"IOB":1.381,"COB":9,"eventualBG":164,"timestamp":"2026-09-25T10:12:43.016Z"},
            "iob":{"iob":1.377,"time":"2026-09-25T10:17:33.706Z"}}},
         {"created_at":"2026-09-25T10:16:00.000Z","device":"xDrip","uploader":{"battery":80}}]
    """.trimIndent()

    private val t1017 = NightscoutClient.parseTime("2026-09-25T10:17:34.465Z")!!

    @Test
    fun `v1 reads entries oldest first and skips error codes`() {
        val transport = FakeTransport { HttpResponse(200, entriesJson) }
        val client = NightscoutClient("my.site/", transport = transport)
        val readings = client.readings(sinceMillis = 1790333000000)

        assertEquals(listOf(218, 212, 207), readings.map { it.mgdl })
        assertEquals(listOf(Trend.Flat, Trend.NotComputable, Trend.FortyFiveDown), readings.map { it.trend })
        assertEquals(1790334414821, readings.last().timeMillis)
        val request = transport.requests.single()
        assertTrue(request.url.startsWith("https://my.site/api/v1/entries/sgv.json?"))
        assertEquals("1790333000000", request.query("find[date][\$gte]"))
        assertNull(request.query("token"))
        assertTrue(request.headers.isEmpty())
    }

    @Test
    fun `v1 sends an access token as query and an API secret as its SHA-1`() {
        val transport = FakeTransport { HttpResponse(200, "[]") }
        NightscoutClient("https://my.site", "watch-0123456789abcdef", transport = transport).readings(0)
        NightscoutClient("https://my.site", "my long api secret", transport = transport).readings(0)

        val (token, secret) = transport.requests
        assertEquals("watch-0123456789abcdef", token.query("token"))
        assertFalse("api-secret" in token.headers)
        assertNull(secret.query("token"))
        assertEquals(NightscoutClient.sha1("my long api secret"), secret.headers["api-secret"])
        assertEquals(40, secret.headers.getValue("api-secret").length)
    }

    @Test
    fun `v1 maps a private site to a clear error`() {
        val transport = FakeTransport { HttpResponse(401, """{"status":401,"message":"Unauthorized","description":"Invalid/Missing"}""") }
        val e = assertFailsWith<NightscoutException.Unauthorized> { NightscoutClient("https://my.site", transport = transport).readings(0) }
        assertTrue("access token" in e.message!!)
    }

    @Test
    fun `v1 rejects an HTML page`() {
        val transport = FakeTransport { HttpResponse(200, "<html>Application error</html>") }
        assertFailsWith<NightscoutException.Server> { NightscoutClient("https://my.site", transport = transport).readings(0) }
    }

    @Test
    fun `v3 trades the token for a JWT and unwraps the result`() {
        val transport = FakeTransport { request ->
            when {
                "/api/v2/authorization/request/watch-0123456789abcdef" in request.url ->
                    HttpResponse(200, """{"token":"jwt-1","sub":"watch","iat":1,"exp":2}""")
                else -> {
                    assertEquals("Bearer jwt-1", request.headers["Authorization"])
                    HttpResponse(200, """{"status":200,"result":$entriesJson}""")
                }
            }
        }
        val client = NightscoutClient("https://my.site", "watch-0123456789abcdef", NightscoutApi.V3, transport = transport)
        assertEquals(3, client.readings(sinceMillis = 5).size)
        assertEquals("jwt-1", client.jwt)
        val search = transport.requests.last()
        assertTrue(search.url.startsWith("https://my.site/api/v3/entries?"))
        assertEquals("sgv", search.query("type\$eq"))
        assertEquals("5", search.query("date\$gte"))
        assertEquals("date", search.query("sort\$desc"))
        assertNull(search.query("token"))
    }

    @Test
    fun `v3 renews an expired JWT once`() {
        val transport = FakeTransport { request ->
            when {
                "authorization/request" in request.url -> HttpResponse(200, """{"token":"jwt-new"}""")
                request.headers["Authorization"] == "Bearer jwt-old" -> HttpResponse(401, """{"status":401,"message":"Missing or bad access token or JWT"}""")
                else -> HttpResponse(200, """{"status":200,"result":[]}""")
            }
        }
        val client = NightscoutClient("https://my.site", "watch-0123456789abcdef", NightscoutApi.V3, jwt = "jwt-old", transport = transport)
        assertEquals(emptyList(), client.readings(0))
        assertEquals("jwt-new", client.jwt)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun `v3 explains that it needs an access token`() {
        val none = FakeTransport { HttpResponse(200, "[]") }
        assertFailsWith<NightscoutException.Unauthorized> { NightscoutClient("https://my.site", api = NightscoutApi.V3, transport = none).readings(0) }
        assertTrue(none.requests.isEmpty())

        val refused = FakeTransport { HttpResponse(401, """{"status":401,"message":"Unauthorized"}""") }
        val e = assertFailsWith<NightscoutException.Unauthorized> {
            NightscoutClient("https://my.site", "my api secret", NightscoutApi.V3, transport = refused).readings(0)
        }
        assertTrue("not the API secret" in e.message!!)
    }

    @Test
    fun `keeps insulin and carbs, drops basals, priming and deleted entries`() {
        val treatments = Json.parseToJsonElement(treatmentsJson).jsonArray.mapNotNull { NightscoutClient.parseTreatment(it.jsonObject) }
        assertEquals(4, treatments.size)
        val (bolus, carbs, smb, aapsSmb) = treatments
        assertEquals(Treatment(NightscoutClient.parseTime("2026-09-25T08:07:29.000Z")!!, insulin = 3.6), bolus)
        assertEquals(61.0, carbs.carbs)
        assertTrue(smb.automatic)
        assertTrue(aapsSmb.automatic)
        assertEquals(1790330000000, aapsSmb.timeMillis)
    }

    @Test
    fun `last manual bolus ignores automatic boluses`() {
        val list = listOf(Treatment(1, insulin = 4.0), Treatment(2, carbs = 30.0), Treatment(3, insulin = 0.2, automatic = true))
        assertEquals(1, list.lastManualBolus(now = 10)?.timeMillis)
        assertEquals(2, list.lastCarbs(now = 10)?.timeMillis)
        assertNull(list.lastManualBolus(now = 0))
    }

    @Test
    fun `merges the two iAPS documents of one cycle`() {
        val docs = Json.parseToJsonElement(devicestatusJson).jsonArray.map { it.jsonObject }
        val status = assertNotNull(NightscoutClient.parseLoopStatus(docs))

        assertEquals(1.377, status.iob)
        assertEquals(8.0, status.cob)
        assertEquals(146.0, status.eventualMgdl)
        assertEquals("COB", status.forecastName)
        assertEquals(listOf(202.0, 205.0, 207.0, 209.0, 211.0, 212.0, 213.0), status.forecast.map { it.mgdl })
        assertEquals(t1017, status.forecast.first().timeMillis)
        assertEquals(t1017 + 5 * 60_000L, status.forecast[1].timeMillis)
        assertEquals(t1017, status.timeMillis)
    }

    @Test
    fun `picks the UAM curve when there are no carbs`() {
        val doc = Json.parseToJsonElement(
            """{"openaps":{"suggested":{"COB":0,"timestamp":"2026-09-25T10:00:00Z","predBGs":{"IOB":[100,99],"UAM":[100,104],"ZT":[100,95]}},
               "iob":[{"iob":0.5,"time":"2026-09-25T10:00:00Z"}]}}""",
        ).jsonObject
        val status = assertNotNull(NightscoutClient.parseDeviceStatus(doc))
        assertEquals("UAM", status.forecastName)
        assertEquals(0.5, status.iob)
    }

    @Test
    fun `reads Loop's device status`() {
        val doc = Json.parseToJsonElement(
            """{"created_at":"2026-09-25T10:00:05Z","device":"loop://iPhone","loop":{"name":"Loop","timestamp":"2026-09-25T10:00:00Z",
               "iob":{"iob":2.25,"timestamp":"2026-09-25T09:59:00Z"},"cob":{"cob":31.5,"timestamp":"2026-09-25T10:00:00Z"},
               "predicted":{"startDate":"2026-09-25T09:58:00Z","values":[140,142,145,149]}}}""",
        ).jsonObject
        val status = assertNotNull(NightscoutClient.parseDeviceStatus(doc))
        assertEquals(2.25, status.iob)
        assertEquals(31.5, status.cob)
        assertEquals("Loop", status.forecastName)
        assertEquals(149.0, status.eventualMgdl)
        assertEquals(NightscoutClient.parseTime("2026-09-25T09:58:00Z")!! + 15 * 60_000L, status.forecast.last().timeMillis)
    }

    @Test
    fun `loop forecast is cut to the horizon and dropped when old`() {
        val start = 1_000_000_000L
        val status = LoopStatus(start, iob = 1.0, forecast = (0..12).map { PredictedPoint(start + it * 300_000L, 100.0 + it) }, forecastName = "IOB")
        val p = assertNotNull(status.prediction(lastReadingMillis = start - 30_000, horizonMinutes = 30, now = start + 60_000))
        assertEquals(listOf(101.0, 102.0, 103.0, 104.0, 105.0, 106.0), p.points.map { it.mgdl })
        assertEquals(LoopStatus.MODEL_ID, p.modelId)
        assertNull(status.prediction(start, 30, now = start + 16 * 60_000L))
        assertFalse(status.isStale(now = start + 30 * 60_000L))
        assertTrue(status.isStale(now = start + 31 * 60_000L))
    }

    @Test
    fun `parses addresses as users paste them`() {
        assertEquals(NightscoutAddress("https://my.site/", null), NightscoutAddress.parse(" my.site "))
        assertEquals(NightscoutAddress("https://my.site/ns/", null), NightscoutAddress.parse("https://my.site/ns/api/v1/"))
        assertEquals(NightscoutAddress("https://my.site:8443/", "reader-0123456789abcdef"), NightscoutAddress.parse("https://my.site:8443/?token=reader-0123456789abcdef"))
        assertEquals(NightscoutAddress("https://my.site/", "my secret"), NightscoutAddress.parse("https://my%20secret@my.site/api/v1/"))
        assertFailsWith<IllegalArgumentException> { NightscoutAddress.parse("ftp://my.site") }
    }

    @Test
    fun `parses the time formats uploaders use`() {
        val z = 1790330867871L
        assertEquals(z, NightscoutClient.parseTime("2026-09-25T10:07:47.871Z"))
        assertEquals(z, NightscoutClient.parseTime("2026-09-25T13:07:47.871+03:00"))
        assertEquals(z, NightscoutClient.parseTime("2026-09-25T13:07:47.871+0300"))
        assertEquals(z, NightscoutClient.parseTime("2026-09-25T10:07:47.871"))
        assertEquals(z, NightscoutClient.parseTime("1790330867871"))
        assertEquals(z - 871, NightscoutClient.parseTime("2026-09-25T10:07:47Z"))
        assertNull(NightscoutClient.parseTime("yesterday"))
    }

    @Test
    fun `parses Nightscout trend spellings and API names`() {
        assertEquals(Trend.NotComputable, Trend.parse("NOT COMPUTABLE"))
        assertEquals(Trend.RateOutOfRange, Trend.parse("RATE OUT OF RANGE"))
        assertEquals(Trend.None, Trend.parse("NONE"))
        assertEquals(Trend.DoubleDown, Trend.parse("7"))
        assertEquals(Trend.FortyFiveUp, Trend.parse("FortyFiveUp"))
        assertEquals(NightscoutApi.V3, NightscoutApi.parse("v3"))
        assertEquals(NightscoutApi.V1, NightscoutApi.parse("1"))
    }

    @Test
    fun `delta spans 5 minutes with 1-minute readings too`() {
        val fiveMin = listOf(GlucoseReading(0, 100, Trend.Flat), GlucoseReading(300_000, 106, Trend.Flat))
        assertEquals(6.0, fiveMin.lastDelta())
        val oneMin = (0..10).map { GlucoseReading(it * 60_000L, 100 + it, Trend.Flat) }
        assertEquals(5.0, oneMin.lastDelta())
        val gap = listOf(GlucoseReading(0, 100, Trend.Flat), GlucoseReading(13 * 60_000L, 120, Trend.Flat))
        assertNull(gap.lastDelta())
    }

    @Test
    fun `formats amounts and ages for the watch`() {
        assertEquals("3.6", formatAmount(3.6))
        assertEquals("10", formatAmount(10.0))
        assertEquals("1.4", formatAmount(1.377, 1))
        assertEquals("0", formatAmount(-0.04, 1))
        assertEquals("35m", formatAge(35))
        assertEquals("1h", formatAge(60))
        assertEquals("3h 26m", formatAge(206))
        assertEquals("2d 1h", formatAge(49 * 60 + 5))
    }

    @Test
    fun `cache format round-trips`() {
        val readings = listOf(GlucoseReading(1, 100, Trend.Flat), GlucoseReading(2, 110, Trend.SingleUp))
        assertEquals(readings, CacheFormat.decodeReadings(CacheFormat.encodeReadings(readings)))
        val treatments = listOf(Treatment(1, insulin = 3.55), Treatment(2, carbs = 40.0), Treatment(3, insulin = 0.1, automatic = true))
        assertEquals(treatments, CacheFormat.decodeTreatments(CacheFormat.encodeTreatments(treatments)))
        val loop = LoopStatus(5, iob = 1.2, cob = null, eventualMgdl = 150.0, forecast = listOf(PredictedPoint(6, 120.5)), forecastName = "COB")
        assertEquals(loop, CacheFormat.decodeLoop(CacheFormat.encodeLoop(loop)))
        assertEquals(LoopStatus(5), CacheFormat.decodeLoop(CacheFormat.encodeLoop(LoopStatus(5))))
        assertNull(CacheFormat.decodeLoop(""))
        assertEquals(emptyList(), CacheFormat.decodeTreatments(""))
    }

    @Test
    fun `demo meals are stable and feed the demo loop`() {
        val now = 1_700_000_000_000L
        val meals = DemoData.treatments(now, hours = 24)
        assertEquals(meals, DemoData.treatments(now, hours = 24))
        assertTrue(meals.count { it.carbs > 0 } in 6..7)
        assertTrue(meals.all { it.timeMillis <= now })
        val loop = DemoData.loopStatus(now)
        assertTrue((loop.iob ?: -1.0) >= 0)
        assertTrue((loop.cob ?: -1.0) >= 0)
    }
}
