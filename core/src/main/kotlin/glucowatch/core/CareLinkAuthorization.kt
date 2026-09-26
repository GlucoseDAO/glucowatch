package glucowatch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Browser-based CarePartner authorization with state validation and PKCE. No password reaches this client. */
class CareLinkAuthorization(private val transport: HttpTransport = UrlConnectionTransport()) {
    class Pending(
        val country: String, val clientId: String, val redirect: String, val tokenUrl: String,
        val verifier: String, val state: String, val url: String, val startedAt: Long,
    ) {
        override fun toString() = "CareLinkAuthorization.Pending(country=$country)"

        fun encode() = buildJsonObject {
            put("country", country); put("clientId", clientId); put("redirect", redirect); put("tokenUrl", tokenUrl)
            put("verifier", verifier); put("state", state); put("url", url); put("startedAt", startedAt)
        }.toString()

        companion object {
            fun decode(text: String?): Pending? = runCatching {
                val j = Json.parseToJsonElement(text ?: return null) as JsonObject
                Pending(j.required("country"), j.required("clientId"), j.required("redirect"), j.required("tokenUrl"),
                    j.required("verifier"), j.required("state"), j.required("url"), j.required("startedAt").toLong())
            }.getOrNull()
        }
    }

    fun begin(country: String, now: Long = System.currentTimeMillis()): Pending {
        val selected = country.trim().uppercase()
        require(selected.matches(Regex("[A-Z]{2}"))) { "Enter the two-letter CareLink country code" }
        val discovery = get(CareLinkClient.DISCOVERY_URL)
        val region = (discovery["supportedCountries"] as? JsonArray).orEmpty().firstNotNullOfOrNull {
            ((it as? JsonObject)?.get(selected) as? JsonObject)?.string("region")
        } ?: error("CareLink does not list country $selected")
        val cp = (discovery["CP"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .firstOrNull { it.string("region") == region } ?: error("CareLink has no $region sign-in server")
        val config = get(cp.string(cp.string("UseSSOConfiguration") ?: "SSOConfiguration") ?: cp.required("SSOConfiguration"))
        val server = config["server"] as JsonObject
        val port = server.string("port")?.takeUnless { it == "443" }?.let { ":$it" }.orEmpty()
        val prefix = server.string("prefix").orEmpty().trim('/').let { if (it.isEmpty()) "" else "/$it" }
        val base = "https://${server.required("hostname")}$port$prefix"
        val client = config["client"] as JsonObject
        val endpoints = config["system_endpoints"] as JsonObject
        val verifier = random(32)
        val state = random(24)
        val redirect = client.required("redirect_uri")
        val query = form(mapOf(
            "client_id" to client.required("client_id"), "response_type" to "code", "scope" to client.required("scope"),
            "redirect_uri" to redirect, "audience" to client.required("audience"), "state" to state,
            "code_challenge" to base64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))),
            "code_challenge_method" to "S256",
        ))
        return Pending(selected, client.required("client_id"), redirect, base + endpoints.required("token_endpoint_path"),
            verifier, state, base + endpoints.required("authorization_endpoint_path") + "?" + query, now)
    }

    fun finish(pending: Pending, callback: String, now: Long = System.currentTimeMillis()): CareLinkToken {
        require(now - pending.startedAt in 0..10 * 60_000L) { "CareLink sign-in timed out. Start again." }
        require(callback.substringBefore('?') == pending.redirect) { "Unexpected CareLink sign-in redirect" }
        val query = URI(callback).rawQuery.orEmpty().split('&').associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        require(query["state"] == pending.state) { "CareLink sign-in does not match this request. Start again." }
        require(query["error"] == null) { "CareLink sign-in was cancelled or refused" }
        val code = requireNotNull(query["code"]?.takeIf { it.isNotBlank() }) { "CareLink returned no sign-in code" }
        val response = request(HttpRequest("POST", pending.tokenUrl,
            mapOf("Content-Type" to "application/x-www-form-urlencoded"), form(mapOf(
                "grant_type" to "authorization_code", "client_id" to pending.clientId, "redirect_uri" to pending.redirect,
                "code" to code, "code_verifier" to pending.verifier,
            ))))
        check(response.status in 200..299) { "CareLink refused the sign-in code (HTTP ${response.status})" }
        val json = Json.parseToJsonElement(response.body) as JsonObject
        return CareLinkToken(pending.country, pending.clientId, json.required("access_token"), json.required("refresh_token"),
            now + (json.string("expires_in")?.toLongOrNull() ?: 3600L) * 1000L)
    }

    private fun get(url: String): JsonObject {
        require(URI(url).scheme == "https") { "CareLink requires HTTPS" }
        val response = request(HttpRequest.get(url))
        check(response.status in 200..299) { "CareLink sign-in configuration: HTTP ${response.status}" }
        return Json.parseToJsonElement(response.body) as JsonObject
    }

    private fun request(request: HttpRequest): HttpResponse = try {
        executeCareLink(transport, request)
    } catch (error: IOException) {
        throw CareLinkException.Network(error)
    }

    private fun form(values: Map<String, String>) = values.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }
    private fun random(size: Int) = base64(ByteArray(size).also(SecureRandom()::nextBytes))
    private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.required(key: String) = string(key)?.takeIf { it.isNotBlank() } ?: error("CareLink response has no $key")
