package glucowatch.core

import kotlin.test.*

class GlucoseDaoPredictorTest {
    @Test fun `GlucoseDao defaults to the full metadata horizon`() {
        val longContract = contract.copy(inputSteps = 144, horizonSteps = 48)
        val history = history()
        val model = GlucoseDaoPredictor(longContract, object : ForecastEngine {
            override fun run(inputs: Map<String, ForecastTensor>, output: String) = FloatArray(144) { 125f }
            override fun close() {}
        })
        assertEquals(240, model.defaultHorizonMinutes)
        val result = assertNotNull(model.predict(history, model.defaultHorizonMinutes))
        assertEquals(48, result.points.size)
        assertEquals(history.last().timeMillis + 240 * 60_000L, result.points.last().timeMillis)
        assertEquals(120, GlucoseDaoPredictor(longContract.copy(horizonSteps = 24), object : ForecastEngine {
            override fun run(inputs: Map<String, ForecastTensor>, output: String) = FloatArray(144) { 125f }
            override fun close() {}
        }).defaultHorizonMinutes)
    }
    private val contract = GlucoseDaoContract("inpaint_bicitras",24,6,
        listOf("glucose_div100","glucose_observed","log1p_basal","basal_observed","log1p_bolus","bolus_observed","hidden","real_slot"),
        listOf("x_features"),"glucose_mgdl",12,emptyMap())
    private fun history() = System.currentTimeMillis().let { now -> (0..12).map { GlucoseReading(now-(12-it)*300_000L,120,Trend.None) } }
    @Test fun `missing or nonoverlapping pump keeps the glucose model running`() {
        var input: FloatArray? = null
        val engine = object : ForecastEngine {
            override fun run(inputs: Map<String,ForecastTensor>,output:String): FloatArray {
                input=inputs.getValue("x_features").values;return FloatArray(24){125f}
            }
            override fun close() {}
        }
        val model=GlucoseDaoPredictor(contract,engine);val history=history();val now=history.last().timeMillis
        val result=model.predict(history,listOf(Treatment(now-24*3_600_000L,insulin=9.0)),30,
            ForecastTherapy(listOf(TherapyRange(now-25*3_600_000L,now-24*3_600_000L))))
        assertNotNull(result);assertEquals(6,result.points.size);assertEquals("Glucose only · pump history unavailable",model.lastProblem)
        assertEquals(1.2f,input!![0]);assertEquals(1f,input!![1]);assertEquals(0f,input!![3]);assertEquals(0f,input!![5])
    }
    @Test fun `a model unable to handle missing channels falls back to labelled glucose trend`() {
        val engine=object :ForecastEngine {
            override fun run(inputs:Map<String,ForecastTensor>,output:String)=FloatArray(24){Float.NaN}
            override fun close() {}
        }
        val model=GlucoseDaoPredictor(contract,engine)
        val result=model.predict(history(),emptyList(),30)
        assertNotNull(result);assertEquals(LinearTrendPredictor.ID,result.modelId)
        assertTrue(model.lastProblem!!.contains("Glucose trend"))
    }
    @Test fun `available bolus is served even if other pump channels have no coverage`() {
        val model=GlucoseDaoPredictor(contract,object:ForecastEngine {
            override fun run(inputs:Map<String,ForecastTensor>,output:String)=FloatArray(24){120f}
            override fun close() {}
        })
        val history=history();val at=history.last().timeMillis
        val input=model.inputs(history,listOf(Treatment(at,insulin=2.0))).getValue("x_features").values
        assertEquals(1f,input[11*8+5]);assertEquals(kotlin.math.ln1p(2f),input[11*8+4]);assertEquals(0f,input[11*8+3])
        assertNotNull(model.predict(history,listOf(Treatment(at,insulin=2.0)),30))
        assertEquals("Partial pump history · using available events",model.lastProblem)
    }
}
