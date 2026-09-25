"""Render review-only GlucoWatch face concepts; does not change the Wear face."""

from pathlib import Path
import subprocess

from PIL import Image, ImageDraw, ImageFont


OUT = Path(__file__).resolve().parent
WHITE = "#F5F7F8"
MUTED = "#91A0A7"
RED = "#F04449"
CYAN = "#45C7D5"
ORANGE = "#E8A044"
GREEN = "#55C897"
PURPLE = "#B2A0E6"
AMBER = "#FFC05A"


def text(x, y, value, size, color=WHITE, weight=500, anchor="middle", spacing=""):
    return (f'<text x="{x}" y="{y}" text-anchor="{anchor}" '
            f'font-family="Inter, Noto Sans, sans-serif" font-size="{size}" '
            f'font-weight="{weight}" fill="{color}" {spacing}>{value}</text>')


def logo(x, y, scale=1):
    return f'''<g transform="translate({x} {y}) scale({scale})" stroke="#DDE4E7" stroke-width="2.3" fill="none" stroke-linecap="round">
      <path d="M0 0 L19 0 L31 -12 M19 0 L33 13"/>
      <circle cx="0" cy="0" r="6" fill="{RED}" stroke="none"/>
      <circle cx="19" cy="0" r="6" fill="#505A60" stroke="none"/>
      <circle cx="31" cy="-12" r="5"/><circle cx="33" cy="13" r="5"/>
    </g>'''


def chart(y=185, h=125, labels=True, bright=True):
    # A hand traced approximation of the demo screenshot. The shipped chart stays live.
    path = "M15 78 C28 80 39 87 50 82 S74 92 88 79 S109 52 125 53 S141 62 157 53 S177 52 190 40 S205 38 218 22 S232 26 247 21 S262 35 277 33 S296 37 309 40 S326 30 342 32 S356 28 371 46 S390 73 405 76 S420 75 435 78"
    color = CYAN if bright else "#BCC9CE"
    labels_svg = (text(43, 34, "180", 15, MUTED, 500, "start") +
                  text(43, 98, "70", 15, MUTED, 500, "start") +
                  text(225, 123, "−2h", 15, MUTED) +
                  text(336, 123, "−1h", 15, MUTED)) if labels else ""
    return f'''<g transform="translate(0 {y})">
      <rect x="18" y="36" width="414" height="65" fill="#102428" opacity=".60"/>
      <path d="M18 36 H432 M18 101 H432" stroke="#31565A" stroke-width="1" stroke-dasharray="3 5"/>
      <path d="M225 20 V107 M337 20 V107" stroke="#263239" stroke-width="1"/>
      <path d="{path}" fill="none" stroke="{color}" stroke-width="3.3" stroke-linecap="round" stroke-linejoin="round"/>
      <path d="M433 78 C441 77 447 80 452 84" fill="none" stroke="{PURPLE}" stroke-width="2" stroke-dasharray="4 4"/>
      <circle cx="432" cy="78" r="6" fill="#081013" stroke="{color}" stroke-width="2.5"/>
      {labels_svg}
    </g>'''


