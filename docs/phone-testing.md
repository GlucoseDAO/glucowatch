# Test the phone app on Windows

From the repository root, build and open the phone app:

```powershell
uv run scripts/run_emulator.py phone
```

Leave the window open. After editing, rerun the same command to rebuild, reinstall, and reopen
the app. `--no-build` uses the existing APK. `--demo` selects demo data. Use `watch` instead of
`phone` to test the watch app and face on an existing watch AVD.

The Windows launcher uses software graphics with guest Vulkan and GuestAngle disabled,
two virtual CPU cores, a cold boot with no snapshots,
and the SDK's own adb. Emulator startup logs are in gitignored `data/output/emulator/`.
Use this launcher on the workstation that froze during hardware-rendered emulator launches.
Do not switch a failed launch back to automatic/host GPU rendering. Finish full release
compilation before running interactive UI checks: software rendering and a large R8 build
compete for CPU and can trigger emulator input timeouts.

## If the emulator freezes during a portal visit

On 2026-10-15 Android recorded a Firefox CareLink Custom Tab input-dispatch ANR
(`org.mozilla.firefox`, five seconds without processing a touch). adb still answered,
but screen capture and emulator shutdown then hung. The startup log contained missing
ColorBuffer errors. Windows had approximately 13 GiB free and Android reported approximately
1 GiB available; these observations do not establish the underlying cause.

