# PDF report standard

The approved report follows the supplied Clarity reference's clean white layout,
typography hierarchy and glucose colors, with the insulin additions described here.
Use this standard for the app's PDF exports. Do not include a Dexcom logo.

The renderer is `phone/.../ReportPdf.kt`; printable constants are in
`phone/.../ReportStyle.kt`; chart calculations are in `core/.../ReportPresentation.kt`.
The PDF palette is independent of the watch interface palette.

## Reviewed one-day and activity reports

The user-reviewed layout supersedes the overview/daily combination below for combined
sensor-and-pump one-day reports and reports including activities. `ReviewedReportPdf`
renders whole-day pages first and activity details afterwards. Keep the large mean,
sample SD and vertical time-in-range bar. Omit GMI, coverage counts and best-day patterns;
show activity time as a percentage of the day. Retain the two aligned daily charts and
a visible activity-detail page reference.

Activity details repeat summaries for the exact interval, add workout metrics, and use
separate HR and estimated-GAP profiles over identical uniform light-grey elevation.
Glucose has that elevation backdrop too. All activity plots share the narrower width
with zone notes on the right. Preserve genuine observation gaps; slow/stopped motion
must stay visible rather than disappear at the pace cap.

This layout explicitly permits a **day-specific calculated basal estimate** from native
rate observations, without claiming a recurring schedule. `ReportBasalEstimate` holds
rates between changes, backfills the first unambiguous rate to the start of a day with
rate data, and carries the final rate to that day's end. Conflicts and these assumptions
are printed. Apply temporary rates and suspension over exact intervals. Add this amount
to recorded bolus only under **Calculated total**; never call it measured delivery or
write it into normalized CSV events. Missing days remain missing. Multi-day statistical
profiles below retain their stricter recurring-schedule evidence rules.

## Layout and colors

- US Letter, 612 × 792 points, 22-point side margins, white background.
- Black text, grey rules, clear headings, aligned numeric values and shaded table headers.
- Overview: large average glucose, vertical time-in-range column and compact Sensor / Pump /
  Both coverage counts. Each count means days with any data out of requested days, not continuous coverage.
- Glucose: green `#58A618`, yellow `#FED65C`, orange `#E36A00`, low `#FD8C80`,
  very low `#A60006`. Threshold rules: orange `#FFA43C` at 180 and red `#FF514B` at 70 mg/dL.
- Bolus red `#D52B35`; basal blue `#168BD3`. All legends state units and meanings.
- Repeated footers give time zone, generation time, page count and the distinction between
  reconstructed programmed basal and measured delivery.
- Omit detailed device/availability and Clarity alert-configuration sections.

## Summary charts

Glucose uses 96 quarter-hour bins: rounded grey 15th–75th percentile bars, colored portions
above/below target, and a black mean line. Missing bins break the line.

The overview's insulin summary uses spaced, rounded min–max rectangles for hourly totals across observed days,
with black median strokes and numbered horizontal gridlines. Red is delivered bolus units.
Blue is programmed basal units if a recurring historical schedule can be inferred, otherwise
recorded basal delivery if supplied. The pale blue scheduled background is the integrated
scheduled amount for that hour, including schedule changes inside the hour.

Only complete hours within the pump's first/last recorded boundaries on days with pump data
enter recorded-dose distributions. Within such hours no recorded dose contributes zero; missing days
and partial hours are excluded. Presence of pump events does not prove complete delivery capture.

## Overlay and daily panels

General PDF exports keep their overview, weekly overlays, daily pages and statistics even
when workouts are included. Both weekly overlays and combined daily pages have three aligned
graphs: glucose, red bolus and blue basal. Weekly insulin panels use the exact dates named on
the glucose overlay, with rounded hourly min-max ranges, black medians and matching individual
day traces. Recorded basal doses take precedence over estimates; the two are not added together.

Daily bolus panels show pale rounded hourly totals and delivered spikes at their original
timestamps. Daily basal panels use the recorded/held rate-time estimate described in
[workout reports](workouts.md), with exact-interval calculations and an explicit settings label.

- Draw scheduled basal as rounded hourly blue blocks with visible gaps. Split blocks when
  the underlying schedule changes within an hour.
- Scheduled fill is 25% opacity. Temporary fill is another 25%; normalize their intersection
  to 50% opacity so the active overlap is distinct. Increased and reduced settings both use
  this same rule. Suspension is hatched.
- Temporary setting and schedule boundaries snap to the nearest five minutes for drawing.
  Calculations and original/exported timestamps retain exact precision.
- Keep bolus U and basal U/h on their separate aligned panels (basal is U when doses are supplied).
- Replace recorded-event tables and continuation pages with open food circles on the daily
  glucose graph, labelled with grams and source. Read food logs from both pump and sensor
  exports. Identical timestamps and amounts merge their P/S provenance without double counting;
  distinct logs stay distinct. The CSV still retains all native events.
