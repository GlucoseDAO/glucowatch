package glucowatch.core

import java.net.URI
import java.net.UnknownHostException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CareLinkAuthorizationTest {
    private val now = 1_790_000_000_000L
    private fun fields(query: String) = query.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test
    fun `discovered browser sign-in validates state and exchanges a PKCE code`() {
        val requests = mutableListOf<HttpRequest>()
        val auth = CareLinkAuthorization(HttpTransport { r ->
            requests += r
            when {
                r.url == CareLinkClient.DISCOVERY_URL -> HttpResponse(200, """{
                    "supportedCountries":[{"DE":{"region":"EU"}}],
                    "CP":[{"region":"EU","UseSSOConfiguration":"Auth0SSOConfiguration","Auth0SSOConfiguration":"https://cfg.test/auth"}]
                }""")
                r.url == "https://cfg.test/auth" -> HttpResponse(200, """{
                    "server":{"hostname":"login.test","port":443},
                    "client":{"client_id":"public","scope":"openid offline_access","redirect_uri":"com.medtronic.carepartner:/sso","audience":"pump"},
                    "system_endpoints":{"authorization_endpoint_path":"/authorize","token_endpoint_path":"/oauth/token"}
                }""")
                else -> HttpResponse(200, """{"access_token":"access","refresh_token":"refresh","expires_in":3600}""")
            }
        })
        val pending = auth.begin("de", now)
        val restored = CareLinkAuthorization.Pending.decode(pending.encode())!!
        val query = fields(URI(restored.url).rawQuery)
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(restored.verifier.toByteArray()))
        assertEquals(expectedChallenge, query["code_challenge"])
        assertEquals("S256", query["code_challenge_method"])
        assertFalse(restored.toString().contains(restored.verifier))
        val token = auth.finish(restored, "${restored.redirect}?code=returned-code&state=${restored.state}", now)
        assertEquals(now + 3_600_000, token.expiresAt)
        assertEquals("refresh", token.refreshToken)
        val body = fields(requests.last().body!!)
        assertEquals(restored.verifier, body["code_verifier"])
        assertEquals("returned-code", body["code"])
        assertEquals("https://login.test/oauth/token", requests.last().url)
    }

    @Test
    fun `wrong state redirect expired and error callbacks never send a token request`() {
        var calls = 0
        val auth = CareLinkAuthorization(HttpTransport { calls++; error("Must reject before making a request") })
        val pending = CareLinkAuthorization.Pending("DE", "public", "com.medtronic.carepartner:/sso",
            "https://login.test/oauth/token", "verifier", "state", "https://login.test/authorize", now)
        listOf(
            "${pending.redirect}?code=code&state=other" to now,
            "attacker:/sso?code=code&state=state" to now,
            "${pending.redirect}?code=code&state=state" to now + 11 * 60_000,
            "${pending.redirect}?error=access_denied&state=state" to now,
            "${pending.redirect}?state=state" to now,
        ).forEach { (url, time) -> assertFailsWith<IllegalArgumentException> { auth.finish(pending, url, time) } }
        assertEquals(0, calls)
        assertTrue(CareLinkAuthorization.Pending.decode("bad") == null)
    }

    @Test
    fun `discovery retries the official global host only when eu dns fails`() {
        val calls = mutableListOf<String>()
        val auth = CareLinkAuthorization(HttpTransport { request ->
            calls += request.url
            when {
                request.url == CareLinkClient.DISCOVERY_URL -> throw UnknownHostException("clcloud.minimed.eu")
                request.url.startsWith("https://clcloud.minimed.com/") -> HttpResponse(200, """{
                    "supportedCountries":[{"DE":{"region":"EU"}}],
                    "CP":[{"region":"EU","SSOConfiguration":"https://cfg.test/auth"}]
                }""")
                else -> HttpResponse(200, """{
                    "server":{"hostname":"login.test","port":443},
                    "client":{"client_id":"public","scope":"openid","redirect_uri":"com.medtronic.carepartner:/sso","audience":"pump"},
                    "system_endpoints":{"authorization_endpoint_path":"/authorize","token_endpoint_path":"/token"}
                }""")
            }
        })

        auth.begin("DE", now)
        assertEquals(CareLinkClient.DISCOVERY_URL, calls[0])
        assertTrue(calls[1].startsWith("https://clcloud.minimed.com/"))
    }
}
