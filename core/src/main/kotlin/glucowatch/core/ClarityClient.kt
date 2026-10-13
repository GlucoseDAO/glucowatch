package glucowatch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

/** Read-only Clarity history using the user's sharing code, independently of Dexcom Share. */
class ClarityClient(private val region: Region, private val transport: HttpTransport = ExportHttpTransport()) {
    private val base get() = if (region == Region.US) "https://clarity.dexcom.com" else "https://clarity.dexcom.eu"

    fun csv(sharingCode: String, window: ExportWindow): String {
        require(region != Region.JP) { "Clarity sharing-code downloads are supported for US and international accounts. Import a CSV for Japan." }
        val code = sharingCode.replace("-", "").replace(" ", "").uppercase()
        require(code.matches(Regex("[A-Z0-9]{8,16}"))) { "Enter the sharing code from Clarity > Profile > Authorize Sharing" }
        val auth = call(HttpRequest.get("$base/api/access_code/redeem?accesscode=${encode(code)}"))
        val token = json(auth)?.text("accessToken")
            ?: error("Clarity returned no access token. Generate a new sharing code.")
        val claims = runCatching { json(String(Base64.getUrlDecoder().decode(token.split('.')[1]))) }
            .getOrNull() ?: error("Clarity authentication response changed")
        val subject = claims.text("subjectId")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            ?: error("Clarity returned no subject")
        val session = transport.execute(HttpRequest("POST", "$base/user/sharing", mapOf(
            "Content-Type" to "application/x-www-form-urlencoded", "Origin" to base,
            "Referer" to "$base/professional/"), "sharing_code=${encode(code.chunked(4).joinToString("-"))}&commit="))
        require(session.status == 302) { "Clarity sharing session could not be opened. Check the code and region, or import a CSV." }
        val cookie = session.headers.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
            .map { it.substringBefore(';') }.firstOrNull { it.startsWith("_rogue_material_session=") }
            ?.takeIf { it.substringAfter('=').isNotBlank() && !it.contains('\r') && !it.contains('\n') }
            ?: error("Clarity returned no sharing session. Import its CSV instead.")
        val headers = mapOf("Access-Token" to token, "Cookie" to cookie)
        val info = json(call(HttpRequest.get("$base/subject_info", headers)))
            ?: error("Clarity account information could not be read")
        val form = mapOf("dateInterval" to "${window.start}/${window.end}", "accessToken" to token,
            "firstName" to info.text("first_name").orEmpty(), "lastName" to info.text("last_name").orEmpty(),
            "locale" to "en-US", "units" to "mgdl")
            .entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return call(HttpRequest("POST", "$base/api/subject/$subject/export",
            headers + mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "text/csv"), form))
    }

    private fun call(request: HttpRequest): String {
        val response = transport.execute(request)
        require(response.status in 200..299) {
            if (response.status == 401 || response.status == 403) "Clarity denied access. Check the region and sharing code."
            else "Clarity download failed (HTTP ${response.status}). Try again or import its CSV."
        }
        return response.body
    }
    private fun json(value: String): JsonObject? = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
    private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content
}

/** No redirects carrying a sharing code or token; bounded history downloads and generic errors. */
class ExportHttpTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val url = URI(request.url)
        require(url.scheme == "https" && url.host in setOf("clarity.dexcom.com", "clarity.dexcom.eu"))
        val connection = url.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 90_000
            connection.requestMethod = request.method
            request.headers.forEach(connection::setRequestProperty)
            request.body?.let {
                connection.doOutput = true
                connection.outputStream.use { output -> output.write(it.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
            if (status !in 200..299) return HttpResponse(status, "", headers)
            require(connection.contentLengthLong <= HistoricalCsv.MAX_BYTES) { "Clarity CSV exceeds 20 MB" }
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= HistoricalCsv.MAX_BYTES) { "Clarity CSV exceeds 20 MB" }
                    output.write(buffer, 0, n)
                }
            }
            return HttpResponse(status, output.toString(Charsets.UTF_8.name()), headers)
        } catch (e: java.io.IOException) {
            // Network exceptions may contain the request URL, whose query has the sharing code.
            throw java.io.IOException("Could not reach Clarity. Check the connection and try again.")
        } finally { connection.disconnect() }
    }
}
