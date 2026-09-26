package glucowatch.core

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/** An HTTP CONNECT proxy for HTTPS Share requests. Dexcom TLS still ends at Dexcom. */
data class ProxyEndpoint(val host: String, val port: Int) {
    fun javaProxy() = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))

    companion object {
        fun parse(input: String): ProxyEndpoint {
            val value = input.trim()
            require(value.isNotEmpty()) { "Enter a proxy host and port" }
            val uri = URI(if ("://" in value) value else "http://$value")
            require(uri.scheme.equals("http", ignoreCase = true)) { "Use an HTTP CONNECT proxy (http://host:port)" }
            require(uri.rawUserInfo == null) { "Proxy login in the address is not supported" }
            require(uri.host != null && uri.port in 1..65535) { "Enter a proxy host and port" }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "Proxy address cannot have a path" }
            require(uri.rawQuery == null && uri.rawFragment == null) { "Proxy address cannot have a query or fragment" }
            return ProxyEndpoint(uri.host, uri.port)
        }
    }
}
