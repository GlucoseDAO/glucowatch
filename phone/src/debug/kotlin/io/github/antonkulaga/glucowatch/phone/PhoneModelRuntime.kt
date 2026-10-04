package io.github.antonkulaga.glucowatch.phone

import glucowatch.core.GlucosePredictor
import glucowatch.debug.DebugModels
import java.io.File

object PhoneModelRuntime {
    const val MAX_BYTES = 128L * 1024 * 1024
    const val supportsBundles = true
    fun load(file: File, metadata: File?, scalers: File?): GlucosePredictor = DebugModels.load(file, metadata, scalers)
}
