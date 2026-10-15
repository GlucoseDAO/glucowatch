# PDF report standard

The approved report follows the supplied Clarity reference's clean white layout,
typography hierarchy and glucose colors, with the insulin additions described here.
Use this standard for the app's PDF exports. Do not include a Dexcom logo.

The renderer is `phone/.../ReportPdf.kt`; printable constants are in
`phone/.../ReportStyle.kt`; chart calculations are in `core/.../ReportPresentation.kt`.
The PDF palette is independent of the watch interface palette.

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

Insulin uses spaced, rounded min–max rectangles for hourly totals across observed days,
with black median strokes and numbered horizontal gridlines. Red is delivered bolus units.
Blue is programmed basal units if a recurring historical schedule can be inferred, otherwise
recorded basal delivery if supplied. The pale blue scheduled background is the integrated
scheduled amount for that hour, including schedule changes inside the hour.

Only complete hours within the pump's first/last recorded boundaries on days with pump data
enter these distributions. Within such hours no recorded dose contributes zero; missing days
and partial hours are excluded. Presence of pump events does not prove complete delivery capture.

## Daily insulin panel

- Draw scheduled basal as rounded hourly blue blocks with visible gaps. Split blocks when
  the underlying schedule changes within an hour.
- Scheduled fill is 25% opacity. Temporary fill is another 25%; normalize their intersection
  to 50% opacity so the active overlap is distinct. Increased and reduced settings both use
  this same rule. Suspension is hatched.
- Temporary setting and schedule boundaries snap to the nearest five minutes for drawing.
  Calculations and original/exported timestamps retain exact precision.
- Overlay red bolus spikes at their native timestamps. Use a red left axis in U and a blue
  right axis in U/h for rate settings, or U when showing delivered basal pulses.
- Keep glucose on its own aligned daily panel. Give metrics aligned labels and values;
  print events in two columns, up to eighteen per daily page, then continue on event pages.

## Basal evidence and unavailable data

Infer a recurring schedule only from native historical rate snapshots repeated across
multiple days, including a midnight setting. Reject tied rate votes. Label the result as
inferred. A sparse live upload or a single day does not establish a recurring full-day schedule.

Percent temporary settings multiply the scheduled rate as it changes; absolute temporary
settings replace it within their recorded duration. A later setting supersedes an earlier
one. Preserve native suspend/resume states even when they share a rate row. Programmed basal
amounts integrate these settings over exact intervals and remain separate from delivered doses.
Never add them to delivered insulin totals or normalized CSV measurements.

Without sufficient evidence, show recorded rate observations or delivered basal pulses and
an unavailable schedule. Missing data must not turn into an inferred flat schedule or zero dose.

## Verification

Run `:core:test` and build the phone app after changing this renderer. `ReportPresentationTest`
covers repeated schedules, percent/absolute changes, suspension, five-minute display snapping,
within-hour schedule integration, missing data and observed zero hours.

The debug-only `ReportPreviewActivity` renders synthetic data using the production Android
PDF renderer. Launch with `--ez all_cases true` to cover sensor-only, pump-only and combined
14-day reports, plus 1-day and 90-day combined reports. The PDFs are written to app-private
cache as `standard-<days>-<content>.pdf`. Render the PDFs to images and inspect the overview,
hourly charts, daily layers, continuation tables and final pages before accepting layout changes.
