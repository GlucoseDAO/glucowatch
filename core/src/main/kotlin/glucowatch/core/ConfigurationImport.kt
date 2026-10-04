package glucowatch.core

import glucowatch.core.link.LinkSource
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.MarkedYAMLException

/** A validated partial configuration. Never include its contents in logs or error messages. */
class ImportedConfiguration private constructor(private val values: Map<String, String>) {
    operator fun get(key: String): String? = values[key]
    val source: LinkSource? = values["GLUCOWATCH_SOURCE"]?.takeIf(String::isNotBlank)?.let(::sourceOf)
        ?: when {
            !values["DEXCOM_USERNAME"].isNullOrBlank() && !values["DEXCOM_PASSWORD"].isNullOrEmpty() -> LinkSource.SHARE
            !values["NIGHTSCOUT_URL"].isNullOrBlank() -> LinkSource.NIGHTSCOUT
            else -> null
        }
    val alsoFrom: Set<LinkSource>? = values["GLUCOWATCH_ALSO_FROM"]?.split(',')
        ?.filter(String::isNotBlank)?.map(::sourceOf)?.toSet()
    val carelinkToken: CareLinkToken? = if (values["CARELINK_ACCESS_TOKEN"].isNullOrEmpty()) null else CareLinkToken(
        values["CARELINK_COUNTRY"]?.uppercase() ?: "DE", values.getValue("CARELINK_CLIENT_ID"),
        values.getValue("CARELINK_ACCESS_TOKEN"), values.getValue("CARELINK_REFRESH_TOKEN"),
        values.getValue("CARELINK_EXPIRES_AT").toLong(),
    )
    override fun toString() = "ImportedConfiguration(contents=redacted)"

    companion object {
        const val MAX_BYTES = 64 * 1024
        private val schema = mapOf(
            "source" to "GLUCOWATCH_SOURCE", "unit" to "GLUCOWATCH_UNIT",
            "also_from" to "GLUCOWATCH_ALSO_FROM", "heart_track" to "GLUCOWATCH_HEART_TRACK",
            "dexcom.username" to "DEXCOM_USERNAME", "dexcom.password" to "DEXCOM_PASSWORD",
            "dexcom.region" to "DEXCOM_REGION", "dexcom.notifications" to "DEXCOM_NOTIFICATIONS",
            "nightscout.url" to "NIGHTSCOUT_URL", "nightscout.token" to "NIGHTSCOUT_TOKEN",
            "nightscout.api" to "NIGHTSCOUT_API", "carelink.country" to "CARELINK_COUNTRY",
            "carelink.client_id" to "CARELINK_CLIENT_ID", "carelink.access_token" to "CARELINK_ACCESS_TOKEN",
            "carelink.refresh_token" to "CARELINK_REFRESH_TOKEN", "carelink.expires_at" to "CARELINK_EXPIRES_AT",
            "prediction.model" to "HF_MODEL_ADDRESS", "prediction.hf_token" to "HF_TOKEN",
        )
        private val known = schema.values.toSet()

        /** Format is inferred from content, so dotfiles and servers with generic MIME types work. */
        fun parse(text: String): ImportedConfiguration {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Configuration exceeds 64 KiB" }
            val clean = text.removePrefix("\uFEFF")
            val first = clean.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
                ?: error("Configuration is empty")
            val values = if (Regex("^(?:export )?[A-Za-z_][A-Za-z0-9_]*\\s*=").containsMatchIn(first)) dotenv(clean) else yaml(clean)
            require(values.isNotEmpty()) { "No supported configuration settings found" }
            validate(values)
            return ImportedConfiguration(values)
        }

        private fun dotenv(text: String): Map<String, String> {
            val seen = mutableSetOf<String>()
            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.trim().removePrefix("export ").trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
                require(Regex("^[A-Za-z_][A-Za-z0-9_]*\\s*=").containsMatchIn(line)) { "Invalid .env assignment on line ${index + 1}" }
                val key = line.substringBefore('=').trim()
                if (key in known) {
                    require(seen.add(key)) { "Duplicate configuration setting on line ${index + 1}" }
                    val value = line.substringAfter('=').trim()
                    require(value.firstOrNull() !in listOf('\'', '"') || (value.length >= 2 && value.last() == value.first())) {
                        "Unclosed quote on line ${index + 1}"
                    }
                }
            }
            return DotEnv.parse(text).filterKeys { it in known }
        }

