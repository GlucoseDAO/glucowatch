# The phone app and the Bluetooth link

Written on 2026-09-25, for the first version of `phone/` (after tag `v0.1.5`). The protocol is
in `core/src/main/kotlin/glucowatch/core/link/`, and its tests in `core/src/test/.../link/`.

The watch still works on its own. The phone app is optional and does two things:

- **Relay.** On the watch, the source **Phone app** reads the phone's readings, treatments, loop
  status and, if asked, a forecast from the phone's model. Then the watch needs no login and no
  internet. The phone fetches from Dexcom Share, Nightscout, CareLink or demo data. Additional
  insulin sources add pump/loop data to the selected CGM's trajectory (see [CareLink](carelink.md)).
- **Copy login.** With the source **Dexcom Share** or **Nightscout**, the watch can copy the
  phone's login instead of the user typing it on the watch. After that the watch fetches by itself.

## Why no Play Services

The usual phone–watch channel, the Wearable Data Layer, is part of Google Play Services. F-Droid
rejects that dependency, and it would contaminate every tag of this repo (see `AGENTS.md`). The link
uses only platform APIs instead: `BluetoothAdapter`, RFCOMM sockets and `javax.crypto`. The
phone and the watch are already bonded by Wear OS, so there is no scanning and no second system
pairing. The phone app has its own package id, `io.github.antonkulaga.glucowatch.phone`. The Data
Layer would need the same package name on both sides; RFCOMM does not.

## The exchange

The phone app runs a foreground service (type `connectedDevice`) that listens on an RFCOMM
service record, `PhoneLink.SERVICE_UUID`. The watch opens one connection per request and the
phone answers it. Each message is a 4-byte length followed by the body.

Protocol version 2 adds the insulin kind, basal rate/percentage and duration to each relayed
treatment. Update the watch and phone together; a version mismatch shows an update message.
The service UUID and pairing keys are unchanged, so existing pairings survive the update.
Old four-field treatment caches still read as boluses/carbs.

| Request | When | Answer |
|---|---|---|
| `PAIR` | The user taps **Pair with phone** on the watch while pairing is open on the phone | The phone's id, public key and name |
| `SYNC` | On every watch refresh with the source **Phone app** | The last 24 h: readings, treatments, loop status, the phone's source name and its last error, and a forecast if the watch asked for one |
| `ACCOUNT` | The user taps **Copy login from phone** | The phone's source and login |

The phone sends the whole day on every sync, which is a few kB. The watch merges the readings
into its cache while `LinkSnapshot.upstream` stays the same. `upstream` is a hash of the phone's
account keys and selected additional insulin sources. When the phone switches account or pump
selection, `upstream` changes and the watch replaces its cache
instead of merging. The watch's own cache rule still applies: `Settings.accountKey` is
`phone:<phone id>`, so a new pairing with another phone starts empty.

The phone merges its glucose source with its additional insulin sources before it answers, so
one sync carries both: Dexcom readings next to CareLink boluses, basal and active insulin. With
the source **Phone app**, the watch offers no additional sources of its own. A CareLink extra on
the watch would have to take the phone's sign-in, which cannot live on both devices. When one of
the phone's sources fails, the sync still relays what the others returned; the watch shows the
phone's error but does not record it as a failure of its own network.

A sync makes the phone fetch, unless its last fetch is under 30 s old. While the phone dashboard
is visible, it also checks for updates every minute and when reopened. This polling stops when
the dashboard is hidden. Glucose older than ten minutes is grey and labelled **STALE**; a
successful request does not make an old reading current. The watch waits up to 45 s for an
answer, which is enough for a Dexcom or Nightscout round trip.

## Pairing and encryption

1. On the phone, **Pair a watch** opens pairing for two minutes. At any other time the phone
   refuses `PAIR`.
2. The watch sends a SHA-256 commitment to a fresh P-256 public key. The phone answers with its
   own public key. Then the watch reveals its key, and the phone checks it against the commitment.
3. Both derive a key and a six-digit code from the ECDH secret and a hash of both ids and both
   public keys (HMAC-SHA256). Both screens show the code, and the user confirms it on both. The
   phone keeps the key only after its user confirms, and so does the watch.

The commitment comes first so a device in the middle cannot choose keys that make the two codes
match. It would have to guess the code, a one-in-a-million chance per attempt.

Each later request and answer is AES-256-GCM under that key. The authenticated data binds each
message to its direction, its kind and the watch's id. Each request carries a fresh random
challenge, and the answer must return it. A replayed or reflected message therefore fails.
Error answers are not encrypted. They carry only text such as "Pair them again".

The keys are in app-private storage on each device, as the settings are (`allowBackup` is off
in both apps). Forgetting a watch on the phone removes its key.

## Permissions

