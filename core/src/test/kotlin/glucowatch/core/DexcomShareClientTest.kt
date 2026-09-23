package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DexcomShareClientTest {
    private val account = "11111111-2222-3333-4444-555555555555"
    private val session = "66666666-7777-8888-9999-000000000000"

    private class FakeTransport(val handler: (String, String) -> HttpResponse) : HttpTransport {
        val calls = mutableListOf<String>()
        override fun postJson(url: String, body: String): HttpResponse {
            calls += url.substringAfter("/Services/").substringBefore("?")
            return handler(url, body)
        }
    }

    private val readingsJson = """
        [{"WT":"Date(1745081913085)","ST":"Date(1745081913085)","DT":"Date(1745081913085-0400)","Value":216,"Trend":"Flat"},
         {"WT":"Date(1745081613085)","ST":"Date(1745081613085)","DT":"Date(1745081613085-0400)","Value":210,"Trend":"FortyFiveUp"}]
    """.trimIndent()

    private fun happyPath() = FakeTransport { url, body ->
        when {
            "AuthenticatePublisherAccount" in url -> {
                assertTrue("\"applicationId\":\"d89443d2-327c-4a6f-89e5-496bbb0317db\"" in body)
                HttpResponse(200, "\"$account\"")
            }
            "LoginPublisherAccountById" in url -> HttpResponse(200, "\"$session\"")
            else -> {
                assertTrue("sessionId=$session" in url)
                HttpResponse(200, readingsJson)
            }
        }
    }

    @Test
    fun `logs in and parses readings oldest first`() {
        val transport = happyPath()
        val client = DexcomShareClient(Region.OUS, "user", "pass", transport = transport)
        val readings = client.readings(minutes = 60, maxCount = 12)

        assertEquals(listOf(210, 216), readings.map { it.mgdl })
        assertEquals(Trend.FortyFiveUp, readings.first().trend)
        assertEquals(1745081913085, readings.last().timeMillis)
        assertEquals(session, client.sessionId)
        assertEquals(listOf("General/AuthenticatePublisherAccount", "General/LoginPublisherAccountById", "Publisher/ReadPublisherLatestGlucoseValues"), transport.calls)
    }

    @Test
    fun `re-logs in once when the stored session expired`() {
        var first = true
        val transport = FakeTransport { url, _ ->
            when {
                "Read" in url && first -> { first = false; HttpResponse(500, """{"Code":"SessionIdNotFound","Message":"x"}""") }
                "Authenticate" in url -> HttpResponse(200, "\"$account\"")
                "LoginPublisher" in url -> HttpResponse(200, "\"$session\"")
                else -> HttpResponse(200, readingsJson)
            }
        }
        val client = DexcomShareClient(Region.OUS, "u", "p", sessionId = "99999999-9999-9999-9999-999999999999", transport = transport)
        assertEquals(2, client.readings().size)
        assertEquals(4, transport.calls.size)
    }

    @Test
    fun `maps wrong password to AuthFailed`() {
        val transport = FakeTransport { _, _ -> HttpResponse(500, """{"Code":"AccountPasswordInvalid","Message":"Publisher account password failed"}""") }
        assertFailsWith<ShareException.AuthFailed> { DexcomShareClient(Region.OUS, "u", "p", transport = transport).login() }
    }

    @Test
    fun `rejects the all-zero session id`() {
        val transport = FakeTransport { url, _ ->
            if ("Authenticate" in url) HttpResponse(200, "\"$account\"") else HttpResponse(200, "\"00000000-0000-0000-0000-000000000000\"")
        }
        assertFailsWith<ShareException.AuthFailed> { DexcomShareClient(Region.OUS, "u", "p", transport = transport).login() }
    }

    @Test
    fun `linear predictor follows a rising trend`() {
        val now = 1_000_000_000L
        val history = (0 until 6).map { GlucoseReading(now - (5 - it) * 300_000L, 100 + it * 10, Trend.SingleUp) }
        val p = assertNotNull(LinearTrendPredictor().predict(history, 30))
        assertEquals(6, p.points.size)
        assertEquals(210.0, p.points.last().mgdl, 0.5)
    }

    @Test
    fun `demo data is stable and in range`() {
        val a = DemoData.readings(1_700_000_000_000L, hours = 3)
        val b = DemoData.readings(1_700_000_000_000L, hours = 3)
        assertEquals(36, a.size)
        assertEquals(a, b)
        assertTrue(a.all { it.mgdl in 40..400 })
    }
}
