package glucowatch.core.cli

import glucowatch.core.DexcomShareClient
import glucowatch.core.DotEnv
import glucowatch.core.GlucoseReading
import glucowatch.core.GlucoseUnit
import glucowatch.core.LoopStatus
import glucowatch.core.NightscoutApi
import glucowatch.core.NightscoutClient
import glucowatch.core.NightscoutException
import glucowatch.core.Predictors
import glucowatch.core.Region
import glucowatch.core.ShareException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Desktop check of the Share or Nightscout connection, without any watch:
 *   ./gradlew -q --console=plain :core:run --args="--hours 1 --predict"
 *   ./gradlew -q --console=plain :core:run --args="--source nightscout --url https://my.site --hours 3"
 * Account, region, Nightscout address and unit come from the project's `.env` (or environment
 * variables, or the flags below); a missing Dexcom username or password is asked for, the password
 * without echo. Flags: --source share|nightscout, --region eu|us|jp, --unit mmol|mgdl,
 * --url, --token, --api v1|v3, --hours N, --predict.
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
                        if (it.insulin > 0) "%.2f U".format(it.insulin) else null,
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
        else -> {
            System.err.println("Unknown --source '$source': use share or nightscout")
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

private fun readLineOrExit(prompt: String): String {
    print(prompt)
    return readlnOrNull()?.takeIf { it.isNotBlank() } ?: exitProcess(2)
}
