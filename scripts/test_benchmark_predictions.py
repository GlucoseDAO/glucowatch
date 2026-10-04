#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=2.0", "onnxruntime>=1.22"]
# ///
"""Verify inference input masks, missing-data semantics, scaling, and redirect privacy."""
import unittest
import tempfile
import json
from pathlib import Path
import urllib.request

import numpy as np

from benchmark_predictions import SafeRedirect, inputs_for, scale, PumpHistory, fill_causal


class InputContractTest(unittest.TestCase):
    def test_forward_inpainter_never_observes_post_origin_slots(self):
        model = dict(meta=dict(task="inpaint", gap_start=1440, input_steps=2880,
                               channels=[str(i) for i in range(8)], inputs=[dict(name="x_features")]))
        history = np.full(1440, np.nan, dtype=np.float32)
        history[-288:] = 123
        features = inputs_for(model, history)["x_features"]
        np.testing.assert_array_equal(features[0, 1440:, :6], 0)
        np.testing.assert_array_equal(features[0, 1440:, 6], 1)
        np.testing.assert_array_equal(features[0, :1152, 7], 0)
        np.testing.assert_array_equal(features[0, 1152:, 7], 1)
        np.testing.assert_allclose(features[0, 1152:1440, 0], 1.23)
        # Unknown insulin is value zero with observed mask zero, never an observed zero.
        np.testing.assert_array_equal(features[0, :, 2:6], 0)

    def test_forecast_uses_missing_covariates_and_no_announcements(self):
        model = dict(meta=dict(task="forecast", family_kind="nf", input_steps=576,
                               horizon_steps=24, channels=["glucose", "basal", "bolus", "carbs"],
                               inputs=[dict(name="x_scaled"), dict(name="future_scaled")]),
                     scalers=dict(glucose=dict(type="minmax", scale=[0.002], min=[-0.004])))
        history = np.full(1440, 102, dtype=np.float32)
        inputs = inputs_for(model, history)
        np.testing.assert_allclose(inputs["x_scaled"][0, :, 0], 0.2)
        self.assertTrue(np.isnan(inputs["x_scaled"][0, :, 1:]).all())
        self.assertTrue(np.isnan(inputs["future_scaled"]).all())
        imputed = inputs_for(model, history, nf_impute=True)
        np.testing.assert_array_equal(imputed["x_scaled"][0, :, 1:], 0)
        self.assertTrue(np.isnan(imputed["future_scaled"]).all())

    def test_pump_covariates_use_log_zeros_basal_units_and_cutoff(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"treatments.json"
            path.write_text(json.dumps([
                dict(timeMillis=1700000000000,insulin=0),
                dict(timeMillis=1700000300000,insulinKind="BASAL",insulin=.1),
                dict(timeMillis=1700000600000,insulin=2,carbs=30),
                dict(timeMillis=1700000900000,insulin=9,carbs=90)]))
            pump=PumpHistory(path,None)
            values=pump.grid(np.array([1700000300000,1700000600000]),1700000600000)
            self.assertAlmostEqual(values[0,0],1.2)
            np.testing.assert_array_equal(values[:,1:],[ [0,0],[2,30] ])
            future=pump.grid(np.array([1700000900000]),1700000600000)
            self.assertTrue(np.isnan(future).all())

    def test_inpainter_supplies_observed_basal_and_bolus_masks(self):
        model=dict(meta=dict(task="inpaint",gap_start=12,input_steps=24,channels=list(range(8)),inputs=[dict(name="x_features")]))
        history=np.full(12,100,dtype=np.float32); cov=np.zeros((12,3),dtype=np.float32)
        cov[:,0]=1.2;cov[-1,1]=2.5
        features=inputs_for(model,history,covariates=cov)["x_features"]
        np.testing.assert_allclose(features[0,:12,2],np.log1p(1.2))
        np.testing.assert_array_equal(features[0,:12,3],1)
        self.assertAlmostEqual(features[0,11,4],np.log1p(2.5),places=6)
        np.testing.assert_array_equal(features[0,:12,5],1)
        np.testing.assert_array_equal(features[0,12:,:6],0)

    def test_causal_carry_forward_has_a_limit_and_does_not_fill_pre_history(self):
        values=np.array([np.nan,100,np.nan,np.nan,np.nan,110],dtype=np.float32)
        np.testing.assert_array_equal(fill_causal(values,2),[np.nan,100,100,100,np.nan,110])

    def test_persisted_scaling_round_trip_preserves_missing(self):
        glucose = np.array([55, 120, 250, np.nan], dtype=np.float32)
        for scaler in [dict(type="minmax", scale=[0.002003612], min=[-0.003610108]),
                       dict(type="standard", scale=[45], mean=[140])]:
            np.testing.assert_allclose(scale(scale(glucose, scaler), scaler, inverse=True), glucose,
                                       rtol=1e-6, equal_nan=True)

    def test_hub_token_does_not_follow_storage_redirect(self):
        request = urllib.request.Request("https://huggingface.co/owner/model/resolve/main/model.onnx",
                                         headers={"Authorization": "Bearer hf_test"})
        redirected = SafeRedirect().redirect_request(request, None, 302, "Found", {},
                                                       "https://cdn.example/model.onnx?signature=example")
        self.assertIsNone(redirected.get_header("Authorization"))
        with self.assertRaises(ValueError):
            SafeRedirect().redirect_request(request, None, 302, "Found", {}, "http://cdn.example/model.onnx")


if __name__ == "__main__":
    unittest.main()
