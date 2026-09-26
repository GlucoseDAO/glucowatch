package glucowatch.core.cli

import glucowatch.core.CareLinkClient
import glucowatch.core.CareLinkException
import glucowatch.core.CareLinkLogin
import glucowatch.core.CareLinkToken
import glucowatch.core.DexcomShareClient
import glucowatch.core.DotEnv
import glucowatch.core.GlucoseReading
import glucowatch.core.GlucoseUnit
import glucowatch.core.HttpTransport
import glucowatch.core.UrlConnectionTransport
import glucowatch.core.LoopStatus
import glucowatch.core.NightscoutApi
import glucowatch.core.NightscoutClient
import glucowatch.core.NightscoutException
import glucowatch.core.Predictors
import glucowatch.core.Region
import glucowatch.core.ShareException
import glucowatch.core.Treatment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Desktop check of the Share, Nightscout or CareLink connection, without any watch:
 *   ./gradlew -q --console=plain :core:run --args="--hours 1 --predict"
 *   ./gradlew -q --console=plain :core:run --args="--source nightscout --url https://my.site --hours 3"
 *   ./gradlew -q --console=plain :core:run --args="--source carelink --hours 3"
 * Account, region, Nightscout address and unit come from the project's `.env` (or environment
 * variables, or the flags below); a missing Dexcom username or password is asked for, the password
 * without echo. CareLink reads and refreshes the tokens scripts/carelink_login.py saved
 * (CARELINK_TOKEN_FILE, default ~/.config/glucowatch/carelink-token.json).
 * Flags: --source share|nightscout|carelink, --region eu|us|jp, --unit mmol|mgdl,
 * --url, --token, --api v1|v3, --hours N, --predict, --trace (CareLink: each request's URL,
 * status and JSON keys, never the values).
 */
fun main(args: Array<String>) {
    val opts = args.toList()
    fun opt(name: String) = opts.indexOf(name).takeIf { it >= 0 }?.let { opts.getOrNull(it + 1) }

    val env = DotEnv.load().filterValues { it.isNotBlank() }
    val hours = opt("--hours")?.toInt() ?: 1
    val unit = GlucoseUnit.parse(opt("--unit") ?: env["GLUCOWATCH_UNIT"] ?: "mmol")
    val source = (opt("--source") ?: env["GLUCOWATCH_SOURCE"] ?: if (opt("--url") != null) "nightscout" else "share").lowercase()
    val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    val now = System.currentTimeMillis()

    var loop: LoopStatus? = null
    val readings = when (source) {
        "share", "dexcom" -> fetchShare(env, ::opt, hours)
        "nightscout", "ns" -> {
            val url = opt("--url") ?: env["NIGHTSCOUT_URL"] ?: readLineOrExit("Nightscout address: ")
            val api = NightscoutApi.parse(opt("--api") ?: env["NIGHTSCOUT_API"] ?: "v1")
            val client = NightscoutClient(url, opt("--token") ?: env["NIGHTSCOUT_TOKEN"] ?: "", api)
            try {
                val readings = client.readings(sinceMillis = now - hours * 3_600_000L)
                println("Nightscout OK (${client.baseUrl}, ${api.label}), ${readings.size} readings in the last $hours h")
                val treatments = client.treatments(sinceMillis = now - hours * 3_600_000L)
                treatments.forEach {
                    val what = listOfNotNull(
                        if (it.isBasal) it.basalDescription() else if (it.isBolus) "Bolus %.2f U".format(it.insulin) else null,
                        if (it.carbs > 0) "%.0f g".format(it.carbs) else null,
                        if (it.automatic) "automatic" else null,
                    )
                    println("${fmt.format(Instant.ofEpochMilli(it.timeMillis))}  treatment ${what.joinToString(", ")}")
                }
                loop = client.loopStatus(sinceMillis = now - 2 * 3_600_000L)
                loop?.let {
                    println(
                        "Loop ${it.ageMinutes(now)} min ago: IOB ${it.iob ?: "-"} U, COB ${it.cob ?: "-"} g, " +
                            "eventual ${it.eventualMgdl?.let(unit::format) ?: "-"}, forecast ${it.forecastName ?: "none"} (${it.forecast.size} points)",
                    )
                } ?: println("No loop status in the last 2 h")
                readings
            } catch (e: NightscoutException) {
                System.err.println("FAILED: ${e.message}")
                exitProcess(1)
            }
        }
        "carelink", "cl" -> fetchCareLink(env, "--trace" in opts, hours, fmt)
        else -> {
            System.err.println("Unknown --source '$source': use share, nightscout or carelink")
            exitProcess(2)
        }
    }

    if (readings.isEmpty()) {
        println("No readings in the last $hours h.")
        return
    }
    readings.takeLast(12).forEach {
        println("${fmt.format(Instant.ofEpochMilli(it.timeMillis))}  ${unit.format(it.mgdl.toDouble()).padStart(5)} ${unit.label} ${it.trend.arrow}")
    }
    val ageMin = (now - readings.last().timeMillis) / 60_000
    println("Latest reading is $ageMin min old")

    if ("--predict" in opts) {
        Predictors.all.forEach { model ->
            val p = model.predict(readings, 30) ?: return@forEach println("${model.displayName}: no forecast")
            println("${model.displayName}: " + p.points.joinToString("  ") {
                "+${(it.timeMillis - readings.last().timeMillis) / 60_000}m ${unit.format(it.mgdl)}"
            })
        }
        loop?.let { status ->
            val p = status.prediction(readings.last().timeMillis, 30, now)
                ?: return@let println("Loop forecast: none, or older than ${LoopStatus.FORECAST_STALE_MINUTES} min")
            println("Loop forecast (${status.forecastName}): " + p.points.joinToString("  ") {
                "+${(it.timeMillis - readings.last().timeMillis) / 60_000}m ${unit.format(it.mgdl)}"
            })
        }
    }
}

