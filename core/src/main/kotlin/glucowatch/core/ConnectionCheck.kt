package glucowatch.core

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket

/** One step of the ladder, in the order the app tried it. */
data class Probe(val step: String, val ok: Boolean, val detail: String, val millis: Long = 0, val kind: FailureKind? = null)

/**
 * Walks a connection one layer at a time — resolve, connect, handshake, request — so a failure can
 * be pinned to the layer that produced it instead of showing up as one "network error". Each step
 * runs only when the one below it worked.
 *
 * Nothing here relaxes certificate checking: the handshake probe uses the platform trust store, so
 * a server presenting someone else's certificate is reported as a failed handshake rather than
 * quietly accepted.
 */
class ConnectionCheck(
    private val timeoutMs: Int = 5_000,
    private val resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    private val transports: ((URL) -> URLConnection) -> HttpTransport = { UrlConnectionTransport(5_000, 5_000, it) },
) {
    /** The ladder for [baseUrl], then the same request again through [doh] when the name did not resolve. */
    fun run(baseUrl: String, doh: DohResolver? = null): List<Probe> {
        val uri = URI(baseUrl)
        val host = uri.host ?: return listOf(Probe("address", false, "Not a valid address: $baseUrl"))
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "http") 80 else 443
        val steps = mutableListOf<Probe>()

        val addresses = timed("resolve") { resolver(host).toList() }
        steps += addresses.probe { list -> list.joinToString(" ") { describe(it) } }
        val first = addresses.value?.firstOrNull()

        if (first != null) {
            val connected = timed("connect") {
                Socket().also { it.connect(InetSocketAddress(first, port), timeoutMs) }
            }
            steps += connected.probe { "${first.hostAddress} port $port" }
            connected.value?.use { socket ->
                if (uri.scheme == "https") {
                    val handshake = timed("handshake") { handshake(socket, host, port) }
                    steps += handshake.probe { it }
                }
            }
            steps += timed("request") { request(baseUrl) { it.openConnection() } }.probe { "HTTP $it" }
        }

        if (doh != null && steps.any { !it.ok && it.kind == FailureKind.DNS }) {
            val viaDoh = timed("DoH lookup") { doh.resolve(host) }
            steps += viaDoh.probe { it.joinToString(" ").ifEmpty { "no records" } }
            viaDoh.value?.firstOrNull()?.let { address ->
                val direct = DirectAddress(address)
                steps += timed("request via $address") { request(baseUrl, direct::openConnection) }.probe { "HTTP $it" }
            }
        }
        return steps
    }

    private fun handshake(socket: Socket, host: String, port: Int): String {
        val ssl = HttpsURLConnection.getDefaultSSLSocketFactory().createSocket(socket, host, port, false) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        val session = ssl.session
        val issuer = (session.peerCertificates.firstOrNull() as? X509Certificate)
            ?.issuerX500Principal?.name?.let { name -> ISSUER_CN.find(name)?.groupValues?.get(1) ?: name }
        return "${session.protocol}, issued by ${issuer ?: "unknown"}"
    }

    private fun request(url: String, open: (URL) -> URLConnection): Int =
        transports(open).execute(HttpRequest.get(url)).status

    private fun <T> timed(step: String, body: () -> T): Step<T> {
        val start = System.nanoTime()
        return runCatching(body).fold(
            onSuccess = { Step(step, it, null, elapsed(start)) },
            onFailure = { Step(step, null, it, elapsed(start)) },
        )
    }

    private fun elapsed(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    private class Step<T>(val step: String, val value: T?, val error: Throwable?, val millis: Long)

    private fun <T> Step<T>.probe(detail: (T) -> String) = when {
        value != null -> Probe(step, true, detail(value), millis)
        else -> Probe(step, false, NetworkFailure.describe(error), millis, NetworkFailure.classify(error))
    }

    companion object {
        private val ISSUER_CN = Regex("CN=([^,]+)")

        /** The well-known prefix a DNS64 resolver puts an IPv4-only server behind (RFC 6052). */
        const val NAT64_PREFIX = "64:ff9b:"

        fun describe(address: InetAddress): String {
            val text = address.hostAddress.orEmpty()
            return when {
                address is Inet6Address && text.startsWith(NAT64_PREFIX) -> "$text (NAT64)"
                address is Inet6Address -> "$text (IPv6)"
                else -> text
            }
        }
    }
}
