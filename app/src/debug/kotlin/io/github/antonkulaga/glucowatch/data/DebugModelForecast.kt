package io.github.antonkulaga.glucowatch.data

import android.content.Context
import glucowatch.core.GlucoseDaoPredictor
import glucowatch.core.GlucoseReading
import glucowatch.core.GlucosePredictor
import glucowatch.core.Prediction
import glucowatch.core.Treatment
import glucowatch.debug.DebugModels

/** An explicitly installed private debug bundle, used by emulator screenshots only. */
object DebugModelForecast {
    private var loaded: Pair<String, GlucosePredictor>? = null
    @Synchronized
    fun predict(context: Context, readings: List<GlucoseReading>, treatments: List<Treatment>, horizon: Int, therapy: glucowatch.core.ForecastTherapy): Prediction? {
        val folder = context.filesDir.resolve("debug-model")
        val model = folder.resolve("model.onnx")
        if (!model.isFile) return null
        val key = "${model.absolutePath}:${model.lastModified()}:${model.length()}"
        if (loaded?.first != key) {
            (loaded?.second as? AutoCloseable)?.close()
            loaded = key to DebugModels.load(model, folder.resolve("onnx_meta.json"), folder.resolve("scalers.json"))
        }
        val predictor = loaded!!.second
        val minutes = minOf(horizon, predictor.defaultHorizonMinutes)
        return if (predictor is GlucoseDaoPredictor) predictor.predict(readings, treatments, minutes, therapy) else predictor.predict(readings, minutes)
    }
}
