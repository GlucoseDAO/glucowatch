# Workout imports

Use **More → Workouts** on the phone. Pace and heart-rate zones are editable directly on
this page, one boundary per field; valid edits save automatically. Existing values are
preserved. Four HR boundaries define five zones (clear all four to omit HR zones), and
five min:sec/km pace boundaries define six zones.

**Generate report per activity** applies to **Import workout from Strava**. Chrome is
checked before opening Strava; if it is missing or disabled, the app offers its official
Play Store installation page. Authentication stays in Chrome. Choose an activity and
**Export Original**. GlucoPhone does not collect Google/Strava passwords or script the site.

**Generate report from downloaded FIT files** opens a multi-select Android file picker. Select up to 50 files; exact duplicates are reused. The app asks you to confirm or extend the covering calendar dates before opening either portal. The range must include every selected activity and remain within 90 days. One PDF contains day context once per date, followed by each selected activity. Invalid files are reported individually; you can continue with the valid imports. Open/Share FIT with GlucoPhone also
works. **Select download folder** grants access to a browser download folder or
**Download/GlucoPhone/Raw**, including its category subfolders. On Android 12+, completed
FITs are collected while the user-started Strava import is active. Original copies go to
**Download/GlucoPhone/Raw/Strava**. Collection requires stable document metadata and a
matching completed media-index entry, then checks the complete FIT length/checksum.
It stops after 24 hours, does not start at boot, and can be cancelled with the last
**Stop waiting for workout** button. Downloaded originals are retained.

## Archive and formats

The first version accepts one-session FIT activity files up to 32 MB and 300,000
distinct sample timestamps, with a workout span no longer than seven days. It rejects
truncated/corrupt files, multi-session files and files without a usable session interval. Summary-only sessions and pool-length-only swim files are accepted; missing observations are shown as unavailable.
GPX, TCX, externally compressed FIT, bulk ZIPs and multi-session/multisport files remain unsupported and produce a per-file error. FIT compressed timestamp headers are supported, including record definitions that contain the replaced timestamp field.

Import validates and decodes the original once to find the session interval. It merges
different native fields at the same timestamp; it does not fill gaps or mix in GPX
elevations. Stored fields are heart rate, speed, elevation, cumulative distance,
session duration/distance/ascent/descent, sport, and actual session timestamps. Heart-rate warm-up/recovery records outside that session are excluded from the analysis, counted in the report, and retained in the original FIT. The importer no longer rejects an otherwise valid swim because of those records. Native pool length, length durations, active/rest types and length speed are also read.
Unknown fields remain preserved in the original file, but are not interpreted.

Originals persist in private, unbacked `files/workouts/strava/`. A visible copy is
published through MediaStore in **Downloads/GlucoPhone/Raw/Strava**. Its name is assigned
before publication: `yyyy-MM-dd_HHmmss±HHmm_name_sha256-prefix.fit`, using the actual
workout start in the phone's time zone at import. Existing browser downloads are
never renamed or deleted. If Downloads publication fails, the private archive is
still available and the import screen says so. Exact SHA-256 duplicates are reused.
Other encodings of the same workout are not automatically deduplicated.

A small private index records ID, start/end instants, archive name, display name,
import time zone and optional published URI. Export queries this index for interval
overlap before decoding matching FITs. Renaming a visible copy cannot move an activity
to the wrong day. Private archive files are verified against their original digest
when loaded. Removing the public copy does not remove the private archive. Uninstall
removes the private archive; user-visible Downloads copies remain.

