package glucowatch.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.math.roundToInt

/** Portal CSVs belong to the pending export, never to a live account's chart cache. */
object HistoricalCsv {
    enum class DateOrder { DAY_FIRST, MONTH_FIRST }
    data class DateOrderInference(val order: DateOrder?, val remember: Boolean = true)
    const val MAX_BYTES = 20 * 1024 * 1024

    fun source(text: String): String {
        require(text.length <= MAX_BYTES) { "CSV exceeds 20 MB" }
        val kinds = records(text.removePrefix("\uFEFF")).mapNotNull { cells ->
            when {
                cells.any { it.startsWith("Timestamp (") } -> ExportRow.SOURCE_DEXCOM_CLARITY
                "Date" in cells && "Time" in cells -> ExportRow.SOURCE_CARELINK
                else -> null
            }
        }.toSet()
        require(kinds.size == 1) { "Use an English CareLink or Clarity CSV data export from one service." }
        return kinds.single()
    }

    /** Inspect every date, including rows outside the requested interval. Null means genuinely ambiguous. */
    fun inferDateOrder(text: String, start: LocalDate? = null, end: LocalDate? = null): DateOrder? =
        inspectDateOrder(text, start, end).order

    fun inspectDateOrder(text: String, start: LocalDate? = null, end: LocalDate? = null): DateOrderInference {
        require(text.length <= MAX_BYTES) { "CSV exceeds 20 MB" }
        val dates = mutableListOf<Pair<LocalDate?, LocalDate?>>()
        var dateColumn = -1
        val numeric = Regex("^(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})$")
        records(text.removePrefix("\uFEFF")).forEach { cells ->
            if ("Date" in cells && "Time" in cells) { dateColumn = cells.indexOf("Date"); return@forEach }
            val match = cells.getOrNull(dateColumn)?.trim()?.let(numeric::matchEntire) ?: return@forEach
            val (a, b, year) = match.destructured
            fun day(d: String, m: String) = runCatching { LocalDate.of(year.toInt(), m.toInt(), d.toInt()) }.getOrNull()
            dates += day(a, b) to day(b, a)
        }
        if (dates.isEmpty()) return DateOrderInference(DateOrder.DAY_FIRST, false) // ISO dates.
        val dayValid = dates.all { it.first != null }
        val monthValid = dates.all { it.second != null }
        require(dayValid || monthValid) { "CSV contains invalid dates or mixed day/month formats; no rows were imported." }
        if (dayValid != monthValid) return DateOrderInference(if (dayValid) DateOrder.DAY_FIRST else DateOrder.MONTH_FIRST)
        if (dates.all { it.first == it.second }) return DateOrderInference(DateOrder.DAY_FIRST, false)
        if (start != null && end != null) {
            fun inside(day: LocalDate?) = day != null && !day.isBefore(start) && !day.isAfter(end)
            val dayIn = dates.any { inside(it.first) }
            val monthIn = dates.any { inside(it.second) }
            if (dayIn != monthIn) return DateOrderInference(if (dayIn) DateOrder.DAY_FIRST else DateOrder.MONTH_FIRST)
        }
        return DateOrderInference(null)
    }

