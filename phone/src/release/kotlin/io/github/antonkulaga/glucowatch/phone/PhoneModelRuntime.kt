package io.github.antonkulaga.glucowatch.phone

import glucowatch.core.GlucosePredictor
import glucowatch.core.OnnxPredictor
import java.io.File

object PhoneModelRuntime {
    const val MAX_BYTES = 10L * 1024 * 1024
    const val supportsBundles = false
    fun load(file: File, metadata: File?, scalers: File?): GlucosePredictor = OnnxPredictor.load(file.readBytes())
}
