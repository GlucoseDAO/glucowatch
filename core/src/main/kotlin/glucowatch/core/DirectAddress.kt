package glucowatch.core

import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Connects to a literal IP [address] while the request keeps the original host name: the TLS server
 * name, the certificate check and the `Host` header all stay on the name the URL asked for. Pair it
 * with [DohResolver] to reach a server whose name the network's own resolver will not answer.
 *
 * Nothing about the trust chain is relaxed. The certificate is verified by the platform's trust
 * store and then matched against the host name, so a network that answers in Dexcom's place is
 * rejected exactly as it would be on an ordinary connection.
 *
 * Android's HttpURLConnection is OkHttp underneath and lets the `Host` header be set. The desktop
 * JVM treats it as a restricted header and drops it unless `sun.net.http.allowRestrictedHeaders`
 * is true, which is why `:core:test` sets that property.
 */
class DirectAddress(private val address: String) {
    init {
        require(DohResolver.isAddress(address)) { "Not an IP address: $address" }
    }

    @Suppress("DEPRECATION") // URL's constructor is deprecated on the desktop JVM only; Android still has it.
    fun openConnection(url: URL): URLConnection {
        val hostname = url.host
        val literal = if (':' in address) "[$address]" else address
        val conn = URL(url.protocol, literal, url.port, url.file).openConnection()
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = SniFactory(HttpsURLConnection.getDefaultSSLSocketFactory(), hostname)
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            conn.hostnameVerifier = HostnameVerifier { _, session -> verifier.verify(hostname, session) }
        }
        conn.setRequestProperty("Host", hostname)
        return conn
    }

    override fun toString() = address

    /** Presents [hostname] to the server, whatever address the socket was actually opened to. */
    private class SniFactory(private val delegate: SSLSocketFactory, private val hostname: String) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(socket: Socket, host: String?, port: Int, autoClose: Boolean) =
            named(delegate.createSocket(socket, hostname, port, autoClose))

        override fun createSocket(host: String?, port: Int) = named(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) =
            named(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int) = named(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) =
            named(delegate.createSocket(address, port, localAddress, localPort))

        private fun named(socket: Socket): Socket {
            if (socket is SSLSocket) {
                socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(hostname)) }
            }
            return socket
        }
    }
}
