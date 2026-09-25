#!/usr/bin/env python3
"""Store screenshots and picker previews from the demo captures of scripts/screenshots.py.

Run `python3 scripts/screenshots.py demo` first. This then writes, from demo data only (never a
real account's readings):

    fastlane/metadata/android/en-US/images/phoneScreenshots/01-face.png ... 05-app.png
        1080 x 1920, a round watch capture with a title, in GlucoseDAO colours
    watchface/src/main/res/drawable/preview.png        the face in the watch face picker
    app/src/main/res/drawable/tile_preview*.png        each tile in the "Add tiles" list

    python3 scripts/store_images.py
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / 'data' / 'output' / 'screenshots' / 'raw'
SHOTS = ROOT / 'fastlane' / 'metadata' / 'android' / 'en-US' / 'images' / 'phoneScreenshots'

# GlucoseDAO: deep teal with an orange accent (see app/.../ui/Brand.kt).
BACKGROUND_TOP, BACKGROUND_BOTTOM = (9, 38, 42), (4, 12, 14)
TEAL_LIGHT, WHITE, BEZEL = (94, 194, 204), (255, 255, 255), (58, 58, 62)
FONT_BOLD = '/usr/share/fonts/opentype/inter/Inter-Bold.otf'
FONT_REGULAR = '/usr/share/fonts/opentype/inter/Inter-Regular.otf'

SCREENS = [
    ('01-face.png', 'demo-face.png', 'GlucoWatch', 'Glucose, trend and 3 hours on your watch face'),
    ('02-tile-glucose-only.png', 'demo-tile-glucose-only.png', 'Glucose tile', 'Swipe from the face for glucose at a glance'),
    ('03-tile-glucose-all.png', 'demo-tile-glucose-all.png', 'Glucose, time and heart', 'Clock, glucose, heart rate and battery together'),
    ('04-tile-glucose-light.png', 'demo-tile-glucose-light.png', 'Light tile', 'The glucose tile in GlucoseDAO\'s light colours'),
    ('05-app.png', 'demo-app-0.png', 'App', 'Dexcom Share or Nightscout, no phone app'),
]
# Preview in the "Add tiles" list -> the capture it comes from.
TILE_PREVIEWS = {
    'tile_preview.png': 'demo-tile-glucose-only.png',
    'tile_preview_all.png': 'demo-tile-glucose-all.png',
    'tile_preview_light.png': 'demo-tile-glucose-light.png',
}


def circle(frame, size):
    """The square capture cut to the round screen, antialiased, at [size] px."""
    ss = 4
    mask = Image.new('L', (frame.width * ss, frame.height * ss), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, frame.width * ss - 1, frame.height * ss - 1), fill=255)
    out = Image.new('RGBA', frame.size, (0, 0, 0, 0))
    out.paste(frame.convert('RGBA'), (0, 0), mask.resize(frame.size, Image.LANCZOS))
    return out.resize((size, size), Image.LANCZOS)


def store_image(capture, title, subtitle):
    w, h = 1080, 1920
    img = Image.new('RGB', (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / (h - 1)
        d.line((0, y, w, y), fill=tuple(round(a + (b - a) * t) for a, b in zip(BACKGROUND_TOP, BACKGROUND_BOTTOM)))
    size = 96
    while ImageFont.truetype(FONT_BOLD, size).getlength(title) > w - 120:
        size -= 4
    d.text((w / 2, 250), title, font=ImageFont.truetype(FONT_BOLD, size), fill=WHITE, anchor='mm')
    d.text((w / 2, 360), subtitle, font=ImageFont.truetype(FONT_REGULAR, 44), fill=TEAL_LIGHT, anchor='mm')
    watch, bezel = 860, 34
    top = 560
    d.ellipse(((w - watch) / 2 - bezel, top - bezel, (w + watch) / 2 + bezel, top + watch + bezel), fill=BEZEL)
    screen = circle(capture, watch)
    img.paste(screen, ((w - watch) // 2, top), screen)
    icon = Image.open(ROOT / 'fastlane/metadata/android/en-US/images/icon.png').convert('RGBA').resize((150, 150), Image.LANCZOS)
    img.paste(icon, ((w - 150) // 2, h - 260), icon)
    return img


def main():
    missing = [raw for _, raw, _, _ in SCREENS if not (RAW / raw).is_file()]
    missing += [raw for raw in TILE_PREVIEWS.values() if not (RAW / raw).is_file() and raw not in missing]
    if missing:
        sys.exit(f'missing {", ".join(missing)} in {RAW}: run python3 scripts/screenshots.py demo first')
    SHOTS.mkdir(parents=True, exist_ok=True)
    for old in SHOTS.glob('*.png'):
        old.unlink()
    for name, raw, title, subtitle in SCREENS:
        store_image(Image.open(RAW / raw), title, subtitle).save(SHOTS / name, optimize=True)
    circle(Image.open(RAW / 'demo-face.png'), 450).save(ROOT / 'watchface/src/main/res/drawable/preview.png', optimize=True)
    for preview, raw in TILE_PREVIEWS.items():
        circle(Image.open(RAW / raw), 320).save(ROOT / 'app/src/main/res/drawable' / preview, optimize=True)
    print(f'wrote {len(SCREENS)} store screenshots, the face preview and {len(TILE_PREVIEWS)} tile previews')


if __name__ == '__main__':
    main()