`FitWorkoutReader` is a bounded Kotlin adaptation of the definition/data framing in
the [MIT fitdecode reader](https://github.com/polyvertex/fitdecode), with a small
workout-only FIT profile. The attribution/license ships in
`core/src/main/resources/licenses/fitdecode.txt`. No Garmin SDK, downloaded parser
binary or Python runtime is added to the Android app. `fitdecode` is also useful as
an independent local inspection tool; it is not a project runtime dependency.

## Reports

**Generate report per activity** saves the workout, then starts the existing
sensor-and-pump historical download flow for its calendar dates. The PDF starts with
whole-day context, followed by the exact workout interval. Downloaded day data is retained
so the activity can use the preceding basal setting. Both sensor and pump data are required.
The phone cache remains
an explicit alternative, with the same missing-source checks. The account-bound
export checkpoint preserves the workout ID and activity choice across process death.

The whole-day page shows large mean glucose, sample SD, time in range, activity share
of the day, and aligned glucose/insulin charts. The activity page recalculates the
summary for its own interval, adds distance/time/ascent/descent/speed/pace, and shows
separate heart-rate and estimated-GAP charts with zone summaries on the right. The
same light uniform grey elevation shape appears behind heart rate, GAP and glucose.
The activity charts share a narrower time axis; insulin retains rounded hourly blocks,
25% scheduled and temporary layers, 50% overlap, suspension hatch and native bolus spikes.

`WorkoutAnalysis` implements the published Minetti (2002) running cost polynomial:
`C(g) = 155.4g^5 - 30.4g^4 - 43.3g^3 + 46.3g^2 + 19.5g + 3.6`.
Equivalent flat speed is `v * C(g) / C(0)`. Grade uses a centered 100 m distance
window, limited to the studied +/-45% range; speed uses 30 s cumulative-distance
changes because native FIT speed can report zero while distance advances. This is
an estimate, not Strava's model. Source: [Minetti et al.](https://journals.physiology.org/doi/10.1152/japplphysiol.01177.2001).
One-minute medians are printed, with slow/stopped GAP visibly capped at 30 min/km.
Stationary time remains in zones. Genuine gaps over 15 s break workout traces and
are excluded from zone time; gaps over 15 minutes break glucose traces.

`ReportBasalEstimate` integrates recorded rate settings over exact durations, applying
absolute/percent temporary settings and suspend/resume. Each day with rate data holds
the first unambiguous rate back to its start and the last rate to its end; conflicts
are noted. Missing days are not filled. The summary adds this estimate to recorded
bolus as **Calculated total**, distinct from independently measured delivery. If basal
doses are actually supplied they remain recorded doses, never doubled with the estimate.
Raw CSV events are unchanged.

Set four increasing **Heart-rate zones** boundaries in bpm to calculate five zones.
The reviewed editable HR defaults are 115, 143, 157 and 171 bpm. An explicitly blank
setting disables HR zones. **Pace zones** also accepts five editable min:sec boundaries;
the reviewed defaults are 6:45, 7:11, 7:40, 8:33 and 9:56 min/km.
These are the user's report settings, not inferred physiological thresholds or Strava results.

Ordinary **Export data → Include imported activities** defaults on for new export choices and applies independently of the
sensor/pump selection and CSV/PDF format. PDF daily charts mark the workout interval
on glucose, bolus and basal panels, add an HR/GAP graph on exercise days, and append enlarged
workout pages clipped to the selected report dates. General reports keep their weekly
overlays and statistics. The separate workout export and its reviewed layout remain available.
CSV keeps the existing header and adds `strava_fit` event rows: workout start/end and separate HR,
speed, elevation and distance observations in `value` / `value_unit`. Original sensor
and pump rows retain their own sources and timestamps. No saved workouts means zero
imported activities; it does not establish that no exercise happened.

## Verification

Run `:core:test`, then build the phone app. `WorkoutTest` covers byte orders, invalid
values, corruption/truncation, interval boundaries, naming and zone gaps. An optional
local smoke test reads `GLUCOWATCH_WORKOUT_FIXTURE`; no personal workout is committed.
`WorkoutAnalysisTest` covers the Minetti flat/uphill/downhill cost ratio, distance-based
speed recovery, stationary time, real gaps, and basal conflicts/temporary rates/suspension
including changes crossing midnight. A selected private FIT can be cross-checked against
the reviewed calculation without adding it to the repository.

The debug-only `ReportPreviewActivity --ez workouts true` produces
`cache/workout-synthetic.pdf` and `cache/workout-in-daily-synthetic.pdf`. Render and
inspect them with Poppler. `--ez inspect_fit true` explicitly imports the local private
`cache/workout-fixture.fit` twice and checks duplicate suppression and archive reload;
its summary is written to `cache/workout-inspection.txt`. It is excluded from release.
For private report QA, `--ez reviewed_fixture true --es zone Europe/Bucharest` reads
explicitly supplied `cache/sensor-fixture.csv`, `cache/pump-fixture.csv` and
`cache/workout-fixture.fit`, and writes `cache/reviewed-fixture.pdf`. Keep all of these
private and outside git. This entry point is debug-only and makes no network request.
Live Strava/Google login, access permissions, browser download completion, and automatic
collection of the downloaded sensor/pump/FIT files still need debugging on hardware.
The report implementation and local fixture checks do not establish that this access
and file-acquisition flow works end to end. Keep diagnostic outputs local.

## Activity types and target dates

Running, walking and hiking keep the existing estimated-GAP calculation when elevation
and distance support it; otherwise the report uses recorded pace. Swimming uses min/100m
from native pool lengths or recorded motion, never the running cost model or GAP zones.
Cycling and other activity types use recorded speed (km/h). Missing HR/GPS/elevation
channels and stationary/summary-only activities remain valid and visibly sparse.

Every managed Clarity/CareLink opening shows the exact inclusive target interval
on the portal screen. Dates stay pinned on the app's portal screen. External fallback browsing
does not initialize an unused WebView. The embedded fallback runs in its own process,
so a native WebView failure cannot take down the main dashboard.

For local regression checks, set `GLUCOWATCH_WORKOUT_FIXTURES` to an explicitly selected
private directory of FITs and run `:core:test`. The debug-only `ReportPreviewActivity
--ez activity_matrix true` reads `cache/workout-cases/*.fit`, renders selected-activity
and general reports plus synthetic missing-field cases, and writes private QA output
to cache. These files are never included in release APKs.

## Native originals, date bounds and page order

The importer keeps incoming names and bytes in `Raw/Strava/Unedited`, and publishes arranged FIT copies alongside that folder. Browser suffixes such as `(1)` and `(2)` are not duplicate indicators. Native session bounds are decoded for every distinct checksum, and arranged names include both bounds and sport. Equal bytes under another name reuse the activity but preserve the incoming alias; different bytes are retained even at the same time rather than risking a false duplicate decision.

Combined reports require completed dates, ending no later than yesterday. Activities imported today remain available for later export. The whole-day context is immediately followed by the detail pages for its activities, including clipped cross-midnight intervals. This ordering applies to both general and dedicated multi-activity exports. Daily insulin shares one red bolus / blue basal panel; summary distributions remain separate.

## Elevation and swimming context

Recorded altitude is shaded behind the time graphs, normalized within each activity; no terrain lookup or invented swim elevation is used. The saved `Pump disconnected during swimming` checkbox adds user-reported disconnection to reports/CSV, with FIT session bounds as timing approximation. Original FIT and pump rows stay unchanged. Dedicated activity reports are newest-first and place each activity immediately after its whole-day context.

The download folder tree is created on app/workout/export opening. Strava Google sign-in still belongs to Chrome; importing or sharing a downloaded FIT archives an exact copy into Raw/Strava/Unedited even when Chrome left its own original in Downloads.
