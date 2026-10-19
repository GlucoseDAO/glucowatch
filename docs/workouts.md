# Workout imports

Use **More → Import workout** on the phone. **Import workout from Strava** opens
the user's activity list in a browser Custom Tab, preferring Chrome. The browser
owns Google sign-in and its session. Choose an activity and **Export Original**.
GlucoPhone does not collect a Google/Strava password, script the site, or call the
Strava API. The browser controls the original download name and destination.

**Choose downloaded FIT** uses Android's file picker. **Open/Share** a downloaded
FIT with GlucoPhone also works. On Android 12+, the existing **Automatic download
folder** grant can collect new FIT downloads while a user-started import is active.
Choose Downloads/GlucoPhone in device storage and save the browser download to the
same folder; if the browser cannot do that, use the file picker. Collection requires
stable document metadata and a matching, completed MediaStore entry, then validates
the complete FIT length and checksum. It is limited to 24 hours and does not start
at boot. A foreground notification returns to the workout screen. Cancel waiting
stops collection without deleting downloaded files.

## Archive and formats

The first version accepts one-session FIT activity files up to 32 MB and 300,000
distinct sample timestamps, with a workout span no longer than seven days. It rejects
truncated/corrupt files, multi-session files and files without session/record data.
GPX, TCX, compressed FIT, bulk ZIPs and multisport activities are not implemented.

Import validates and decodes the original once to find the session interval. It merges
different native fields at the same timestamp; it does not fill gaps or mix in GPX
elevations. Stored fields are heart rate, speed, elevation, cumulative distance,
session duration/distance/ascent/descent, sport, and actual start/stop timestamps.
Unknown fields remain preserved in the original file, but are not interpreted.

Originals persist in private, unbacked `files/workouts/strava/`. A visible copy is
published through MediaStore in **Downloads/GlucoPhone/Strava**. Its name is assigned
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

**Import and generate event report** saves the workout, then starts the existing
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

Ordinary **Export data → Include imported activities** applies independently of the
sensor/pump selection and CSV/PDF format. PDF daily charts mark the workout interval
and append enlarged workout pages clipped to the selected report dates. CSV keeps the
existing header and adds `strava_fit` event rows: workout start/end and separate HR,
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
