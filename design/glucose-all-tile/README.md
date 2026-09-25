# Glucose-all tile: B-inspired contrast

The final `emulator-final.png` is a screenshot of the running **glucose-all tile**
on the 432 × 432 Wear emulator, using demo data. The earlier SVGs and
`comparison.png` show the monochrome exploration that preceded the B-inspired
revision. Neither TIR nor weather appears.

The configured target range is a gray band inside the chart. The line moves
through green (comfortably in range), yellow (near a limit), and red (outside
the range). The large reading uses the same states; the fastest reported
upward or downward trends also turn it red. The existing arrow gives direction,
and the compact status line shows only change and reading age. Heart rate is
red by design, while time and battery remain white. An earlier exploratory
capture (`emulator-b.png`) used a red rim, but it was removed because its meaning
was unclear. The chart was then given more vertical room.

The chart carries the logo's connected-circle idea:
outlined nodes are recent readings, the filled node is the latest reading,
and a dotted link leads to an open forecast point. These nodes represent
actual data positions in the intended design, rather than a separate logo.

`glucose-first` makes the glucose value the main glance target. `balanced`
gives time more weight. Both place heart rate and battery in a plain bottom
row. The implementation uses a dedicated chart palette for the glucose-all tile.

The date row was removed from the implementation after a round-screen capture
showed it pushed the battery and heart-rate row too close to the rim. The
previous monochrome emulator capture remains as `emulator.png` for comparison.

Run `python3 render.py` to regenerate the SVG and PNG previews.
