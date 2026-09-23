# glucowatch

Shows your Dexcom glucose on a Wear OS watch (Galaxy Watch and others): current value with trend
arrow, a glucose chart, and an optional forecast from your own prediction model.

It talks to **Dexcom Share** directly from the watch: no phone app, no third-party server,
no Google Play Services (so it can go to F-Droid).

> Not a medical device. Do not make treatment decisions based on this app; keep using the
> official Dexcom app and its alarms.

## Install from F-Droid

F-Droid is a catalog of free Android apps. You do not need a Google account. GlucoWatch is two
packages, and both go on the watch:

- the app, [GlucoWatch](https://f-droid.org/packages/io.github.antonkulaga.glucowatch/)
- the watch face, [GlucoWatch face](https://f-droid.org/packages/io.github.antonkulaga.glucowatch.watchface/)

The watch has no F-Droid app of its own, and installing F-Droid on a phone does not install these
packages onto the watch. Download both APKs from those pages (open the latest version, then
**Download APK**), then send them to the watch.

On the watch:

1. Settings → About watch → Software information → tap **Software version** 5 times, until Developer options appear.
2. Settings → Developer options → turn on **ADB debugging** and **Wireless debugging**. The watch and the computer must be on the same Wi-Fi.
3. Open **Wireless debugging** and tap **Pair new device**. Leave that screen open. It shows an IP address, a pairing port, and a 6-digit code.
4. Go back to the main Wireless debugging screen. The port shown there is the connection port. It is a different number from the pairing port.

On a computer, install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) so you have the `adb` command. Then, with the APKs in the current directory:

```bash
adb pair <watch-ip>:<pair-port>          # type the 6-digit code
adb connect <watch-ip>:<connect-port>
adb install -r io.github.antonkulaga.glucowatch_<version>.apk
adb install -r io.github.antonkulaga.glucowatch.watchface_<version>.apk
```

On the watch, open **GlucoWatch** → **Settings** → **Dexcom Share**, enter your username, password, and region, and tap **Save & test**. Then long-press the current watch face, swipe to **GlucoWatch**, and tap it. If a slot is empty, long-press the face → **Customize** → tap the slot → **Glucose** or **Glucose chart**.

Turn **Wireless debugging** off when you are done. It uses extra battery.

A later version is installed the same way. `adb install -r` replaces the copy already on the watch.

From a phone, without a computer: download both APKs in the phone's browser, install **Wear Installer 2**, pair it with the watch from the Wireless debugging screen, and install each file with **Custom APK**.

## Modules

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin: Dexcom Share client, models, `GlucosePredictor` interface, demo data, desktop CLI. Unit-tested on the JVM. |
| `app/` | Wear OS app: fetches every 5 min, caches 24 h, provides 3 complications (value, chart, forecast), app screen + settings. |
| `watchface/` | Watch Face Format (XML, no code) face that shows the three complications. |

## Build

Requirements: Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`). The wrapper
runs on Java 17 through 25, including the JDKs on the F-Droid build server, and compiles with a
JDK 21 toolchain. Release builds (`assembleRelease`) leave the Dexcom fields empty, so they are
safe to publish.

```bash
./gradlew :core:test                 # unit tests
./gradlew :app:assembleDebug :watchface:assembleDebug
```

APKs: `app/build/outputs/apk/debug/app-debug.apk`, `watchface/build/outputs/apk/debug/watchface-debug.apk`.

## 0. Your settings in `.env` (optional)

Instead of typing your login on the watch, put it in a git-ignored `.env` in the project root:

```bash
cp .env.example .env     # then edit it
```

```ini
DEXCOM_USERNAME=you@example.com
DEXCOM_PASSWORD="your password"
DEXCOM_REGION=eu          # eu (= outside US), us or jp
GLUCOWATCH_UNIT=mmol      # mmol or mgdl
GLUCOWATCH_PREDICTION=false
```

- The desktop check (`:core:run`) uses it and stops asking.
- **Debug builds** compile the values in as initial settings, so a freshly installed debug APK is
  already logged in. Changed values are applied again on the next start after a rebuild and
  reinstall; edits made in the app's Settings are kept until `.env` changes.
- **Release builds never contain these values** (Gradle writes empty strings), so a release APK
  is safe to give to friends or publish. The debug APK does contain your password in plain text:
  keep it to yourself.
- Environment variables with the same names override the file. A typo in `DEXCOM_REGION`,
  `GLUCOWATCH_UNIT` or `GLUCOWATCH_PREDICTION` fails the build with a clear message.

## 1. Check your Share account from the PC (no watch needed)

Share must be **on** in the Dexcom app with at least one follower (you can invite yourself and
accept in the Dexcom Follow app). Then:

```bash
./gradlew -q --console=plain :core:run --args="--hours 1 --predict"
# uses .env; without it asks for username and password (not echoed).
# Override with --region eu|us|jp and --unit mmol|mgdl
```

Expected: `Login OK (Outside US (EU)), 12 readings in the last 1 h` and the last values.

## 2. Emulator

```bash
~/Android/Sdk/emulator/emulator -avd glucowatch_wear6 &
adb -e install -r app/build/outputs/apk/debug/app-debug.apk
adb -e install -r watchface/build/outputs/apk/debug/watchface-debug.apk
```

Long-press the watch face → swipe to **GlucoWatch** → tap it. The face starts with demo data, so
the chart shows up immediately. Tap the glucose value to open the app → **Settings**.

## 3. Connect real Share data

### With `.env` (easiest)

Fill in `.env` (section 0), rebuild and install the debug APK: the app starts in Share mode with
your account. Open it once and check the status line (`Share · EU`, value, or the error).

### In the emulator or on the watch, by hand

App → **Settings** → *Dexcom Share* → username, password, region *Outside US (EU)* → **Save & test**.
It shows `OK: 6.8 mmol/L, 2 min ago` or the error (wrong password, wrong region, no readings).
In the emulator you can type with the PC keyboard.

### From the PC over adb (debug builds only)

Typing an email on a watch keyboard is tedious; debug builds accept the settings from adb.
The password is read without echo and does not end up in your shell history:

```bash
read -r -p "Dexcom user: " DXU; read -r -s -p "Password: " DXP; echo
adb shell am start -n io.github.antonkulaga.glucowatch/.ui.SettingsActivity \
  --es source SHARE --es region eu --es unit mmol \
  --es username "'$DXU'" --es password "'$DXP'" --ez save true
unset DXP
```

(Use `adb -e` for the emulator, `adb -s <ip:port>` for a specific watch.) Release builds ignore these extras.

## 4. Install on a Galaxy Watch

1. Watch: *Settings → About watch → Software information* → tap **Software version** 5× → Developer options on.
2. *Settings → Developer options* → **ADB debugging** on, **Wireless debugging** on (watch and PC on the same Wi-Fi).
3. In *Wireless debugging* tap **Pair new device** and pair from the PC:
   ```bash
   adb pair <watch-ip>:<pair-port>      # enter the 6-digit code shown on the watch
   adb connect <watch-ip>:<port>        # the port shown on the main Wireless debugging screen
   adb devices                          # the watch should be listed
   ```
4. Install both APKs:
   ```bash
   adb -s <watch-ip>:<port> install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s <watch-ip>:<port> install -r watchface/build/outputs/apk/debug/watchface-debug.apk
   ```
5. Enter credentials (section 3), then long-press the face → pick **GlucoWatch**. If a slot shows
   nothing: long-press → *Customize* → tap the slot → pick GlucoWatch *Glucose* / *Glucose chart*.

You can also use the three complications on any other watch face that has matching slots.

Tip: turn Wireless debugging off again when you are done; it drains the battery.

## Forecast (optional, off by default)

Enable in Settings → *Show forecast*. It adds a dashed line with an uncertainty band to the chart
and fills the bottom slot of the face (`30m 7.9`).

To plug in your own model, implement `GlucosePredictor` in `core/` and register it:

```kotlin
class MyModel : GlucosePredictor {
    override val id = "my-model"
    override val displayName = "My model"
    override fun predict(history: List<GlucoseReading>, horizonMinutes: Int): Prediction? {
        // history: oldest first, up to 24 h, mg/dL. Return points every 5 min up to the horizon.
    }
}

object Predictors { val all = listOf(LinearTrendPredictor(), MyModel()) }
```

It then appears as a choice in Settings. `LinearTrendPredictor` is the reference example. You can
test models on the PC with `:core:run --args="--predict"` against your real Share data.

## How it works

- Dexcom Share login: `AuthenticatePublisherAccount` → accountId, `LoginPublisherAccountById` →
  sessionId, `ReadPublisherLatestGlucoseValues`. The session id is cached and renewed on expiry.
  Servers: `shareous1.dexcom.com` (outside US), `share2.dexcom.com` (US), `share.dexcom.jp`.
- Refresh: exact alarm ~20 s after the next expected reading, polling every minute if Dexcom is
  late; after a fetch all complications are asked to update.
- Credentials are stored only in the app's private storage on the watch and sent only to Dexcom.

## Publish on F-Droid

F-Droid builds the tag you push. It does not publish whatever happens to be on `main`. The request
goes to [fdroiddata](https://gitlab.com/fdroid/fdroiddata), as a merge request, following their
[quick start](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/) and
[inclusion policy](https://f-droid.org/docs/Inclusion_Policy/).

1. The license is Apache-2.0, in `LICENSE`. Use that same identifier in the metadata.
2. Tag the commit that matches `versionName`, for example `v0.1.0`, and push the tag. `versionCode` in both `app/build.gradle.kts` and `watchface/build.gradle.kts` has to go up for every later release, followed by a new tag.
3. Fork fdroiddata and add one metadata file per package, because these are two separate installs:
   - `metadata/io.github.antonkulaga.glucowatch.yml`
   - `metadata/io.github.antonkulaga.glucowatch.watchface.yml`
4. Both recipes use this git repository, `RepoType: git`, and `gradle: [yes]` from the repository root (no `subdir`). Point `output` at the unsigned release APK:
   - `app/build/outputs/apk/release/app-release-unsigned.apk`
   - `watchface/build/outputs/apk/release/watchface-release-unsigned.apk`
5. Set `UpdateCheckMode: Tags` and `AutoUpdateMode: Version`, so a later tag is picked up without a new request.
6. Declare the `NonFreeNet` anti-feature. Live readings come from Dexcom Share, which is a proprietary service. Demo data works with no account.
7. Open the merge request and answer the review. The store text is taken from `fastlane/metadata/android/en-US/` in this repository.

Check the release build locally before tagging:

```bash
./gradlew :app:assembleRelease :watchface:assembleRelease
```
