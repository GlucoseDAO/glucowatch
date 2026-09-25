# Glucose light tile

`emulator-final.png` is the running tile on the 432 px Watch6 Classic emulator with demo data.
It shares the glucose-first layout with the dark tile: clock, state-coloured glucose value and
trend, change and reading age, a range chart, then heart rate and battery. A fast trend or an
out-of-range reading is red; near either range edge is amber; the comfortable range is green.
Stale readings turn grey. The chart keeps the connected reading circles as a functional nod to
the GlucoseDAO logo, and the target range is the grey band inside the chart.

Only the background and contrast values change from the dark tile. Heart rate stays red, while
the clock and battery use dark neutral text. Forecast remains available as the dashed continuation
of the chart when enabled.
