# Export files

GlucoPhone exports from **More → Export data**. Choose **Sensor and pump**, **Sensor**, or **Pump**, independently of **CSV** or **PDF report**.

**Include imported activities** adds saved FIT workouts overlapping the selected dates.
Import them through **More → Import workout** first. That screen also offers a separate
workout-interval PDF using sensor and pump data. See [Workout imports](workouts.md).

The form has separate start and end calendar buttons and accepts 1–90 inclusive calendar days. Changing either date updates the day count; changing the count keeps the date chosen most recently and moves the other. Before choosing a calendar date, Days keeps the end date. The interval summary includes both selected dates. Future end dates and invalid day counts are refused. The most recently started device and its first day are stored in Connect (also supported by `GLUCOWATCH_LATEST_DEVICE` / `GLUCOWATCH_DEVICE_SINCE` and configuration imports).

For **Sensor and pump**, the default start is the later of the requested start and the latest-device start. An interval ending before that device began is refused. **Allow incomplete data before the latest device started** preserves the requested dates instead. Single-device exports also preserve them. Downloads are requested only after this date check. Missing uploads are a separate issue: the final preview gives days with sensor readings and days with pump events and asks before exporting missing days unless incomplete data was already allowed. Days containing events can still have gaps.

## Historical downloads

After date validation, GlucoPhone opens the latest selected device's portal first. Sensor-and-pump exports collect both sources in that order; single-source exports open just that source. The date/format/content selections are remembered privately for the next export. Each service stays marked complete after its CSV is accepted; reopening a completed pump file cannot replace the missing sensor download. Both sources must contain selected events before a combined export can finish. The selected PDF/CSV is generated automatically after the last source; incomplete coverage still requires confirmation when it was not already allowed. Progress is shown during generation. Saving succeeds before the checkpoint is cleared, so a failed or cancelled save keeps the collected data for retry.

