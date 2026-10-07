package io.github.antonkulaga.glucowatch.data

import android.content.Context
import glucowatch.core.GlucoseReading
import glucowatch.core.Prediction
import glucowatch.core.Treatment

object DebugModelForecast {
    fun predict(context: Context, readings: List<GlucoseReading>, treatments: List<Treatment>, horizon: Int, therapy: glucowatch.core.ForecastTherapy): Prediction? = null
}