private fun fetchShare(env: Map<String, String>, opt: (String) -> String?, hours: Int): List<GlucoseReading> {
    val region = Region.parse(opt("--region") ?: env["DEXCOM_REGION"] ?: "eu")
    val user = env["DEXCOM_USERNAME"] ?: readLineOrExit("Dexcom username: ")
    val pass = env["DEXCOM_PASSWORD"]
        ?: System.console()?.readPassword("Dexcom password: ")?.let(::String)
        ?: readLineOrExit("Dexcom password: ")
    val client = DexcomShareClient(region, user, pass)
    val readings = try {
        client.readings(minutes = hours * 60, maxCount = (hours * 12).coerceIn(1, 288))
    } catch (e: ShareException) {
        System.err.println("FAILED: ${e.message}")
        exitProcess(1)
    }
    println("Login OK (${region.label}), ${readings.size} readings in the last $hours h")
    if (readings.isEmpty()) {
        println("No readings: check that Share is ON in the Dexcom app and that you have at least one follower.")
    }
    return readings
}

/** The token file scripts/carelink_login.py wrote; refreshed tokens are written back to it. */
private class FileLogin(private val file: File) : CareLinkLogin {
    override fun load() = CareLinkToken.decode(file.takeIf { it.isFile }?.readText())

    override fun replace(old: CareLinkToken, new: CareLinkToken) {
        if (load()?.refreshToken != old.refreshToken) return
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(new.encode())
        tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true)
        tmp.renameTo(file)
    }
}

private fun fetchCareLink(env: Map<String, String>, trace: Boolean, hours: Int, fmt: DateTimeFormatter): List<GlucoseReading> {
    val file = File(env["CARELINK_TOKEN_FILE"] ?: (System.getProperty("user.home") + "/.config/glucowatch/carelink-token.json"))
    val login = FileLogin(file)
    val token = login.load() ?: run {
        System.err.println("No CareLink sign-in at $file. Run: uv run --with playwright scripts/carelink_login.py")
        exitProcess(2)
    }
    val base = UrlConnectionTransport()
    val transport = if (!trace) base else HttpTransport { request ->
        base.execute(request).also { response ->
            val json = runCatching { Json.parseToJsonElement(response.body) }.getOrNull()
            System.err.println("${request.method} ${request.url.substringBefore('?')} -> ${response.status} ${json?.let(::shape) ?: "(${response.body.length} bytes)"}")
        }
    }
    val now = System.currentTimeMillis()
    val data = try {
        CareLinkClient(login, transport = transport).recent(now)
    } catch (e: CareLinkException) {
        System.err.println("FAILED: ${e.message}")
        exitProcess(1)
    }
    val since = now - hours * 3_600_000L
    println("CareLink OK (${token.country}), ${data.readings.size} readings in the last day, last upload " +
        (data.lastUploadMillis?.let { "${(now - it) / 60_000} min ago" } ?: "unknown"))
    data.treatments.filter { it.timeMillis >= since }.forEach { println("${fmt.format(Instant.ofEpochMilli(it.timeMillis))}  treatment ${describe(it)}") }
    data.loop?.let { println("Active insulin ${it.iob} U, ${it.ageMinutes(now)} min ago") } ?: println("No active insulin reported")
    return data.readings.filter { it.timeMillis >= since }
}

private fun describe(t: Treatment) = listOfNotNull(
    if (t.isBasal) t.basalDescription() else if (t.isBolus) "Bolus %.2f U".format(t.insulin) else null,
    if (t.carbs > 0) "%.0f g".format(t.carbs) else null,
    if (t.automatic) "automatic" else null,
).joinToString(", ")

/** Keys and array sizes of a JSON answer, two levels deep, without any values. */
private fun shape(json: JsonElement, depth: Int = 0): String = when (json) {
    is JsonObject -> if (depth >= 2) "{…}" else json.entries.joinToString(", ", "{", "}") { (k, v) -> if (v is JsonObject || v is JsonArray) "$k: ${shape(v, depth + 1)}" else k }
    is JsonArray -> "[${json.size}${json.firstOrNull()?.takeIf { depth < 2 }?.let { " × " + shape(it, depth + 1) }.orEmpty()}]"
    else -> "value"
}

private fun readLineOrExit(prompt: String): String {
    print(prompt)
    return readlnOrNull()?.takeIf { it.isNotBlank() } ?: exitProcess(2)
}