- **Manual sign-in and dates:** enter country, username/password, captcha and dates on the provider's site yourself. Export does not fill or submit login forms, choose portal dates, poll login fields, or request a sharing code. CareLink Personal uses the patient's own account, separately from the live CarePartner connection.
- **Regional portals:** CareLink opens `carelink.minimed.com` for US accounts and `carelink.minimed.eu` otherwise. Clarity follows the configured Dexcom region: `clarity.dexcom.com` (US), `clarity.dexcom.eu` (International), or `clarity.dexcom.jp` (Japan).
- **Browser downloads:** a full browser opens directly, using a Custom Tab when supported. CareLink prefers installed Chrome (or Chromium on a test device) before falling back to the default browser. MiniMed lists Chrome/Safari for Personal and warns that full Personal functionality is unavailable on mobile OS ([requirements](https://www.minimed.com/en-us/products/carelink-personal-software)); changing the browser is not a guarantee of mobile portal compatibility. Download the English CSV data export. An unrelated full-browser tab is managed by that browser and cannot be closed by GlucoPhone.
- **Automatic folder import (Android 12+):** in Export sources, choose **Automatic download folder**, grant **Downloads/GlucoPhone** in device storage, and set the browser's download location to that same folder. Android does not permit a grant to the root Downloads folder. This setup is remembered. A temporary **Collecting export downloads** foreground notification keeps collection runnable while the browser owns the screen; ordinary cached apps can be frozen by Android. It stops after collection or cancellation and does not start at boot. New CSVs are checked twice per second; accepted files close the app-task browser tab and open the remaining source. GlucoPhone requires stable file metadata, matching Android media-index size/time, no pending/partial flag, a valid CSV and selected events in the requested dates. It does not scan files before the index matches: a read can itself trigger indexing. This avoids treating a briefly stalled, open transfer as finished. These checks cannot certify a provider's successful server response; check coverage before saving. Unsupported providers or files Android has not indexed require the handoff below.
- **Open/Share fallback:** **Open** or **Share** the downloaded CSV with **GlucoPhone**. Android grants access to that file, and the portal tab in the app's task closes. Alternatively, close the browser tab: the Downloads file picker opens directly. Select the CSV once; there is no second browser-choice step. Android 10–11 use this handoff.
- **Browser availability and embedded capture:** the Chromium WebView test shell is not a downloading browser. Install a full browser on an AOSP emulator. Without one, GlucoPhone uses the embedded portal, with Desktop site, Fit to screen and pinch zoom. Its origin-restricted script retains blobs from short-lived windows/iframes, captures original fetch/XHR responses, and preserves Clarity export form POSTs. It never reads login fields or replays authentication as an unrelated native GET. A failed capture or renderer automatically switches to a full browser when available. Downloads are limited to 20 MB and CSV headers are checked.
- **Visible files:** embedded captures and CSVs selected through the portal's picker are saved in **Downloads/GlucoPhone** before import. Browser originals remain in that browser's configured download location. The phone header's **Downloads** button and completion screen's **Open Downloads** open that specific folder. The completion screen also offers **Open PDF** or **Open CSV** with a read grant for a viewer. Files shared directly from a browser or read from the granted folder are imported privately without moving their original. Scoped storage requires an explicit folder, file selection or Open/Share grant; GlucoPhone does not request broad file access.
- **Next device:** the CSV's actual headers identify CareLink or Clarity, independently of which portal was open. Accepted files close the embedded portal or app-task Custom Tab and advance to the remaining source. Duplicate files do not reopen completed sources. Unreadable timestamps, an unselected source or an empty selected interval block advancement. Missing-day checks still run before final export.
- **Date order:** CareLink format detection examines every CSV date, including rows outside the requested interval. Dates after the 12th or an unambiguous selected interval distinguish day/month from month/day. The format is remembered privately per account. Only a new account's genuinely ambiguous file needs a one-time confirmation; year-first dates (`yyyy-MM-dd` and `yyyy/MM/dd`) and identical day/month values do not overwrite the learned format. Semicolon exports, decimal commas and CareLink's repeated device tables are supported.
- **Use phone cache:** explicitly uses already stored readings/events, supplemented by files imported during this export. The live cache keeps 14 days; Share/CarePartner recent requests provide roughly a day. A saved CarePartner token is not used to backfill the portal's history.

Pending historical CSVs stay in app-private storage, separate from live source caches. An account-bound checkpoint lasts up to 24 hours, so Android reclaiming the app while a browser is open preserves the completed source and requested interval. Account/region changes discard it; successful export or explicit cancellation clears it. Application backup is disabled. Temporary portal files are deleted after parsing or activity destruction. Downloads originals are never deleted by import. Final CSV/PDF output goes to **Downloads/GlucoPhone**, with the system save dialog as a fallback. WebView cookies stay in private WebView storage; external-browser sessions belong to that browser. GlucoPhone does not read or save manually entered passwords.

CSV glucose stays mg/dL. Sensor-only exports omit normalized pump events; pump-only exports omit sensor readings, calibrations and meter readings. Combined exports preserve both sensor series when provided, separately labelled. The file is an event table; it does not interpolate readings or invent matching timestamps.

Times in the CSV are corrected with the same whole-hour pump clock shift as the chart. `time_utc` is UTC. `time_local` is that instant in the phone's time zone at the moment of export. Join and sort on `time_millis` (Unix epoch milliseconds). Do not parse `time_local` as UTC.

The file is UTF-8 with a byte-order mark, so Excel opens the encoding correctly. Decimals use a dot. A comma, quote, or line break inside a cell is quoted by the usual CSV rules, and quotes inside a cell are doubled. A cell that would otherwise start with `=`, `+`, `-`, or `@` is prefixed with an apostrophe so a spreadsheet does not treat it as a formula. An empty cell means the field does not apply. A `0` is a real zero.

This is health data. A CareLink payload can include a pump serial number and the name on the account. Keep the file private. The PDF says the same. None of this is a medical device, and none of it is a Dexcom Clarity report: the arrangement is the familiar one, the numbers are computed here.

## One row is one event

Glucose, boluses, meals, and basal pulses do not share timestamps. The file is a long table, one event per row, not a grid with one column per signal. That is the form that does not invent an alignment.

`source` says who measured the row:

| `source` | Meaning |
|---|---|
| `carelink` | MiniMed / CareLink |
| `dexcom_share` | Dexcom Share |
| `dexcom_clarity` | Historical Clarity CSV download/import |
| `dexcom_notifications` | The G6 notification on this phone. Points that Share filled in after a gap are in this same series |

In the combined file both glucose series are present when the phone has them. They are not averaged and not spliced. The chart uses one glucose source; the file keeps both so a model can choose. Dexcom rows are only `sensor_glucose`. Pump rows are only `carelink`.

## Columns

The header is fixed. New columns, if any are added later, are added at the end.

| Column | Unit | Meaning |
|---|---|---|
| `time_utc` | | `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` |
| `time_local` | | Same instant with the phone's offset |
| `time_millis` | ms | Join key |
| `source` | | See the table above |
| `kind` | | See the next section |
| `glucose_mgdl` | mg/dL | Sensor glucose, only when CareLink or Dexcom sent a reading of at least 40 |
| `trend` | | Dexcom's trend name on a Dexcom row (`Flat`, `SingleUp`, `DoubleUp`, and the rest). A CareLink glucose row leaves this empty. A row rebuilt from the chart cache copies the stored trend: the newest reading has CareLink's arrow mapped onto those names, and older points are `None`. The raw `lastSGTrend` is in the device row's `detail` |
| `sensor_state` | | CareLink's `sensorState` when the payload had one, such as `WARM_UP` |
| `insulin_u` | U | A delivered amount. Never a rate |
| `insulin_kind` | | `BOLUS` or `BASAL` |
| `automatic` | | `true` when CareLink said `AUTOCORRECTION`. `false` when it sent another activation type. Empty when it sent none |
| `activation_type` | | CareLink's `activationType`, uppercased |
| `bolus_type` | | CareLink's `bolusType`, such as `FAST` or `DUAL` |
| `programmed_fast_u` | U | What the bolus wizard or the user programmed as a now-bolus |
| `delivered_fast_u` | U | What the pump delivered of that now-bolus |
| `programmed_extended_u` | U | Programmed square portion |
| `delivered_extended_u` | U | Delivered square portion |
| `carbs_g` | g | Meal marker |
| `basal_rate_u_per_h` | U/h | A rate setting. Not a dose |
| `basal_percent` | % | Temporary basal as a percent of the scheduled rate. 100 means unchanged |
| `duration_min` | min | How long that temporary basal was set for. 0 means it was cancelled |
| `percent_of_profile` | | `true` when `basal_percent` is the pump's own percent (100 = no change). Empty on other rows |
| `iob_u` | U | Active insulin as the pump computed it at `time_millis`. One sample per fetch, not a curve |
| `value` | `value_unit` | A number that is not one of the columns above: a calibration, a meter glucose, or the raw `sg` of a gap |
| `value_unit` | | Usually `mg/dL` |
| `marker_type` | | CareLink's marker type, or `SG`, `BASAL_RATE`, `ACTIVE_INSULIN`, `DEVICE` |
| `detail` | | JSON object of the fields that did not fit a column. This is where an unfamiliar marker keeps its payload |

Numbers are rounded to 4 decimal places. Insulin pulses of 0.025 U survive that.

## `kind`

| `kind` | What the row is | What to train on |
|---|---|---|
| `sensor_glucose` | One sensor point | `glucose_mgdl` when it is filled. When it is empty, `value` is the raw `sg` CareLink sent (0 is a gap, under 40 is not a glucose). Do not treat an empty glucose as 0 mg/dL |
| `bolus` | An insulin marker | `insulin_u` is delivered now-bolus plus delivered extended, the same total the chart shows. The programmed and delivered columns are the split. A row can exist with programmed units and no delivered units |
| `meal` | Carbs | `carbs_g` |
| `basal_delivery` | An `AUTO_BASAL_DELIVERY` pulse | `insulin_u` is the pulse, in units, already the amount delivered in that interval. Do not multiply it by hours |
| `temp_basal` | A temporary basal setting | `basal_percent` or `basal_rate_u_per_h`, plus `duration_min`. `insulin_u` is empty. Do not turn the rate into units by multiplying by the duration: the pump may have changed or cancelled it, and the delivered pulses are the `basal_delivery` rows |
| `reported_basal` | `basal.basalRate` at the upload | A single rate sample, U/h, not a schedule and not a delivered dose. The app does not reconstruct the basal program from it |
| `active_insulin` | Pump IOB | `iob_u` at that instant. The next sample may be many hours later. Forward-filling it across a long gap invents a curve the pump did not send |
| `calibration` | A calibration marker | `value` |
| `meter_bg` | A fingerstick marker | `value` |
| `auto_mode` | Auto-mode status | `detail` |
| `suspend` | A suspend marker, including low-glucose suspend | `duration_min` when CareLink sent one, otherwise `detail` |
| `alarm` | An alarm marker | `detail` |
| `device` | Pump and sensor snapshot at the upload time | `detail`: model, serial, reservoir units, sensor time left, conduit battery, trend name, and the account's first and last name when CareLink sent them. The time is the upload, not the moment each field changed |
| `limit` | A limit object from the payload | `detail` |
| `notification` | One item from notification history, at most 200 per fetch | Its own time when the item had one, otherwise the upload time |
| `algorithm` | `therapyAlgorithmState` | `detail` |
| `banner` | `pumpBannerState` | `detail` |
| `other` | A marker type this app does not classify | `marker_type` and `detail`. Nothing from `dataValues` is dropped on purpose |

`detail` is a flat JSON object. Nested objects use dotted keys. Arrays are stored as JSON text. Keys that contain `token` or `password` are left out. The object is cut at 2,000 characters.

## Building a model matrix

A 5-minute grid is a reasonable match to Dexcom and to Guardian points, but it has to be built from this file rather than read off it.

1. Pick one `source` for glucose. Sort by `time_millis`.
2. Keep `sensor_glucose` rows whose `glucose_mgdl` is filled. Leave gaps empty. Do not interpolate a `value` of 0 into a glucose, and do not fill a missing point with the previous glucose unless that is an explicit choice.
3. Sum `bolus.insulin_u` and `meal.carbs_g` into the bin that contains the event, or onto the next grid time. Do not also add `programmed_*` or you will count the bolus twice.
4. Sum `basal_delivery.insulin_u` into the bin. That sum is delivered basal for the bin. Do not also integrate `reported_basal` or `temp_basal` across the same minutes.
5. If a model wants the setting rather than the delivery, use `temp_basal` and `reported_basal` as their own channels, held only until `duration_min` runs out or another setting replaces them. A `reported_basal` row has no duration. It is not a warrant to paint that rate across the whole day.
6. `active_insulin` is sparse. Either leave it sparse or hold the last value for a few minutes. Holding it for hours is the model's assumption, not a measurement.
7. Dexcom and CareLink glucose in the combined file are two sensors. Give them two columns.

The PDF's daily insulin uses the same split: bolus units, plus `basal_delivery` pulses, divided by the number of requested calendar days, including days with no recorded events. Temporary basals and the reported rate are counted as settings (how many, and for how long) and are not added to the units.

## PDF

The report follows the reference's categories without claiming to be an official Dexcom report:

- Overview: mean, sample SD, GMI, CV, time in range, sensor coverage, recorded insulin/carbs and best day.
- Compact coverage: sensor, pump and both, each as days with any data out of the requested days. No separate device or Clarity alert-configuration section.
- Patterns: best-day chart and its descriptive statistics. Best day needs at least 24 readings and uses the share of recorded samples.
- Glucose by time of day: quarter-hour 15th–75th percentile bars with a black mean line, followed by separate red bolus and blue basal summaries when pump data is selected.
- Weekly overlays: every requested week; gaps longer than 15 minutes break traces.
- Compare: first versus second halves of the selected interval; no invented previous-period data.
- Daily and hourly statistics: ranges, counts, extrema, mean, sample SD, quartiles, IQR and CV. Empty buckets are shown as unavailable.
- Pump by day: every requested day, paginated; delivered basal pulses remain distinct from rates and temporary settings.
- Daily pages: each selected day, sensor chart, combined insulin panel, recorded totals and formatted event tables. Eighteen events fit in two columns; additional events continue on subsequent pages. CSV retains all event fields.
- Event categories: counts for all supplied categories, including unfamiliar markers.

Sensor-only PDFs omit pump sections; pump-only PDFs omit glucose sections. No category or setting is fabricated when a portal has not supplied it. Insulin averages use requested calendar days, including days with no recorded data. Zero recorded units does not confirm zero delivery. Sensor coverage uses the requested time span up to generation time, not only the first and last sample. All report dates use the requested range.

Time-in-range bins are non-overlapping: under 54, 54–69, 70–180, 181–250, above 250 mg/dL. GMI is `3.31 + 0.02392 × mean mg/dL`; CV is `100 × sample SD / mean`. Percentiles interpolate sorted observed values; missing timestamps stay missing. These statistics are descriptive and the app is not a medical device.

The approved layout and drawing rules are recorded in [PDF standard](pdf-standard.md). `ReportPdf` is the production renderer; `ReportStyle` holds the printable palette, spacing, rounded corners and opacity constants. `ReportPresentation` computes the chart distributions and inferred basal timeline separately from drawing.

For local rendering checks, the debug-only `ReportPreviewActivity` generates synthetic PDFs in private cache. By default it renders 90-day combined, sensor-only and pump-only reports; `--ez all_cases true` covers 14 days in each mode plus 1-day and 90-day combined reports. It has no network or account access and is excluded from release builds. A debug-only `ExportPortalPreviewActivity` supplies synthetic downloaded CSV blobs to verify capture, closure, sequencing and export without account access. Live Clarity/CareLink downloads still require manual account/portal testing.
