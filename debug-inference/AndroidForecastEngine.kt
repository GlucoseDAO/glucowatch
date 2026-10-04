package glucowatch.debug

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import glucowatch.core.ForecastEngine
import glucowatch.core.ForecastTensor
import glucowatch.core.GlucoseDaoContract
import glucowatch.core.Predictors
import glucowatch.core.GlucosePredictor
import glucowatch.core.OnnxPredictor
import java.io.File
import java.nio.FloatBuffer

/** Included through the debug source sets only. CPU sessions work on x86_64 emulators and ARM. */
class AndroidForecastEngine(model: File) : ForecastEngine {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        environment.createSession(model.absolutePath, options)
    }

    override fun run(inputs: Map<String, ForecastTensor>, output: String): FloatArray {
        val tensors = mutableMapOf<String, OnnxTensor>()
        try {
            inputs.forEach { (name, tensor) -> tensors[name] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor.values), tensor.shape) }
            session.run(tensors).use { result ->
                val tensor = result.get(output).orElseThrow { IllegalArgumentException("Missing forecast output") } as OnnxTensor
                val values = tensor.floatBuffer
                return FloatArray(values.remaining()).also(values::get)
            }
        } finally { tensors.values.forEach(OnnxTensor::close) }
    }

    override fun close() = session.close()
}

object DebugModels {
    fun load(model: File, metadata: File?, scalers: File?): GlucosePredictor {
        if (metadata?.isFile != true) return OnnxPredictor.load(model.readBytes())
        val contract = GlucoseDaoContract.parse(metadata.readText(), scalers?.takeIf(File::isFile)?.readText())
        val engine = AndroidForecastEngine(model)
        try {
            val predictor = Predictors.imported(contract, engine)
            // Validate preprocessing against the actual graph before replacing a working import.
            val now = System.currentTimeMillis()
            val first = now - (contract.pastSteps - 1) * glucowatch.core.ForecastFeatures.STEP
            val history = (0 until contract.pastSteps).map { i ->
                glucowatch.core.GlucoseReading(first + i * glucowatch.core.ForecastFeatures.STEP, 120, glucowatch.core.Trend.None)
            }
            val therapy = glucowatch.core.ForecastTherapy(
                listOf(glucowatch.core.TherapyRange(first - glucowatch.core.ForecastFeatures.STEP, now)),
                listOf(glucowatch.core.BasalProfile(0, "UTC", listOf(glucowatch.core.BasalEntry(0, 1f)))))
            val output = engine.run(predictor.inputs(history, emptyList(), therapy), contract.outputName)
            require(output.size >= (contract.gapStart ?: 0) + contract.horizonSteps && output.all(Float::isFinite)) {
                "Model failed inference with its declared input contract"
            }
            return predictor
        } catch (failure: Throwable) { engine.close(); throw failure }
    }
}
