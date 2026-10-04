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

## Phone and watch runtimes

The normal GlucoPhone release APK includes ONNX Runtime Android 1.28.0 (MIT licensed)
from Maven Central. It runs all three GlucoseDao exports locally, including on x86_64
emulators. Import your private configuration on Connect; no separate development APK
is needed. Imports allow up to 128 MiB and fetch preprocessing sidecars automatically.
The shared adapter is in `onnx-inference/`; feature preparation and predictors are
Kotlin source in core. No runtime or model binary is committed. Model weights are
downloaded only after the user chooses a model. The phone APK includes the runtime's
MIT licence and third-party notices under `assets/licenses/`.

Version 1.28's Android package has no telemetry initializer or transport classes. The
adapter also disables telemetry before creating sessions. Later runtime versions must
be checked again for telemetry before upgrading. Release minification keeps the JNI
classes and members by their Java names, as required by the runtime.

Watch release APKs retain the source-only small dense ONNX interpreter with a 10 MiB
limit. Watch development builds include the same native adapter for emulator tests.
For the transformer models in normal use, the phone computes the forecast and relays
all points to the watch; neither the model nor the Hub token crosses that link.
A compatible standalone small model takes float32 `[1,12]` or `[1,24]` glucose and
returns `[1,1..24]` future glucose.

F-Droid has [no blanket ban on prebuilt free-software Maven dependencies](https://f-droid.org/en/docs/Inclusion_Policy/).
Its acceptance still requires a dependency, scanner and manual review. The existing
watch and face merge requests do not evaluate the phone runtime; no phone submission
has been made. Developer screenshot/configuration extras stay out of release builds.

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
