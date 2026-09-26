package glucowatch.core

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    companion object {
        fun get(url: String, headers: Map<String, String> = emptyMap()) = HttpRequest("GET", url, headers)

        fun postJson(url: String, body: String) =
            HttpRequest("POST", url, mapOf("Content-Type" to "application/json"), body)
    }
}

data class HttpResponse(val status: Int, val body: String)

fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

/** Plain HttpURLConnection transport: works on the JVM and on Android without extra dependencies. */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 10_000,
    private val openConnection: (URL) -> URLConnection = { it.openConnection() },
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val conn = openConnection(URI(request.url).toURL()) as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "glucowatch")
            request.headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            if (request.body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(request.body.toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return HttpResponse(status, text)
        } finally {
            conn.disconnect()
        }
    }
}
