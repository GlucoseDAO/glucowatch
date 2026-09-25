# Watch face design concepts

Review mockups only. `render.py` produces editable SVGs and PNG previews; it does not
change the shipped Watch Face Format XML or install a face.

The example values come from the 2026-09-25 demo face capture in
`data/output/screenshots/demo-face.png`: 23:09, glucose 95↗, delta +8,
IOB 1.4U, COB 0g, last bolus 4.5U at 3h 3m, and forecast 57 in 30m.
The chart is hand traced from that demo capture. Its points are illustrative;
the production chart remains the app's live chart complication.

| Concept | Hierarchy | Existing complication mapping |
| --- | --- | --- |
| Signal | Time, full-width chart, glucose with grouped loop data | Slots 0–4 |
| Focus | Glucose, full-width chart, compact supporting data | Slots 0–4 |
| Focus functional | Focus with the logo's circles doing work: battery ring and recent chart data points | Slots 0–4, plus watch battery and heart rate |
| Orbit | Time and chart, glucose in a quiet circular frame | Slots 0–4 |
| Momentum | Glucose and change in its recent rate, using an amber motion accent | Slots 0–4 plus a new derived acceleration signal |

The small connected-circle mark refers to the supplied logo, using its red,
charcoal and open nodes. Red is reserved for this brand accent; it is not used
for an in-range glucose reading. The chart and forecast retain their current
data colors. All three designs use black OLED backgrounds and keep the chart
visible across the widest part of the circle. `focus-ambient.png` shows how the
recommended Focus option could dim to a low-color always-on state.

The Focus mockup separates the numeric value and trend arrow visually. The
current glucose complication delivers them as one text string, so implementing
that exact color split would need a small data/layout adjustment. The other
positions can be implemented by moving existing slots while keeping their
element order (slot IDs 0–4) intact.

`focus-functional.png` answers the feedback that the logo felt overused. It
removes the separate mark. The small upper circle shows watch battery level;
the outlined circles on the glucose line mark recent samples, with its endpoint
showing the latest sample. Heart rate is a plain number because it is not a
bounded progress metric. `focus-comparison.png` places this next to the prior
Focus mockup. The displayed battery and heart-rate values are illustrative.

`momentum.png` explores additional color and glucose acceleration. It uses a
**hypothetical** 146 mg/dL sequence that changed +5 and then +12 mg/dL over
successive five-minute intervals. The amber line endpoint and the explicit
"RISING FASTER" label mean that the upward rate increased. The current app has
trend and five-minute delta but no acceleration calculation or complication;
the badge must not be shown from an arrow alone. A production version should
require three recent, appropriately spaced readings, suppress the badge for
stale or irregular data, and keep alert colors based on actual glucose level.
Motion color candidates: cyan for no clear acceleration, amber for a faster
rise, violet for an easing rise, and rose for a faster fall. Text and shape
remain with color so the state is still distinguishable without color.
`motion-language.png` shows these candidate states together.