The Windows launcher now also passes `-feature -Vulkan`. Software graphics alone still
advertised guest Vulkan on this SDK. Android documents disabling Vulkan as a workaround for
browser rendering problems in its [emulator troubleshooting guide](https://developer.android.com/studio/run/emulator-troubleshooting).
This is a precaution, not proof that Vulkan caused this specific ANR. A subsequent CareLink country-selector hang occurred with `-Vulkan` confirmed on the running process, so disabling Vulkan alone did not resolve it. CareLink now prefers installed Chrome/Chromium, but the official Chromium Android Desktop test build (revision 1719364, 158.0.8107.0) could not initialize its graphics process on this non-Vulkan AVD and was removed. The emulator uses Firefox with software WebRender instead. Test browsers are separate tools, never bundled into an APK or release tag. Keep software graphics,
two cores, disabled GuestAngle, no snapshots, and the local DNS override when restarting.

This test AVD also uses a persistent Android display override of **540 × 1200 at 210 dpi**,
half its physical 1080 × 2400 / 420 dpi in each dimension. The app retains the same dp layout
(approximately 411 × 914 dp) while rendering one quarter as many pixels. After restart, UI
inspection and screen capture responded again; this short recovery check does not establish
long-term browser stability. Apply it only to this phone emulator, not the watch screenshot AVDs:

```powershell
& $adb -s emulator-5554 shell wm size 540x1200
& $adb -s emulator-5554 shell wm density 210
```

To restore the AVD's physical display, use `shell wm size reset` and `shell wm density reset`.
These commands preserve app storage and Downloads and may recreate foreground activities.

### Firefox country-selector workaround on this AVD

Disabling **guest Vulkan in the emulator** did not prevent the Firefox country-selector
hang. Firefox 158.0 now has a separate, persistent **software WebRender** override in its
private Gecko profile's `user.js`:

```javascript
user_pref("gfx.webrender.software", true);
```

Stop Firefox before changing its private profile. On a debug/root-capable AOSP emulator,
the profile is under `/data/user/0/org.mozilla.firefox/files/mozilla/<profile>/`; preserve an
existing `user.js`, add only this setting, and retain the profile's app UID and SELinux context.
Do not edit the browser-managed `prefs.js`. Restart Firefox and confirm only this preference
in `prefs.js` is `true`; avoid dumping that file because it can contain private settings.
This workstation's `user.js` did not exist beforehand. Browser cookies/accounts and Downloads
were retained. adb root access was turned back off after configuring the test emulator.

With this override we opened CareLink's country list, filtered `Rom`, selected **România**,
and selected **English** from the language list while screen capture remained responsive.
The same search/selection was repeated successfully in the Custom Tab launched by GlucoPhone.
These controls can take a moment to focus before typing. This tests the public selector, not
an authenticated download or long-running stability. MiniMed still lists Chrome/Safari and
warns that full Personal functionality is unavailable on mobile OS; prefer a supported browser
on a physical device where available.

To undo this test override, set the same preference to `false` in `user.js` and restart Firefox;
after that restart, remove the added override (or the newly created file if it contains only
this line). Removing `user.js` alone does not reset a value already saved in `prefs.js`.
See Mozilla's [preference file documentation](https://gecko-docs.mozilla.org.s3.amazonaws.com/modules/libpref/index.html)
and [software WebRender issue/fix](https://bugzilla.mozilla.org/show_bug.cgi?id=1798714).

If adb responds, check `shell dumpsys activity lastanr` and `shell dumpsys window` before
restarting. Try stopping the named browser package first. If the display remains stuck, close
the emulator normally (`adb -s emulator-5554 emu kill`) and wait for it to exit before relaunching.
If shutdown itself hangs, terminate only the verified emulator process for this AVD, then
run `uv run scripts/run_emulator.py phone --no-build`. Do not wipe AVD data. A normal restart
preserves browser/app storage and Downloads; an active export resumes from its private checkpoint.
Keep diagnostics private: portal URLs, intents and app logs can contain account or health data.

## If portal names do not resolve

`net::ERR_NAME_NOT_RESOLVED` / WebView error `-2` happens before sign-in or CSV download.
If the PC resolves the site but the emulator cannot resolve it or other public sites,
the emulator's inherited DNS settings may be failing. After verifying an alternative
resolver works, add this emulator-only setting to the gitignored `local.properties`:

```properties
glucowatch.emulator.dns=1.1.1.1,8.8.8.8
```

This workstation uses that override. Close the emulator and run the launcher again to
apply it; reinstalling the APK or reloading the page cannot change emulator startup DNS.
The normal launcher preserves app settings and Downloads. An active export resumes from its private checkpoint after a restart, including any
source already imported (up to 24 hours). Remove the property to restore the SDK's automatic DNS,
or set `GLUCOWATCH_EMULATOR_DNS` to a different comma-separated list of up to four IPs.
The override affects emulator launches only; Windows and physical-phone DNS stay unchanged.

The [emulator DNS troubleshooting guide](emulator-dns.md) records the verified Windows case,
diagnostic commands, restart requirements, startup timing, and rollback for other systems.

## Token and date checks

1. Set either `HF_TOKEN` or `HUGGINGFACE_TOKEN` in the repository's private `.env` and rebuild.
   Both Model token fields should be filled and masked. They can be edited independently.
   Existing blank preferences must not hide newly supplied debug defaults. Release defaults
   remain empty; never share a debug APK containing your credentials.
2. In **More → Export data**, change Days to 7. The start date should move six days before
   the end before a date has been chosen, and the summary should say 7 days including both dates.
3. Open each date button: both must show a calendar. Choose a different start or end within
   the allowed interval and check that Days changes. Selecting the same date for both gives
   one day. Change Days again: it must keep the calendar endpoint chosen most recently and
   update the other. Future intervals show an error and cannot continue. Cancel keeps the
   previous saved export draft.

## CareLink CSV check

1. Install a full browser on an AOSP emulator (the WebView test shell cannot download).
   This workstation's test emulator uses Firefox with the software-rendering override documented above. Country selection was observed to hang without that browser override even with guest Vulkan disabled. Start an export including Pump.
   CareLink Personal opens directly in the browser for the configured account country.
   Sign in with the patient account, select dates and download the English CSV export.
2. For automatic import on Android 12+, return to **Export sources → Automatic download
   folder** and choose **Downloads/GlucoPhone** in device storage. In Firefox, use
   **Settings → Download Settings → Default download location** to choose the same folder.
   Both grants are retained. On this emulator these two grants are already configured.
   Download a new CSV during an active export and leave the browser tab open: the app should
   accept it and open the next service without selecting the file individually. Older files
   must not satisfy a new export. Root Downloads and cloud-provider folders are unsuitable.
   As a fallback, Open/Share the CSV with GlucoPhone, or close the browser tab and select the
   file once in the Downloads picker. Android 10–11 use this fallback. No extra browser-choice
   prompt or date-order prompt should appear for a file with dates after the 12th.
3. For **Sensor and pump**, pump import must open Clarity next (or the reverse when the
   sensor is the latest device). A duplicate pump file must not count as sensor. Final Export
   stays disabled until both sources have been collected, and combined output must contain
   both sensor and pump events. Force-stop/reopen between sources: only the missing source
   should reopen. Explicit cancellation must clear the checkpoint.
4. Test 30- and 90-day CareLink CSVs in both date formats, plus a short ambiguous CSV from
   the same account. Inspect all rows to infer the format; subsequent imports should reuse it.
   A new account with an entirely ambiguous first file may need one confirmation.
5. Check the header's **Downloads** button and the final **Open Downloads** action. Final CSV
   and PDF files, plus portal captures, live in **Downloads → GlucoPhone**. Browser originals
   follow the browser's configured download location. Import must leave originals intact.
   Keep real exports private.

The folder reader uses a narrow persisted SAF read grant, not broad storage access. On
Android 12+, `MediaStore.getMediaUri` can map a granted external-storage document to the
media index with the same access. Before reading, require indexed size/time to match the
stable folder entry and reject pending/partial files. Android's local provider scans writes
when the writer closes, while opening a document for read can itself trigger a scan; do not
read first to test readiness. See the [Android API](https://developer.android.com/reference/android/provider/MediaStore#getMediaUri(android.content.Context,android.net.Uri))
and [provider implementation](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/com/android/internal/content/FileSystemProvider.java).
This is a local file readiness check; it cannot certify the browser's server response. Keep
the final coverage check and explicit Open/Share fallback when indexing is unavailable.
Test a transfer paused at a valid CSV row for longer than five seconds, then complete it:
the incomplete file must not advance the wizard. Test both sources, duplicates, malformed
files, cancelled folder selection and restored progress after process restart.

On 2026-10-15, a synthetic 90-day combined export passed through Firefox 158 Custom Tabs on
the API 36 emulator: a valid partial pump CSV held open for 12 seconds was ignored, completing
the transfer imported pump and opened the sensor page, and the sensor download enabled final
Export without per-file selection. The test backed up/restored the existing private export
and removed its own fixtures. Authenticated provider downloads still need a manual retry.

If no full browser is installed, embedded capture remains available. Fit to screen should show
wide pages; uncheck to restore the viewport, or pinch to zoom. A failed capture switches to the
browser automatically when one is available. The site should close after a successful capture.

The debug-only `ExportPortalPreviewActivity` tests synthetic downloads without a login:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 shell am start -W -n io.github.antonkulaga.glucowatch.phone/.ExportPortalPreviewActivity --es portal CARELINK --es country RO --es region OUS --es start 2026-10-01 --es end 2026-10-02
```

Tap Download CSV and check for a file named `synthetic-carelink-history-*.csv` in
Downloads/GlucoPhone. The default fixture revokes its blob URL immediately. Add `--es mode popup`
to test a window that closes immediately after download, or `--es mode iframe` for a removed
iframe. For authenticated response tests, use `--es portal CLARITY` with `--es mode fetch`,
`--es mode xhr`, or `--es mode form`. These synthetic endpoints reject GET and accept the
original export POST, so an authentication-losing replay cannot pass. `--ez wide true` makes
the page 1200 pixels wide for Fit to screen checks. The fixture activity is absent from release
builds. It uses fake credentials/data and never signs into an account.

## Record a failing browser download

Run the local recorder, then reproduce the CSV download in the emulator:

```powershell
uv run scripts/diagnose_downloads.py record --seconds 120
```

The recorder saves logcat and once-per-second MediaStore size/pending-state samples under
`data/output/download-diagnostics/<run>/`. It does not read CSV contents, clear logs, change
browser settings, or mark an indexed/stable file as complete. `summary.json` contains
download lifecycle events, hashed filenames and network error codes; raw logs stay private
because they can contain portal URLs and account data. Error codes can belong to background
requests: correlate them with the export transaction before assigning a cause.
Debug phone builds also keep a bounded private `export-diagnostics.jsonl` journal and emit
`GlucoExport` events for folder candidates, parser rejections, import launches, accepted sources
and incomplete coverage. The recorder collects that journal and, on the AOSP emulator, Firefox's
download statuses. Neither summary retains filenames, URLs, CSV rows or exception messages.

For a Firefox transfer that fails without logging its exception, enable the emulator's
[native Gecko network logger](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/automation.html)
before recording:

```powershell
uv run scripts/diagnose_downloads.py firefox-logging on
# Open Firefox, sign in manually if needed, then run the recorder and generate a fresh export.
uv run scripts/diagnose_downloads.py record --seconds 120
uv run scripts/diagnose_downloads.py firefox-logging off
```

The logger uses only the AOSP emulator, stops Firefox when enabling/disabling, preserves its
cookies/data, and refuses to replace an existing Gecko configuration or another debug app.
The recorder copies its network logs through the emulator's `su`, without restarting adbd.
Native logs can contain credentials: keep the output local. The off command must be run even
after an interrupted recording; it removes only the configuration this tool installed. Run
`analyze <run-directory>` to rebuild the summary from saved logs. Analyzer regression checks:

```powershell
uv run python -m unittest discover -s scripts -p test_diagnose_downloads.py
```

## Automated checks

```powershell
.\gradlew.bat --console=plain --max-workers=2 :core:test :phone:assembleDebug
uv run python -m unittest discover -s scripts -p test_emulator_tools.py
node scripts/test_portal_csv_capture.cjs
```

Core tests cover inclusive dates, endpoint anchoring, token-default migration and aliases,
provider origin restrictions, both-source completion, whole-file date inference (including
90-day exports and late decisive rows), CSV parsing and export rules. The JavaScript checks (Node 22+)
cover revoked blobs, authenticated export responses/forms, and leaving login/background
requests untouched. These checks do not authenticate to Medtronic or Dexcom. A real-account download still needs the manual check above.

For a standalone phone release check on Windows, set `ANDROID_HOME` to the installed SDK and
run `..\gradlew.bat assembleRelease` inside `phone/`, as described in `AGENTS.md`. When switching
between repository-root and standalone module builds in the same checkout, stale asset-merger
outputs can report the same asset twice. Run `clean assembleRelease` from `phone/` in that case;
this removes generated build files, not simulator data. Rebuild the debug APK afterwards.
On this workstation the release check passed with `--no-daemon --max-workers=2` and
`"-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8"`. Keep interactive portal testing separate
from the R8 build.