| | Watch app | Phone app |
|---|---|---|
| `BLUETOOTH_CONNECT` ("Nearby devices") | Asked when the user pairs, copies a login or saves with the source **Phone app** | Asked on first start and when pairing |
| `BLUETOOTH` | Not needed: minSdk 33 | API 29–30 only (`maxSdkVersion="30"`) |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | – | For the listening service |
| `POST_NOTIFICATIONS` | – | For the service's notification. Optional; the service runs without it |
| `CAMERA` | – | Not declared. A meal photo is taken by the phone's own camera app through a `FileProvider` |
| `health.READ_HEART_RATE` | – | Optional, foreground only, Android 14+. Never relayed or uploaded |

## Forecast on the phone

The watch's forecast setting gets a choice **Phone app model** (`PhoneLink.MODEL_ID`) when its
source is **Phone app**. The phone then runs the model chosen in its own settings, from
`Predictors` or a user-imported ONNX model. The latter stays in the phone's app-private storage;
no model is bundled, fetched automatically, uploaded or sent to the watch. The source-only
interpreter in `core/` accepts a single float32 input `[1,12]` or `[1,24]` of five-minute
glucose values in mg/dL, oldest first, and a single float32 output `[1,1..24]` of future
five-minute mg/dL values. Supported operators are Gemm, MatMul, Add, Sub, Mul, Relu, Sigmoid,
Tanh, Identity, Flatten and Reshape. Other ONNX graphs are rejected on import.

A model comes from one of two places, and only when the user asks for it:

- **A Hugging Face repo.** The repo id on its own is enough (`owner/model`, or `owner/model@rev`);
  a repo page, a `tree`, `blob` or `resolve` link works too. `HuggingFaceModel` in `core/` turns
  what was typed into `https://huggingface.co/api/models/<repo>/revision/<rev>`, the app reads
  that listing, and the repo's `.onnx` files are shown for the user to pick from. Only then is a
  file downloaded, over HTTPS, following Hugging Face's redirect to its file storage. A `resolve`
  or `blob` link to one file skips the listing, so a repo whose listing is unavailable still
  works. Gated and private repos are refused rather than authenticated: no token is ever sent.
- **A file on the phone**, through the system document picker.

Either way the model is loaded and checked before it replaces the one already stored, is capped at
10 MB, and lives only in `filesDir`. A new bundled model still belongs in `core/` as source and
implements `GlucosePredictor`. The watch shows the
phone's forecast only when it starts from the latest reading. Otherwise
it shows "No fresh phone forecast". The choice **Loop (Nightscout)** also works through the phone,
because the phone relays the loop status.

## The phone screen

Four tabs on a bottom bar, built in code like the watch app's screens, with no layout XML and no
UI library:

- **Today.** The latest reading in its colour with its trend arrow, an IN RANGE / LOW / HIGH
  pill, its age and the current heart rate; the glucose chart; insulin and carbs on board and the
  last bolus; time in range, the 30-minute change and the forecast; logging a meal or insulin;
  and the switch for the heart-rate track.
- **Connect.** Source, login and units — the same fields as before.
- **Model.** The forecast model, and importing one (above).
- **Watch.** Pairing, and the watches this phone answers.

The chart is `GlucoseChartView`, a plain `View` drawing on a `Canvas`, in the style of the
GlucoseDAO logo: readings are ball-and-stick atoms — a coloured ball with a dark core — strung on
bonds. Gaps longer than 20 minutes are left open rather than bridged, the forecast is dashed in
grey because it is a guess and not a measurement, and logged meals hang off the chain.

The palette is in `phone/…/Brand.kt`: black, `#9AA3A8` grey, `#F0525A` red and white, the same
values as the watch face in `watchface/src/main/res/raw/watchface.xml`. Colour means one thing
only — where the glucose sits — as a numeric code running green through yellow and orange to red,
interpolated between anchors so a run drifting high shades over before it crosses 180. The watch
app's `ui/Brand.kt` still holds the older teal for its tiles, complications and settings screens;
that is a separate palette and nothing in `phone/` reads it.

## History

The chart follows now until it is dragged. Drag sideways to go back, fling to travel further,
pinch to show between 1 and 48 hours, tap to read one moment (time, glucose, heart rate, insulin
and carbs there), and double-tap or **Now** to come back. Midnight is marked and labelled with the
day, and the day being shown sits in the top corner.

The phone keeps 14 days (`PhoneRepository.HISTORY_HOURS`), through `SourceSync(retainMs = …)`;
the watch keeps its day, the default. Dexcom Share only ever serves the last 24 hours, so a Share
user's longer history builds up fetch by fetch, from the day the app is installed. Nightscout's
first run backfills up to five days, as much as one request returns. The phone still sends the
watch only its last 24 hours, so the Bluetooth message is the size it was.

