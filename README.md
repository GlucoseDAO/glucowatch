# glucowatch

Shows your glucose on a Wear OS watch (Galaxy Watch and others): current value with trend
arrow, a glucose chart from rim to rim, and an optional forecast, on a watch face, three tiles and
in the app, in GlucoseDAO colours. The face and one tile also show your heart rate from the
watch's own sensor, smaller than glucose. With Nightscout it also shows insulin and carbs
on board, boluses and carbs on the chart, and your loop's own forecast (AAPS, Trio, iAPS, Loop).

The watch talks to **Dexcom Share** or to **your Nightscout** directly: no phone app needed, no
third-party server, no Google Play Services (so it can go to F-Droid). An optional phone app can
fetch for the watch over Bluetooth, or hand the watch its login so you do not type it on the
watch. See [Phone app](#phone-app-optional).

> Not a medical device. Do not make treatment decisions based on this app; keep using the
> official Dexcom app and its alarms.

## Run it on a computer

This is the setup for someone who cloned the repository and wants the watch face on screen.
The [F-Droid install](#install-from-f-droid) below is for a watch you already wear. You do not
need Android Studio to use a released APK.

Three programs are involved, and they do different jobs:

- **Android Studio** installs the Android SDK and can start a virtual watch, which is an emulator: a watch in a window on the computer. You can keep editing the code in Cursor. Studio is only there for the SDK and the emulator.
- **JDK 21** is the Java compiler. Gradle uses it to build the app. Android Studio's own Java is newer than 21, and an old Java 8 on your PATH is not the compiler this project uses.
- **The system image** is the Wear OS version inside the emulator. Pick the one that matches the watch you are copying. The app itself runs on Wear OS 4 and later.

### 1. Install Android Studio

Download [Android Studio](https://developer.android.com/studio) and finish the first-run wizard.
That downloads the SDK. On Windows the SDK is usually
`%LOCALAPPDATA%\Android\Sdk`. On macOS and Linux it is usually `~/Android/Sdk` or
`~/Library/Android/sdk`.

### 2. Install JDK 21

Install [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21). On Windows:

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK -e
```

Open a new terminal after the installer finishes, so the new Java is visible. Gradle looks for a
JDK 21 on the machine. The wrapper that runs the build can itself be Java 17 through 25.

### 3. Create a virtual watch

On Android Studio's welcome screen: **More Actions → Virtual Device Manager → Create Virtual Device**.

Choose **Wear OS**, then **Wear OS Large Round**. That is the round profile used here for a
Galaxy Watch6 Classic 43 mm (SM-R950) and the other 432 px Galaxy watches. **Small Round** is a
384 px screen, smaller than that watch. **XL** is the 480 px size (Watch6 Classic 47 mm and the
Ultra).

Then choose the system image. The app's minimum is Wear OS 4, so a newer image still runs it.
Use whichever Wear OS image the wizard will actually download. A current Android Studio often
offers Wear OS 7.0 (API 37) and refuses older images. That is a fine emulator for this repo.

If you do get a choice and you want the image to match a watch, the rows are:

| On the real watch | Row in the wizard |
|---|---|
| Wear OS 6.0, system version 16 (Galaxy Watch6 Classic on the current stable software, for example `R950XXS2CZF5`) | API 36, Wear OS 6.0, Android 16 |
| Wear OS 6.1 | API 36.1 |
| Wear OS 7.0 | API 37 |

Download the image and press **Finish**.

Press the play button next to the device. The first boot takes a few minutes. Leave the window open.

The stock Large Round screen is 454 × 454 px at density 320. A real SM-R950 is 432 × 432 px at
density 340. Large Round is close enough to look at the face. For a capture at the real size,
`uv run scripts/screenshots.py` builds an emulator at 432 px. See
[docs/screenshots.md](docs/screenshots.md). That script needs [uv](https://docs.astral.sh/uv/) and
is optional if you only want the emulator window.

### 4. Point Gradle at the SDK

A terminal build needs the SDK path. Opening the project in Android Studio writes
`local.properties` for you. If you never open it there, create that file in the repository root.
It is gitignored.

Windows:

```
sdk.dir=C:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

macOS:

```
sdk.dir=/Users/you/Library/Android/sdk
```

Linux:

```
sdk.dir=/home/you/Android/Sdk
```

`ANDROID_HOME` set to the same folder works instead of the file.

### 5. Optional login in `.env`

```bash
cp .env.example .env
```

On Windows, copy the file in Explorer or with `copy .env.example .env`, then edit it.

A **debug** build of the watch app and the phone app compiles the Dexcom lines in, so a fresh
install starts logged in to Share. A **release** build leaves those fields empty. `.env` is
gitignored. The debug APK contains the password in plain text: keep it on your machine.

Leave `GLUCOWATCH_SOURCE` empty. With a Dexcom username and password filled in, the debug app
uses Dexcom Share. `DEXCOM_REGION` is `eu` (outside the US), `us`, or `jp`.

CareLink (Medtronic) username and password in `.env` are read by the desktop sign-in script only.
They are not written into an APK. See [docs/carelink.md](docs/carelink.md).

### 6. Build the watch app and the face

From the repository root. This compiles two packages. `app` is the program that fetches glucose
and draws the chart. `watchface` is the face you select on the watch; it has no Kotlin of its
own and shows the app's complications. Both have to be installed, or the face has nothing to show.

Windows, in PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :watchface:assembleDebug
```

macOS and Linux:

```bash
./gradlew :app:assembleDebug :watchface:assembleDebug
```

You get `app/build/outputs/apk/debug/app-debug.apk` and
`watchface/build/outputs/apk/debug/watchface-debug.apk`.

### 7. Install them on the virtual watch

With the emulator window still open:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r watchface\build\outputs\apk\debug\watchface-debug.apk
```

On macOS and Linux use the same commands with forward slashes. `adb` comes with the SDK, in
`platform-tools`. If the shell cannot find it, use the full path, or add `platform-tools` to PATH.

On the emulator, long-press the current face, swipe to **GlucoWatch face**, and tap it. Open
**GlucoWatch** once. With a Dexcom login in `.env`, the status line shows Share and a reading, or
Dexcom's error. With no login, the face still draws, using demo data.

The phone app is a third APK, `phone/build/outputs/apk/debug/phone-debug.apk`. It goes on a phone
or a phone emulator, not on this watch. The watch fetches Dexcom or Nightscout by itself. See
[Phone app](#phone-app-optional).

### Each time you want to see a change

Start the virtual watch yourself and leave its window open. In Android Studio that is
**Device Manager**, the play button on **Wear OS Large Round**. Wait until a watch face is on
the screen. A window that is still booting answers `offline` and will refuse the install.

Then, from the repository root in PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :watchface:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb install -r watchface\build\outputs\apk\debug\watchface-debug.apk
& $adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-watchface --es watchFaceId io.github.antonkulaga.glucowatch.watchface
& $adb shell am start -n io.github.antonkulaga.glucowatch/.ui.MainActivity
```

The two `install` lines put the new app and the new face on the watch that is already running.
`-r` replaces the copy already there. The broadcast selects **GlucoWatch face**. The last line
opens the app, which is the same screen as tapping the face. If `adb` is already on your PATH,
you can type `adb` instead of `& $adb`.

On macOS and Linux, from the repository root, with the emulator window open:

```bash
./gradlew :app:assembleDebug :watchface:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r watchface/build/outputs/apk/debug/watchface-debug.apk
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-watchface --es watchFaceId io.github.antonkulaga.glucowatch.watchface
adb shell am start -n io.github.antonkulaga.glucowatch/.ui.MainActivity
```

Run the whole block again after the next code change. You do not create a new virtual watch.

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

On the watch, open **GlucoWatch** → **Settings** and pick a data source: **Dexcom Share** (username, password, region) or **Nightscout** (your site's address and an access token, see [Nightscout](#nightscout)). Tap **Save & test**. Then long-press the current watch face, swipe to the end, tap **+ Add watch face** and pick **GlucoWatch face**. If a slot is empty, long-press the face → **Customize** → tap the slot → **Glucose** or **Glucose chart**. For a tile, swipe left from the face to the end of the tiles, tap **+ Add tiles** and pick one of the three: **Glucose** (glucose-only), **Glucose, time and heart** (clock, glucose, heart rate and battery), or **Glucose (light)** (the same glucose-first layout on a light background). The face asks for heart-rate access when you choose it; for heart rate on the tile, tap **Allow heart rate** in GlucoWatch → Settings.

Turn **Wireless debugging** off when you are done. It uses extra battery.

A later version is installed the same way. `adb install -r` replaces the copy already on the watch.

From a phone, without a computer: download both APKs in the phone's browser, install **Wear Installer 2**, pair it with the watch from the Wireless debugging screen, and install each file with **Custom APK**.

## Modules

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin: Dexcom Share and Nightscout clients, models, `GlucosePredictor` interface, demo data, desktop CLI. Unit-tested on the JVM. |
| `app/` | Wear OS app: aims to fetch about every 5 min, caches 24 h, provides 5 complications (value, chart, forecast, IOB/COB, last bolus and carbs), 3 tiles, app screen + settings. |
| `watchface/` | Watch Face Format (XML, no code) face that shows the five complications. |
| `phone/` | Optional phone app (GlucoPhone): a glucose-first dashboard with two weeks of draggable history, meal photos, insulin, heart rate and a forecast model you can import from Hugging Face or a file. It also relays glucose to the watch over Bluetooth, or gives the watch its login. |

The icon is GlucoseDAO's glucose molecule, and the charts draw in the same ball-and-stick style.
Everything that is not glucose is black, grey and white; glucose is a colour code, green in range,
then yellow, orange and red toward either extreme. The values live in one place,
`core/…/GlucosePalette.kt`, shared by the watch face, tiles, watch app and phone app. On the watch,
insulin stays orange, carbs green and the forecast purple, as on GlucoseDAO's posters.
The light tile uses a restrained off-white version of the glucose-first layout, with darker
green, amber and red state colours. `uv run scripts/make_icon.py` draws the launcher
vector and the store icon from one geometry. `uv run scripts/store_images.py` makes the store
screenshots and the face and tile previews from a `demo` run of the screenshot script.

## Build

First-time setup, including the emulator that matches a Watch6 Classic, is in
[Run it on a computer](#run-it-on-a-computer).

Requirements: Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`) and a JDK 21
compiler on the machine that runs Gradle. The wrapper itself runs on Java 17 through 25. Release
builds (`assembleRelease`) leave the Dexcom fields empty, so they are safe to publish.

```bash
./gradlew :core:test                 # unit tests
./gradlew :app:assembleDebug :watchface:assembleDebug :phone:assembleDebug
```

APKs: `app/build/outputs/apk/debug/app-debug.apk`, `watchface/build/outputs/apk/debug/watchface-debug.apk`,
`phone/build/outputs/apk/debug/phone-debug.apk` (this one goes on the phone).

## 0. Your settings in `.env` (optional)

Instead of typing your login on the watch, put it in a git-ignored `.env` in the project root:

```bash
cp .env.example .env     # then edit it
```

```ini
DEXCOM_USERNAME=you@example.com
DEXCOM_PASSWORD="your password"
DEXCOM_REGION=eu          # eu (= outside US), us or jp
NIGHTSCOUT_URL=https://your-site.example
NIGHTSCOUT_TOKEN=         # access token, empty for a public site
NIGHTSCOUT_API=v1         # v1 or v3 (v3 needs a token)
GLUCOWATCH_SOURCE=        # demo, share or nightscout; empty picks share if the Dexcom login is set
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
  `NIGHTSCOUT_API`, `GLUCOWATCH_SOURCE`, `GLUCOWATCH_UNIT` or `GLUCOWATCH_PREDICTION` fails the
  build with a clear message.

## 1. Check your Share account from the PC (no watch needed)

Share must be **on** in the Dexcom app with at least one follower (you can invite yourself and
accept in the Dexcom Follow app). Then:

```bash
./gradlew -q --console=plain :core:run --args="--hours 1 --predict"
# uses .env; without it asks for username and password (not echoed).
# Override with --region eu|us|jp and --unit mmol|mgdl
```

Expected: `Login OK (Outside US (EU)), 12 readings in the last 1 h` and the last values.

For Nightscout, the same check also lists boluses, carbs and the loop's status:

```bash
./gradlew -q --console=plain :core:run --args="--source nightscout --url https://your-site.example --hours 3 --predict"
# --token <access token>, --api v1|v3; or NIGHTSCOUT_URL / NIGHTSCOUT_TOKEN / NIGHTSCOUT_API in .env
```

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

Nightscout works the same way:

```bash
adb shell am start -n io.github.antonkulaga.glucowatch/.ui.SettingsActivity \
  --es source NIGHTSCOUT --es nightscoutUrl https://your-site.example --es nightscoutApi v1 \
  --es nightscoutToken "'$NS_TOKEN'" --ez prediction true --es predictor loop --ez save true
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

## Phone app (optional)

Install `phone/` on the phone that the watch is paired with. It needs Bluetooth and no Google
Play Services. The watch keeps working without it.

For faster setup, the phone's **Connect** tab imports a filled `.env` or YAML file, or its
HTTPS URL. A supplied Hugging Face model is downloaded, checked and selected automatically.
See [configuration import](docs/configuration-import.md) and [the YAML example](config.example.yaml).

1. On the phone, open **GlucoWatch**, pick a source (Demo data, Dexcom Share or Nightscout), enter
   the login and tap **Save & test**. Allow **Nearby devices** when asked.
2. Tap **Pair a watch**. On the watch, open GlucoWatch → Settings → **Pair with phone**.
3. Both screens show the same six-digit code. Tap **Codes match** on both.
4. On the watch, either pick the source **Phone app** (the phone fetches, the watch needs no
   login and no internet), or keep **Dexcom Share** / **Nightscout** and tap **Copy login from
   phone**. Then tap **Save & test**.

With the source **Phone app**, the watch's forecast can also be **Phone app model**: the phone
runs the model picked in its own settings. How the link works, and why it is not the Wearable
Data Layer, is in [docs/phone-link.md](docs/phone-link.md). The link has passed its unit tests but
has not yet been tried between a real watch and phone.

## Nightscout

Pick **Nightscout** in Settings and enter your site's address (`https://` is added if you leave
it out). A public site needs nothing else. For a private one, create an access token in Nightscout
under *Admin tools → Subjects* with the `readable` role and enter it. The API secret works with
API v1 too, but it grants full write access, so a token is the better choice.

**API v1 (classic)** works with every Nightscout. **API v3** is the newer API of Nightscout 14 and
later and always needs a token. Pick v1 unless you have a reason not to.

With Nightscout the watch also shows:

- **Insulin and carbs on board** from your loop (AAPS, Trio, iAPS, OpenAPS, Loop), as a
  complication and in the app. They disappear when the loop has not reported for 30 minutes, and
  the app then says how long it has been quiet.
- **Boluses and carbs** on the chart (automatic boluses as small ticks), and the last bolus and
  carbs with how long ago, as a complication.
- **The loop's forecast**: choose *Loop (Nightscout)* under Forecast. It is shown only while it is
  less than 15 minutes old.

Details, including how each uploader writes its data: [docs/nightscout.md](docs/nightscout.md).

## When the network will not reach Dexcom

Some mobile networks stop answering for `shareous1.dexcom.com` while Wi-Fi keeps working. Settings →
**Connection check** walks the connection one layer at a time — resolve, connect, handshake,
request — and names the one that failed, next to what the network looks like (whether the link has
IPv4 at all, which resolvers it uses, whether Private DNS is on, whether it uses NAT64) and the
failures the last fetches ran into.

If the name is what fails, Settings → *If DNS fails* → **Resolve over HTTPS** asks Cloudflare or
Google over DoH instead and connects straight to the address, with Dexcom's certificate still
checked in full. It is off by default, because turning it on tells that resolver this device looks
up Dexcom, and it cannot help when a network blocks the address itself rather than the name.

What each failure means, what to try from a shell, and what no app code can fix:
[docs/carrier-blocking.md](docs/carrier-blocking.md).

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
test models on the PC with `:core:run --args="--predict"` against your real Share or Nightscout data.
With Nightscout there is one more choice, *Loop (Nightscout)*, which shows your loop's own forecast
instead of running a model on the watch.

The phone's Model tab also saves a Hugging Face address and optional download token. The
[prediction guide](docs/prediction.md) describes the source interpreter's supported ONNX models
and the local GlucoseDao inference comparison, including the separate Dexcom Share experiment.

## Screenshots

`uv run scripts/screenshots.py` builds the debug APKs, boots an emulator shaped like a Galaxy
Watch6 Classic 43 mm (round, 432 px, density 340) and saves round screenshots of the face, the
three tiles and the app for demo data, Dexcom Share and Nightscout into `data/output/screenshots/`.
`--watch all` adds the other round Galaxy sizes since the Watch6 (438 px Watch8 Classic, 480 px
Watch6 Classic 47 mm and Ultra). GlucoWatch needs Wear OS 4 or later. See
[docs/screenshots.md](docs/screenshots.md).

## How it works

- Dexcom Share login: `AuthenticatePublisherAccount` → accountId, `LoginPublisherAccountById` →
  sessionId, `ReadPublisherLatestGlucoseValues`. The session id is cached and renewed on expiry.
  Servers: `shareous1.dexcom.com` (outside US), `share2.dexcom.com` (US), `share.dexcom.jp`.
- Nightscout: `entries`, `treatments` and `devicestatus`, read-only, through API v1 or v3. Only
  new readings are fetched after the first run. See [docs/nightscout.md](docs/nightscout.md).
- Refresh: alarm ~20 s after the next expected reading, polling sooner if the reading is late;
  Android may delay alarms while the watch is idle. After a fetch all complications and tiles are
  asked to update. The face marks the value **OLD DATA** when the newest reading is more than
  10 minutes old, even when a fetch succeeded but returned no newer value. A separate alarm also
  requests a face update at that threshold. Settings offers an old-reading notification: Off,
  Vibrate (default), or Sound and vibrate. Android notification permission is needed for the
  vibration or sound, and idle mode can delay the notification.
- Share fallback: after a failed request or once the newest reading is 7 minutes old, the watch
  makes a second request. It uses another connected Wi-Fi or cellular network if Wear OS exposes
  one. You can enter an HTTP CONNECT proxy (`host:port`) in the watch's Share settings as another
  route and test its tunnel without sending your login. There is no public proxy preset; the
  field is empty by default. If no other route is available, it retries through the watch's
  default connection. It tries at most once every 2 minutes.
  This runs on the watch without the optional phone app. It cannot create a second network when
  the watch has only one, or recover readings Dexcom has not uploaded. Use only a proxy you trust;
  the Dexcom request stays inside HTTPS, while the proxy can see the destination and timing. An
  HTTP CONNECT proxy can help with DNS or destination-IP trouble, but it does not hide the
  Dexcom TLS server name from a carrier inspecting traffic to the proxy.
- Heart rate is not fetched: the face reads it through Watch Face Format (`[HEART_RATE]`), and
  the glucose-all tile through the tile renderer (`PlatformHealthSources`). Both come from the
  watch's own sensor, with the heart-rate permission the watch asks for. No library is added.
- Credentials are stored only in the app's private storage on the watch (and on the phone, if you
  use the phone app) and sent only to Dexcom, to your Nightscout, or to your paired watch, with
  every message encrypted with the key from pairing ([docs/phone-link.md](docs/phone-link.md)).

## Publish on F-Droid

F-Droid builds the tag you push. It does not publish whatever happens to be on `main`. The request
goes to [fdroiddata](https://gitlab.com/fdroid/fdroiddata), as a merge request, following their
[quick start](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/) and
[inclusion policy](https://f-droid.org/docs/Inclusion_Policy/).

1. The license is Apache-2.0, in `LICENSE`. Use that same identifier in the metadata.
2. Tag the commit that matches `versionName`, for example `v0.1.0`, and push the tag. `versionCode` in both `app/build.gradle.kts` and `watchface/build.gradle.kts` has to go up for every later release, followed by a new tag.
3. Fork fdroiddata and open one merge request per package. The recipes are `metadata/io.github.antonkulaga.glucowatch.yml` and `metadata/io.github.antonkulaga.glucowatch.watchface.yml`.
4. Each recipe uses this git repository, `RepoType: git`, and `gradle: [yes]`. Set `subdir` to the module (`app` or `watchface`), so F-Droid runs Gradle there and finds the APK in its `build/` directory. Leave out `output` and `prebuild`. The `commit` field is the full hash of the tag, not the tag name. Publish a signed APK in the GitHub release for that version and set `Binaries` plus `AllowedAPKSigningKeys` so the build is reproducible.
5. Set `UpdateCheckMode: Tags` and `AutoUpdateMode: Version`, so a later tag is picked up without a new request.
6. Declare the `NonFreeNet` anti-feature. Live readings can come from Dexcom Share, which is a proprietary service. Nightscout is free software, and demo data works with no account.
7. Open the merge request and answer the review. The store text is taken from `fastlane/metadata/android/en-US/` in this repository.

Check the release build locally before tagging. F-Droid runs Gradle inside each module, so do
the same. The APKs you sign for the GitHub release must come from these builds:

```bash
(cd app && ../gradlew assembleRelease)
(cd watchface && ../gradlew assembleRelease)
```

## Other stores

Google Play is the only store that installs straight onto the watch. The app does not pass Play's
Wear OS review yet, because it asks for the Dexcom password on the watch. IzzyOnDroid and
Obtainium use the APKs from the GitHub release. Galaxy Store takes watch apps only in China.
[docs/store-publishing.md](docs/store-publishing.md) covers what each store needs, and what can go
into a tagged commit without breaking the F-Droid build.