def shell(contents, ambient=False):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="450" height="450" viewBox="0 0 450 450">
      <defs><clipPath id="round"><circle cx="225" cy="225" r="223"/></clipPath></defs>
      <circle cx="225" cy="225" r="224" fill="#42474B"/>
      <circle cx="225" cy="225" r="211" fill="#05090B"/>
      <g clip-path="url(#round)">{contents}</g>
    </svg>'''


def signal():
    # A dense but orderly face, preserving all five current complications.
    c = logo(120, 54, .67)
    c += text(225, 61, "FRI 25 SEP", 19, MUTED, 600)
    c += text(326, 61, "♥ 72", 15, MUTED, 600, "end")
    c += text(225, 145, "23:09", 76, WHITE, 600)
    c += chart(153)
    c += '<path d="M47 321 H403" stroke="#273137" stroke-width="1"/>'
    c += text(125, 348, "IOB", 13, MUTED, 650)
    c += text(125, 375, "1.4U", 23, ORANGE, 650)
    c += text(125, 395, "COB 0g", 12, GREEN, 550)
    c += text(225, 378, "95↗", 59, WHITE, 700)
    c += text(225, 397, "+8 mg/dL", 14, CYAN, 600)
    c += text(325, 348, "BOLUS", 13, MUTED, 650)
    c += text(325, 375, "4.5U", 23, WHITE, 600)
    c += text(325, 395, "3h 3m", 12, MUTED, 550)
    c += text(225, 419, "57 in 30m", 16, PURPLE, 600)
    return shell(c)


def focus():
    # The glucose reading wins the visual hierarchy; chart remains full width.
    c = logo(120, 54, .67)
    c += text(225, 61, "23:09", 31, WHITE, 600)
    c += text(326, 61, "FRI 25", 15, MUTED, 600, "end")
    c += text(225, 156, "95", 111, WHITE, 750)
    c += text(324, 145, "↗", 49, CYAN, 600)
    c += text(225, 184, "mg/dL   ·   +8", 19, MUTED, 550)
    c += chart(195, labels=False)
    c += text(225, 325, "GLUCOSE HISTORY", 12, MUTED, 700, spacing='letter-spacing="2"')
    c += '<path d="M65 342 H385" stroke="#273137" stroke-width="1"/>'
    c += text(145, 370, "IOB / COB", 12, MUTED, 650)
    c += text(145, 394, "1.4U / 0g", 16, WHITE, 600)
    c += text(305, 370, "LAST BOLUS", 12, MUTED, 650)
    c += text(305, 394, "4.5U · 3h", 16, WHITE, 600)
    c += text(225, 418, "57 IN 30M", 15, PURPLE, 650)
    return shell(c)


def focus_functional():
    # No standalone brand mark: circles now show battery state and recent chart samples.
    c = '''<circle cx="118" cy="53" r="11" fill="none" stroke="#334148" stroke-width="3"/>
      <circle cx="118" cy="53" r="11" fill="none" stroke="#45C7D5" stroke-width="3"
        stroke-dasharray="46 70" transform="rotate(-90 118 53)"/>
      <rect x="115" y="49" width="6" height="8" rx="1" fill="none" stroke="#B9C9CE" stroke-width="1"/>
      <rect x="117" y="47" width="2" height="2" fill="#B9C9CE"/>'''
    c += text(134, 60, "67%", 15, MUTED, 600, "start")
    c += text(225, 61, "23:09", 31, WHITE, 600)
    c += text(323, 60, "♥ 74", 15, MUTED, 600, "end")
    c += text(225, 156, "95", 111, WHITE, 750)
    c += text(324, 145, "↗", 49, CYAN, 600)
    c += text(225, 184, "mg/dL   ·   +8", 19, MUTED, 550)
    c += chart(195, labels=False)
    c += '''<circle cx="342" cy="228" r="4.5" fill="#081013" stroke="#7F9CA3" stroke-width="2"/>
      <circle cx="371" cy="242" r="4.5" fill="#081013" stroke="#7F9CA3" stroke-width="2"/>
      <circle cx="405" cy="270" r="4.5" fill="#081013" stroke="#7F9CA3" stroke-width="2"/>'''
    c += text(225, 325, "FRI 25 SEP", 13, MUTED, 650, spacing='letter-spacing="1.2"')
    c += '<path d="M65 342 H385" stroke="#273137" stroke-width="1"/>'
    c += text(145, 370, "IOB / COB", 12, MUTED, 650)
    c += text(145, 394, "1.4U / 0g", 16, WHITE, 600)
    c += text(305, 370, "LAST BOLUS", 12, MUTED, 650)
    c += text(305, 394, "4.5U · 3h", 16, WHITE, 600)
    c += text(225, 418, "57 IN 30M", 15, PURPLE, 650)
    return shell(c)


def orbit():
    # Minimal language. Small molecular nodes never carry a reading or alert meaning.
    c = logo(54, 157, .75)
    c += text(225, 60, "FRIDAY 25 SEPTEMBER", 17, MUTED, 600)
    c += text(225, 143, "23:09", 73, WHITE, 550)
    c += '<path d="M86 164 H364" stroke="#354248" stroke-width="1"/>'
    c += chart(175, labels=False, bright=False)
    c += '<circle cx="225" cy="362" r="58" fill="#0D1417" stroke="#3E4A50" stroke-width="1.5"/>'
    c += '<circle cx="164" cy="362" r="5" fill="'+RED+'"/>'
    c += '<circle cx="286" cy="362" r="5" fill="#69757B"/>'
    c += text(225, 361, "95↗", 48, WHITE, 700)
    c += text(225, 385, "+8", 17, CYAN, 600)
    c += text(87, 352, "1.4U", 23, ORANGE, 650)
    c += text(87, 374, "IOB", 13, MUTED, 650)
    c += text(363, 352, "4.5U", 23, WHITE, 650)
    c += text(363, 374, "BOLUS", 13, MUTED, 650)
    c += text(225, 417, "57 IN 30M", 17, PURPLE, 600)
    return shell(c)


def momentum():
    # Hypothetical sequence: +5 then +12 mg/dL over consecutive five-minute windows.
    # This demonstrates visual semantics only; no acceleration signal exists in the app yet.
    c = logo(120, 54, .67)
    c += text(225, 61, "23:09", 31, WHITE, 600)
    c += text(326, 61, "FRI 25", 15, MUTED, 600, "end")
    c += text(215, 151, "146", 101, WHITE, 750)
    c += text(328, 146, "↑", 47, AMBER, 650)
    c += text(225, 181, "mg/dL   ·   +12 / 5m", 18, MUTED, 550)
    c += '''<defs><linearGradient id="motion" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#45C7D5"/><stop offset=".74" stop-color="#45C7D5"/>
      <stop offset="1" stop-color="#FFC05A"/></linearGradient></defs>'''
    c += '''<g transform="translate(0 194)">
      <rect x="18" y="35" width="414" height="74" fill="#102428" opacity=".60"/>
      <path d="M18 35 H432 M18 109 H432" stroke="#31565A" stroke-width="1" stroke-dasharray="3 5"/>
      <path d="M225 20 V112 M337 20 V112" stroke="#263239" stroke-width="1"/>
      <path d="M16 91 C65 92 76 86 112 87 S158 80 188 83 S232 79 264 76 S295 79 321 75 S349 70 367 65 S389 53 405 42 S421 25 432 14"
        fill="none" stroke="url(#motion)" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>
      <circle cx="432" cy="14" r="7" fill="#081013" stroke="#FFC05A" stroke-width="3"/>
    </g>'''
    c += '<rect x="139" y="321" width="172" height="31" rx="15.5" fill="#382B1B" stroke="#8B6630"/>'
    c += text(225, 342, "↗  RISING FASTER", 14, AMBER, 700, spacing='letter-spacing=".7"')
    c += text(145, 373, "IOB / COB", 12, MUTED, 650)
    c += text(145, 396, "1.4U / 0g", 16, WHITE, 600)
    c += text(305, 373, "LAST BOLUS", 12, MUTED, 650)
    c += text(305, 396, "4.5U · 3h", 16, WHITE, 600)
    c += text(225, 418, "FORECAST 166 IN 30M", 14, PURPLE, 650)
    return shell(c)


def main():
    variants = {"signal": signal(), "focus": focus(), "focus-functional": focus_functional(), "orbit": orbit(), "momentum": momentum()}
    ambient = focus()
    for source, replacement in {
        WHITE: "#B8C0C4",
        MUTED: "#727F85",
        RED: "#898F93",
        CYAN: "#AAB5B9",
        ORANGE: "#AAB5B9",
        GREEN: "#AAB5B9",
        PURPLE: "#AAB5B9",
    }.items():
        ambient = ambient.replace(source, replacement)
    variants["focus-ambient"] = ambient
    for name, svg in variants.items():
        (OUT / f"{name}.svg").write_text(svg)
        subprocess.run(["rsvg-convert", "-w", "900", "-h", "900", "-o", str(OUT / f"{name}.png"), str(OUT / f"{name}.svg")], check=True)

    sheet = Image.new("RGB", (1440, 610), "#101518")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype("/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Medium.ttf", 27)
    subfont = ImageFont.truetype("/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Regular.ttf", 17)
    labels = [("01  SIGNAL", "All data, clearer grouping"), ("02  FOCUS", "Glucose first"), ("03  ORBIT", "Quiet and minimal")]
    for i, name in enumerate(("signal", "focus", "orbit")):
        face = Image.open(OUT / f"{name}.png").convert("RGBA").resize((430, 430), Image.Resampling.LANCZOS)
        x = 25 + i * 480
        sheet.paste(face, (x, 30), face)
        draw.text((x + 215, 488), labels[i][0], fill="#F5F7F8", font=font, anchor="mm")
        draw.text((x + 215, 530), labels[i][1], fill="#91A0A7", font=subfont, anchor="mm")
    sheet.save(OUT / "overview.png")

    comparison = Image.new("RGB", (960, 560), "#101518")
    cd = ImageDraw.Draw(comparison)
    for i, (name, label) in enumerate((("focus", "B / original"), ("focus-functional", "B / data nodes"))):
        face = Image.open(OUT / f"{name}.png").convert("RGBA").resize((430, 430), Image.Resampling.LANCZOS)
        x = 25 + 480 * i
        comparison.paste(face, (x, 20), face)
        cd.text((x + 215, 495), label, fill="#F5F7F8", font=font, anchor="mm")
    comparison.save(OUT / "focus-comparison.png")

    states = Image.new("RGB", (1200, 318), "#101518")
    sd = ImageDraw.Draw(states)
    title_font = ImageFont.truetype("/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Medium.ttf", 28)
    label_font = ImageFont.truetype("/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Medium.ttf", 21)
    detail_font = ImageFont.truetype("/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Regular.ttf", 16)
    sd.text((40, 38), "Motion color language", fill="#F5F7F8", font=title_font)
    cards = [
        (CYAN, "→", "Steady pace", "Recent rate unchanged"),
        (AMBER, "↗", "Rising faster", "Upward rate increasing"),
        (PURPLE, "↗", "Rise easing", "Upward rate decreasing"),
        ("#FF7890", "↘", "Falling faster", "Downward rate increasing"),
    ]
    for i, (color, icon, label, detail) in enumerate(cards):
        x = 35 + 292 * i
        sd.rounded_rectangle((x, 95, x + 275, 275), radius=25, fill="#192126", outline="#344047", width=2)
        sd.ellipse((x + 18, 114, x + 46, 142), fill=color)
        if icon == "→":
            sd.line((x + 60, 128, x + 90, 128), fill=color, width=4)
            sd.line((x + 80, 119, x + 90, 128, x + 80, 137), fill=color, width=4)
        elif icon == "↗":
            sd.line((x + 60, 141, x + 87, 114), fill=color, width=4)
            sd.line((x + 75, 114, x + 87, 114, x + 87, 126), fill=color, width=4)
        else:
            sd.line((x + 60, 114, x + 87, 141), fill=color, width=4)
            sd.line((x + 75, 141, x + 87, 141, x + 87, 129), fill=color, width=4)
        sd.text((x + 20, 177), label, fill="#F5F7F8", font=label_font)
        sd.text((x + 20, 225), detail, fill="#91A0A7", font=detail_font)
    states.save(OUT / "motion-language.png")


if __name__ == "__main__":
    main()
