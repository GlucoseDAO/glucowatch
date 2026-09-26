package glucowatch.core

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionCheckTest {
    private val share = Region.OUS.baseUrl

    @Test
    fun `a name that does not resolve stops the ladder at the DNS step`() {
        val steps = ConnectionCheck(resolver = { throw UnknownHostException("shareous1.dexcom.com") }).run(share)
        assertEquals(listOf("resolve"), steps.map { it.step })
        assertFalse(steps.single().ok)
        assertEquals(FailureKind.DNS, steps.single().kind)
    }

    @Test
    fun `DoH is tried only after a DNS failure, and its address is used for the retry`() {
        val doh = DohResolver("https://1.1.1.1/dns-query") {
            HttpResponse(200, """{"Status":0,"Answer":[{"type":1,"data":"20.31.2.3"}]}""")
        }
        val steps = ConnectionCheck(
            resolver = { throw UnknownHostException("shareous1.dexcom.com") },
            transports = { HttpTransport { HttpResponse(404, "") } },
        ).run(share, doh)
        assertEquals(listOf("resolve", "DoH lookup", "request via 20.31.2.3"), steps.map { it.step })
        assertTrue(steps.last().ok)
        assertEquals("HTTP 404", steps.last().detail)
    }

    @Test
    fun `a resolver that answers but a server that refuses leaves DoH alone`() {
        val steps = ConnectionCheck(
            timeoutMs = 50,
            resolver = { arrayOf(InetAddress.getByName("127.0.0.1")) },
            transports = { HttpTransport { HttpResponse(200, "") } },
        ).run("https://shareous1.dexcom.com/", DohResolver("https://1.1.1.1/dns-query") { error("never asked") })
        assertEquals(listOf("resolve", "connect", "request"), steps.map { it.step })
        assertTrue(steps.first().ok)
        assertFalse(steps[1].ok)
    }

    @Test
    fun `a synthesized NAT64 address is named as one`() {
        assertTrue(ConnectionCheck.describe(InetAddress.getByName("64:ff9b::1414:1414")).endsWith("(NAT64)"))
        assertTrue(ConnectionCheck.describe(InetAddress.getByName("2606:4700::1111")).endsWith("(IPv6)"))
        assertEquals("20.31.2.3", ConnectionCheck.describe(InetAddress.getByName("20.31.2.3")))
    }
}