- Shade imported exercise intervals uniformly grey on all three graphs. Only overlapping
  dates are marked, including a correctly clipped interval at midnight. Exercise days add
  an HR/estimated-GAP graph with grey elevation; enlarged activity details retain HR/GAP zones.
- Preserve the separate exercise-export route and its reviewed layout. Including exercises
  in a general report does not replace that report with the dedicated exercise export.

## Basal evidence and unavailable data

For the recurring overview profile, infer a schedule only from native historical rate snapshots repeated across
multiple days, including a midnight setting. Reject tied rate votes. Label the result as
inferred. A sparse live upload or a single day does not establish a recurring full-day schedule.

Percent temporary settings multiply the scheduled rate as it changes; absolute temporary
settings replace it within their recorded duration. A later setting supersedes an earlier
one. Preserve native suspend/resume states even when they share a rate row. Programmed basal
amounts integrate these settings over exact intervals and remain separate from delivered doses.
Never add them to delivered insulin totals or normalized CSV measurements.

Without sufficient evidence, the recurring schedule remains unavailable. Daily and weekly
rate-time estimates instead use each day's explicit recorded/held settings as documented above;
they are labelled as calculated settings, not independently measured delivery. A day without
rate data is not filled from a different day. Missing dose capture must not become zero delivery.

## Verification

Run `:core:test` and build the phone app after changing this renderer. `ReportPresentationTest`
covers repeated schedules, percent/absolute changes, suspension, five-minute display snapping,
within-hour schedule integration, missing data and observed zero hours.

The debug-only `ReportPreviewActivity` renders synthetic data using the production Android
PDF renderer. Launch with `--ez all_cases true` to cover sensor-only, pump-only and combined
14-day reports, plus 1-day and 90-day combined reports. The PDFs are written to app-private
cache as `standard-<days>-<content>.pdf`. Render the PDFs to images and inspect the overview,
hourly charts, daily layers, continuation tables and final pages before accepting layout changes.

## Corrected cumulative bolus and personal baseline

The extended-bolus ramp rises from the immediately delivered component to the cumulative total by the end of the recorded interval. It is a linear interpolation of delivery over that interval, not an absorption/action curve. Native CareLink programming/delivery rows match by Bolus Number, component and a maximum 12-hour pairing window; raw CSV retains both records. Delivered duration takes precedence over selected duration. Unmatched programmed components remain unconfirmed and dashed.

ISF/ICR panels are scatter plots. Black circles show native pump setting observations at their actual timestamps; blue diamonds show calculated response estimates at the CGM observation that ends each eligible window. No hourly sampling, held baseline, TDD reference curve or line connecting sparse points is drawn. Programming/delivery matching and duration-weighted extended totals remain separate from this display.

ISF response points begin after 90 minutes of isolated correction response. Meal ICR points begin at 3 hours, using an earlier independent observed ISF or recorded pump ISF; near starting glucose they can use carbs/effective insulin without a configured factor. Early points are explicitly provisional (correction before 4 hours; meal before 6). Windows exclude overlapping food, exercise, altered basal, unconfirmed doses and sensor gaps. The explicit 10/75/360-minute triangular insulin-action kernel, neutral basal assumption and assumed meal appearance remain experimental; points are not measured physiological absorption or dose recommendations. CSV preserves each point's observation time, window bounds, inputs and method.

Response eligibility requires endpoint CGM within 7.5 minutes, >=80% sample coverage, no CGM gap >15 minutes, no unconfirmed bolus, no recent temporary/suspend/disconnection interval, and no overlapping meals. Exercise and recent exercise are excluded. Excluded windows retain baseline curves; they are not fabricated zero estimates. CSV model rows identify their source, assumptions, baseline values, effective insulin and glucose change. Source equations: [Medtronic Therapy Management Protocol](https://www.medtronic.com/content/dam/medtronic-wide/public/united-states/products/diabetes/mm780g-therapy-management-protocol.pdf), [LoopDocs glucose prediction / insulin counteraction](https://loopkit.github.io/loopdocs/operation/algorithm/prediction/), [LoopKit model time scales](https://loopkit.github.io/loopdocs/version/code-custom-edits/). The local action approximation and eligibility rules are implementation assumptions, not a reproduction or validation of Loop. No analysis changes therapy settings or computes a recommended dose.

Recorded elevation profiles appear as grey area behind daily glucose and combined insulin panels, and on activity detail graphs. Flat shading is used only when native elevation is unavailable. The profile is normalized within its activity, so shading does not encode glucose or insulin. Dedicated reports use descending dates and descending activities within each day, and retain whole-day -> activity interleaving. Generated names use `workouts` without a count suffix.

Swimming pump disconnection is a saved user preference, not inferred from sport for every user. When enabled, FIT bounds approximate the interval. The PDF and user-reported CSV annotations state this timing assumption. Basal calculated as reaching the user is zero inside it; native pump records are preserved, even if the disconnected pump continued logging.
