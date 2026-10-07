package glucowatch.core

import kotlinx.serialization.json.*
import kotlin.math.ln1p
import kotlin.math.roundToInt

/** Runtime-independent feature preparation; native runtimes are supplied by debug builds only. */
data class ForecastTensor(val shape: LongArray, val values: FloatArray)

interface ForecastEngine : AutoCloseable {
    fun run(inputs: Map<String, ForecastTensor>, output: String): FloatArray
}

data class GlucoseDaoContract(
    val family: String,
    val inputSteps: Int,
    val horizonSteps: Int,
    val channels: List<String>,
    val inputNames: List<String>,
    val outputName: String,
    val gapStart: Int?,
    val scalers: Map<String, FeatureScaler>,
) {
    val pastSteps get() = gapStart ?: inputSteps
    val displayName get() = when (family) {
        "inpaint_bicitras" -> "INPAINT-CITRAS"
        "citras" -> "CITRAS"
        else -> "NF-TFT"
    }

    companion object {
        fun parse(metadata: String, scalerJson: String? = null): GlucoseDaoContract {
            val root = Json.parseToJsonElement(metadata).jsonObject
            fun number(name: String) = root[name]?.jsonPrimitive?.intOrNull ?: error("Missing model field: $name")
            val family = root["family_kind"]?.jsonPrimitive?.content ?: error("Missing model family")
            require(family in setOf("inpaint_bicitras", "citras", "nf")) { "Unsupported GlucoseDao model family" }
            require(number("version") == 2 && number("step_minutes") == 5) { "Use a version 2 five-minute ONNX bundle" }
            val steps = number("input_steps")
            val horizon = number("horizon_steps")
            require(steps in 12..2880 && horizon in 1..48) { "Unsupported model window" }
            require(root["external_data"] == null || root["external_data"] is JsonNull) { "External ONNX weights are unsupported" }
            require(root["metadata_schema"] == null || root["metadata_schema"] is JsonNull) { "Static model metadata is unsupported" }
            val channels = root.getValue("channels").jsonArray.map { it.jsonPrimitive.content }
            val inpaint = family == "inpaint_bicitras"
            val expected = if (inpaint) listOf("glucose_div100", "glucose_observed", "log1p_basal", "basal_observed",
                "log1p_bolus", "bolus_observed", "hidden", "real_slot") else listOf("glucose", "basal", "bolus", "carbs")
            require(channels == expected) { "Unsupported model channel order" }
            val names = root.getValue("inputs").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            require(names == if (inpaint) listOf("x_features") else listOf("x_scaled", "future_scaled")) { "Unsupported model input names" }
            val gap = if (inpaint) number("gap_start") else null
            require(gap == null || gap in 12 until steps && gap + horizon <= steps) { "Invalid inpainting gap" }
            val scalers = if (inpaint) emptyMap() else {
                val features = Json.parseToJsonElement(requireNotNull(scalerJson) { "The model needs scalers.json" }).jsonObject.getValue("features").jsonObject
                expected.associateWith { FeatureScaler.parse(features.getValue(it).jsonObject) }
            }
            return GlucoseDaoContract(family, steps, horizon, channels, names,
                root.getValue("output").jsonPrimitive.content, gap, scalers)
        }
    }
}

data class FeatureScaler(val factor: Float, val offset: Float) {
    fun scale(raw: Float) = raw * factor + offset
    fun inverse(value: Float) = (value - offset) / factor

    companion object {
        fun parse(root: JsonObject): FeatureScaler {
            fun value(key: String) = root.getValue(key).jsonArray.single().jsonPrimitive.float
            val kind = root.getValue("type").jsonPrimitive.content
            val scale = value("scale")
            require(scale.isFinite() && scale > 0) { "Invalid model scaling" }
            val factor = if (kind == "standard") 1f / scale else scale
            val offset = when (kind) {
                "minmax" -> value("min")
                "standard" -> -value("mean") / scale
                else -> error("Unsupported model scaler")
            }
            require(offset.isFinite()) { "Invalid model offset" }
            return FeatureScaler(factor, offset)
        }
    }
}

/**
 * All future observations are withheld, including the inpainter's post-gap record. Historical
 * pump pulses use U/h (delivered five-minute U × 12), matching the training adapter. A reported
 * basal rate already has U/h units. Unknown intervals remain missing, never assumed zero.
 */
class GlucoseDaoPredictor(val contract: GlucoseDaoContract, private val engine: ForecastEngine) : GlucosePredictor, AutoCloseable {
    override val id = OnnxPredictor.ID
    override val displayName get() = contract.displayName
    override val defaultHorizonMinutes get() = contract.horizonSteps * 5
    var lastProblem: String? = null
        private set
    private var previous: List<Any>? = null
    private var previousResult: Prediction? = null

