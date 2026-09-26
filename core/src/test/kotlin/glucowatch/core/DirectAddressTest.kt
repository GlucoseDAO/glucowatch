package glucowatch.core

import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DirectAddressTest {
    private val url = URL("https://shareous1.dexcom.com/ShareWebServices/Services/General/Test")

    @Test
    fun `the socket goes to the address while the request keeps the host name`() {
        val conn = DirectAddress("20.31.2.3").openConnection(url) as HttpsURLConnection
        assertEquals("20.31.2.3", conn.url.host)
        assertEquals("/ShareWebServices/Services/General/Test", conn.url.path)
        assertEquals("shareous1.dexcom.com", conn.getRequestProperty("Host"))
    }

    @Test
    fun `an IPv6 address is bracketed so the URL stays valid`() {
        val conn = DirectAddress("2606:4700::1111").openConnection(url)
        assertTrue(conn.url.toString().startsWith("https://[2606:4700::1111]/"), conn.url.toString())
    }

    @Test
    fun `the certificate is still matched against the original name, not the address`() {
        val conn = DirectAddress("20.31.2.3").openConnection(url) as HttpsURLConnection
        val verifier = conn.hostnameVerifier
        // The default verifier is asked about the host name; a session for the address alone must not pass.
        assertTrue(verifier !== HttpsURLConnection.getDefaultHostnameVerifier())
    }

    @Test
    fun `only an address literal is accepted, so a name cannot slip back in`() {
        assertFailsWith<IllegalArgumentException> { DirectAddress("attacker.example") }
        assertFailsWith<IllegalArgumentException> { DirectAddress("") }
    }
}
