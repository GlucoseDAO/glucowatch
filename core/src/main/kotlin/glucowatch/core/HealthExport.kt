package glucowatch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One event in an export file. A row is a sensor point, a pump event, or a snapshot of pump
 * state. Columns that do not apply to that kind are null and become empty cells.
 * docs/export.md is the contract for this layout.
 */
data class ExportRow(
    val timeMillis: Long,
    val source: String,
    val kind: String,
    val glucoseMgdl: Int? = null,
    val trend: String? = null,
    val sensorState: String? = null,
    val insulinU: Double? = null,
    val insulinKind: String? = null,
    val automatic: Boolean? = null,
    val activationType: String? = null,
    val bolusType: String? = null,
    val programmedFastU: Double? = null,
    val deliveredFastU: Double? = null,
    val programmedExtendedU: Double? = null,
    val deliveredExtendedU: Double? = null,
    val carbsG: Double? = null,
    val basalRateUPerH: Double? = null,
    val basalPercent: Double? = null,
    val durationMin: Double? = null,
    val percentOfProfile: Boolean? = null,
    val iobU: Double? = null,
    val value: Double? = null,
    val valueUnit: String? = null,
    val markerType: String? = null,
    val detail: String? = null,
    val pumpIsfMgdlPerU: Double? = null,
    val pumpIcrGPerU: Double? = null,
    val bolusId: String? = null,
) {
    /** The chart's reading, when this row is a usable sensor glucose. */
    fun toReading(): GlucoseReading? {
        val mgdl = glucoseMgdl ?: return null
        if (kind != KIND_GLUCOSE) return null
        return GlucoseReading(timeMillis, mgdl, Trend.parse(trend ?: Trend.None.name))
    }

    fun identity(): String = listOf(
        timeMillis, kind, markerType.orEmpty(), glucoseMgdl?.toString().orEmpty(),
        num(insulinU), num(carbsG), num(basalRateUPerH), num(basalPercent), num(value),
        if (kind == KIND_BOLUS) listOf(bolusType.orEmpty(), num(programmedFastU), num(deliveredFastU),
            num(programmedExtendedU), num(deliveredExtendedU), num(durationMin)).joinToString(":") else "",
        num(pumpIsfMgdlPerU), num(pumpIcrGPerU), bolusId.orEmpty(),
    ).joinToString("|")

    private fun toJson(): JsonObject = buildJsonObject {
        put("timeMillis", timeMillis)
        put("source", source)
        put("kind", kind)
        glucoseMgdl?.let { put("glucoseMgdl", it) }
        trend?.let { put("trend", it) }
        sensorState?.let { put("sensorState", it) }
        insulinU?.let { put("insulinU", it) }
        insulinKind?.let { put("insulinKind", it) }
        automatic?.let { put("automatic", it) }
        activationType?.let { put("activationType", it) }
        bolusType?.let { put("bolusType", it) }
        programmedFastU?.let { put("programmedFastU", it) }
        deliveredFastU?.let { put("deliveredFastU", it) }
        programmedExtendedU?.let { put("programmedExtendedU", it) }
        deliveredExtendedU?.let { put("deliveredExtendedU", it) }
        carbsG?.let { put("carbsG", it) }
        basalRateUPerH?.let { put("basalRateUPerH", it) }
        basalPercent?.let { put("basalPercent", it) }
        durationMin?.let { put("durationMin", it) }
        percentOfProfile?.let { put("percentOfProfile", it) }
        iobU?.let { put("iobU", it) }
        value?.let { put("value", it) }
        valueUnit?.let { put("valueUnit", it) }
        markerType?.let { put("markerType", it) }
        detail?.let { put("detail", it) }
        pumpIsfMgdlPerU?.let { put("pumpIsfMgdlPerU", it) }
        pumpIcrGPerU?.let { put("pumpIcrGPerU", it) }
        bolusId?.let { put("bolusId", it) }
    }

    companion object {
        const val SOURCE_CARELINK = "carelink"
        const val SOURCE_DEXCOM_SHARE = "dexcom_share"
        const val SOURCE_DEXCOM_CLARITY = "dexcom_clarity"
        const val SOURCE_DEXCOM_NOTIFICATIONS = "dexcom_notifications"

        const val KIND_GLUCOSE = "sensor_glucose"
        const val KIND_BOLUS = "bolus"
        const val KIND_MEAL = "meal"
        const val KIND_BASAL_DELIVERY = "basal_delivery"
        const val KIND_TEMP_BASAL = "temp_basal"
        const val KIND_REPORTED_BASAL = "reported_basal"
        const val KIND_IOB = "active_insulin"
        const val KIND_CALIBRATION = "calibration"
        const val KIND_METER = "meter_bg"
        const val KIND_AUTO_MODE = "auto_mode"
        const val KIND_SUSPEND = "suspend"
        const val KIND_ALARM = "alarm"
        const val KIND_DEVICE = "device"
        const val KIND_LIMIT = "limit"
        const val KIND_NOTIFICATION = "notification"
        const val KIND_ALGORITHM = "algorithm"
        const val KIND_BANNER = "banner"
        const val KIND_OTHER = "other"

        fun fromReading(reading: GlucoseReading, source: String) = ExportRow(
            timeMillis = reading.timeMillis,
            source = source,
            kind = KIND_GLUCOSE,
            glucoseMgdl = reading.mgdl,
            trend = reading.trend.name,
        )

        /**
         * Rows rebuilt from the chart cache. They have the fields the app stored, which is less
         * than a fresh CareLink payload (no programmed-versus-delivered split, no sensor state).
         */
        fun fromCareLinkCache(
            readings: List<GlucoseReading>,
            treatments: List<Treatment>,
            loop: LoopStatus?,
        ): List<ExportRow> {
            val events = treatments.mapNotNull { treatment ->
                when {
                    treatment.isBolus -> ExportRow(
                        treatment.timeMillis, SOURCE_CARELINK, KIND_BOLUS,
                        insulinU = treatment.insulin, insulinKind = InsulinKind.BOLUS.name,
                        automatic = treatment.automatic, markerType = "INSULIN",
                    )
                    treatment.durationMinutes != null -> ExportRow(
                        treatment.timeMillis, SOURCE_CARELINK, KIND_TEMP_BASAL,
                        insulinKind = InsulinKind.BASAL.name,
                        basalRateUPerH = treatment.basalRate,
                        basalPercent = treatment.basalPercent,
                        durationMin = treatment.durationMinutes,
                        percentOfProfile = treatment.percentOfProfile,
                        markerType = "TEMP_BASAL",
                    )
                    treatment.isBasal && treatment.insulin > 0 -> ExportRow(
                        treatment.timeMillis, SOURCE_CARELINK, KIND_BASAL_DELIVERY,
                        insulinU = treatment.insulin, insulinKind = InsulinKind.BASAL.name,
                        automatic = true, markerType = "AUTO_BASAL_DELIVERY",
                    )
                    treatment.basalRate != null -> ExportRow(
                        treatment.timeMillis, SOURCE_CARELINK, KIND_REPORTED_BASAL,
                        insulinKind = InsulinKind.BASAL.name,
                        basalRateUPerH = treatment.basalRate,
                        markerType = "BASAL_RATE",
                    )
                    else -> null
                }
            }
            val iob = loop?.iob?.let {
                ExportRow(loop.timeMillis, SOURCE_CARELINK, KIND_IOB, iobU = it, markerType = "ACTIVE_INSULIN")
            }
            val meals = treatments.filter { it.carbs > 0 }.map { treatment ->
                ExportRow(treatment.timeMillis, SOURCE_CARELINK, KIND_MEAL, carbsG = treatment.carbs, markerType = "MEAL")
            }
            return readings.map { fromReading(it, SOURCE_CARELINK) } + events + meals + listOfNotNull(iob)
        }

        private fun fromJson(obj: JsonObject): ExportRow? {
            val time = obj.long("timeMillis") ?: return null
            val source = obj.text("source") ?: return null
            val kind = obj.text("kind") ?: return null
            return ExportRow(
                timeMillis = time,
                source = source,
                kind = kind,
                glucoseMgdl = obj.int("glucoseMgdl"),
                trend = obj.text("trend"),
                sensorState = obj.text("sensorState"),
                insulinU = obj.double("insulinU"),
                insulinKind = obj.text("insulinKind"),
                automatic = obj.bool("automatic"),
                activationType = obj.text("activationType"),
                bolusType = obj.text("bolusType"),
                programmedFastU = obj.double("programmedFastU"),
                deliveredFastU = obj.double("deliveredFastU"),
                programmedExtendedU = obj.double("programmedExtendedU"),
                deliveredExtendedU = obj.double("deliveredExtendedU"),
                carbsG = obj.double("carbsG"),
                basalRateUPerH = obj.double("basalRateUPerH"),
                basalPercent = obj.double("basalPercent"),
                durationMin = obj.double("durationMin"),
                percentOfProfile = obj.bool("percentOfProfile"),
                iobU = obj.double("iobU"),
                value = obj.double("value"),
                valueUnit = obj.text("valueUnit"),
                markerType = obj.text("markerType"),
                detail = obj.text("detail"),
                pumpIsfMgdlPerU = obj.double("pumpIsfMgdlPerU"),
                pumpIcrGPerU = obj.double("pumpIcrGPerU"),
                bolusId = obj.text("bolusId"),
            )
        }

        internal fun encodeJson(rows: List<ExportRow>): String = JsonArray(rows.map { it.toJson() }).toString()

        internal fun decodeJson(text: String): List<ExportRow> {
            val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
            return array.mapNotNull { (it as? JsonObject)?.let(::fromJson) }
        }
    }
}

