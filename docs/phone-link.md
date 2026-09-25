# The phone app and the Bluetooth link

Written on 2026-09-25, for the first version of `phone/` (after tag `v0.1.5`). The protocol is
in `core/src/main/kotlin/glucowatch/core/link/`, and its tests in `core/src/test/.../link/`.

The watch still works on its own. The phone app is optional and does two things:

- **Relay.** On the watch, the source **Phone app** reads the phone's readings, treatments, loop
  status and, if asked, a forecast from the phone's model. Then the watch needs no login and no
  internet. The phone fetches from Dexcom Share, Nightscout or demo data.
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

| Request | When | Answer |
|---|---|---|
| `PAIR` | The user taps **Pair with phone** on the watch while pairing is open on the phone | The phone's id, public key and name |
| `SYNC` | On every watch refresh with the source **Phone app** | The last 24 h: readings, treatments, loop status, the phone's source name and its last error, and a forecast if the watch asked for one |
| `ACCOUNT` | The user taps **Copy login from phone** | The phone's source and login |

The phone sends the whole day on every sync, which is a few kB. The watch merges the readings
into its cache while `LinkSnapshot.upstream` stays the same. `upstream` is a hash of the phone's
account key. When the phone switches account, `upstream` changes and the watch replaces its cache
instead of merging. The watch's own cache rule still applies: `Settings.accountKey` is
`phone:<phone id>`, so a new pairing with another phone starts empty.

A sync makes the phone fetch, unless its last fetch is under 30 s old. The phone schedules nothing
of its own. The watch waits up to 45 s for an answer, which is enough for a Dexcom or Nightscout
round trip.

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

## Forecast on the phone

The watch's forecast setting gets a choice **Phone app model** (`PhoneLink.MODEL_ID`) when its
source is **Phone app**. The phone then runs the model chosen in its own settings, from
`Predictors`. A new model goes into `core/`, implements `GlucosePredictor` and is registered on
`Predictors`, as before, so both apps get it. The rule against binary models holds on the phone
too. The watch shows the phone's forecast only when it starts from the latest reading. Otherwise
it shows "No fresh phone forecast". The choice **Loop (Nightscout)** also works through the phone,
because the phone relays the loop status.

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
