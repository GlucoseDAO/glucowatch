package glucowatch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * DNS over HTTPS (RFC 8484's JSON form), for a network whose own resolver does not answer for
 * Dexcom. The default endpoint is an IP literal, so it needs no working DNS to bootstrap itself;
 * Cloudflare's certificate carries 1.1.1.1 as a subject alternative name, and Google's carries
 * 8.8.8.8, so ordinary certificate verification still applies.
 *
 * It only helps against DNS tampering. A network that blocks by address or by TLS server name
 * blocks the address this returns just as well, which is why the app tries it after a [FailureKind.DNS]
 * failure and not before.
 */
class DohResolver(
    private val endpoint: String = CLOUDFLARE,
    private val transport: HttpTransport = UrlConnectionTransport(connectTimeoutMs = 5_000, readTimeoutMs = 5_000),
) {
    /** Addresses for [host], IPv4 first, or an empty list when the resolver has none. */
    fun resolve(host: String): List<String> = buildList {
        addAll(query(host, A))
        addAll(query(host, AAAA))
    }.distinct()

    private fun query(host: String, type: Int): List<String> {
        val url = "$endpoint?name=${URLEncoder.encode(host, "UTF-8")}&type=$type"
        val response = transport.execute(HttpRequest.get(url, mapOf("Accept" to "application/dns-json")))
        if (response.status !in 200..299) return emptyList()
        return parse(response.body, type)
    }

    companion object {
        const val CLOUDFLARE = "https://1.1.1.1/dns-query"
        const val GOOGLE = "https://8.8.8.8/dns-query"
        const val A = 1
        const val AAAA = 28

        val PRESETS = listOf("Cloudflare" to CLOUDFLARE, "Google" to GOOGLE)

        private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
        private val IPV6 = Regex("^[0-9A-Fa-f:]{2,45}$")

        /**
         * Records of [type] from a dns-json answer. CNAME rows are skipped, and anything that is not
         * a plain address literal is dropped, so a hostile answer cannot steer a later lookup.
         */
        fun parse(body: String, type: Int): List<String> {
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
            if (root["Status"]?.jsonPrimitive?.intOrNull != 0) return emptyList()
            val answers = runCatching { root["Answer"]?.jsonArray }.getOrNull() ?: return emptyList()
            return answers.mapNotNull { entry ->
                val row = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
                if (row["type"]?.jsonPrimitive?.intOrNull != type) return@mapNotNull null
                row["data"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(::isAddress)
            }
        }

        fun isAddress(value: String) = IPV4.matches(value) || (':' in value && IPV6.matches(value))
    }
}
