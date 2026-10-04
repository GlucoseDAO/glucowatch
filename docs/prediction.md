# Prediction models

The phone’s Model tab saves a Hugging Face model name and optional access token privately.
Enter `owner/repo`, paste a repo/file/tree URL, or search by a short name such as `citras`.
Search returns a repository chooser, then the ONNX files. The three GlucoseDao shortcuts
avoid typing their long repository names. Imports pin the graph and its adjacent
`onnx_meta.json` / `scalers.json` to the same Hub commit. Validation runs before replacing
the previous model. Tokens authenticate HTTPS requests to huggingface.co only; they never
follow a storage redirect, enter the phone link, or accompany glucose uploads. There are no
glucose uploads for inference. `.env` supplies debug defaults; release defaults are empty.

The phone's Connect tab can also [import a filled `.env` or YAML configuration](configuration-import.md)
from a file or URL, save credentials and automatically download/select the supplied model.

## Development and release runtimes

Debug phone and watch APKs include ONNX Runtime Android 1.30.0 through `debugImplementation`
from Maven Central. They run the three requested GlucoseDao exports on the device, including
x86_64 emulators. The shared runtime adapter is in `debug-inference/`; feature preparation
and the predictor are Kotlin source in core. No model or runtime binary is committed.
Debug imports allow up to 128 MiB and fetch preprocessing sidecars automatically.

GitHub releases also provide `glucowatch-phone-onnx-development-<version>.apk` for these
models. This development APK has empty credential defaults and uses the same signing
certificate as the standard releases. Import your private configuration on Connect.
Its native runtime is separate from the three standard release APKs and F-Droid builds.

Release APKs retain the source-only small dense ONNX interpreter and its 10 MiB limit.
The native runtime and developer screenshot hooks are excluded from release builds.
Consequently the three transformer bundles require a development APK; this work does not
make their native runtime part of an F-Droid release. A compatible small model takes
float32 `[1,12]` or `[1,24]` glucose and returns `[1,1..24]` future glucose.

| Export | Past context | Inputs |
|---|---|---|
| CITRAS upper bound | 72 h | Glucose, basal, bolus, carbs, persisted scalers |
| INPAINT-CITRAS | 120 h | Eight glucose/basal/bolus value and availability features; **no carb channel** |
| NF-TFT | 48 h | Glucose, basal, bolus, carbs, persisted min-max scalers |

The display uses **the model's maximum horizon** on both phone and watch, including the
forecast metric and complications. CITRAS and NF-TFT export 24 five-minute future steps
(120 minutes). This INPAINT bundle declares a 48-step gap, so the app shows all 48 steps
(240 minutes). Small dense imports use their output length up to 120 minutes; the built-in
linear trend keeps its 30-minute phone default. The comparison below evaluates the common
first 120 minutes; its scores do not establish accuracy at 240 minutes.

Basal uses U/h, bolus uses U per five-minute bin, and carbs use g per bin. Delivered basal
pulses in U are multiplied by 12. Nightscout schedules respect their version/effective time
and timezone, with temp basals overriding the schedule for their recorded duration. Rates
are averaged over each bin at event/schedule boundaries. A CareLink rate reported now is
not fabricated into a historical delivery schedule. Missing channels remain NaN or have
an observation mask of zero; empty bins within a fetched treatment log mean recorded zero
bolus/carbs. Pump inputs outside the glucose interval are ignored. Different people’s
sources must never be paired.

When pump history is unavailable, the live app continues with glucose and shows a small
“Glucose only” note in Model. If an export cannot return a finite, plausible forecast from
the available inputs, it uses the glucose trend and labels that fallback. This commonly
applies to NF-TFT with short history or missing historical channels. The app does not
silently fabricate zero insulin/carbs. Freshness checks still apply to glucose.

The phone computes a forecast for a paired watch through the existing phone link. Select
“Phone app model” on the watch. No model or Hub token crosses that link.

## Repeating the private comparison

```bash
# Download revision-pinned bundles and fetch glucose:
uv run scripts/benchmark_predictions.py --nightscout
# Glucose + the SAME participant’s pump history, using cached bundles:
uv run scripts/benchmark_predictions.py --entries readings.json --treatments treatments.json \
  --profiles profiles.json --offline --glucose-fill-max-minutes 15 --results data/output/prediction/pump
```

`treatments.json` accepts Nightscout treatment documents or the normalized CareLink export.
`profiles.json` accepts versioned Nightscout profile documents. The benchmark intersects
glucose and treatment coverage before selecting origins; it rejects nonoverlapping input
files for a requested pump comparison. Glucose-only comparison omits `--treatments`.
The native app’s missing-pump fallback is separate from this strict evaluation protocol.

Share offers at most 24 h per request. The phone accumulates two weeks of history as it
continues fetching; a new install initially has only that first day. CareLink recent data
also covers approximately a day. Export both at the same time (absolute paths are necessary
because Gradle’s CLI runs from core):

```bash
./gradlew -q --console=plain :core:run --args="--source share --hours 24 --export-readings $PWD/data/output/prediction/share.json"
./gradlew -q --console=plain :core:run --args="--source carelink --hours 24 --export-treatments $PWD/data/output/prediction/pump.json --export-readings $PWD/data/output/prediction/carelink.json"
uv run scripts/benchmark_predictions.py --entries data/output/prediction/share.json \
  --treatments data/output/prediction/pump.json --offline --minimum-context-hours 12 \
  --minimum-coverage .85 --stride-minutes 15 --results data/output/prediction/share-pump
```

The full-context comparison requires 120 h with 95% glucose coverage and observed 120-minute
targets. The optional causal glucose carry-forward fills small gaps only, never pre-history.
A shorter Share comparison pads unavailable earlier context as missing. All models see
common origins, future glucose never enters inputs, and the forward inpainter has no
post-origin glucose or pump observations. Its padding has real_slot=0 before observed history.
The default future covariates are withheld. `--future-covariates oracle` is an explicitly
separate upper-bound experiment using later pump records; it cannot be deployed live.
`--nf-history-zero-imputed` is a separate, declared missing-channel ablation, not the main
comparison. Non-finite models are reported; metrics use common finite outputs.

Reports record model/input hashes, participant labels when supplied, preprocessing, coverage,
origins, runtime, timing, MAE, RMSE and MARD at 30/60/90/120 minutes, plus glucose baselines.
Overlapping windows are correlated. No weights are fitted, but training-participant overlap
is unverified: these runs do not establish performance on unseen people.
All raw data, reports, models and real screenshots are private under ignored data/output.
Run feature/mask/redirect checks with `uv run scripts/test_benchmark_predictions.py` and
Kotlin checks with `./gradlew :core:test`.

## Emulator captures

```bash
uv run scripts/phone_screenshots.py dexcom --model-bundle data/output/prediction/1 \
  --also CARELINK --preserve-settings --keep \
  --snapshot-out data/output/prediction/phone-snapshot.json
uv run scripts/screenshots.py dexcom --phone-snapshot data/output/prediction/phone-snapshot.json \
  --out data/output/screenshots/prediction --keep
```

Sign CareLink into the phone first. Preserve its rotating session; do not copy the token to
another running device. The phone capture requires an actual model forecast before accepting
screenshots. The watch renders that real phone forecast through a private debug snapshot file:
this verifies rendering and account-cache handling, **not Bluetooth RFCOMM pairing**. Local
watch ONNX inference can also be captured with `--model-bundle` using its own source inputs.
