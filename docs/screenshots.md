# GlucoWatch screenshots

Checked on 2026-09-25 with the Wear OS 6 emulator image (API 36) and the code after tag `v0.1.4`.

## Phone companion screenshots

`uv run scripts/phone_screenshots.py` builds the phone debug APK and captures its dashboard on
a Galaxy S22-sized Android emulator. The default frame is **1080 × 2340 px**, matching the
[Galaxy S22 display resolution](https://www.samsung.com/au/business/smartphones/galaxy-s/galaxy-s22-for-business-sm-s901-sm-s901elbeats/).
The emulator uses density 425 as an approximation of the phone's pixel density; it remains a
Pixel 7 system image with Galaxy S22 display dimensions, not a Samsung firmware emulator.

It captures `demo-today.png`, `demo-today-bottom.png`, `demo-connect.png`, `demo-model.png`,
`demo-watch.png`, and, when configured, `dexcom-today.png` and `nightscout-today.png`. An
`overview.png` puts the available Today screenshots side by side. Output is in the gitignored
`data/output/screenshots/phone-galaxy-s22/` directory. Use `--no-build` to reuse the debug APK
or `--keep` to leave the emulator running.

The phone debug APK reads the same `.env` defaults as the watch debug APK. The screenshot script
passes only the source name over adb; it does not print or pass passwords through adb arguments.
Dexcom needs `DEXCOM_USERNAME` and `DEXCOM_PASSWORD`; Nightscout needs `NIGHTSCOUT_URL` and
optionally `NIGHTSCOUT_TOKEN`/`NIGHTSCOUT_API`. Captures using either real source contain private
health data. Keep them local and do not use them as store images.

The phone's heart-rate card reads optional Health Connect data on Android 14 and later. The
unmodified emulator has no samples, so its screenshots show the empty state.
The script uses adb port 5588, the same port as the optional 480 px watch capture; run those
captures separately.

## Watch screenshots

`uv run scripts/screenshots.py` builds both debug APKs, installs them on an emulator that matches a
Galaxy Watch, and saves round screenshots of the watch face, the three tiles and the app for each
data source. Every file is named after what it shows.

```bash
uv run scripts/screenshots.py                     # every scenario that is configured
uv run scripts/screenshots.py dexcom nightscout   # only these
uv run scripts/screenshots.py --no-build --keep   # reuse built APKs, leave the emulator running
uv run scripts/screenshots.py --unit mmol         # override GLUCOWATCH_UNIT for this run
```

A full run takes about 3 minutes: 1 to boot the emulator, 1 to build, and about 20 seconds per scenario.

## The simulated watches

GlucoWatch needs Wear OS 4 or later and has to fit every round Galaxy watch from the Watch6 on.
Those come in three screen sizes, and the script has one simulated watch for each (`--watch`):

| `--watch` | AVD | Screen | adb serial | Real devices |
|---|---|---|---|---|
| `watch6-classic-43` (default) | `glucowatch_gw6c` | round 432 × 432 px | `emulator-5584` | Watch6 40 mm, **Watch6 Classic 43 mm (SM-R950, the watch GlucoWatch is tried on)**, Watch7 40 mm |
| `watch8-classic` | `glucowatch_gw8c` | round 438 × 438 px | `emulator-5586` | Watch8 Classic, Watch8 40 mm |
| `watch6-classic-47` | `glucowatch_gw6c47` | round 480 × 480 px | `emulator-5588` | Watch6 44 mm, Watch6 Classic 47 mm, Watch7 44 mm, Watch8 44 mm, Watch Ultra (2024, 2025) |

```bash
uv run scripts/screenshots.py demo                                   # the default watch
uv run scripts/screenshots.py demo --watch all                       # all three sizes
uv run scripts/screenshots.py demo --watch watch8-classic watch6-classic-47
```

All three run Wear OS 6 (`system-images;android-36;android-wear-signed;x86_64`) at density 340.
340 is Samsung's value on the 432 px watch; the 438 and 480 px watches are assumed to use the same,
which a real one confirms with `adb shell wm density`. The script creates each AVD from
`wearos_large_round` if it is missing. Each has its own adb serial, so none collides with an
emulator on the default port or with the others, and `--keep` can leave all three up.

Store images and the face and tile previews come only from the default watch
(`scripts/store_images.py` reads `watch6-classic-43/raw/`). Checked on 2026-09-25 with demo data:
the face, the three tiles and the app fit all three sizes; on 480 px everything has more room.

The emulator returns a square framebuffer even for a round AVD. The script clips each capture to
the circle and adds a bezel, so what you see is what the watch shows. The unclipped captures are
kept in `raw/`. The face is drawn on a 450 px canvas that Wear OS scales to the screen. The app
and the tiles lay out in dp, and the tiles draw the chart at the screen's own pixel width and a
share of its height, so each watch gets a sharp chart of the same proportions. Older 396 and
450 px Galaxy watches (Watch4, Watch5) can run Wear OS 4 but are not simulated.

## Scenarios

| Scenario | Source | Needs in `.env` (or the environment) |
|---|---|---|
| `demo` | built-in demo data, with meals, boluses, IOB and COB | nothing |
| `dexcom` | Dexcom Share | `DEXCOM_USERNAME`, `DEXCOM_PASSWORD`, `DEXCOM_REGION` |
| `nightscout` | the live Nightscout | `NIGHTSCOUT_URL`, and `NIGHTSCOUT_TOKEN` / `NIGHTSCOUT_API` if needed |
| `nightscout-replay` | the same Nightscout, replayed so the loop looks fresh | `NIGHTSCOUT_URL` (API v1 read access) |

A scenario whose settings are missing is skipped with a message. The debug build compiles the
Dexcom login in from `.env`, so the script never passes the password over adb.

`nightscout-replay` exists because the watch hides IOB, COB and the loop's forecast once the loop
has not reported for 30 (forecast: 15) minutes. That is correct, but a quiet loop leaves those
elements off the screenshot. `scripts/nightscout_replay.py` fetches the last 30 hours from
`NIGHTSCOUT_URL`, shifts every timestamp so the last loop report is 90 seconds old, and serves it
on port 8537. The emulator reaches it at `http://10.0.2.2:8537`, which only debug builds allow
(`app/src/debug/res/xml/network_security_config.xml`). It can also run on its own for manual
testing: `uv run scripts/nightscout_replay.py <url> 8537 [token]`.

Each scenario sets the source with the debug-only adb extras of `SettingsActivity` (see README,
"From the PC over adb"). It then waits until the app's `fetchedAt` changes, captures the app
screen scrolled to the end, shows each tile, and captures the face in interactive and ambient mode.

## Output

`data/output/screenshots/<watch>/`, one folder per simulated watch, and `overview.png` beside them
with every watch of the run (the whole `data/output/` directory is gitignored). Each run deletes
these folders and PNGs first, so only the last run's captures remain:

| File | Content |
|---|---|
| `<scenario>-face.png` | the watch face |
| `<scenario>-face-ambient.png` | the face in ambient (always-on) mode |
| `<scenario>-tile-glucose-only.png` | the Glucose tile |
| `<scenario>-tile-glucose-all.png` | the Glucose, time and heart tile |
| `<scenario>-tile-glucose-light.png` | the Glucose (light) tile |
| `<scenario>-app.png` | the app's main screen, one round frame per scroll step |
| `overview.png` | one row per scenario: the face and the three tiles |
| `raw/` | the unclipped 432 × 432 captures |

Screenshots from `dexcom` and `nightscout` show real glucose data. Do not publish them without
asking the person the data belongs to.

## Requirements

- Android SDK with the emulator, platform-tools and the system image above
  (`sdkmanager "system-images;android-36;android-wear-signed;x86_64"`), found through
  `ANDROID_HOME` or `sdk.dir` in `local.properties`
- KVM (`/dev/kvm`)
- [uv](https://docs.astral.sh/uv/). `uv run` from the repo root installs Pillow from `pyproject.toml` into `.venv`
- The same JDK setup as any build (see `AGENTS.md`)

## Pitfalls found while building this

- `am start` of an activity that is already the root of its task only brings the task to the
  front and drops the new extras. The script passes `-S`, which restarts the app for each scenario.
- `adb shell` joins its arguments into one command line for the device shell. An empty value,
  such as an empty token, vanishes and shifts every argument after it. The script shell-quotes
  every argument and fails when `am` prints an error.
- A plugged-in battery puts a charging bolt over the bottom of the face. The script runs
  `dumpsys battery unplug`.
- The Wear runtime keeps a user's complication providers by the position of each
  `ComplicationSlot` in `watchface.xml`, not by `slotId`. Reordering the slots swapped the
  forecast and last-bolus providers on update. Keep the elements in `slotId` order. The script
  uninstalls the face before installing it, so each run starts from the default providers.
- A build started by the IDE while the script was building once left an empty `core.jar`, which
  Gradle then treated as up to date, and the app failed to compile against it. If the app suddenly
  cannot resolve any `glucowatch.core` class, run `./gradlew :core:jar --rerun`.
- Tiles use two different debug actions. `add-tile` goes to
  `com.google.android.wearable.app.DEBUG_SURFACE` (like `set-watchface`), but `show-tile` goes to
  `com.google.android.wearable.app.DEBUG_SYSUI`. Sent to `DEBUG_SURFACE`, `show-tile` answers
  "Unrecognized operation" and the capture shows the face instead.
- A child that reaches into its parent's padding with negative margins (the app's chart) is still
  cut at the padding unless the parent sets `clipToPadding = false`.
- The chart's glow used to be clipped by band, so a line above the target range painted the whole
  band in the in-range colour. It is now coloured by the line above each column; check a
  scenario that stays high (a real `dexcom` capture) after changing `ChartRenderer`.
- `add-tile` always inserts the new tile first and answers `Index=[0]`, so indices from several
  `add-tile` calls do not say where each tile ended up. The script captures one tile at a time:
  it removes all GlucoWatch tiles, adds the one it wants, and shows index 0.
- The emulator's heart rate comes from Health Services' synthetic data (`adb shell am broadcast
  -a whs.USE_SYNTHETIC_PROVIDERS com.google.android.wearable.healthservices`, then
  `whs.synthetic.user.START_WALKING`). The glucose-all tile shows it. The face's `[HEART_RATE]`
  stayed 0 on the emulator even with that data and the permission granted, so the face shows
  the date only there; check heart rate on the face on a real watch.
- Two runs at once share an emulator and an output folder: each run's cleanup deletes the other's
  captures, and both drive the same screen. Give a second run its own `--out` and a watch the
  first is not using.
