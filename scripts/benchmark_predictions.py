#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=2.0", "onnxruntime>=1.22"]
# ///
"""Compare user-supplied HF ONNX bundles locally, without training or future observations.

    uv run scripts/benchmark_predictions.py --nightscout
    uv run scripts/benchmark_predictions.py --entries readings.json --offline

Downloads and private results stay in ignored data/output/prediction. Nothing is uploaded.
The comparison accepts pump treatments and versioned basal profiles. Historical covariates
are provided in their training units. Future observations are withheld; an explicitly labelled
upper-bound mode may supply future insulin/carbs, never future glucose. No fitting, participant metadata, or per-person normalization is performed.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
from pathlib import Path

import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
REPOS = (
    "GlucoseDao/CITRAS-full-1e-4-cov-lossmard-ctx864-futureallchannels-fdrop0-upperbound-mv1-s42",
    "GlucoseDao/INPAINT-CITRAS-120h_past-116h_post-one_sensor_wear-bidirectional_attention-full_finetune-s42",
    "GlucoseDao/NF-TFT-lossmard-ctx576-futurecarbs-fdrop0-refit-mv1-s42",
)
NAMES = ("CITRAS (future withheld)", "INPAINT-CITRAS (forward)", "NF-TFT (future withheld)")
STEP_MS = 300_000
HORIZONS = (30, 60, 90, 120)
MAX_DOWNLOAD = 128 * 1024 * 1024


def dotenv() -> dict[str, str]:
    values = {}
    path = ROOT / ".env"
    for line in path.read_text().splitlines() if path.exists() else []:
        line = line.strip().removeprefix("export ").strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        else:
            value = value.split(" #", 1)[0].rstrip()
        values[key.strip()] = value
    import os
    return values | {key: os.environ[key] for key in values.keys() | {"HF_TOKEN"} if key in os.environ}


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlparse(newurl).scheme != "https":
            raise ValueError("Download redirect must use HTTPS")
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None:
            redirected.remove_header("Authorization")
        return redirected


def fetch(url: str, token: str = "") -> bytes:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.username or parsed.password:
        raise ValueError("Downloads require HTTPS without credentials in the address")
    headers = {"User-Agent": "glucowatch-prediction-benchmark"}
    if token and parsed.hostname == "huggingface.co" and parsed.port in (None, 443):
        headers["Authorization"] = "Bearer " + token
    opener = urllib.request.build_opener(SafeRedirect())
    with opener.open(urllib.request.Request(url, headers=headers), timeout=90) as response:
        body = response.read(MAX_DOWNLOAD + 1)
    if len(body) > MAX_DOWNLOAD:
        raise ValueError("Download exceeds 128 MB")
    return body


def bundles(cache: Path, token: str, offline: bool) -> list[dict]:
    models = []
    for i, repo in enumerate(REPOS):
        folder = cache / str(i)
        folder.mkdir(parents=True, exist_ok=True)
        info_path = cache / f"model-{i}.json"
        if not offline:
            info = json.loads(fetch(f"https://huggingface.co/api/models/{repo}", token))
            old = json.loads(info_path.read_text()) if info_path.exists() else {}
            if old.get("sha") != info["sha"]:
                for existing in folder.iterdir():
                    if existing.is_file():
                        existing.unlink()
            info_path.write_text(json.dumps(info, indent=2))
        info = json.loads(info_path.read_text())
        for name in ("onnx/onnx_meta.json", "onnx/scalers.json", "onnx/model.onnx", "onnx/example_inputs.npz"):
            if name not in {f["rfilename"] for f in info["siblings"]}:
                continue
            local = folder / Path(name).name
            if not local.exists():
                if offline:
                    raise ValueError(f"Offline bundle {i} is incomplete")
                body = fetch(f"https://huggingface.co/{repo}/resolve/{info['sha']}/{name}", token)
                temp = local.with_suffix(local.suffix + ".tmp")
                temp.write_bytes(body)
                temp.replace(local)
        meta = json.loads((folder / "onnx_meta.json").read_text())
        scalers = json.loads((folder / "scalers.json").read_text())["features"] if (folder / "scalers.json").exists() else {}
        models.append(dict(repo=repo, revision=info["sha"], folder=folder, meta=meta, scalers=scalers))
    return models


def scale(values: np.ndarray, scaler: dict, inverse: bool = False) -> np.ndarray:
    factor = float(scaler["scale"][0])
    if scaler["type"] == "minmax":
        offset = float(scaler["min"][0])
        return (values - offset) / factor if inverse else values * factor + offset
    if scaler["type"] == "standard":
        mean = float(scaler["mean"][0])
        return values * factor + mean if inverse else (values - mean) / factor
    raise ValueError(f"Unsupported scaler: {scaler['type']}")


def inputs_for(model: dict, history: np.ndarray, nf_impute: bool = False,
               covariates: np.ndarray | None = None, future: np.ndarray | None = None) -> dict[str, np.ndarray]:
    """History is raw glucose; covariates are basal U/h, bolus U, carbs g. No targets enter."""
    meta = model["meta"]
    cov = np.full((len(history), 3), np.nan, dtype=np.float32) if covariates is None else covariates
    if meta["task"] == "inpaint":
        past = int(meta["gap_start"])
        x = np.zeros((1, meta["input_steps"], len(meta["channels"])), dtype=np.float32)
        g = history[-past:]
        seen = np.isfinite(g) & (g > 0)
        x[0, :past, 0] = np.where(seen, g / 100, 0)
        x[0, :past, 1] = seen
        for channel, value_index, mask_index in [(0, 2, 3), (1, 4, 5)]:
            values = cov[-past:, channel]
            observed = np.isfinite(values) & (values >= 0)
            x[0, :past, value_index] = np.where(observed, np.log1p(np.maximum(values, 0)), 0)
            x[0, :past, mask_index] = observed
            if future is not None:
                vals = future[:, channel]; known = np.isfinite(vals) & (vals >= 0)
                x[0, past:past+len(vals), value_index] = np.where(known, np.log1p(np.maximum(vals, 0)), 0)
                x[0, past:past+len(vals), mask_index] = known
        x[0, past:, 6] = 1
        first_seen = np.flatnonzero(seen)
        if first_seen.size:
            x[0, first_seen[0]:, 7] = 1
        return {meta["inputs"][0]["name"]: x}
    raw = np.column_stack([history, cov])[-meta["input_steps"]:]
    x = np.full((1, meta["input_steps"], len(meta["channels"])), np.nan, dtype=np.float32)
    for channel, name in enumerate(meta["channels"]):
        if name in model["scalers"]:
            x[0, :, channel] = scale(raw[:, channel], model["scalers"][name])
    if nf_impute and meta["family_kind"] == "nf":
        x = np.nan_to_num(x, nan=0.0)
    result = {meta["inputs"][0]["name"]: x}
    if len(meta["inputs"]) > 1:
        f = np.full((1, meta["horizon_steps"], len(meta["channels"])), np.nan, dtype=np.float32)
        if future is not None:
            for channel, name in enumerate(meta["channels"][1:], 1):
                f[0, :len(future), channel] = scale(future[:, channel-1], model["scalers"][name])
        result[meta["inputs"][1]["name"]] = f
    return result


def timestamp(value) -> int | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return int(value if value > 1e12 else value * 1000)
    try:
        return int(datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp() * 1000)
    except (ValueError, TypeError):
        return None


class PumpHistory:
    def __init__(self, treatment_path: Path | None, profile_path: Path | None):
        document = json.loads(treatment_path.read_text()) if treatment_path else []
        rows = document.get("treatments",[]) if isinstance(document,dict) else document
        self.events = []
        for row in rows:
            at = timestamp(row.get("timeMillis") or row.get("created_at") or row.get("timestamp") or row.get("date"))
            if at is None or row.get("isValid") is False:
                continue
            kind = str(row.get("eventType", "")).lower()
            basal = row.get("insulinKind") == "BASAL" or "basal" in kind
            rate = row.get("basalRate", row.get("absolute", row.get("rate"))) if basal else None
            percent = row.get("basalPercent", row.get("percent")) if basal else None
            duration = row.get("durationMinutes", row.get("duration"))
            self.events.append(dict(time=at, available=max(at, timestamp(row.get("creation_date")) or at),
                insulin=max(0, float(row.get("insulin") or 0)), carbs=max(0, float(row.get("carbs") or 0)),
                basal=basal, rate=None if rate is None else float(rate), percent=None if percent is None else float(percent),
                duration=None if duration is None else float(duration), percent_of_profile=bool(row.get("percentOfProfile"))))
        self.events.sort(key=lambda x: x["time"])
        self.start = min((x["time"] for x in self.events), default=0)
        self.end = max((x["time"] for x in self.events), default=0)
        if isinstance(document,dict):
            self.start = int(document.get("coverage_start",self.start))
            self.end = int(document.get("coverage_end",self.end))
        self.profiles = []
        for row in json.loads(profile_path.read_text()) if profile_path else []:
            effective = timestamp(row.get("mills") or row.get("startDate") or row.get("created_at"))
            store = row.get("store", {}); data = store.get(row.get("defaultProfile"), {})
            if effective is None or not data.get("basal") or not data.get("timezone"):
                continue
            entries = sorted((int(v.get("timeAsSeconds", sum(int(n)*m for n,m in zip(v["time"].split(":"), [3600,60,1])))), float(v["value"])) for v in data["basal"])
            self.profiles.append(dict(time=effective, zone=ZoneInfo(data["timezone"]), entries=entries))
        self.profiles.sort(key=lambda p:p["time"])

    def grid(self, times: np.ndarray, origin: int, oracle: bool = False) -> np.ndarray:
        events = [e for e in self.events if oracle or e["available"] <= origin]
        temps = [e for e in events if e["basal"] and e["duration"] is not None]
        profiles = [p for p in self.profiles if p["time"] <= origin]
        def rate(at):
            applicable = [p for p in profiles if p["time"] <= at]
            scheduled = np.nan
            if applicable:
                p = applicable[-1]; clock = datetime.fromtimestamp(at/1000, p["zone"])
                seconds = clock.hour*3600+clock.minute*60+clock.second
                known = [v for t,v in p["entries"] if t <= seconds]
                scheduled = known[-1] if known else p["entries"][-1][1]
            known_temps = [e for e in temps if e["time"] <= at]
            if known_temps:
                t = known_temps[-1]
                if t["duration"] > 0 and at < t["time"] + t["duration"]*60000:
                    if t["rate"] is not None: return t["rate"]
                    if t["percent"] is not None: return scheduled * (t["percent"]/100 if t["percent_of_profile"] else 1+t["percent"]/100)
            return scheduled
        result = np.full((len(times),3), np.nan, dtype=np.float32)
        if not len(times): return result
        first = int(times[0])
        # Exact integration at temp/profile boundaries within each five-minute bin.
        changes = [v for e in temps for v in (e["time"], e["time"]+e["duration"]*60000)]
        changes += [p["time"] for p in profiles]
        for i, end in enumerate(times):
            start = int(end)-STEP_MS; end=int(end)
            if self.start <= start and end <= self.end and (oracle or end <= origin): result[i,1:] = 0
            points = [start,end]+[v for v in changes if start < v < end]
            for p in profiles:
                day = datetime.fromtimestamp(start/1000,p["zone"]).replace(hour=0,minute=0,second=0,microsecond=0)
                for seconds,_ in p["entries"]:
                    at=int(day.timestamp()*1000)+seconds*1000
                    if start < at < end: points.append(at)
            points=sorted(set(points))
            result[i,0] = sum(rate(a)*(b-a)/STEP_MS for a,b in zip(points,points[1:]))
        pulses=set()
        for e in events:
            if not oracle and e["time"] > origin: continue
            slot = int(np.ceil((e["time"]-first)/STEP_MS))
            if not 0 <= slot < len(times): continue
            for ch,amount in [(1,0 if e["basal"] else e["insulin"]),(2,e["carbs"])]:
                if amount > 0: result[slot,ch]=(result[slot,ch] if np.isfinite(result[slot,ch]) else 0)+amount
            if e["basal"] and e["rate"] is None and e["percent"] is None and e["duration"] is None:
                if slot not in pulses: result[slot,0]=0; pulses.add(slot)
                result[slot,0]+=e["insulin"]*12
            elif e["rate"] is not None and e["duration"] is None and slot not in pulses: result[slot,0]=e["rate"]
        return result


def fill_causal(history: np.ndarray, max_steps: int) -> np.ndarray:
    history=history.copy(); last=np.nan; gap=0
    for i,value in enumerate(history):
        if np.isfinite(value): last=value; gap=0
        else:
            gap+=1
            if gap <= max_steps: history[i]=last
    return history


def prediction(model: dict, output: np.ndarray) -> np.ndarray:
    if model["meta"]["task"] == "inpaint":
        start = model["meta"]["gap_start"]
        return output[0, start:start + 24]
    return scale(output[0, :24], model["scalers"]["glucose"], inverse=True)


def nightscout_entries(values: dict, cache: Path) -> Path:
    base = values.get("NIGHTSCOUT_URL", "").rstrip("/")
    if not base:
        raise ValueError("Configure NIGHTSCOUT_URL in .env or pass --entries")
    if values.get("NIGHTSCOUT_API", "v1") != "v1":
        raise ValueError("This benchmark fetches API v1; export readings and pass --entries for v3")
    params = {"count": 10000, "find[date][$gte]": int((time.time() - 14 * 86400) * 1000)}
    if values.get("NIGHTSCOUT_TOKEN"):
        params["token"] = values["NIGHTSCOUT_TOKEN"]
    body = fetch(base + "/api/v1/entries/sgv.json?" + urllib.parse.urlencode(params))
    path = cache / "nightscout-entries.json"
    path.write_bytes(body)
    path.chmod(0o600)
    return path


def grid(path: Path) -> tuple[np.ndarray, np.ndarray, dict]:
    rows = json.loads(path.read_text())
    points = sorted({int(row["date"]): float(row["sgv"]) for row in rows
                     if row.get("date") and row.get("sgv") and 20 <= float(row["sgv"]) <= 600}.items())
    if not points:
        raise ValueError("No valid Nightscout glucose readings")
    stamps, glucose = map(np.asarray, zip(*points))
    anchor = int(stamps[-1])
    slots = np.rint((stamps - anchor) / STEP_MS).astype(int)
    offsets = np.abs(stamps - (anchor + slots * STEP_MS))
    good = offsets <= STEP_MS // 2
    first = slots.min()
    values = np.full(1 - first, np.nan, dtype=np.float32)
    times = anchor + np.arange(first, 1) * STEP_MS
    # Assign in timestamp order so the latest duplicate wins. Never interpolate missing glucose.
    for slot, value in zip(slots[good], glucose[good]):
        values[slot - first] = value
    # Use the actual origin timestamp to enforce the causal cutoff if a reading was snapped early.
    actual = np.full(values.size, np.nan)
    for slot, stamp in zip(slots[good], stamps[good]):
        actual[slot - first] = stamp
    return values, times, dict(readings=len(points), aligned=int(good.sum()), alignment_tolerance_seconds=150,
                              observed_slots=int(np.isfinite(values).sum()), actual_times=actual)


def evaluate(models: list[dict], entries: Path, cache: Path, args) -> dict:
    values, times, coverage = grid(entries)
    pump = PumpHistory(args.treatments, args.profiles)
    actual_times = coverage.pop("actual_times")
    past = max(int(m["meta"].get("gap_start") or m["meta"]["input_steps"]) for m in models)
    if args.treatments:
        shared = (times >= pump.start) & (times <= pump.end)
        if not shared.any():
            raise ValueError("Glucose and pump history do not overlap. Fetch matching records; never shift timestamps or substitute another person's pump.")
        values[~shared] = np.nan
        first_shared = int(np.flatnonzero(shared)[0])
        last_shared = int(np.flatnonzero(shared)[-1])
    else:
        first_shared, last_shared = 0, len(values)-1
    minimum_past = round(args.minimum_context_hours * 12)
    stride = args.stride_minutes // 5
    eligible = []
    for origin in range(first_shared + minimum_past - 1, last_shared - 23, stride):
        history = values[max(first_shared, origin - past + 1):origin + 1]
        target = values[origin + 1:origin + 25]
        if np.isfinite(history).mean() >= args.minimum_coverage and np.isfinite(history[-5:]).all() and np.isfinite(target).all():
            eligible.append(origin)
    if not eligible:
        raise ValueError(f"No common windows: need {args.minimum_context_hours:g} h history ({args.minimum_coverage:.0%} coverage), then 120 min observed targets")
    if len(eligible) > args.max_origins:
        eligible = [eligible[i] for i in np.linspace(0, len(eligible)-1, args.max_origins, dtype=int)]
    model_names = list(NAMES)
    if args.future_covariates == "oracle":
        model_names = ["CITRAS (oracle pump)", "INPAINT-CITRAS (oracle pump)", "NF-TFT (oracle pump)"]
    if args.nf_history_zero_imputed:
        model_names[2] = "NF-TFT (history zero-imputed)"
    names = model_names + ["Persistence", "Linear trend"]
    outputs = {name: [] for name in names}
    latencies = {name: [] for name in model_names}
    options = ort.SessionOptions()
    options.intra_op_num_threads = args.threads
    sessions = [ort.InferenceSession(str(m["folder"] / "model.onnx"), options,
                                    providers=["CPUExecutionProvider"]) for m in models]
    smoke = []
    for name, model, session in zip(model_names, models, sessions):
        with np.load(model["folder"] / "example_inputs.npz", allow_pickle=False) as example:
            trial = session.run(None, dict(example))[0]
        if not np.isfinite(trial).all():
            raise ValueError(f"{name} failed example inference")
        smoke.append(dict(model=name, shape=list(trial.shape), finite=True))
    targets, origins = [], []
    for index, origin in enumerate(eligible):
        first = max(first_shared, origin - past + 1)
        observed = values[first:origin + 1].copy()
        # A snapped historical reading after this actual origin must stay missing.
        observed[actual_times[first:origin + 1] > actual_times[origin]] = np.nan
        history = np.full(past, np.nan, dtype=np.float32)
        history[-len(observed):] = observed
        history = fill_causal(history, args.glucose_fill_max_minutes // 5)
        origin_time = int(actual_times[origin])
        history_times = origin_time + np.arange(1-past, 1)*STEP_MS
        covariates = pump.grid(history_times, origin_time)
        future = pump.grid(origin_time + np.arange(1,25)*STEP_MS, origin_time, oracle=True) if args.future_covariates == "oracle" else None
        result = {}
        for name, model, session in zip(model_names, models, sessions):
            inputs = inputs_for(model, history, args.nf_history_zero_imputed, covariates, future)
            start = time.perf_counter()
            output = session.run(None, inputs)[0]
            latencies[name].append((time.perf_counter() - start) * 1000)
            result[name] = prediction(model, output).astype(np.float64)
        result["Persistence"] = np.full(24, history[-1])
        xs = np.arange(-20, 1, 5)
        slope = np.sum(xs * (history[-5:] - history[-5:].mean())) / np.sum((xs - xs.mean()) ** 2)
        intercept = history[-5:].mean() - slope * xs.mean()
        result["Linear trend"] = np.clip(intercept + slope * np.arange(5, 121, 5), 40, 400)
        for name in names:
            outputs[name].append(result[name])
        targets.append(values[origin + 1:origin + 25])
        origins.append(int(actual_times[origin]))
        if (index + 1) % 40 == 0:
            print(f"Scored {index + 1}/{len(eligible)} origins", flush=True)
    target = np.asarray(targets, dtype=np.float64)
    outputs = {name: np.asarray(y) for name, y in outputs.items()}
    valid = {name: np.isfinite(y).all(axis=1) for name, y in outputs.items()}
    invalid = {name: int((~valid[name]).sum()) for name in model_names}
    supported = [name for name in names if valid[name].any()]
    shared = np.logical_and.reduce([valid[name] for name in supported])
    if not shared.any():
        raise ValueError("No common finite outputs among runnable models")
    origins = np.asarray(origins)[shared]
    target = target[shared]
    outputs = {name: y[shared] for name, y in outputs.items()}
    metrics = []
    for name in supported:
        y = outputs[name]
        for minutes in HORIZONS:
            step = minutes // 5 - 1
            error = y[:, step] - target[:, step]
            metrics.append(dict(model=name, horizon_minutes=minutes, windows=len(target),
                                mae_mgdl=float(np.abs(error).mean()),
                                rmse_mgdl=float(np.sqrt(np.mean(error**2))),
                                mard_percent=float(np.mean(np.abs(error)/target[:, step])*100)))
    with (cache / "metrics.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=list(metrics[0]))
        writer.writeheader()
        writer.writerows(metrics)
    np.savez_compressed(cache / "predictions-private.npz", origins=origins, targets=target,
                        **{f"model_{i}": np.asarray(outputs[name]) for i, name in enumerate(names)})
    report = dict(protocol="Glucose plus available pump covariates, no fitting, common origins; future glucose never supplied",
                  future_covariates=args.future_covariates,
                  covariate_units=dict(basal="U/h", bolus="U per five-minute bin", carbs="g per five-minute bin"),
                  treatment_events=len(pump.events), participant=args.participant, basal_profiles=len(pump.profiles),
                  therapy_source_sha256=hashlib.sha256(args.treatments.read_bytes()).hexdigest() if args.treatments else None,
                  profile_source_sha256=hashlib.sha256(args.profiles.read_bytes()).hexdigest() if args.profiles else None,
                  glucose_causal_fill_max_minutes=args.glucose_fill_max_minutes,
                  nf_historical_missing="scaled zero imputation (ablation)" if args.nf_history_zero_imputed else "NaN (missing)",
                  training_overlap="Not verified: this is inference without adaptation, not proof of unseen participants",
                  source_sha256=hashlib.sha256(entries.read_bytes()).hexdigest(), coverage=coverage,
                  required_context_hours=past*5/60, minimum_context_hours=args.minimum_context_hours,
                  short_context=args.minimum_context_hours < past*5/60,
                  minimum_coverage=args.minimum_coverage,
                  available_context_hours=[(min(past, origin+1)*5/60) for origin in eligible],
                  stride_minutes=args.stride_minutes, windows=len(target),
                  first_origin=datetime.fromtimestamp(origins[0]/1000, timezone.utc).isoformat(),
                  last_origin=datetime.fromtimestamp(origins[-1]/1000, timezone.utc).isoformat(),
                  attempted_windows=len(eligible), invalid_output_windows=invalid,
                  unsupported_models=[name for name in model_names if name not in supported],
                  smoke=smoke, metrics=metrics,
                  runtime=ort.__version__, provider="CPUExecutionProvider", threads=args.threads,
                  latency_ms={name: dict(median=float(np.median(t)), p95=float(np.percentile(t,95)))
                              for name,t in latencies.items()},
                  models=[dict(repo=m["repo"], revision=m["revision"],
                               model_sha256=hashlib.sha256((m["folder"] / "model.onnx").read_bytes()).hexdigest())
                          for m in models])
    (cache / "report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps({"windows": len(target), "invalid_output_windows": invalid,
                      "metrics": metrics, "latency_ms": report["latency_ms"]}, indent=2))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / "data/output/prediction")
    parser.add_argument("--entries", type=Path, help="Nightscout entries JSON, without timestamp shifting")
    parser.add_argument("--participant", help="Private participant label for separate reports")
    parser.add_argument("--treatments", type=Path, help="Nightscout or normalized pump treatment history")
    parser.add_argument("--profiles", type=Path, help="Versioned Nightscout pump basal profiles")
    parser.add_argument("--future-covariates", choices=["withheld", "oracle"], default="withheld",
                        help="Oracle is an upper-bound experiment using later pump records, never deployable live")
    parser.add_argument("--glucose-fill-max-minutes", type=int, default=0,
                        help="Optional causal glucose carry-forward for small gaps; never fills pre-history")
    parser.add_argument("--nightscout", action="store_true", help="Read the last 14 days from .env Nightscout (v1)")
    parser.add_argument("--offline", action="store_true", help="Use cached, revision-pinned bundles without Hub requests")
    parser.add_argument("--max-origins", type=int, default=192)
    parser.add_argument("--stride-minutes", type=int, default=60)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--minimum-context-hours", type=float, default=120,
                        help="Default 120; a smaller value is an explicitly labelled short-context experiment")
    parser.add_argument("--results", type=Path, help="Results folder, separate from the model cache")
    parser.add_argument("--minimum-coverage", type=float, default=0.95,
                        help="Fraction of available history observed (default .95)")
    parser.add_argument("--nf-history-zero-imputed", action="store_true",
                        help="Separate ablation: replace missing TFT historical inputs with scaled zeros")
    args = parser.parse_args()
    if args.max_origins < 1 or args.threads < 1 or args.stride_minutes < 5 or args.stride_minutes % 5:
        parser.error("Positive origins/threads and a stride divisible by five minutes are required")
    if not 1 <= args.minimum_context_hours <= 120:
        parser.error("Minimum context must be between 1 and 120 hours")
    if not 0 < args.minimum_coverage <= 1:
        parser.error("Minimum coverage must be in (0, 1]")
    if args.entries and args.nightscout:
        parser.error("Choose --entries or --nightscout")
    args.cache.mkdir(parents=True, exist_ok=True)
    values = dotenv()
    models = bundles(args.cache, values.get("HF_TOKEN", ""), args.offline)
    entries = nightscout_entries(values, args.cache) if args.nightscout else args.entries
    if entries is None:
        parser.error("Choose --entries or --nightscout")
    results_dir = args.results or (args.cache / "nf-zero-imputed" if args.nf_history_zero_imputed else args.cache)
    results_dir.mkdir(parents=True, exist_ok=True)
    evaluate(models, entries, results_dir, args)


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        raise SystemExit(f"Download failed with HTTP {error.code}; check credentials and access") from None
    except urllib.error.URLError:
        raise SystemExit("Download failed; check connectivity and the configured address") from None
