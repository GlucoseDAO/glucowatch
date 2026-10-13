package glucowatch.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.math.roundToInt

/** Portal CSVs are kept in memory during export, never merged into a live account's chart. */
object HistoricalCsv {
    enum class DateOrder { DAY_FIRST, MONTH_FIRST }
    const val MAX_BYTES = 20 * 1024 * 1024

    fun parse(text: String, source: String, zone: ZoneId, order: DateOrder = DateOrder.DAY_FIRST): List<ExportRow> {
        require(text.length <= MAX_BYTES) { "CSV exceeds 20 MB" }
        val records = records(text.removePrefix("\uFEFF"))
        var header = emptyList<String>()
        val output = mutableListOf<ExportRow>()
        var invalid = 0
        records.forEach { cells ->
            if (cells.any { it.startsWith("Timestamp (") } || ("Date" in cells && "Time" in cells)) {
                val clarity = cells.any { it.startsWith("Timestamp (") }
                require((source == ExportRow.SOURCE_DEXCOM_CLARITY) == clarity) {
                    "This CSV belongs to the other service. Select the ${if (source == ExportRow.SOURCE_CARELINK) "CareLink" else "Clarity"} download."
                }
                header = cells
                return@forEach
            }
            if (header.isEmpty() || cells.all(String::isBlank)) return@forEach
            val fields = header.zip(cells).toMap()
            fun value(vararg names: String): String? = names.firstNotNullOfOrNull { name -> fields[name]?.trim()?.takeIf(String::isNotEmpty) }
            fun number(vararg names: String): Double? = value(*names)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            val timestamp = header.firstOrNull { it.startsWith("Timestamp (") }?.let { fields[it] }
                ?: listOfNotNull(value("Date"), value("Time")).joinToString(" ")
            if (timestamp.isBlank()) return@forEach
            val time = parseTime(timestamp, zone, order)
            if (time == null) { invalid++; return@forEach }
            val detail = buildJsonObject {
                fields.filter { (k, v) -> k.isNotBlank() && v.isNotBlank() &&
                    !Regex("token|password|secret|patient|name", RegexOption.IGNORE_CASE).containsMatchIn(k) }
                    .forEach { (k, v) -> put(k, v.take(500)) }
            }.toString()
            val glucose = number("Glucose Value (mg/dL)", "Sensor Glucose (mg/dL)")
                ?: number("Glucose Value (mmol/L)", "Sensor Glucose (mmol/L)")?.times(18.0)
            val event = value("Event Type", "Raw-Type", "Event Marker").orEmpty()
            fun duration(raw: String?): Double? {
                val parts = raw?.split(':')?.map { it.toDoubleOrNull() ?: return null } ?: return null
                return when (parts.size) { 3 -> parts[0] * 60 + parts[1] + parts[2] / 60; 2 -> parts[0] * 60 + parts[1]; else -> null }
            }
            var emitted = false
            fun row(kind: String, mgdl: Int? = null, insulin: Double? = null, carbs: Double? = null,
                    rate: Double? = null, reading: Double? = null, insulinKind: String? = null) {
                output += ExportRow(time, source, kind, glucoseMgdl = mgdl, insulinU = insulin,
                    insulinKind = insulinKind, carbsG = carbs, basalRateUPerH = rate,
                    value = reading, valueUnit = if (reading != null) "mg/dL" else null,
                    markerType = event.takeIf(String::isNotBlank), detail = if (kind in setOf(ExportRow.KIND_GLUCOSE, ExportRow.KIND_METER, ExportRow.KIND_CALIBRATION))
                        buildJsonObject {
                            fields.filterKeys { it in setOf("Date", "Time", "Event Type", "Note", "Sensor Glucose (mg/dL)", "Sensor Glucose (mmol/L)", "Glucose Value (mg/dL)", "Glucose Value (mmol/L)", "BG Reading (mg/dL)", "Sensor Calibration BG (mg/dL)") || it.startsWith("Timestamp (") }
                                .forEach { (k, v) -> if (v.isNotBlank()) put(k, v.take(500)) }
                        }.toString() else detail)
                emitted = true
            }
            if (glucose != null && glucose >= 40) row(ExportRow.KIND_GLUCOSE, mgdl = glucose.roundToInt())
            val delivered = number("Bolus Volume Delivered (U)")
            val programmed = number("Bolus Volume Selected (U)")
            if (delivered != null || programmed != null) {
                output += ExportRow(time, source, ExportRow.KIND_BOLUS, insulinU = delivered,
                    insulinKind = InsulinKind.BOLUS.name, bolusType = value("Bolus Type"),
                    programmedFastU = programmed, deliveredFastU = delivered, durationMin = duration(value("Bolus Duration (h:mm:ss)")),
                    markerType = event.takeIf(String::isNotBlank), detail = detail)
                emitted = true
            }
            number("Temp Basal Amount")?.let { amount ->
                val percent = value("Temp Basal Type")?.contains("percent", true) == true
                output += ExportRow(time, source, ExportRow.KIND_TEMP_BASAL, insulinKind = InsulinKind.BASAL.name,
                    basalRateUPerH = if (percent) null else amount, basalPercent = if (percent) amount else null,
                    durationMin = duration(value("Temp Basal Duration (h:mm:ss)")), percentOfProfile = percent,
                    markerType = event.takeIf(String::isNotBlank), detail = detail)
                emitted = true
            }
            // Clarity insulin is a logged dose, not measured pump delivery: retain it as an event.
            if (source == ExportRow.SOURCE_DEXCOM_CLARITY && number("Insulin Value (u)", "Insulin Value (U)") != null)
                row(ExportRow.KIND_OTHER, insulin = number("Insulin Value (u)", "Insulin Value (U)"))
            number("BWZ Carb Input (grams)", "Carb Value (grams)")?.let { row(ExportRow.KIND_MEAL, carbs = it) }
            number("Basal Rate (U/h)")?.let { row(ExportRow.KIND_REPORTED_BASAL, rate = it, insulinKind = InsulinKind.BASAL.name) }
            number("Sensor Calibration BG (mg/dL)")?.let { row(ExportRow.KIND_CALIBRATION, reading = it) }
            number("BG Reading (mg/dL)")?.let { row(ExportRow.KIND_METER, reading = it) }
            if (!emitted) row(when {
                value("Suspend") != null || event.contains("suspend", true) -> ExportRow.KIND_SUSPEND
                event.contains("alarm", true) || event.contains("alert", true) -> ExportRow.KIND_ALARM
                else -> ExportRow.KIND_OTHER
            })
        }
        require(header.isNotEmpty()) { "Unrecognized CSV. Use an English Dexcom Clarity or CareLink data export." }
        require(invalid == 0) { "$invalid CSV timestamps could not be read. Check day/month order and time zone; no rows were imported." }
        require(output.isNotEmpty()) { "CSV contains no dated events" }
        return output.distinctBy { it.source + it.identity() }.sortedBy { it.timeMillis }
    }