    override fun predict(history: List<GlucoseReading>, horizonMinutes: Int): Prediction? = predict(history, emptyList(), horizonMinutes)

    @Synchronized
    fun predict(history: List<GlucoseReading>, treatments: List<Treatment>, horizonMinutes: Int, therapy: ForecastTherapy = ForecastTherapy()): Prediction? {
        val origin = history.lastOrNull()?.timeMillis ?: return null
        if (System.currentTimeMillis() - origin !in -60_000L..900_000L) return null
        val steps = horizonMinutes / 5
        if (steps !in 1..contract.horizonSteps || history.size < 12) return null
        val firstGlucose = history.first().timeMillis
        val overlapping = ForecastTherapy(therapy.ranges.mapNotNull { range ->
            val start = maxOf(firstGlucose, range.start); val end = minOf(origin, range.end)
            if (start < end) TherapyRange(start, end) else null
        }, therapy.profiles)
        val matchingTreatments = treatments.filter { t -> t.timeMillis in firstGlucose..origin ||
            (t.durationMinutes != null && t.timeMillis <= origin && t.timeMillis + (t.durationMinutes * 60_000).toLong() > firstGlucose) }
        val key = listOf(history, matchingTreatments, horizonMinutes, overlapping)
        if (key == previous) return previousResult
        previous = key
        previousResult = null
        lastProblem = when {
            overlapping.ranges.isNotEmpty() -> null
            matchingTreatments.isNotEmpty() -> "Partial pump history · using available events"
            else -> "Glucose only · pump history unavailable"
        }
        val raw = engine.run(inputs(history, matchingTreatments, overlapping), contract.outputName)
        val offset = contract.gapStart ?: 0
        require(raw.size >= offset + steps) { "Model forecast output is shorter than its metadata" }
        val values = (0 until steps).map { i ->
            if (contract.gapStart != null) raw[offset + i] else contract.scalers.getValue("glucose").inverse(raw[i])
        }
        if (values.any { !it.isFinite() || it !in 20f..600f }) {
            lastProblem = "Glucose trend · selected model needs more history or pump inputs"
            return LinearTrendPredictor().predict(history, horizonMinutes).also { previousResult = it }
        }
        return Prediction(id, values.mapIndexed { i, value -> PredictedPoint(origin + (i + 1) * STEP, value.toDouble()) })
            .also { previousResult = it }
    }

    fun inputs(history: List<GlucoseReading>, treatments: List<Treatment>, therapy: ForecastTherapy = ForecastTherapy()): Map<String, ForecastTensor> {
        val origin = history.last().timeMillis
        val past = contract.pastSteps
        val first = origin - (past - 1) * STEP
        val glucose = FloatArray(past) { Float.NaN }
        val (basal, bolus, carbs) = ForecastFeatures.therapy(first, past, origin, treatments, therapy)
        history.filter { it.timeMillis <= origin }.forEach { reading ->
            val slot = ((reading.timeMillis - first).toDouble() / STEP).roundToInt()
            if (slot in glucose.indices) glucose[slot] = reading.mgdl.toFloat()
        }
        if (contract.gapStart != null) {
            val data = FloatArray(contract.inputSteps * 8)
            val firstSeen = glucose.indexOfFirst(Float::isFinite).takeIf { it >= 0 } ?: past
            for (i in 0 until contract.inputSteps) {
                val at = i * 8
                if (i < past) {
                    if (glucose[i].isFinite() && glucose[i] > 0) { data[at] = glucose[i] / 100; data[at + 1] = 1f }
                    if (basal[i].isFinite()) { data[at + 2] = ln1p(basal[i]); data[at + 3] = 1f }
                    if (bolus[i].isFinite()) { data[at + 4] = ln1p(bolus[i]); data[at + 5] = 1f }
                } else data[at + 6] = 1f
                if (i >= firstSeen) data[at + 7] = 1f
            }
            return mapOf("x_features" to ForecastTensor(longArrayOf(1, contract.inputSteps.toLong(), 8), data))
        }
        val channels = listOf(glucose, basal, bolus, carbs)
        val data = FloatArray(past * 4) { index -> contract.scalers.getValue(contract.channels[index % 4]).scale(channels[index % 4][index / 4]) }
        return mapOf("x_scaled" to ForecastTensor(longArrayOf(1, past.toLong(), 4), data),
            "future_scaled" to ForecastTensor(longArrayOf(1, contract.horizonSteps.toLong(), 4), FloatArray(contract.horizonSteps * 4) { Float.NaN }))
    }

    @Synchronized
    override fun close() = engine.close()

    private companion object { const val STEP = 300_000L }
}
