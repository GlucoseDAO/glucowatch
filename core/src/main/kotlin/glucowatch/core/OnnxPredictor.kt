package glucowatch.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.tanh

/**
 * A deliberately small, source-only ONNX interpreter for phone forecasts. The contract is one
 * float32 input [1, 12] or [1, 24]: five-minute glucose samples, oldest first, in mg/dL. One
 * float32 output [1, 1..24] contains future glucose in mg/dL at five-minute intervals. Only
 * feed-forward graphs made of the operators below are accepted. No external tensor data, native
 * runtime or bundled model is used. Unsupported models fail at import, rather than at sync time.
 */
class OnnxPredictor private constructor(private val graph: Graph) : GlucosePredictor {
    override val id = ID
    override val displayName = "Imported ONNX"

    override fun predict(history: List<GlucoseReading>, horizonMinutes: Int): Prediction? {
        val count = graph.inputWidth
        val recent = history.takeLast(count)
        if (recent.size != count || horizonMinutes <= 0) return null
        if (System.currentTimeMillis() - recent.last().timeMillis > 15 * 60_000L) return null
        if (recent.zipWithNext().any { (a, b) -> b.timeMillis - a.timeMillis !in 1..(7 * 60_000L) }) return null
        val steps = horizonMinutes / 5
        if (steps <= 0 || steps > graph.outputWidth) return null
        val output = graph.run(Tensor(intArrayOf(1, count), recent.map { it.mgdl.toFloat() }.toFloatArray()))
        if (output.data.any { !it.isFinite() || it < 20f || it > 600f }) return null
        val last = recent.last().timeMillis
        return Prediction(ID, (1..steps).map { n -> PredictedPoint(last + n * 300_000L, output.data[n - 1].toDouble()) })
    }

    companion object {
        const val ID = "onnx"
        private const val MAX_BYTES = 10 * 1024 * 1024

        fun load(bytes: ByteArray): OnnxPredictor {
            require(bytes.size in 1..MAX_BYTES) { "Model must be an ONNX file under 10 MB" }
            val model = Proto(bytes)
            val graphMessage = model.messages(7).singleOrNull() ?: error("ONNX graph is missing")
            val initializers = graphMessage.messages(5).associate { tensorMessage ->
                val name = tensorMessage.string(8) ?: error("Unnamed ONNX tensor")
                name to parseTensor(tensorMessage)
            }
            val inputInfos = graphMessage.messages(11).mapNotNull { info ->
                info.string(1)?.takeUnless(initializers::containsKey)?.let { it to inputShape(info) }
            }
            require(inputInfos.size == 1) { "Use a model with one glucose input" }
            val (input, shape) = inputInfos.single()
            require(shape.size == 2 && shape[0] in listOf(-1, 1) && shape[1] in listOf(12, 24)) {
                "Input must be float32 [batch,12] or [batch,24] glucose values"
            }
            val outputs = graphMessage.messages(12).mapNotNull { it.string(1) }
            require(outputs.size == 1) { "Use a model with one forecast output" }
            val nodes = graphMessage.messages(1).map(::parseNode)
            require(nodes.isNotEmpty() && nodes.size <= 100) { "Model graph is empty or too large" }
            val graph = Graph(input, shape[1], outputs.single(), initializers, nodes)
            val trial = graph.run(Tensor(intArrayOf(1, shape[1]), FloatArray(shape[1]) { 120f }))
            require(trial.dims.size == 2 && trial.dims[0] == 1 && trial.dims[1] in 1..24) {
                "Output must be float32 [1,1..24] future values"
            }
            require(trial.data.all(Float::isFinite)) { "Model returned invalid values" }
            graph.outputWidth = trial.dims[1]
            return OnnxPredictor(graph)
        }

        private fun inputShape(info: Proto): IntArray {
            val tensorType = info.messages(2).singleOrNull()?.messages(1)?.singleOrNull()
                ?: error("Input tensor type is missing")
            require(tensorType.number(1) == 1L) { "Input must be float32" }
            return tensorType.messages(2).singleOrNull()?.messages(1)?.map { it.number(1)?.toInt() ?: -1 }?.toIntArray()
                ?: error("Input shape is missing")
        }

        private fun parseTensor(p: Proto): Tensor {
            val type = p.number(2)
            require(type == 1L || type == 7L) { "Only float32 tensors and int64 reshape shapes are supported" }
            val dims = p.numbers(1).map(Long::toInt).toIntArray()
            require(dims.isNotEmpty() && dims.size <= 2 && dims.all { it in 1..4096 }) { "Unsupported tensor shape" }
            val size = dims.fold(1, Int::times)
            require(size <= 200_000) { "ONNX tensor is too large" }
            val raw = p.bytes(9).singleOrNull()
            val data = if (type == 7L) {
                val ints = if (raw != null) {
                    require(raw.size == size * 8) { "Invalid ONNX int64 tensor" }
                    List(size) { ByteBuffer.wrap(raw, it * 8, 8).order(ByteOrder.LITTLE_ENDIAN).long }
                } else p.numbers(7)
                require(ints.size == size && ints.all { it in -1..4096 }) { "Invalid ONNX reshape shape" }
                ints.map(Long::toFloat).toFloatArray()
            } else if (raw != null) {
                require(raw.size == size * 4) { "Invalid ONNX tensor data" }
                FloatArray(size) { ByteBuffer.wrap(raw, it * 4, 4).order(ByteOrder.LITTLE_ENDIAN).float }
            } else p.floats(4).toFloatArray()
            require(data.size == size && data.all(Float::isFinite)) { "Invalid ONNX float tensor" }
            return Tensor(dims, data)
        }

        private fun parseNode(p: Proto): Node {
            val op = p.string(4) ?: error("ONNX operator missing")
            require(p.string(7).orEmpty().isEmpty()) { "Custom ONNX operators are unsupported" }
            require(op in setOf("Gemm", "MatMul", "Add", "Sub", "Mul", "Relu", "Sigmoid", "Tanh", "Identity", "Flatten", "Reshape")) {
                "Unsupported ONNX operator: $op"
            }
            val outputs = p.strings(2)
            require(outputs.size == 1) { "$op must have one output" }
            val attrs = p.messages(5).associateBy { it.string(1).orEmpty() }
            return Node(op, p.strings(1), outputs.single(), attrs)
        }
    }
}

