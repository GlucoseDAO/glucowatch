package glucowatch.core.cli

import glucowatch.core.DexcomShareClient
import glucowatch.core.DotEnv
import glucowatch.core.GlucoseUnit
import glucowatch.core.Predictors
import glucowatch.core.Region
import glucowatch.core.ShareException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Desktop check of the Share connection, without any watch:
 *   ./gradlew -q --console=plain :core:run --args="--hours 1 --predict"
 * Account, region and unit come from the project's `.env` (or environment variables, or
 * --region / --unit flags); a missing username or password is asked for, the password without echo.
 */
fun main(args: Array<String>) {
    val opts = args.toList()
    fun opt(name: String) = opts.indexOf(name).takeIf { it >= 0 }?.let { opts.getOrNull(it + 1) }

    val env = DotEnv.load().filterValues { it.isNotBlank() }
    val region = Region.parse(opt("--region") ?: env["DEXCOM_REGION"] ?: "eu")
    val hours = opt("--hours")?.toInt() ?: 1
    val unit = GlucoseUnit.parse(opt("--unit") ?: env["GLUCOWATCH_UNIT"] ?: "mmol")
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
        return
    }
    val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    readings.takeLast(12).forEach {
        println("${fmt.format(Instant.ofEpochMilli(it.timeMillis))}  ${unit.format(it.mgdl.toDouble()).padStart(5)} ${unit.label} ${it.trend.arrow}")
    }
    val ageMin = (System.currentTimeMillis() - readings.last().timeMillis) / 60_000
    println("Latest reading is $ageMin min old")

    if ("--predict" in opts) {
        Predictors.all.forEach { model ->
            val p = model.predict(readings, 30) ?: return@forEach println("${model.displayName}: no forecast")
            println("${model.displayName}: " + p.points.joinToString("  ") {
                "+${(it.timeMillis - readings.last().timeMillis) / 60_000}m ${unit.format(it.mgdl)}"
            })
        }
    }
}

private fun readLineOrExit(prompt: String): String {
    print(prompt)
    return readlnOrNull()?.takeIf { it.isNotBlank() } ?: exitProcess(2)
}
