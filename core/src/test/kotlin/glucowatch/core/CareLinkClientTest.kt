package glucowatch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CareLinkClientTest {
    private val now = 1_790_000_000_000L

    private fun jwt(sub: String) = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub"}""".toByteArray()) + ".sig"

    private fun token(expiresAt: Long = Long.MAX_VALUE, refresh: String = "r1") =
        CareLinkToken("DE", "client", jwt("auth0|abc"), refresh, expiresAt)

    private class MemoryLogin(var token: CareLinkToken?) : CareLinkLogin {
        override fun load() = token

        override fun replace(old: CareLinkToken, new: CareLinkToken) {
            if (token?.refreshToken == old.refreshToken) token = new
        }
    }

    private class FakeTransport(val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
        val calls = mutableListOf<String>()
        override fun execute(request: HttpRequest): HttpResponse {
            calls += request.method + " " + request.url
            return handler(request)
        }
    }

    private val disco = """
        {"supportedCountries":[{"DE":{"region":"EU"}},{"US":{"region":"US"}}],
         "CP":[{"region":"EU","UseSSOConfiguration":"Auth0SSOConfiguration","SSOConfiguration":"https://old/sso.json",
                "Auth0SSOConfiguration":"https://cfg.example/auth0.json",
                "baseUrlCareLink":"https://cl.example/api/carepartner/v2","baseUrlCumulus":"https://cloud.example/connect/carepartner/v13"}]}
    """.trimIndent()
    private val sso = """{"server":{"hostname":"login.example","port":443,"prefix":""},"system_endpoints":{"token_endpoint_path":"/oauth/token"}}"""

    private fun patientData(extra: String = "") = """
        {"patientData":{
          "lastConduitDateTime":"2026-09-21T16:12:00.000Z","lastConduitUpdateServerDateTime":${now - 60_000},
          "lastSGTrend":"UP_DOUBLE",
          "sgs":[{"sg":0,"timestamp":"2026-09-21T16:00:00.000Z"},
                 {"sg":110,"timestamp":"2026-09-21T16:05:00.000Z"},
                 {"sg":120,"timestamp":"2026-09-21T16:10:00.000Z"}],
          "markers":[{"type":"INSULIN","timestamp":"2026-09-21T15:40:00.000Z","data":{"dataValues":{"deliveredFastAmount":"2.5","activationType":"RECOMMENDED"}}},
                     {"type":"INSULIN","timestamp":"2026-09-21T15:55:00.000Z","data":{"dataValues":{"deliveredFastAmount":"0.4","activationType":"AUTOCORRECTION"}}},
                     {"type":"MEAL","timestamp":"2026-09-21T15:40:00.000Z","data":{"dataValues":{"amount":"45"}}},
                     {"type":"AUTO_BASAL_DELIVERY","timestamp":"2026-09-21T15:45:00.000Z","data":{"dataValues":{"bolusAmount":"0.05"}}}],
          "activeInsulin":{"amount":1.75,"datetime":"2026-09-21T16:10:00.000Z"}$extra}}
    """.trimIndent()

    private fun api(user: String = """{"role":"CARE_PARTNER_OUS","username":"follower"}""", data: String = patientData()) = FakeTransport { r ->
        when {
            r.url.startsWith(CareLinkClient.DISCOVERY_URL) -> HttpResponse(200, disco)
            r.url == "https://cfg.example/auth0.json" -> HttpResponse(200, sso)
            r.url.endsWith("/users/me") -> HttpResponse(200, user)
            r.url.endsWith("/links/patients") -> HttpResponse(200, """[{"username":"patient1"}]""")
            r.url.endsWith("/display/message") -> HttpResponse(200, data)
            r.url == "https://login.example/oauth/token" -> HttpResponse(200, """{"access_token":"${jwt("auth0|abc")}","refresh_token":"r2","expires_in":3600}""")
            else -> HttpResponse(404, "")
        }
    }

    @Test
    fun `token survives encode and decode and names its account`() {
        val t = token(expiresAt = 123)
        assertEquals(t, CareLinkToken.decode(t.encode()))
        assertEquals("auth0|abc", t.subject)
        assertNull(CareLinkToken.decode("not json"))
        assertTrue("r1" !in t.toString() && t.accessToken !in t.toString(), "toString must not leak tokens")
    }

    @Test
    fun `care partner asks for the followed patient and parses the day`() {
        var body = ""
        val base = api()
        val transport = FakeTransport { r -> if (r.url.endsWith("/display/message")) body = r.body.orEmpty(); base.handler(r) }
        val client = CareLinkClient(MemoryLogin(token()), transport = transport)
        val data = client.recent(now)

        val sent = Json.parseToJsonElement(body) as JsonObject
        assertEquals("\"carepartner\"", sent["role"].toString())
        assertEquals("\"patient1\"", sent["patientId"].toString())
        assertEquals(listOf(110, 120), data.readings.map { it.mgdl }, "0 is a gap, not a reading")
        assertEquals(Trend.SingleUp, data.readings.last().trend)
        assertEquals(listOf(2.5, 0.4), data.treatments.filter { it.isBolus }.map { it.insulin }, "auto basal is not a bolus")
        assertEquals(listOf(0.4), data.treatments.filter { it.isBolus && it.automatic }.map { it.insulin })
        val basal = data.treatments.single { it.isBasal }
        assertEquals(0.05, basal.insulin)
        assertTrue(basal.automatic)
        assertNull(basal.basalRate, "Do not turn a delivered pulse into an inferred hourly rate")
        assertEquals(45.0, data.treatments.single { it.carbs > 0 }.carbs)
        assertEquals(1.75, data.loop?.iob)
    }

    @Test
    fun `moves pump times by whole hours when the pump clock is labelled UTC`() {
        // The pump says 16:12 "UTC"; the server got that upload at 14:12 UTC, so the pump runs on UTC+2.
        val data = CareLinkClient.parse((Json.parseToJsonElement(patientData()) as JsonObject)["patientData"] as JsonObject, now)
        assertEquals(NightscoutClient.parseTime("2026-09-21T14:10:00.000Z"), data.readings.last().timeMillis)
        assertEquals(now - 60_000, data.lastUploadMillis)
    }

    @Test
    fun `pump clock offset takes priority over a differently zoned uploading phone`() {
        val serverTime = NightscoutClient.parseTime("2026-06-01T08:00:00Z")!!
        val pumpClock = NightscoutClient.parseTime("2026-06-01T11:00:00Z")!!
        val patient = Json.parseToJsonElement("""{
            "lastConduitDateTime":"2026-06-01T10:00:00",
            "lastConduitUpdateServerDateTime":$serverTime,
            "medicalDeviceTime":$pumpClock,"lastMedicalDeviceDataUpdateServerTime":$serverTime,
            "basal":{"basalRate":0.8},
            "activeInsulin":{"amount":1.2,"datetime":"2026-06-01T10:59:00"},
            "markers":[{"type":"INSULIN","timestamp":"2026-06-01T10:55:00",
                "data":{"dataValues":{"deliveredFastAmount":2.0}}}]
        }""") as JsonObject
        val data = CareLinkClient.parse(patient, serverTime)
        assertEquals(serverTime - 60_000, data.loop?.timeMillis)
        assertEquals(serverTime - 5 * 60_000, data.treatments.single { it.isBolus }.timeMillis)
        val basal = data.treatments.single { it.isBasal }
        assertEquals(0.8, basal.basalRate)
        assertEquals(serverTime, basal.timeMillis)
        assertEquals(0.0, basal.insulin, "A reported rate is not an inferred dose")
        assertEquals("Reported basal 0.8 U/h", basal.basalDescription())
    }

    @Test
    fun `keeps the configuration and session so later fetches go straight to the data`() {
        val transport = api()
        val first = CareLinkClient(MemoryLogin(token()), transport = transport)
        first.recent(now)
        transport.calls.clear()
        CareLinkClient(MemoryLogin(token()), first.config, first.session, transport).recent(now)
        assertEquals(listOf("POST https://cloud.example/connect/carepartner/v13/display/message"), transport.calls)
    }

    @Test
    fun `refreshes an expiring token and stores the rotated one`() {
        val login = MemoryLogin(token(expiresAt = 0))
        CareLinkClient(login, transport = api()).recent(now)
        assertEquals("r2", login.token?.refreshToken)
    }

    @Test
    fun `a late refresh never replaces a newer sign-in`() {
        val login = MemoryLogin(token(expiresAt = 0))
        val base = api()
        val transport = FakeTransport { r ->
            // The user signs in again while this refresh is on its way.
            if (r.url.endsWith("/oauth/token")) login.token = token(refresh = "fresh")
            base.handler(r)
        }
        CareLinkClient(login, transport = transport).recent(now)
        assertEquals("fresh", login.token?.refreshToken)
    }

    @Test
    fun `retries once with a new token after a 401`() {
        var rejected = false
        val base = api()
        val transport = FakeTransport { r ->
            if (r.url.endsWith("/display/message") && !rejected) { rejected = true; HttpResponse(401, "") } else base.handler(r)
        }
        val login = MemoryLogin(token())
        CareLinkClient(login, transport = transport).recent(now)
        assertEquals("r2", login.token?.refreshToken)
    }

    @Test
    fun `a rejected refresh token asks for a new sign-in`() {
        val base = api()
        val transport = FakeTransport { r ->
            if (r.url.endsWith("/oauth/token")) HttpResponse(403, """{"error":"invalid_grant","error_description":"Unknown or invalid refresh token."}""")
            else base.handler(r)
        }
        val e = assertFailsWith<CareLinkException.SignInNeeded> { CareLinkClient(MemoryLogin(token(expiresAt = 0)), transport = transport).recent(now) }
        assertTrue("sign in again" in e.message!!.lowercase())
        assertEquals(FailureKind.AUTH, NetworkFailure.classify(e))
    }

    @Test
    fun `no sign-in at all is reported without a request`() {
        val transport = api()
        assertFailsWith<CareLinkException.SignInNeeded> { CareLinkClient(MemoryLogin(null), transport = transport).recent(now) }
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `merging sources keeps one copy of the same bolus`() {
        val pump = listOf(Treatment(1_000_000, insulin = 2.5), Treatment(2_000_000, carbs = 40.0))
        val nightscout = listOf(Treatment(1_060_000, insulin = 2.5), Treatment(3_000_000, insulin = 1.0))
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L), mergeTreatments(listOf(pump, nightscout)).map { it.timeMillis })
    }
}