private data class Tensor(val dims: IntArray, val data: FloatArray) {
    val rows get() = if (dims.size == 1) 1 else dims[0]
    val cols get() = dims.last()
    fun at(r: Int, c: Int) = data[r * cols + c]
}

private data class Node(val op: String, val inputs: List<String>, val output: String, val attrs: Map<String, Proto>) {
    fun integer(name: String, default: Long) = attrs[name]?.number(3) ?: default
    fun float(name: String, default: Float) = attrs[name]?.fixedFloat(2) ?: default
}

private class Graph(
    private val inputName: String,
    val inputWidth: Int,
    private val outputName: String,
    private val initializers: Map<String, Tensor>,
    private val nodes: List<Node>,
) {
    var outputWidth: Int = 0

    fun run(input: Tensor): Tensor {
        val values = initializers.toMutableMap()
        values[inputName] = input
        for (node in nodes) {
            val args = node.inputs.filter(String::isNotEmpty).map { values[it] ?: error("Missing tensor $it") }
            val out = when (node.op) {
                "Identity" -> args.single()
                "Relu" -> unary(args.single()) { it.coerceAtLeast(0f) }
                "Sigmoid" -> unary(args.single()) { (1.0 / (1.0 + exp(-it.toDouble()))).toFloat() }
                "Tanh" -> unary(args.single()) { tanh(it.toDouble()).toFloat() }
                "Add" -> elementwise(args[0], args[1], Float::plus)
                "Sub" -> elementwise(args[0], args[1], Float::minus)
                "Mul" -> elementwise(args[0], args[1], Float::times)
                "MatMul" -> matmul(args[0], args[1])
                "Gemm" -> {
                    require(node.integer("transA", 0) == 0L) { "Gemm transA is unsupported" }
                    val b = if (node.integer("transB", 0) == 1L) transpose(args[1]) else args[1]
                    val product = matmul(args[0], b)
                    val alpha = node.float("alpha", 1f)
                    val beta = node.float("beta", 1f)
                    if (args.size == 3) elementwise(unary(product) { it * alpha }, unary(args[2]) { it * beta }, Float::plus)
                    else unary(product) { it * alpha }
                }
                "Flatten" -> {
                    require(node.integer("axis", 1) == 1L) { "Only Flatten axis=1 is supported" }
                    Tensor(intArrayOf(1, args.single().data.size), args.single().data)
                }
                "Reshape" -> {
                    val target = args[1].data.map(Float::toInt)
                    require(target.size == 2 && target[0] in setOf(0, 1, -1)) { "Unsupported Reshape" }
                    val width = if (target[1] == -1) args[0].data.size else target[1]
                    require(width == args[0].data.size) { "Unsupported Reshape size" }
                    Tensor(intArrayOf(1, width), args[0].data)
                }
                else -> error("Unsupported ONNX operator: ${node.op}")
            }
            require(out.data.size <= 200_000 && out.data.all(Float::isFinite)) { "Model produced invalid tensor" }
            values[node.output] = out
        }
        return values[outputName] ?: error("Forecast output is missing")
    }

    private fun unary(a: Tensor, f: (Float) -> Float) = Tensor(a.dims, a.data.map(f).toFloatArray())

    private fun transpose(a: Tensor): Tensor {
        require(a.dims.size == 2)
        return Tensor(intArrayOf(a.cols, a.rows), FloatArray(a.data.size) { i -> a.at(i % a.rows, i / a.rows) })
    }

    private fun matmul(a: Tensor, b: Tensor): Tensor {
        require(a.dims.size == 2 && b.dims.size == 2 && a.cols == b.rows) { "MatMul shape mismatch" }
        require(a.rows * b.cols <= 200_000) { "MatMul output too large" }
        require(a.rows.toLong() * b.cols * a.cols <= 2_000_000L) { "MatMul computation too large" }
        return Tensor(intArrayOf(a.rows, b.cols), FloatArray(a.rows * b.cols) { i ->
            val r = i / b.cols; val c = i % b.cols
            (0 until a.cols).sumOf { (a.at(r, it) * b.at(it, c)).toDouble() }.toFloat()
        })
    }

    private fun elementwise(a: Tensor, b: Tensor, f: (Float, Float) -> Float): Tensor {
        require(a.dims.size <= 2 && b.dims.size <= 2) { "Unsupported broadcast" }
        val rows = maxOf(a.rows, b.rows)
        val cols = maxOf(a.cols, b.cols)
        require(a.rows in setOf(1, rows) && b.rows in setOf(1, rows) && a.cols in setOf(1, cols) && b.cols in setOf(1, cols)) {
            "Elementwise shape mismatch"
        }
        require(rows * cols <= 200_000) { "Elementwise output too large" }
        return Tensor(intArrayOf(rows, cols), FloatArray(rows * cols) { i ->
            val r = i / cols; val c = i % cols
            f(a.at(if (a.rows == 1) 0 else r, if (a.cols == 1) 0 else c),
                b.at(if (b.rows == 1) 0 else r, if (b.cols == 1) 0 else c))
        })
    }
}

