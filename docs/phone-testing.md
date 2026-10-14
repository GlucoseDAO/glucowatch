# Test the phone app on Windows

From the repository root, build and open the phone app:

```powershell
uv run scripts/run_emulator.py phone
```

Leave the window open. After editing, rerun the same command to rebuild, reinstall, and reopen
the app. `--no-build` uses the existing APK. `--demo` selects demo data. Use `watch` instead of
`phone` to test the watch app and face on an existing watch AVD.

The launcher uses software graphics, two virtual CPU cores, a cold boot with no snapshots,
and the SDK's own adb. Emulator startup logs are in gitignored `data/output/emulator/`.
Use this launcher on the workstation that froze during hardware-rendered emulator launches.
Do not switch a failed launch back to automatic/host GPU rendering. Finish full release
compilation before running interactive UI checks: software rendering and a large R8 build
compete for CPU and can trigger emulator input timeouts.

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
The normal launcher preserves app settings and Downloads. A restart closes the current
export; its dates remain saved. Remove the property to restore the SDK's automatic DNS,
or set `GLUCOWATCH_EMULATOR_DNS` to a different comma-separated list of up to four IPs.
The override affects emulator launches only; Windows and physical-phone DNS stay unchanged.

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

1. Start an export including Pump and open CareLink Personal. The portal uses the configured
   account country. Sign in using the patient account, choose the dates on the site, and
   select its CSV data export. Use English export headers. Try the Desktop site checkbox if
   the mobile layout hides Data export CSV.
2. When capture succeeds, the site closes, a toast names the CSV, and import continues.
   CareLink asks whether dates are day/month or month/day. The raw CSV is also visible in
   the device's Files app under **Downloads → GlucoPhone**. It is not necessary to search
   app-private storage or grant broad file access.
3. If the site refuses WebView, use **Open in browser**, download there, then return and
   choose **Select downloaded CSV**. A capture/network/save failure must show its reason.
   Browser downloads land in Downloads; only app captures are automatically copied into
   GlucoPhone. This AOSP emulator has only a WebView test shell, which cannot save browser
   downloads; Open in browser must explain that instead of launching it. Real CareLink
   downloads contain health data; do not publish them.

The same steps apply to Sensor / Dexcom Clarity, including its sign-in and CSV data export.
Fit to screen should show the width of a desktop page; uncheck to restore its viewport, or
pinch to zoom. Reload page recovers a stopped portal without losing the export interval.

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

## Automated checks

```powershell
.\gradlew.bat --console=plain --max-workers=2 :core:test :phone:assembleDebug
uv run python -m unittest discover -s scripts -p test_emulator_tools.py
node scripts/test_portal_csv_capture.cjs
```

Core tests cover inclusive dates, endpoint anchoring, token-default migration and aliases,
provider origin restrictions, CSV parsing and export rules. The JavaScript checks (Node 22+)
cover revoked blobs, authenticated export responses/forms, and leaving login/background
requests untouched. These checks do not authenticate to Medtronic or Dexcom. A real-account download still needs the manual check above.