    fun parse(text: String, source: String, zone: ZoneId, order: DateOrder = DateOrder.DAY_FIRST): List<ExportRow> {
        require(text.length <= MAX_BYTES) { "CSV exceeds 20 MB" }
        val records = records(text.removePrefix("\uFEFF"))
        var header = emptyList<String>()
        val output = mutableListOf<ExportRow>()
        var invalid = 0
        records.forEachIndexed { recordIndex, cells ->
            if (cells.any { it.startsWith("Timestamp (") } || ("Date" in cells && "Time" in cells)) {
                val clarity = cells.any { it.startsWith("Timestamp (") }
                require((source == ExportRow.SOURCE_DEXCOM_CLARITY) == clarity) {
                    "This CSV belongs to the other service. Select the ${if (source == ExportRow.SOURCE_CARELINK) "CareLink" else "Clarity"} download."
                }
                header = cells
                return@forEachIndexed
            }
            if (header.isEmpty() || cells.all(String::isBlank)) return@forEachIndexed
            // CareLink repeats the full header after a short device-description record.
            // An indexed event with a bad date must still fail the entire import.
            val next = records.getOrNull(recordIndex + 1).orEmpty()
            val index = header.indexOf("Index").takeIf { it >= 0 }?.let { cells.getOrNull(it) }
            if (source == ExportRow.SOURCE_CARELINK && cells.size < header.size &&
                "Date" in next && "Time" in next && index != null &&
                index.replace(',', '.').toDoubleOrNull() == null) return@forEachIndexed
            val fields = header.zip(cells).toMap()
            fun value(vararg names: String): String? = names.firstNotNullOfOrNull { name -> fields[name]?.trim()?.takeIf(String::isNotEmpty) }
            fun number(vararg names: String): Double? = value(*names)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            val timestamp = header.firstOrNull { it.startsWith("Timestamp (") }?.let { fields[it] }
                ?: listOfNotNull(value("Date"), value("Time")).joinToString(" ")
            if (timestamp.isBlank()) return@forEachIndexed
            val time = parseTime(timestamp, zone, order)
            if (time == null) { invalid++; return@forEachIndexed }
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
                val type = value("Bolus Type")
                val extended = type?.let { it.contains("square", true) || it.contains("extended", true) } == true
                output += ExportRow(time, source, ExportRow.KIND_BOLUS, insulinU = delivered,
                    insulinKind = InsulinKind.BOLUS.name, bolusType = type,
                    programmedFastU = programmed.takeUnless { extended }, deliveredFastU = delivered.takeUnless { extended },
                    programmedExtendedU = programmed.takeIf { extended }, deliveredExtendedU = delivered.takeIf { extended },
                    durationMin = duration(value("Bolus Duration (h:mm:ss)")),
                    markerType = event.takeIf(String::isNotBlank), detail = detail, bolusId = value("Bolus Number"))
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
            number("BWZ Active Insulin (U)", "Active Insulin (U)")?.let {
                output += ExportRow(time, source, ExportRow.KIND_IOB, iobU = it)
                emitted = true
            }
            val isf = number("BWZ Insulin Sensitivity (mg/dL/U)")
            val icr = number("BWZ Carb Ratio (g/U)")
            if (isf != null || icr != null) {
                output += ExportRow(time, source, "pump_therapy_settings", pumpIsfMgdlPerU = isf,
                    pumpIcrGPerU = icr, detail = detail)
                emitted = true
            }
            number("Sensor Calibration BG (mg/dL)")?.let { row(ExportRow.KIND_CALIBRATION, reading = it) }
            number("BG Reading (mg/dL)")?.let { row(ExportRow.KIND_METER, reading = it) }
            // Suspend/resume can share a timestamp and rate with another native record.
            // Keep a separate state event so rate deduplication cannot discard it.
            if (value("Suspend") != null) row(ExportRow.KIND_SUSPEND)
            if (!emitted) row(when {
                value("Suspend") != null || event.contains("suspend", true) -> ExportRow.KIND_SUSPEND
                event.contains("alarm", true) || event.contains("alert", true) -> ExportRow.KIND_ALARM
                else -> ExportRow.KIND_OTHER
            })
        }
        require(header.isNotEmpty()) { "Unrecognized CSV. Use an English Dexcom Clarity or CareLink data export." }
        require(invalid == 0) { "$invalid CSV timestamps use an unsupported or invalid format; no rows were imported." }
        require(output.isNotEmpty()) { "CSV contains no dated events" }
        return output.distinctBy { it.source + it.identity() }.sortedBy { it.timeMillis }
    }

    private fun parseTime(raw: String, zone: ZoneId, order: DateOrder): Long? {
        val s = raw.trim()
        runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        val patterns = listOf("uuuu/M/d H:mm:ss", "uuuu/M/d H:mm", "uuuu-MM-dd'T'HH:mm:ss", "uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd HH:mm", "uuuu-MM-dd'T'HH:mm:ss.SSS") +
            (if (order == DateOrder.DAY_FIRST) listOf("d/M/uuuu HH:mm:ss", "d/M/uuuu HH:mm", "d-M-uuuu HH:mm:ss")
             else listOf("M/d/uuuu HH:mm:ss", "M/d/uuuu HH:mm", "M/d/uuuu h:mm:ss a", "M-d-uuuu HH:mm:ss"))
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