    private fun parseTime(raw: String, zone: ZoneId, order: DateOrder): Long? {
        val s = raw.trim()
        runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        val patterns = listOf("uuuu-MM-dd'T'HH:mm:ss", "uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd HH:mm", "uuuu-MM-dd'T'HH:mm:ss.SSS") +
            (if (order == DateOrder.DAY_FIRST) listOf("d/M/uuuu HH:mm:ss", "d/M/uuuu HH:mm", "d-M-uuuu HH:mm:ss")
             else listOf("M/d/uuuu HH:mm:ss", "M/d/uuuu HH:mm", "M/d/uuuu h:mm:ss a"))
        return patterns.firstNotNullOfOrNull { pattern -> runCatching {
            LocalDateTime.parse(s, DateTimeFormatter.ofPattern(pattern, java.util.Locale.US).withResolverStyle(ResolverStyle.STRICT))
                .atZone(zone).toInstant().toEpochMilli()
        }.getOrNull() }
    }

    /** RFC4180 quoting including embedded newlines; tolerate CareLink's semicolon delimiter. */
    internal fun records(text: String): List<List<String>> {
        val delimiter = if (text.lineSequence().any { it.trimStart().startsWith("Index;") || it.trimStart().startsWith("Date;") || it.trimStart().startsWith("\"Index\";") || it.trimStart().startsWith("\"Date\";") }) ';' else ','
        val result = mutableListOf<List<String>>()
        var cells = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun cell() { cells += field.toString(); field.setLength(0) }
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> cell()
                (ch == '\n' || ch == '\r') && !quoted -> {
                    cell(); result += cells; cells = mutableListOf()
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> field.append(ch)
            }
            i++
        }
        require(!quoted) { "CSV has an unfinished quoted field" }
        if (field.isNotEmpty() || cells.isNotEmpty()) { cell(); result += cells }
        return result
    }
}
