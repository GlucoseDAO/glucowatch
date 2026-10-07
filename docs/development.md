# Developing GlucoWatch

For installation and everyday use, start with the [README](../README.md).
This guide covers building locally, running a virtual watch, and testing data sources.

## Run it on a computer

This is the setup for someone who cloned the repository and wants the watch face on screen.
The [installation guide](../README.md#install-and-test-from-your-phone) is for released apps
on your own devices. You do not need Android Studio to use a released APK.

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
[screenshots.md](screenshots.md). That script needs [uv](https://docs.astral.sh/uv/) and
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
They are not written into an APK. See [carelink.md](carelink.md).

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
[phone setup](../README.md#connect-your-phone-and-watch).

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

## Debug defaults in `.env`

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

## Check your source from the desktop

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

## Send settings over adb (debug builds only)


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

## Add a forecast model in source

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

For imported phone models and runtime compatibility, see [prediction.md](prediction.md).
For release builds and signing, see [store-publishing.md](store-publishing.md) and [AGENTS.md](../AGENTS.md).
