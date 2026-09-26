package glucowatch.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkFailureTest {
    @Test
    fun `each layer keeps its own kind, through the cause chain`() {
        assertEquals(FailureKind.DNS, NetworkFailure.classify(UnknownHostException("shareous1.dexcom.com")))
        assertEquals(FailureKind.DNS, NetworkFailure.classify(ShareException.Network(UnknownHostException("host"))))
        assertEquals(FailureKind.TLS, NetworkFailure.classify(SSLHandshakeException("cert")))
        assertEquals(FailureKind.RESET, NetworkFailure.classify(SocketException("Connection reset by peer")))
        assertEquals(FailureKind.ROUTE, NetworkFailure.classify(ConnectException("ENETUNREACH (Network is unreachable)")))
        assertEquals(FailureKind.AUTH, NetworkFailure.classify(ShareException.AuthFailed("nope")))
        assertEquals(FailureKind.SERVER, NetworkFailure.classify(ShareException.Server("X", "boom")))
        assertEquals(FailureKind.OTHER, NetworkFailure.classify(IOException("something else")))
        assertEquals(FailureKind.OTHER, NetworkFailure.classify(null))
    }

    @Test
    fun `an Android connect timeout is a route failure, a read timeout is not`() {
        val connect = SocketTimeoutException("failed to connect to shareous1.dexcom.com/1.2.3.4 (port 443) after 8000ms")
        assertEquals(FailureKind.ROUTE, NetworkFailure.classify(connect))
        assertEquals(FailureKind.TIMEOUT, NetworkFailure.classify(SocketTimeoutException("Read timed out")))
    }

    private class SelfCaused : IOException("loop") {
        override val cause: Throwable get() = this
    }

    @Test
    fun `a cause that points at itself does not loop`() {
        assertEquals(FailureKind.OTHER, NetworkFailure.classify(SelfCaused()))
    }

    @Test
    fun `history round-trips, drops separators from the text and keeps the newest rows`() {
        val rows = (1..NetworkFailure.HISTORY + 5).map {
            FailureRecord(it.toLong(), FailureKind.DNS, "wifi", "failed, badly; twice")
        }
        val decoded = NetworkFailure.decode(NetworkFailure.encode(rows))
        assertEquals(NetworkFailure.HISTORY, decoded.size)
        assertEquals(6L, decoded.first().timeMillis)
        assertEquals("failed  badly  twice", decoded.first().detail)
        assertEquals(FailureKind.DNS, decoded.first().kind)
        assertEquals("wifi", decoded.first().transport)
    }

    @Test
    fun `a damaged or empty history just starts over`() {
        assertEquals(emptyList(), NetworkFailure.decode(""))
        assertEquals(emptyList(), NetworkFailure.decode("nonsense;1,NOPE,wifi,x"))
        val kept = NetworkFailure.decode(NetworkFailure.append("junk", FailureRecord(7L, FailureKind.TLS, "", "x")))
        assertEquals(1, kept.size)
        assertEquals(7L, kept.single().timeMillis)
    }

    @Test
    fun `only the kinds another route could fix are routable`() {
        assertTrue(FailureKind.DNS.routable && FailureKind.RESET.routable)
        assertTrue(!FailureKind.AUTH.routable && !FailureKind.SERVER.routable && !FailureKind.OTHER.routable)
    }

    @Test
    fun `the description names the layer and keeps the original text`() {
        val text = NetworkFailure.describe(UnknownHostException("shareous1.dexcom.com"))
        assertEquals("Name not resolved: shareous1.dexcom.com", text)
    }
}