/** The phone's rolling CareLink log: the last day is replaced on each fetch, older rows stay. */
object ExportLog {
    const val MAX_CHARS = 450_000

    fun encode(rows: List<ExportRow>): String = ExportRow.encodeJson(rows)

    fun decode(text: String): List<ExportRow> = ExportRow.decodeJson(text)

    fun roll(old: List<ExportRow>, fresh: List<ExportRow>, now: Long, retainMs: Long): List<ExportRow> {
        val covered = now - SourceSync.DAY_MS
        val kept = old.filter { it.kind != ExportRow.KIND_DEVICE && it.timeMillis < covered && now - it.timeMillis <= retainMs }
        val incoming = fresh.filter { it.kind != ExportRow.KIND_DEVICE && it.timeMillis >= covered }
        return cap(kept + incoming)
    }

    /** [primary] wins when a row was stored both from the raw payload and from the chart cache. */
    fun prefer(primary: List<ExportRow>, extra: List<ExportRow>): List<ExportRow> {
        val seen = primary.map { it.identity() }.toMutableSet()
        return (primary + extra.filter { seen.add(it.identity()) }).sortedWith(ROW_ORDER)
    }

    fun cap(rows: List<ExportRow>, maxChars: Int = MAX_CHARS): List<ExportRow> {
        val sorted = rows.sortedWith(ROW_ORDER)
        if (ExportRow.encodeJson(sorted).length <= maxChars) return sorted
        var list = sorted
        while (list.size > 1 && ExportRow.encodeJson(list).length > maxChars) {
            list = list.drop((list.size / 8).coerceAtLeast(1))
        }
        return list
    }
}