## Heart rate

The current heart rate is always in the header: a red heart and the bpm, greyed when the newest
sample is more than 30 minutes old. The row under the meals adds or removes a heart-rate track on
the chart: a thin red line across the top of the plot on its own bpm scale, so glucose keeps the
full height. It reads Health Connect (Android 14 and later) in the foreground only, and nothing
is relayed or uploaded. Demo data has its own synthetic heart rate
(`DemoData.heartRate`), so the demo shows both with no watch and no Health Connect.

## Insulin

Insulin comes from two places. From Nightscout: the treatments the loop or Careportal recorded,
and the loop's insulin and carbs on board. From the phone: **Log insulin** records a basal or bolus dose here,
which is the only insulin a Dexcom Share user has, since Share carries glucose only. On the chart
a bolus hangs from the top as a white atom with its units; a dose the loop gave on its own is a
smaller grey atom. The line under the chart shows insulin and carbs on board, while the loop's
report is under 30 minutes old, and the last bolus. Basal events have a separate row of square
markers with U for doses and U/h (or percentage adjustment) for temp basal settings. The latest
basal event appears with its age below the chart. Logged doses stay on the phone; basal doses
are excluded from "last bolus". Previously logged doses keep their original bolus classification.

## Meals

The Today tab can log what the user ate: **Photograph food** opens whatever camera app the phone
has, then asks for the carbohydrates and an optional note. The entry appears on the chart as a
marker on the curve with the grams and a thumbnail of the photo.

The app declares no `CAMERA` permission. It hands the camera app one private file through a
`FileProvider` limited to `filesDir/food/` (`res/xml/file_paths.xml`), and `android.hardware.camera`
is `required="false"`, so a phone with no camera still logs carbs. The photo is scaled down to
640 px and re-encoded before it is kept.

Meals and logged insulin are health data and stay on the phone: they are not relayed to the
watch, not uploaded, and not written to Nightscout. Entries older
than 30 days are dropped with their photos. Screenshots of a real meal log are the user's health
data; do not publish them.

## Stores

- **F-Droid.** The phone app is a third package, with its own fdroiddata merge request and
  `subdir: phone`. `(cd phone && ../gradlew assembleRelease)` leaves one APK,
  `glucowatch-phone-release-unsigned.apk`. It reads Dexcom Share, so it needs the same
  `NonFreeNet` anti-feature as the watch app. Demo data keeps it usable with no account. Its
  store text is in `phone/fastlane/`. That directory sorts after the root `fastlane/`, so its text
  wins for this package, but the root screenshots still show on its page (see
  `docs/store-publishing.md`).
- **IzzyOnDroid, Obtainium.** They distribute the signed GitHub release APK, like the other two.
- **Google Play.** Copying the login moves the password input to the phone, which is what rule
  WO-P6 asks for. A Play build would also have to remove the login fields on the watch.

## What is verified

- `:core:test` covers pairing (same key and code on both sides), sync and copying a login over
  in-memory pipes, and the refusals: pairing that is closed, an unknown watch, a wrong key, a
  watch that reveals another key than it committed to, and tampered messages.
- Each module's release build, started inside the module as F-Droid does, leaves one APK.
  `fdroid scanner` 2.4.5 finds no non-free classes and no extra signing block in the phone and
  watch APKs.
- On an AOSP phone emulator (API 36, no Play Services), the app shows demo data, asks for Nearby
  devices only when pairing starts, and runs the listening service as a `connectedDevice`
  foreground service.
- On a Wear OS emulator, Settings offers the four sources, asks for Nearby devices, and reports
  when no phone answers. Dexcom Share and Nightscout still fetch through `SourceSync`.
- On a 1080 × 2340 phone emulator, the companion dashboard and its source, model and watch tabs
  were captured with Demo, Dexcom Share and Nightscout (`scripts/phone_screenshots.py`). The
  optional heart-rate card uses platform Health Connect on Android 14+ with foreground permission;
  the emulator has no heart-rate samples.
- **Not yet verified: the RFCOMM link between a real watch and a real phone.** Emulators cannot
  bond a Wear OS AVD to a phone AVD over Bluetooth. Check pairing, a sync and copying a login on
  the Galaxy Watch and a phone before tagging a release that ships `phone/`.

## Known limits

- The watch has to be within Bluetooth range of the phone. When the watch uses Wi-Fi or LTE
  without the phone, the source **Phone app** shows an error and the watch keeps the last readings.
- Some phone makers stop foreground services to save battery. If syncs stop, exempt GlucoWatch
  from battery optimisation on the phone.
- The watch tries each bonded phone when pairing, and only the paired phone's address afterwards.
  A new phone needs a new pairing.
