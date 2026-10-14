package glucowatch.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The device whose start date bounds a coordinated export. Glucose and pump rows share that day. */
enum class LatestDevice(val label: String) {
    DEXCOM_SENSOR("Dexcom sensor"),
    MINIMED_PUMP("MiniMed pump"),
    ;

    companion object {
        /** Accepts the names used in `.env` and YAML, and the enum name stored on the phone. */
        fun parse(text: String): LatestDevice = when (text.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "dexcom", "dexcom_sensor", "sensor" -> DEXCOM_SENSOR
            "minimed", "minimed_pump", "pump", "carelink", "medtronic" -> MINIMED_PUMP
            else -> error("Invalid latest device")
        }
    }
}

enum class ExportContent(val label: String) {
    SENSOR_AND_PUMP("Sensor and pump"), SENSOR("Sensor"), PUMP("Pump");
    val sensor get() = this != PUMP
    val pump get() = this != SENSOR

    fun includes(row: ExportRow): Boolean {
        val sensorEvent = row.kind in setOf(ExportRow.KIND_GLUCOSE, ExportRow.KIND_CALIBRATION, ExportRow.KIND_METER)
        return when (this) {
            SENSOR_AND_PUMP -> true
            SENSOR -> sensorEvent || (row.source == ExportRow.SOURCE_DEXCOM_CLARITY && row.insulinU == null && row.carbsG == null)
            PUMP -> row.source == ExportRow.SOURCE_CARELINK && !sensorEvent
        }
    }
}

/**
 * The inclusive dates an export may contain. Coordinated exports bound [start] to the device day;
 * single-device and explicitly incomplete exports retain the requested start.
 * Built only by [resolve], after the device, its start date, and the requested interval are known.
 */
data class ExportWindow(
    val device: LatestDevice,
    val deviceSince: LocalDate,
    val start: LocalDate,
    val end: LocalDate,
    val requestedStart: LocalDate,
    val content: ExportContent = ExportContent.SENSOR_AND_PUMP,
    val allowIncomplete: Boolean = false,
) {
    val clipped: Boolean get() = start.isAfter(requestedStart)

    fun contains(timeMillis: Long, zone: ZoneId): Boolean {
        val day = Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate()
        return !day.isBefore(start) && !day.isAfter(end)
    }

    companion object {
        const val MIN_DAYS = 1
        const val MAX_DAYS = 90

        fun resolve(device: LatestDevice, deviceSince: LocalDate, end: LocalDate, days: Int, today: LocalDate,
                    content: ExportContent = ExportContent.SENSOR_AND_PUMP, allowIncomplete: Boolean = false): ExportRequest {
            if (days !in MIN_DAYS..MAX_DAYS) return ExportRequest.Refused("Choose a whole number of days from $MIN_DAYS to $MAX_DAYS.")
            if (end.isAfter(today)) return ExportRequest.Refused("The end date is after today.")
            val coordinated = content == ExportContent.SENSOR_AND_PUMP && !allowIncomplete
            if (coordinated && deviceSince.isAfter(today)) return ExportRequest.Refused("The ${device.label} start date is after today.")
            if (coordinated && end.isBefore(deviceSince)) {
                return ExportRequest.Refused(
                    "No export. The interval ends on $end, which is before the ${device.label} start on $deviceSince.",
                )
            }
            val requestedStart = end.minusDays((days - 1).toLong())
            val start = if (coordinated && requestedStart.isBefore(deviceSince)) deviceSince else requestedStart
            return ExportRequest.Ready(ExportWindow(device, deviceSince, start, end, requestedStart, content, allowIncomplete))
        }
    }
}

sealed class ExportRequest {
    data class Ready(val window: ExportWindow) : ExportRequest()
    data class Refused(val message: String) : ExportRequest()
}