object ExportCsv {
    val COLUMNS = listOf(
        "time_utc", "time_local", "time_millis", "source", "kind",
        "glucose_mgdl", "trend", "sensor_state",
        "insulin_u", "insulin_kind", "automatic", "activation_type", "bolus_type",
        "programmed_fast_u", "delivered_fast_u", "programmed_extended_u", "delivered_extended_u",
        "carbs_g", "basal_rate_u_per_h", "basal_percent", "duration_min", "percent_of_profile",
        "iob_u", "value", "value_unit", "marker_type", "detail",
        "pump_isf_mgdl_per_u", "pump_icr_g_per_u", "bolus_id",
    )

    private val UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneId.of("UTC"))
    private val LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    fun write(rows: List<ExportRow>, zone: ZoneId): String {
        val local = LOCAL.withZone(zone)
        val body = rows.sortedWith(ROW_ORDER).joinToString("\n") { row ->
            val instant = Instant.ofEpochMilli(row.timeMillis)
            listOf(
                UTC.format(instant),
                local.format(instant),
                row.timeMillis.toString(),
                row.source,
                row.kind,
                row.glucoseMgdl?.toString().orEmpty(),
                row.trend.orEmpty(),
                row.sensorState.orEmpty(),
                num(row.insulinU),
                row.insulinKind.orEmpty(),
                row.automatic?.toString().orEmpty(),
                row.activationType.orEmpty(),
                row.bolusType.orEmpty(),
                num(row.programmedFastU),
                num(row.deliveredFastU),
                num(row.programmedExtendedU),
                num(row.deliveredExtendedU),
                num(row.carbsG),
                num(row.basalRateUPerH),
                num(row.basalPercent),
                num(row.durationMin),
                row.percentOfProfile?.toString().orEmpty(),
                num(row.iobU),
                num(row.value),
                row.valueUnit.orEmpty(),
                row.markerType.orEmpty(),
                row.detail.orEmpty(),
                num(row.pumpIsfMgdlPerU), num(row.pumpIcrGPerU), row.bolusId.orEmpty(),
            ).joinToString(",") { csvField(it) }
        }
        return COLUMNS.joinToString(",") + "\n" + body + if (body.isEmpty()) "" else "\n"
    }

    fun bytes(text: String): ByteArray {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        return bom + text.toByteArray(Charsets.UTF_8)
    }
}