/** Minimal bounded protobuf reader for the standard ONNX fields used above. */
private class Proto(private val content: ByteArray) {
    private data class Field(val number: Int, val wire: Int, val value: Long, val bytes: ByteArray?)
    private val fields: List<Field> = buildList {
        var pos = 0
        fun varint(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(pos < content.size) { "Truncated ONNX file" }
                val b = content[pos++].toInt() and 255
                result = result or ((b and 127).toLong() shl shift)
                if (b < 128) return result
            }
            error("Invalid ONNX varint")
        }
        while (pos < content.size) {
            val tag = varint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            require(field > 0) { "Invalid ONNX field" }
            when (wire) {
                0 -> add(Field(field, wire, varint(), null))
                1, 5 -> {
                    val size = if (wire == 1) 8 else 4
                    require(content.size - pos >= size) { "Truncated ONNX field" }
                    var bits = 0L
                    for (i in 0 until size) bits = bits or ((content[pos + i].toLong() and 255L) shl (i * 8))
                    pos += size
                    add(Field(field, wire, bits, null))
                }
                2 -> {
                    val size = varint().toInt()
                    require(size >= 0 && size <= content.size - pos) { "Invalid ONNX field length" }
                    add(Field(field, wire, 0, content.copyOfRange(pos, pos + size)))
                    pos += size
                }
                else -> error("Unsupported ONNX wire type")
            }
        }
    }

    fun bytes(n: Int) = fields.filter { it.number == n && it.wire == 2 }.mapNotNull(Field::bytes)
    fun messages(n: Int) = bytes(n).map(::Proto)
    fun string(n: Int) = bytes(n).firstOrNull()?.toString(Charsets.UTF_8)
    fun strings(n: Int) = bytes(n).map { it.toString(Charsets.UTF_8) }
    fun number(n: Int) = fields.firstOrNull { it.number == n && it.wire == 0 }?.value
    fun fixedFloat(n: Int) = fields.firstOrNull { it.number == n && it.wire == 5 }?.value?.toInt()?.let(Float::fromBits)
    fun numbers(n: Int): List<Long> = fields.filter { it.number == n }.flatMap {
        if (it.wire == 0) listOf(it.value) else if (it.wire == 2) packed(it.bytes!!) else emptyList()
    }
    fun floats(n: Int): List<Float> = fields.filter { it.number == n }.flatMap {
        if (it.wire == 5) listOf(Float.fromBits(it.value.toInt()))
        else if (it.wire == 2) {
            val b = it.bytes!!
            require(b.size % 4 == 0) { "Invalid ONNX float data" }
            (0 until b.size / 4).map { i -> ByteBuffer.wrap(b, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).float }
        } else emptyList()
    }
    private fun packed(b: ByteArray): List<Long> {
        var pos = 0
        return buildList {
            while (pos < b.size) {
                var value = 0L
                var done = false
                for (shift in 0..63 step 7) {
                    require(pos < b.size) { "Invalid ONNX packed data" }
                    val next = b[pos++].toInt() and 255
                    value = value or ((next and 127).toLong() shl shift)
                    if (next < 128) { done = true; break }
                }
                require(done) { "Invalid ONNX packed varint" }
                add(value)
            }
        }
    }
}