        private fun yaml(text: String): Map<String, String> {
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 0
                nestingDepthLimit = 8
                codePointLimit = MAX_BYTES
            }
            val root = try { Yaml(SafeConstructor(options)).load<Any>(text) }
            catch (e: MarkedYAMLException) { error("Invalid YAML near line ${(e.problemMark?.line ?: 0) + 1}") }
            catch (_: Exception) { error("Invalid YAML configuration") }
            require(root is Map<*, *>) { "YAML configuration must be a mapping" }
            val result = linkedMapOf<String, String>()
            fun walk(map: Map<*, *>, prefix: String = "") {
                map.forEach { (rawKey, value) ->
                    require(rawKey is String) { "YAML setting names must be strings" }
                    val path = if (prefix.isEmpty()) rawKey else "$prefix.$rawKey"
                    if (path == "version") {
                        require(value == 1 || value == "1") { "Only configuration version 1 is supported" }
                    } else if (path in setOf("dexcom", "nightscout", "carelink", "prediction")) {
                        require(value is Map<*, *>) { "YAML sections must be mappings" }
                        walk(value, path)
                    } else {
                        val key = schema[path] ?: error("Unsupported YAML setting; see the configuration example")
                        val string = when {
                            key == "GLUCOWATCH_ALSO_FROM" && value is List<*> -> {
                                require(value.all { it is String }) { "also_from must contain source names" }
                                value.joinToString(",")
                            }
                            key in setOf("GLUCOWATCH_HEART_TRACK", "DEXCOM_NOTIFICATIONS") && value is Boolean -> value.toString()
                            key == "CARELINK_EXPIRES_AT" && value is Number -> value.toString()
                            value is String -> value
                            else -> error("YAML values must be quoted strings, except booleans, expiry and also_from")
                        }
                        result[key] = string
                    }
                }
            }
            walk(root)
            return result
        }

        private fun validate(v: Map<String, String>) {
            fun field(key: String, check: (String) -> Unit) {
                v[key]?.let { value ->
                    try { check(value) } catch (_: Exception) { error("Invalid $key setting") }
                }
            }
            field("GLUCOWATCH_SOURCE") { if (it.isNotBlank()) sourceOf(it) }
            field("GLUCOWATCH_ALSO_FROM") { list ->
                list.split(',').filter(String::isNotBlank).forEach { require(sourceOf(it) in setOf(LinkSource.NIGHTSCOUT, LinkSource.CARELINK)) }
            }
            field("GLUCOWATCH_UNIT") { GlucoseUnit.parse(it) }
            field("DEXCOM_REGION") { Region.parse(it) }
            field("NIGHTSCOUT_API") { NightscoutApi.parse(it) }
            field("NIGHTSCOUT_URL") { if (it.isNotBlank()) NightscoutAddress.parse(it) }
            field("GLUCOWATCH_HEART_TRACK") { require(it.lowercase() in setOf("true", "false")) }
            field("DEXCOM_NOTIFICATIONS") { require(it.lowercase() in setOf("true", "false")) }
            field("CARELINK_COUNTRY") { require(it.uppercase().matches(Regex("[A-Z]{2}"))) }
            field("HF_MODEL_ADDRESS") { if (it.isNotBlank() && !HuggingFaceModel.isSearch(it)) HuggingFaceModel.parse(it) }
            val sessionKeys = listOf("CARELINK_CLIENT_ID", "CARELINK_ACCESS_TOKEN", "CARELINK_REFRESH_TOKEN", "CARELINK_EXPIRES_AT")
            if (sessionKeys.any { !v[it].isNullOrEmpty() }) {
                require(sessionKeys.all { !v[it].isNullOrEmpty() }) { "CareLink session needs client_id, access_token, refresh_token and expires_at" }
                field("CARELINK_EXPIRES_AT") { require((it.toLongOrNull() ?: 0) > 0) }
                val token = CareLinkToken(v["CARELINK_COUNTRY"] ?: "DE", v.getValue(sessionKeys[0]), v.getValue(sessionKeys[1]),
                    v.getValue(sessionKeys[2]), v.getValue(sessionKeys[3]).toLong())
                require(!token.subject.isNullOrBlank()) { "CareLink access token has no account identifier" }
            }
        }

        private fun sourceOf(text: String): LinkSource = when (text.trim().lowercase()) {
            "dexcom", "share" -> LinkSource.SHARE
            "nightscout" -> LinkSource.NIGHTSCOUT
            "carelink", "medtronic" -> LinkSource.CARELINK
            "demo" -> LinkSource.DEMO
            else -> error("Invalid source")
        }
    }
}
