"""Generate monochrome, review-only concepts for the glucose-all Wear tile."""

from pathlib import Path
import subprocess

from PIL import Image, ImageDraw, ImageFont


OUT = Path(__file__).resolve().parent
INK = "#F5F5F3"
SILVER = "#B8BAB9"
MUTED = "#858989"
DIM = "#5D6262"


def txt(x, y, value, size, color=INK, weight=500, anchor="middle", extra=""):
    return (f'<text x="{x}" y="{y}" text-anchor="{anchor}" '
            f'font-family="Inter, Roboto, sans-serif" font-size="{size}" '
            f'font-weight="{weight}" fill="{color}" {extra}>{value}</text>')


def chart(y):
    # Representative demo history. Range is encoded by location and texture, not hue.
    return f'''<g transform="translate(0 {y})">
      <rect x="14" y="35" width="404" height="53" fill="#1A1C1C"/>
      <path d="M14 35 H418 M14 88 H418" stroke="#5C6161" stroke-width="1" stroke-dasharray="3 5"/>
      <path d="M128 20 V102 M244 20 V102" stroke="#292D2D" stroke-width="1"/>
      {txt(25, 32, '180', 13, MUTED, 500, 'start')}
      {txt(25, 105, '70', 13, MUTED, 500, 'start')}
      <path d="M14 83 C35 87 50 89 61 80 S78 56 99 58 S119 64 139 53
        S159 45 173 35 S189 16 201 20 S215 25 225 30 S241 38 254 39
        S276 31 288 38 S299 53 311 66 S328 79 344 78"
        fill="none" stroke="#F5F5F3" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
      <circle cx="288" cy="38" r="4" fill="#000" stroke="#9DA2A2" stroke-width="2"/>
      <circle cx="311" cy="66" r="4" fill="#000" stroke="#9DA2A2" stroke-width="2"/>
      <circle cx="344" cy="78" r="6" fill="#F5F5F3" stroke="#000" stroke-width="2"/>
      <path d="M350 78 Q375 82 409 92" fill="none" stroke="#8B9191" stroke-width="2" stroke-dasharray="4 5"/>
      <circle cx="409" cy="92" r="5" fill="#000" stroke="#8B9191" stroke-width="2"/>
      {txt(128, 119, '−2h', 13, MUTED)}{txt(244, 119, '−1h', 13, MUTED)}
    </g>'''


def footer(y):
    return f'''<g>
      <path d="M126 {y-15} H306" stroke="#303535" stroke-width="1"/>
      <path d="M150 {y+2} C143 {y-5} 132 {y+1} 140 {y+11} L150 {y+20}
        L160 {y+11} C168 {y+1} 157 {y-5} 150 {y+2}Z"
        fill="none" stroke="#B8BAB9" stroke-width="2" stroke-linejoin="round"/>
      {txt(174, y+17, '110', 21, INK, 600, 'start')}
      <rect x="244" y="{y+1}" width="25" height="17" rx="3" fill="none" stroke="#B8BAB9" stroke-width="2"/>
      <rect x="270" y="{y+6}" width="3" height="7" rx="1" fill="#B8BAB9"/>
      <rect x="248" y="{y+5}" width="17" height="9" rx="1" fill="#B8BAB9"/>
      {txt(285, y+17, '100%', 21, INK, 600, 'start')}
    </g>'''


def shell(content):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="0 0 432 432">
      <defs><clipPath id="dial"><circle cx="216" cy="216" r="215"/></clipPath></defs>
      <circle cx="216" cy="216" r="215" fill="#000000" stroke="#252828" stroke-width="1"/>
      <g clip-path="url(#dial)">{content}</g>
    </svg>'''


def glucose_first():
    c = txt(216, 58, "23:09", 43, INK, 600)
    c += txt(216, 93, "FRI 25 SEP", 14, MUTED, 650, extra='letter-spacing="1.7"')
    c += txt(199, 190, "95", 112, INK, 750)
    c += txt(305, 179, "↗", 45, SILVER, 600)
    c += txt(216, 217, "+8  ·  4m ago", 19, SILVER, 500)
    c += chart(221)
    c += footer(354)
    return shell(c)


def balanced():
    c = txt(216, 49, "FRI 25 SEP", 15, MUTED, 650, extra='letter-spacing="1.6"')
    c += txt(216, 119, "23:09", 66, INK, 600)
    c += txt(195, 194, "95", 82, INK, 730)
    c += txt(284, 184, "↗", 36, SILVER, 600)
    c += txt(216, 219, "+8  ·  4m ago", 18, SILVER, 500)
    c += chart(222)
    c += footer(354)
    return shell(c)


def main():
    designs = {"glucose-first": glucose_first(), "balanced": balanced()}
    for name, svg in designs.items():
        (OUT / f"{name}.svg").write_text(svg)
        subprocess.run(["rsvg-convert", "-w", "864", "-h", "864", "-o", str(OUT / f"{name}.png"), str(OUT / f"{name}.svg")], check=True)

    canvas = Image.new("RGB", (960, 520), "#111313")
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.truetype("/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Medium.ttf", 26)
    names = (("glucose-first", "GLUCOSE FIRST"), ("balanced", "BALANCED"))
    for i, (name, label) in enumerate(names):
        pic = Image.open(OUT / f"{name}.png").convert("RGBA").resize((432, 432), Image.Resampling.LANCZOS)
        x = 20 + 480 * i
        canvas.paste(pic, (x, 12), pic)
        draw.text((x + 216, 478), label, fill="#D8DADA", font=font, anchor="mm")
    canvas.save(OUT / "comparison.png")


if __name__ == "__main__":
    main()
