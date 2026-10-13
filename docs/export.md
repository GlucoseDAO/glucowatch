# Export files

GlucoPhone exports from **More → Export data**. Choose **Sensor and pump**, **Sensor**, or **Pump**, independently of **CSV** or **PDF report**.

The form accepts 1–90 inclusive calendar days anchored by a start date or an end date. It shows both resolved dates before continuing. Future end dates and invalid day counts are refused. The most recently started device and its first day are stored in Connect (also supported by `GLUCOWATCH_LATEST_DEVICE` / `GLUCOWATCH_DEVICE_SINCE` and configuration imports).

For **Sensor and pump**, the default start is the later of the requested start and the latest-device start. An interval ending before that device began is refused. **Allow incomplete data before the latest device started** preserves the requested dates instead. Single-device exports also preserve them. Downloads are requested only after this date check. Missing uploads are a separate issue: the final preview gives days with sensor readings and days with pump events and asks before exporting missing days unless incomplete data was already allowed. Days containing events can still have gaps.

## Historical downloads

After date validation, GlucoPhone opens the latest selected device's portal first. Sensor-and-pump exports collect both sources in that order; single-source exports open just that source. The accepted dates stay visible above the site, and the date/format/content selections are remembered privately for the next export.

- **Manual sign-in and dates:** enter country, username/password, captcha and dates on the provider's site yourself. Export does not autofill or submit forms, poll login fields, or request a sharing code. CareLink Personal uses the patient's own account, separately from the live CarePartner connection.
- **Regional portals:** CareLink opens `carelink.minimed.com` for US accounts and `carelink.minimed.eu` otherwise. Clarity follows the configured Dexcom region: `clarity.dexcom.com` (US), `clarity.dexcom.eu` (International), or `clarity.dexcom.jp` (Japan).
- **CSV capture:** select English on the portal and download its CSV data export. HTTPS attachments and browser-generated blob CSVs are collected into a unique private temporary file. Blob capture runs once, only for the download the user initiated, using a message port transferred to the exact top-frame origin; there is no general JavaScript bridge. Downloads are bounded to 20 MB. HTTPS cookies stay scoped to their original URL and are never forwarded through redirects.
- **Browser fallback:** if the portal does not support WebView, use **Open in browser**, download CSV there, return to GlucoPhone, and choose **Select downloaded CSV**. Android's file picker starts at Downloads. Scoped storage requires the user to select a file downloaded by another app; GlucoPhone does not scan arbitrary Downloads files or request broad storage permission.
- **Next device:** collection closes the embedded site. GlucoPhone validates the CSV's source/date format, keeps only the accepted interval, and opens the other selected source after a successful import. A wrong-source CSV, unreadable timestamps or an empty selected interval blocks advancement. CareLink asks whether dates are day/month or month/day. Missing-day checks still run before the final export.
- **Use phone cache:** explicitly uses already stored readings/events, supplemented by files imported during this export. The live cache keeps 14 days; Share/CarePartner recent requests provide roughly a day. A saved CarePartner token is not used to backfill the portal's history.

Historical rows remain in memory for the current export, separate from live source caches. Account/region changes discard pending results. Temporary files are deleted after parsing, cancellation, dismissal or activity destruction. Starting a new export also removes private portal CSVs abandoned by a killed process. Files selected from Downloads are read without deleting or modifying their originals. Final CSV/PDF output goes to Downloads or the system save dialog. Application backup is disabled. Portal session cookies remain in app-private WebView storage; GlucoPhone does not read or save the manually entered passwords. When using the external-browser fallback, that browser manages its own tab/session; GlucoPhone closes its embedded portal on collection.

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
- Devices and availability: source counts, time zone, latest-device boundary when enforced, supplied snapshots and explicit notes about unavailable settings.
- Patterns: best-day chart and descriptive below/above-range summaries by hour. Best day needs at least 24 readings and uses the share of recorded samples.
- Glucose by time of day / AGP: hourly median and 5th/25th/75th/95th percentiles, with recorded delivery when pump data is selected. Missing hours break bands/lines.
- Weekly overlays: every requested week; gaps longer than 15 minutes break traces.
- Compare: first versus second halves of the selected interval; no invented previous-period data.
- Daily and hourly statistics: ranges, counts, extrema, mean, sample SD, quartiles, IQR and CV. Empty buckets are shown as unavailable.
- Pump by day: every requested day, paginated; delivered basal pulses remain distinct from rates and temporary settings.
- Daily pages: each selected day, sensor chart, recorded totals and counts for every supplied event category. Up to 15 event summaries are printed; CSV retains the event fields.
- Event categories: counts for all supplied categories, including unfamiliar markers.

Sensor-only PDFs omit pump sections; pump-only PDFs omit glucose sections. No category or setting is fabricated when a portal has not supplied it. Insulin averages use requested calendar days, including days with no recorded data. Zero recorded units does not confirm zero delivery. Sensor coverage uses the requested time span up to generation time, not only the first and last sample. All report dates use the requested range.

Time-in-range bins are non-overlapping: under 54, 54–69, 70–180, 181–250, above 250 mg/dL. GMI is `3.31 + 0.02392 × mean mg/dL`; CV is `100 × sample SD / mean`. Percentiles interpolate sorted observed values; missing timestamps stay missing. These statistics are descriptive and the app is not a medical device.

For local rendering checks, a debug-only `ReportPreviewActivity` generates 90-day synthetic combined, sensor-only and pump-only PDFs in private cache. It has no network or account access and is excluded from release builds. A debug-only `ExportPortalPreviewActivity` supplies synthetic downloaded CSV blobs to verify capture, closure, sequencing and export without account access. Live Clarity/CareLink downloads still require manual account/portal testing.