/**
 * Every CareLink field the client can read, as export rows. Times use [shiftMillis], the same
 * clock correction as the chart. A sensor value under 40 mg/dL is kept as a gap: [ExportRow.value]
 * holds the raw number and [ExportRow.glucoseMgdl] stays empty.
 */
internal object CareLinkRows {
    private const val FUTURE_SLACK_MS = 5 * 60_000L
    private const val MAX_NOTIFICATIONS = 200
    private const val MAX_DETAIL = 2_000

    fun build(
        patient: JsonObject,
        shiftMillis: Long,
        now: Long,
        treatments: List<Treatment>,
        loop: LoopStatus?,
        uploadedAt: Long?,
    ): List<ExportRow> {
        val rows = mutableListOf<ExportRow>()
        (patient["sgs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { sg ->
            val time = sg.at(shiftMillis, "timestamp", "datetime") ?: return@forEach
            if (time > now + FUTURE_SLACK_MS) return@forEach
            val raw = sg.double("sg")
            val mgdl = raw?.roundToInt()?.takeIf { it >= 40 }
            val state = sg.text("sensorState") ?: sg.text("sgState") ?: sg.text("kind")
            rows += ExportRow(
                timeMillis = time,
                source = ExportRow.SOURCE_CARELINK,
                kind = ExportRow.KIND_GLUCOSE,
                glucoseMgdl = mgdl,
                sensorState = state,
                value = if (mgdl == null) raw else null,
                valueUnit = if (mgdl == null && raw != null) "mg/dL" else null,
                markerType = "SG",
                detail = leftover(sg, setOf("sg", "timestamp", "datetime", "sensorState", "sgState", "kind")),
            )
        }
        (patient["markers"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { marker ->
            val time = marker.at(shiftMillis, "timestamp", "displayTime", "dateTime") ?: return@forEach
            if (time > now + FUTURE_SLACK_MS) return@forEach
            rows += markerRow(marker, time)
        }
        val tempKeys = rows.filter { it.kind == ExportRow.KIND_TEMP_BASAL }
            .map { it.timeMillis to (it.basalPercent ?: it.basalRateUPerH) }.toSet()
        treatments.forEach { treatment ->
            when {
                treatment.durationMinutes != null -> {
                    val key = treatment.timeMillis to (treatment.basalPercent ?: treatment.basalRate)
                    if (key !in tempKeys) rows += treatment.toTempRow()
                }
                treatment.basalRate != null && treatment.insulin == 0.0 ->
                    rows += ExportRow(
                        treatment.timeMillis, ExportRow.SOURCE_CARELINK, ExportRow.KIND_REPORTED_BASAL,
                        insulinKind = InsulinKind.BASAL.name,
                        basalRateUPerH = treatment.basalRate,
                        markerType = "BASAL_RATE",
                    )
            }
        }
        loop?.iob?.let { iob ->
            rows += ExportRow(
                loop.timeMillis, ExportRow.SOURCE_CARELINK, ExportRow.KIND_IOB,
                iobU = iob, markerType = "ACTIVE_INSULIN",
            )
        }
        val snapshotAt = uploadedAt ?: now
        deviceRow(patient, snapshotAt)?.let { rows += it }
        snapshotList(patient, "limits", ExportRow.KIND_LIMIT, shiftMillis, snapshotAt, rows)
        snapshotList(patient, "notificationHistory", ExportRow.KIND_NOTIFICATION, shiftMillis, snapshotAt, rows)
        (patient["therapyAlgorithmState"] as? JsonObject)?.let { state ->
            rows += snapshot(ExportRow.KIND_ALGORITHM, state.at(shiftMillis, "timestamp", "datetime") ?: snapshotAt, state, "THERAPY_ALGORITHM")
        }
        when (val banner = patient["pumpBannerState"]) {
            is JsonArray -> banner.filterIsInstance<JsonObject>().forEach { item ->
                rows += snapshot(ExportRow.KIND_BANNER, item.at(shiftMillis, "timestamp", "datetime", "dateTime") ?: snapshotAt, item, "BANNER")
            }
            is JsonObject -> rows += snapshot(ExportRow.KIND_BANNER, banner.at(shiftMillis, "timestamp", "datetime") ?: snapshotAt, banner, "BANNER")
            else -> Unit
        }
        return rows.sortedWith(ROW_ORDER)
    }

    private fun markerRow(marker: JsonObject, time: Long): ExportRow {
        val type = marker.text("type")?.uppercase()?.replace(' ', '_')?.replace('-', '_') ?: "UNKNOWN"
        val values = ((marker["data"] as? JsonObject)?.get("dataValues") as? JsonObject) ?: JsonObject(emptyMap())
        val promoted = mutableSetOf(
            "deliveredFastAmount", "programmedFastAmount", "deliveredExtendedAmount", "programmedExtendedAmount",
            "activationType", "bolusType", "insulinUnits", "amount", "bolusAmount",
            "percentage", "percent", "basalPercent", "rate", "tempBasalRate", "basalRate",
            "duration", "programmedDuration", "effectiveDuration", "durationMinutes",
            "basalType", "tempBasalType", "unit", "units", "value", "bgValue", "calibrationValue",
        )
        val detail = leftover(values, promoted) ?: leftover(marker, promoted + setOf("type", "timestamp", "displayTime", "dateTime", "data"))
        return when (type) {
            "INSULIN" -> {
                val fast = values.double("deliveredFastAmount")
                val extended = values.double("deliveredExtendedAmount")
                val delivered = listOfNotNull(fast, extended).sum().takeIf { fast != null || extended != null }
                    ?: values.double("insulinUnits")
                val activation = values.text("activationType")?.uppercase()
                ExportRow(
                    time, ExportRow.SOURCE_CARELINK, ExportRow.KIND_BOLUS,
                    insulinU = delivered?.takeIf { it > 0 },
                    insulinKind = InsulinKind.BOLUS.name,
                    automatic = activation?.let { it == "AUTOCORRECTION" },
                    activationType = activation,
                    bolusType = values.text("bolusType")?.uppercase(),
                    programmedFastU = values.double("programmedFastAmount"),
                    deliveredFastU = fast,
                    programmedExtendedU = values.double("programmedExtendedAmount"),
                    deliveredExtendedU = extended,
                    durationMin = durationMinutes(values, marker),
                    markerType = type,
                    detail = detail,
                )
            }
            "MEAL" -> ExportRow(
                time, ExportRow.SOURCE_CARELINK, ExportRow.KIND_MEAL,
                carbsG = values.double("amount"),
                markerType = type,
                detail = detail,
            )
            "AUTO_BASAL_DELIVERY" -> ExportRow(
                time, ExportRow.SOURCE_CARELINK, ExportRow.KIND_BASAL_DELIVERY,
                insulinU = values.double("bolusAmount"),
                insulinKind = InsulinKind.BASAL.name,
                automatic = true,
                markerType = type,
                detail = detail,
            )
            "TEMP_BASAL", "MANUAL_TEMP_BASAL", "USER_TEMP_BASAL" -> tempRow(type, values, marker, time, detail)
            "CALIBRATION" -> ExportRow(
                time, ExportRow.SOURCE_CARELINK, ExportRow.KIND_CALIBRATION,
                value = values.double("amount") ?: values.double("value") ?: values.double("bgValue") ?: values.double("calibrationValue"),
                valueUnit = values.text("unit") ?: values.text("units") ?: "mg/dL",
                markerType = type,
                detail = detail,
            )
            "BG_READING", "BLOOD_GLUCOSE", "BG" -> ExportRow(
                time, ExportRow.SOURCE_CARELINK, ExportRow.KIND_METER,
                value = values.double("amount") ?: values.double("value") ?: values.double("bgValue"),
                valueUnit = values.text("unit") ?: values.text("units") ?: "mg/dL",
                markerType = type,
                detail = detail,
            )
            "AUTO_MODE_STATUS", "AUTO_MODE" -> ExportRow(
                time, ExportRow.SOURCE_CARELINK, ExportRow.KIND_AUTO_MODE,
                markerType = type,
                detail = detail ?: leftover(values, emptySet()),
            )
            "LOW_GLUCOSE_SUSPENDED", "SUSPEND", "INSULIN_SUSPEND", "DELIVERY_SUSPEND" -> ExportRow(
                time, ExportRow.SOURCE_CARELINK, ExportRow.KIND_SUSPEND,
                durationMin = durationMinutes(values, marker),
                markerType = type,
                detail = detail,
            )
            "ALARM" -> ExportRow(
                time, ExportRow.SOURCE_CARELINK, ExportRow.KIND_ALARM,
                markerType = type,
                detail = detail ?: leftover(marker, setOf("type", "timestamp", "displayTime", "dateTime")),
            )
            else -> ExportRow(
                time, ExportRow.SOURCE_CARELINK, ExportRow.KIND_OTHER,
                markerType = type,
                detail = detail ?: leftover(marker, setOf("type", "timestamp", "displayTime", "dateTime")),
            )
        }
    }

    private fun tempRow(type: String, values: JsonObject, marker: JsonObject, time: Long, detail: String?): ExportRow {
        val mode = (values.text("basalType") ?: values.text("tempBasalType") ?: marker.text("basalType"))?.uppercase()
        val percent = (values.double("percentage") ?: values.double("percent") ?: values.double("basalPercent"))
            ?.takeIf { it.isFinite() && it in 0.0..500.0 }
        val rate = (values.double("rate") ?: values.double("tempBasalRate") ?: values.double("basalRate"))
            ?.takeIf { it.isFinite() && it >= 0 }
        val percentMode = when (mode) {
            "PERCENT", "PERCENTAGE" -> true
            "ABSOLUTE", "RATE", "UNITS" -> false
            else -> percent != null
        }
        return ExportRow(
            time, ExportRow.SOURCE_CARELINK, ExportRow.KIND_TEMP_BASAL,
            insulinKind = InsulinKind.BASAL.name,
            basalRateUPerH = if (percentMode) null else rate,
            basalPercent = if (percentMode) percent else null,
            durationMin = durationMinutes(values, marker),
            percentOfProfile = percentMode && percent != null,
            markerType = type,
            detail = detail,
        )
    }

    private fun Treatment.toTempRow() = ExportRow(
        timeMillis, ExportRow.SOURCE_CARELINK, ExportRow.KIND_TEMP_BASAL,
        insulinKind = InsulinKind.BASAL.name,
        basalRateUPerH = basalRate,
        basalPercent = basalPercent,
        durationMin = durationMinutes,
        percentOfProfile = percentOfProfile,
        markerType = "TEMP_BASAL",
    )

    private fun deviceRow(patient: JsonObject, time: Long): ExportRow? {
        val keys = listOf(
            "medicalDeviceFamily", "pumpModelNumber", "medicalDeviceSerialNumber", "medicalDeviceBatteryLevelPercent",
            "reservoirRemainingUnits", "reservoirLevelPercent", "reservoirAmount", "pumpSuspended",
            "sensorDurationHours", "sensorDurationMinutes", "timeToNextCalibHours", "timeToNextCalibrationMinutes",
            "calibStatus", "calibrationIcon", "conduitSerialNumber", "conduitBatteryLevel", "conduitBatteryStatus",
            "gstBatteryLevel", "gstCommunicationState", "systemStatusMessage", "clientTimeZoneName", "appModelType",
            "lastSGTrend", "sensorState", "cgmInfo", "cgmsSensorState", "averageSG", "belowHypoLimit",
            "aboveHyperLimit", "timeInRange", "sgBelowLimit", "firstName", "lastName",
            "conduitMedicalDeviceInRange", "conduitSensorInRange",
        )
        val fields = linkedMapOf<String, String>()
        keys.forEach { key ->
            val value = patient[key] ?: return@forEach
            if (value is JsonPrimitive) value.contentOrNull?.takeIf { it.isNotEmpty() }?.let { fields[key] = it }
            else if (value is JsonObject || value is JsonArray) fields[key] = value.toString().take(500)
        }
        if (fields.isEmpty()) return null
        return ExportRow(
            time, ExportRow.SOURCE_CARELINK, ExportRow.KIND_DEVICE,
            markerType = "DEVICE",
            detail = jsonObject(fields),
        )
    }

    private fun snapshotList(
        patient: JsonObject,
        key: String,
        kind: String,
        shiftMillis: Long,
        fallback: Long,
        rows: MutableList<ExportRow>,
    ) {
        val items = (patient[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val capped = if (kind == ExportRow.KIND_NOTIFICATION) items.take(MAX_NOTIFICATIONS) else items
        capped.forEach { item ->
            val time = item.at(shiftMillis, "timestamp", "datetime", "dateTime", "displayTime") ?: fallback
            rows += snapshot(kind, time, item, key.uppercase())
        }
    }

    private fun snapshot(kind: String, time: Long, obj: JsonObject, markerType: String) = ExportRow(
        time, ExportRow.SOURCE_CARELINK, kind,
        markerType = markerType,
        detail = leftover(obj, setOf("timestamp", "datetime", "dateTime", "displayTime")),
    )

    private fun durationMinutes(values: JsonObject, marker: JsonObject): Double? {
        val names = arrayOf("duration", "programmedDuration", "effectiveDuration", "durationMinutes")
        val raw = names.firstNotNullOfOrNull { values.double(it) } ?: names.firstNotNullOfOrNull { marker.double(it) } ?: return null
        if (!raw.isFinite() || raw < 0) return null
        return if (raw > 24 * 60) raw / 60_000.0 else raw
    }

    private fun leftover(obj: JsonObject, skip: Set<String>): String? {
        val fields = linkedMapOf<String, String>()
        flatten(obj, "", fields, skip)
        if (fields.isEmpty()) return null
        return jsonObject(fields).take(MAX_DETAIL)
    }

    private fun flatten(element: JsonElement, prefix: String, out: MutableMap<String, String>, skip: Set<String>, depth: Int = 0) {
        if (out.size >= 40 || depth > 4) return
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key in skip || key.contains("token", ignoreCase = true) || key.contains("password", ignoreCase = true)) return@forEach
                val name = if (prefix.isEmpty()) key else "$prefix.$key"
                flatten(value, name, out, emptySet(), depth + 1)
            }
            is JsonArray -> if (prefix.isNotEmpty()) out[prefix] = element.toString().take(500)
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotEmpty() }?.let { out[prefix] = it }
        }
    }

    private fun jsonObject(fields: Map<String, String>): String = buildJsonObject {
        fields.forEach { (key, value) -> put(key, value) }
    }.toString()

    private fun JsonObject.at(shiftMillis: Long, vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key -> text(key)?.let(NightscoutClient::parseTime) }?.plus(shiftMillis)
}

private val ROW_ORDER = compareBy<ExportRow>({ it.timeMillis }, { it.source }, { it.kind }, { it.markerType.orEmpty() })

internal fun num(value: Double?): String {
    if (value == null || !value.isFinite()) return ""
    return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

private fun csvField(value: String): String {
    val first = value.firstOrNull()
    val safe = if (first != null && first in "=+-@\t") "'$value" else value
    val escaped = safe.replace("\"", "\"\"")
    return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.double(key: String) = text(key)?.toDoubleOrNull()
private fun JsonObject.long(key: String) = text(key)?.toLongOrNull() ?: double(key)?.toLong()
private fun JsonObject.int(key: String) = text(key)?.toIntOrNull() ?: double(key)?.toInt()
private fun JsonObject.bool(key: String) = when (text(key)) {
    "true" -> true
    "false" -> false
    else -> null
}

private fun Double.roundToInt() = kotlin.math.round(this).toInt()
