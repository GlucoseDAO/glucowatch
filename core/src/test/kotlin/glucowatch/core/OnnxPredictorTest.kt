package glucowatch.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class OnnxPredictorTest {
    @Test fun `runs a small dense ONNX model with the documented glucose contract`() {
        val model = model("MatMul")
        val predictor = OnnxPredictor.load(model)
        val now = System.currentTimeMillis() / 300_000L * 300_000L
        val history = (11 downTo 0).map { GlucoseReading(now - it * 300_000L, 100 + it, Trend.Flat) }
        val result = assertNotNull(predictor.predict(history, 30))
        assertEquals(6, result.points.size)
        assertEquals(120.0, result.points.last().mgdl)
        assertEquals(now + 1_800_000L, result.points.last().timeMillis)
    }

    @Test fun `rejects an unsupported operator at import`() {
        assertFailsWith<IllegalArgumentException> { OnnxPredictor.load(model("Conv")) }
    }

    @Test fun `accepts a dynamic batch dimension and runs one batch`() {
        val predictor = OnnxPredictor.load(model("MatMul", dynamicBatch = true))
        assertEquals(OnnxPredictor.ID, predictor.id)
    }

    private fun model(op: String, dynamicBatch: Boolean = false): ByteArray {
        val w = tensor("W", intArrayOf(12, 6), FloatArray(72))
        val b = tensor("B", intArrayOf(1, 6), FloatArray(6) { 120f })
        val node1 = message { string(1, "X"); string(1, "W"); string(2, "M"); string(4, op) }
        val node2 = message { string(1, "M"); string(1, "B"); string(2, "Y"); string(4, "Add") }
        val input = valueInfo("X", 12, dynamicBatch)
        val output = valueInfo("Y", 6)
        val graph = message {
            bytes(1, node1); bytes(1, node2)
            bytes(5, w); bytes(5, b)
            bytes(11, input); bytes(12, output)
        }
        return message { varint(1, 8); bytes(7, graph) }
    }

    private fun tensor(name: String, dims: IntArray, values: FloatArray) = message {
        dims.forEach { varint(1, it.toLong()) }
        varint(2, 1)
        string(8, name)
        bytes(9, ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putFloat(it) }
        }.array())
    }

    private fun valueInfo(name: String, width: Int, dynamicBatch: Boolean = false) = message {
        string(1, name)
        bytes(2, message {
            bytes(1, message {
                varint(1, 1)
                bytes(2, message {
                    bytes(1, message { if (dynamicBatch) string(2, "batch") else varint(1, 1) })
                    bytes(1, message { varint(1, width.toLong()) })
                })
            })
        })
    }

    private fun message(block: Writer.() -> Unit) = Writer().apply(block).output.toByteArray()

    private class Writer {
        val output = ByteArrayOutputStream()
        fun varint(field: Int, value: Long) { raw((field * 8).toLong()); raw(value) }
        fun string(field: Int, value: String) = bytes(field, value.toByteArray())
        fun bytes(field: Int, value: ByteArray) {
            raw((field * 8 + 2).toLong()); raw(value.size.toLong()); output.write(value)
        }
        private fun raw(value: Long) {
            var n = value
            while (n >= 128) { output.write((n.toInt() and 127) or 128); n = n ushr 7 }
            output.write(n.toInt())
        }
    }
}
