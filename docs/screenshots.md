# Screenshots on a simulated watch

Checked on 2026-09-25 with the Wear OS 6 emulator image (API 36) and the code after tag `v0.1.4`.

`scripts/screenshots.py` builds both debug APKs, installs them on an emulator that matches a
Galaxy Watch, and saves round screenshots of the watch face, the tile and the app for each data
source.

```bash
python3 scripts/screenshots.py                     # every scenario that is configured
python3 scripts/screenshots.py dexcom nightscout   # only these
python3 scripts/screenshots.py --no-build --keep   # reuse built APKs, leave the emulator running
python3 scripts/screenshots.py --unit mmol         # override GLUCOWATCH_UNIT for this run
```

A full run takes about 3 minutes: 1 to boot the emulator, 1 to build, and about 20 seconds per scenario.

## The simulated watch

| Property | Value | Real device |
|---|---|---|
| AVD | `glucowatch_gw6c`, created by the script from `wearos_large_round` | |
| Screen | round, 432 × 432 px | Galaxy Watch6 40 mm, Watch6 Classic 43 mm (SM-R950, the watch GlucoWatch is tried on) |
| Density | 340 dpi, so about 203 dp across | |
| System | Wear OS 6, `system-images;android-36;android-wear-signed;x86_64` | Galaxy watches run Wear OS 4 to 6 |
| adb serial | `emulator-5584`, so it never collides with an emulator on the default port | |

The emulator returns a square framebuffer even for a round AVD. The script clips each capture to
the circle and adds a bezel, so what you see is what the watch shows. The unclipped captures are
kept in `raw/`. Larger Galaxy watches (450 and 480 px) are not simulated. The face is drawn on a
450 px canvas and scales, and the app and the tile lay out in dp.

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
testing: `python3 scripts/nightscout_replay.py <url> 8537 [token]`.

Each scenario sets the source with the debug-only adb extras of `SettingsActivity` (see README,
"From the PC over adb"). It then waits until the app's `fetchedAt` changes, captures the app
screen scrolled to the end, shows the tile (added once after install), and captures the face in
interactive and ambient mode.

## Output

`data/output/screenshots/` (the whole `data/output/` directory is gitignored):

| File | Content |
|---|---|
| `<scenario>-face.png` | the watch face |
| `<scenario>-face-ambient.png` | the face in ambient (always-on) mode |
| `<scenario>-tile.png` | the GlucoWatch tile |
| `<scenario>-app.png` | the app's main screen, one round frame per scroll step |
| `overview.png` | all faces of the run side by side |
| `raw/` | the unclipped 432 × 432 captures |

Screenshots from `dexcom` and `nightscout` show real glucose data. Do not publish them without
asking the person the data belongs to.

## Requirements

- Android SDK with the emulator, platform-tools and the system image above
  (`sdkmanager "system-images;android-36;android-wear-signed;x86_64"`), found through
  `ANDROID_HOME` or `sdk.dir` in `local.properties`
- KVM (`/dev/kvm`)
- Python 3 with Pillow (`pip install pillow` or `apt install python3-pil`)
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
