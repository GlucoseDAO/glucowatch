#!/usr/bin/env python3
"""Screenshots of the GlucoWatch face, tiles and app on simulated Galaxy watches.

GlucoWatch has to fit every round Galaxy watch from the Watch6 on. Those come in three screen
sizes, one simulated watch each (--watch):

    watch6-classic-43  432 px  Watch6 40 mm, Watch6 Classic 43 mm (SM-R950), Watch7 40 mm   default
    watch8-classic     438 px  Watch8 Classic, Watch8 40 mm
    watch6-classic-47  480 px  Watch6 44 mm, Watch6 Classic 47 mm, Watch7 44 mm, Watch8 44 mm, Ultra

The default is the Watch6 Classic 43 mm the app is tried on; store images come from it.
`--watch all` runs all three. For each watch the script creates the AVD if it is missing,
boots it without a window, installs both debug APKs (built once), sets the GlucoWatch face, and
captures every scenario:

    demo               demo data, no account
    dexcom             Dexcom Share with DEXCOM_USERNAME / DEXCOM_PASSWORD from .env
    nightscout         the Nightscout at NIGHTSCOUT_URL (plus NIGHTSCOUT_TOKEN, NIGHTSCOUT_API)
    nightscout-replay  the same Nightscout replayed so its loop looks fresh (scripts/nightscout_replay.py)

Output (data/output/ is gitignored), in data/output/screenshots/<watch>/, named after what they
show, and data/output/screenshots/overview.png with both watches:

    <scenario>-face.png, <scenario>-face-ambient.png    the watch face
    <scenario>-tile-glucose-only.png                    the three tiles
    <scenario>-tile-glucose-all.png
    <scenario>-tile-glucose-light.png
    <scenario>-app.png                                  the app screen, scrolled, frames side by side
    overview.png                                        one row per scenario: face and tiles
    raw/                                                the unclipped square captures behind these

    python3 scripts/screenshots.py                      # all scenarios that are configured
    python3 scripts/screenshots.py nightscout dexcom    # some of them
    python3 scripts/screenshots.py --watch all          # every screen size
    python3 scripts/screenshots.py --watch watch8-classic watch6-classic-47
    python3 scripts/screenshots.py --no-build --keep    # reuse the APKs, leave the emulators running

Needs the Android SDK (local.properties or ANDROID_HOME) with the emulator and the
system-images;android-36;android-wear-signed;x86_64 image, KVM, and Pillow (python3-pil).
Settings come from .env and the environment, as for the debug build. See docs/screenshots.md.
"""
import argparse
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))

IMAGE = 'system-images;android-36;android-wear-signed;x86_64'
# One simulated watch per round screen size since the Watch6. Density 340 is Samsung's value
# on the 432 px watch the app is tried on; the others are assumed to use the same (check with
# `adb shell wm density` on a real one). Each has its own console port, so all can stay up
# with --keep. The first entry is the default.
WATCHES = {
    'watch6-classic-43': {'label': 'Galaxy Watch6 Classic 43 mm (432 px)', 'avd': 'glucowatch_gw6c', 'size': 432, 'density': 340, 'port': 5584},
    'watch8-classic': {'label': 'Galaxy Watch8 Classic (438 px)', 'avd': 'glucowatch_gw8c', 'size': 438, 'density': 340, 'port': 5586},
    'watch6-classic-47': {'label': 'Galaxy Watch6 Classic 47 mm (480 px)', 'avd': 'glucowatch_gw6c47', 'size': 480, 'density': 340, 'port': 5588},
}
DEFAULT_WATCH = next(iter(WATCHES))
# The watch being captured; set by use_watch().
AVD = WATCH = MIDDLE = PORT = SERIAL = None
REPLAY_PORT = 8537
PKG = 'io.github.antonkulaga.glucowatch'
FACE = PKG + '.watchface'
# Tile name in the file names -> its service. Also the order in the tile carousel.
TILES = {
    'glucose-only': f'{PKG}/{PKG}.tile.GlucoseTileService',
    'glucose-all': f'{PKG}/{PKG}.tile.GlucoseAllTileService',
    'glucose-light': f'{PKG}/{PKG}.tile.GlucoseLightTileService',
}

SCENARIOS = ['demo', 'dexcom', 'nightscout', 'nightscout-replay']


def dotenv():
    """`.env` with the same rules as core's DotEnv; the environment wins."""
    values = {}
    path = ROOT / '.env'
    if path.is_file():
        for raw in path.read_text().splitlines():
            line = raw.strip().removeprefix('export ').strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in '"\'':
                value = value[1:-1]
            else:
                value = re.sub(r'\s+#.*$', '', value)
            values[key.strip()] = value
    values.update({k: v for k, v in os.environ.items() if k in values or k.startswith(('DEXCOM_', 'NIGHTSCOUT_', 'GLUCOWATCH_'))})
    return {k: v for k, v in values.items() if v}


def sdk_dir():
    if os.environ.get('ANDROID_HOME'):
        return Path(os.environ['ANDROID_HOME'])
    props = ROOT / 'local.properties'
    if props.is_file():
        for line in props.read_text().splitlines():
            if line.startswith('sdk.dir='):
                return Path(line.split('=', 1)[1].strip())
    sys.exit('Android SDK not found: set ANDROID_HOME or sdk.dir in local.properties')


SDK = sdk_dir()
ADB = str(SDK / 'platform-tools' / 'adb')
EMULATOR = str(SDK / 'emulator' / 'emulator')
AVDMANAGER = str(SDK / 'cmdline-tools' / 'latest' / 'bin' / 'avdmanager')


def log(message):
    print(f'[screenshots] {message}', flush=True)


def adb(*args, check=True, binary=False):
    result = subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, check=False)
    if check and result.returncode != 0:
        raise RuntimeError(f'adb {" ".join(args)} failed: {result.stderr.decode(errors="replace").strip()}')
    return result.stdout if binary else result.stdout.decode(errors='replace')


def nap(seconds):
    time.sleep(seconds)


def use_watch(key):
    global AVD, WATCH, MIDDLE, PORT, SERIAL
    watch = WATCHES[key]
    AVD, PORT = watch['avd'], watch['port']
    SERIAL = f'emulator-{PORT}'
    WATCH = {'hw.lcd.width': str(watch['size']), 'hw.lcd.height': str(watch['size']),
             'hw.lcd.density': str(watch['density']), 'hw.lcd.circular': 'true'}
    MIDDLE = watch['size'] // 2


def ensure_avd():
    avd_dir = Path.home() / '.android' / 'avd' / f'{AVD}.avd'
    if not avd_dir.is_dir():
        if not (SDK / Path(*IMAGE.split(';'))).is_dir():
            sys.exit(f'Missing system image. Install it with:\n  {SDK}/cmdline-tools/latest/bin/sdkmanager "{IMAGE}"')
        log(f'creating AVD {AVD} (round {WATCH["hw.lcd.width"]}x{WATCH["hw.lcd.height"]}, density {WATCH["hw.lcd.density"]})')
        subprocess.run([AVDMANAGER, 'create', 'avd', '-n', AVD, '-k', IMAGE, '-d', 'wearos_large_round', '--force'],
                       input=b'no\n', check=True, capture_output=True)
    config = avd_dir / 'config.ini'
    lines = [l for l in config.read_text().splitlines() if l.split('=')[0].strip() not in WATCH]
    lines += [f'{k} = {v}' for k, v in WATCH.items()]
    config.write_text('\n'.join(lines) + '\n')


def running():
    out = subprocess.run([ADB, 'devices'], capture_output=True, text=True).stdout
    return any(line.startswith(SERIAL + '\t') and line.endswith('device') for line in out.splitlines())


def boot():
    if running():
        log(f'reusing {SERIAL}')
        return False
    ensure_avd()
    log(f'booting {AVD} on {SERIAL}')
    subprocess.Popen([EMULATOR, '-avd', AVD, '-port', str(PORT), '-no-window', '-no-audio', '-no-boot-anim',
                      '-no-snapshot'],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    deadline = time.time() + 240
    while time.time() < deadline:
        if running() and adb('shell', 'getprop', 'sys.boot_completed', check=False).strip() == '1':
            break
        nap(2)
    else:
        sys.exit('emulator did not boot within 4 minutes')
    nap(5)
    return True


def prepare_device():
    adb('shell', 'svc', 'power', 'stayon', 'true', check=False)
    adb('shell', 'settings', 'put', 'system', 'screen_off_timeout', '2147483647', check=False)
    adb('shell', 'settings', 'put', 'system', 'time_12_24', '24', check=False)
    # A plugged-in battery puts a charging bolt over the bottom of the face.
    adb('shell', 'dumpsys', 'battery', 'unplug', check=False)
    adb('shell', 'dumpsys', 'battery', 'set', 'level', '80', check=False)
    zone = subprocess.run(['timedatectl', 'show', '-p', 'Timezone', '--value'], capture_output=True, text=True).stdout.strip()
    if zone:
        adb('shell', 'service', 'call', 'alarm', '3', 's16', zone, check=False)
    size = adb('shell', 'wm', 'size').strip()
    density = adb('shell', 'wm', 'density').strip()
    log(f'{size.splitlines()[-1]}, {density.splitlines()[-1]}')


def build_and_install(build):
    if build:
        log('building debug APKs')
        subprocess.run([str(ROOT / 'gradlew'), '-q', '--console=plain', ':app:assembleDebug', ':watchface:assembleDebug'],
                       cwd=ROOT, check=True)
    # A fresh face install, so every slot starts from its default provider.
    adb('uninstall', FACE, check=False)
    for apk in ('app/build/outputs/apk/debug/app-debug.apk', 'watchface/build/outputs/apk/debug/watchface-debug.apk'):
        adb('install', '-r', str(ROOT / apk))
    # Heart rate on the face and the glucose-all tile: grant what the watch would ask for.
    for package in (FACE, PKG):
        adb('shell', 'pm', 'grant', package, 'android.permission.health.READ_HEART_RATE', check=False)
    debug_surface('set-watchface', '--es', 'watchFaceId', FACE)


def debug_surface(operation, *extras):
    """The emulator's debug hooks for faces and tiles (Wear OS 4 and later)."""
    return adb('shell', 'am', 'broadcast', '-a', 'com.google.android.wearable.app.DEBUG_SURFACE',
               '--es', 'operation', operation, *extras, check=False)


def fetched_at():
    xml = adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/cache.xml', check=False)
    match = re.search(r'name="fetchedAt" value="(\d+)"', xml)
    return int(match.group(1)) if match else 0


def configure(extras):
    """Applies settings through the debug-only adb extras of SettingsActivity and waits for the fetch."""
    before = fetched_at()
    args = ['am', 'start', '-S', '-W', '-n', f'{PKG}/.ui.SettingsActivity']
    for key, value in {**extras, 'save': True}.items():
        args += ['--ez', key, str(value).lower()] if isinstance(value, bool) else ['--es', key, str(value)]
    # adb shell joins its arguments into one device shell command: quote them, or an empty value
    # or a URL with & shifts everything after it.
    out = adb('shell', ' '.join(shlex.quote(a) for a in args))
    if 'Error' in out:
        raise RuntimeError(f'am start failed: {out.strip()}')
    deadline = time.time() + 45
    while time.time() < deadline and fetched_at() <= before:
        nap(1)
    if fetched_at() <= before:
        log('  no fetch finished within 45 s; capturing anyway')
    nap(2)


def screencap(path):
    path.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def capture(name, raw_dir):
    """App screen (scrolled to the end), each tile, and the watch face, interactive and ambient."""
    adb('shell', 'am', 'start', '-S', '-W', '-n', f'{PKG}/.ui.MainActivity')
    nap(6)
    frames = []
    for i in range(6):
        frame = raw_dir / f'{name}-app-{i}.png'
        screencap(frame)
        if frames and frame.read_bytes() == frames[-1].read_bytes():
            frame.unlink()
            break
        frames.append(frame)
        adb('shell', 'input', 'swipe', str(MIDDLE), str(MIDDLE * 16 // 10), str(MIDDLE), str(MIDDLE * 6 // 10), '400')
        nap(1.5)
    # One tile at a time: add-tile always puts the new tile first and answers Index=[0], so the
    # only reliable way to show a given tile is to have it be the only GlucoWatch tile.
    for tile, component in TILES.items():
        for other in TILES.values():
            debug_surface('remove-tile', '--ecn', 'component', other)
        debug_surface('add-tile', '--ecn', 'component', component)
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        nap(2)
        adb('shell', 'am', 'broadcast', '-a', 'com.google.android.wearable.app.DEBUG_SYSUI',
            '--es', 'operation', 'show-tile', '--ei', 'index', '0')
        nap(6)
        screencap(raw_dir / f'{name}-tile-{tile}.png')
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    nap(6)
    screencap(raw_dir / f'{name}-face.png')
    adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
    nap(4)
    screencap(raw_dir / f'{name}-face-ambient.png')
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    nap(2)
    return frames


def round_frame(frame, bezel=14, ss=4):
    from PIL import Image, ImageDraw
    n = frame.width
    size = n + 2 * bezel
    mask = Image.new('L', (size * ss, size * ss), 0)
    ImageDraw.Draw(mask).ellipse((bezel * ss, bezel * ss, (bezel + n) * ss - 1, (bezel + n) * ss - 1), fill=255)
    ring = Image.new('L', (size * ss, size * ss), 0)
    ImageDraw.Draw(ring).ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
    mask, ring = mask.resize((size, size), Image.LANCZOS), ring.resize((size, size), Image.LANCZOS)
    out = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    out.paste(Image.new('RGBA', (size, size), (58, 58, 62, 255)), (0, 0), ring)
    screen = Image.new('RGBA', (size, size), (0, 0, 0, 255))
    screen.paste(frame.convert('RGBA'), (bezel, bezel))
    out.paste(screen, (0, 0), mask)
    return out


def side_by_side(images, gap=16, background=(0, 0, 0, 0)):
    from PIL import Image
    out = Image.new('RGBA', (sum(i.width for i in images) + gap * (len(images) - 1), max(i.height for i in images)), background)
    x = 0
    for image in images:
        out.paste(image, (x, 0), image)
        x += image.width + gap
    return out


def stacked(rows, gap=16, background=(255, 255, 255, 255)):
    from PIL import Image
    out = Image.new('RGBA', (max(r.width for r in rows), sum(r.height for r in rows) + gap * (len(rows) - 1)), background)
    y = 0
    for row in rows:
        out.paste(row, (0, y), row)
        y += row.height + gap
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('scenarios', nargs='*', help=f'any of {", ".join(SCENARIOS)}; default: every configured one')
    parser.add_argument('--no-build', action='store_true', help='install the APKs that are already built')
    parser.add_argument('--keep', action='store_true', help='leave the emulators running afterwards')
    parser.add_argument('--watch', nargs='+', choices=[*WATCHES, 'all'], default=[DEFAULT_WATCH],
                        help=f'which watches to simulate (default: {DEFAULT_WATCH}, the one the app is tried on)')
    parser.add_argument('--unit', choices=['mmol', 'mgdl'], help='override GLUCOWATCH_UNIT')
    parser.add_argument('--out', default=str(ROOT / 'data' / 'output' / 'screenshots'))
    args = parser.parse_args()

    try:
        from PIL import Image
    except ImportError:
        sys.exit('Pillow is missing: pip install pillow (or apt install python3-pil)')

    unknown = [s for s in args.scenarios if s not in SCENARIOS]
    if unknown:
        parser.error(f'unknown scenario {", ".join(unknown)}: use {", ".join(SCENARIOS)}')
    env = dotenv()
    wanted = args.scenarios or SCENARIOS
    common = {'prediction': True}
    if args.unit:
        common['unit'] = args.unit
    plans = {}
    for scenario in wanted:
        if scenario == 'demo':
            plans[scenario] = {**common, 'source': 'DEMO', 'predictor': 'linear'}
        elif scenario == 'dexcom':
            if not (env.get('DEXCOM_USERNAME') and env.get('DEXCOM_PASSWORD')):
                log('skipping dexcom: DEXCOM_USERNAME and DEXCOM_PASSWORD are not in .env')
                continue
            # The debug build compiles the Dexcom login in from .env, so it is not passed over adb.
            plans[scenario] = {**common, 'source': 'SHARE', 'predictor': 'linear'}
        elif not env.get('NIGHTSCOUT_URL'):
            log(f'skipping {scenario}: NIGHTSCOUT_URL is not in .env')
        else:
            url = env['NIGHTSCOUT_URL'] if scenario == 'nightscout' else f'http://10.0.2.2:{REPLAY_PORT}'
            plans[scenario] = {**common, 'source': 'NIGHTSCOUT', 'nightscoutUrl': url, 'predictor': 'loop',
                               'nightscoutToken': env.get('NIGHTSCOUT_TOKEN', '') if scenario == 'nightscout' else '',
                               'nightscoutApi': env.get('NIGHTSCOUT_API', 'v1') if scenario == 'nightscout' else 'v1'}
    if not plans:
        sys.exit('nothing to capture')

    out = Path(args.out)
    watches = list(WATCHES) if 'all' in args.watch else list(dict.fromkeys(args.watch))
    # Each run replaces its output: no captures from older runs, older designs or older layouts
    # of this folder stay behind. Only what this script writes goes, in case --out is shared.
    for old in [out / 'raw', *(out / w for w in WATCHES)]:
        if old.exists():
            shutil.rmtree(old)
    for old in out.glob('*.png'):
        old.unlink()

    replay = None
    if 'nightscout-replay' in plans:
        import nightscout_replay
        replay, shift = nightscout_replay.serve(env['NIGHTSCOUT_URL'], REPLAY_PORT, env.get('NIGHTSCOUT_TOKEN', ''))
        log(f'replaying {env["NIGHTSCOUT_URL"]} shifted by {shift / 60000:.0f} min on port {REPLAY_PORT}')

    overviews = []
    build = not args.no_build
    try:
        for watch in watches:
            use_watch(watch)
            log(f'{watch}: {WATCHES[watch]["label"]}')
            overviews.append(capture_watch(watch, plans, out / watch, build, args.keep))
            build = False
        stacked(overviews).save(out / 'overview.png')
        log(f'done: {out}')
    finally:
        if replay:
            replay.shutdown()


def capture_watch(watch, plans, out, build, keep):
    """Every scenario on the booted watch into [out]; returns its overview (a row per scenario)."""
    from PIL import Image
    raw_dir = out / 'raw'
    raw_dir.mkdir(parents=True)
    started = boot()
    try:
        prepare_device()
        build_and_install(build)
        rows = []
        for scenario, extras in plans.items():
            log(f'{watch} {scenario}: configuring')
            configure(extras)
            frames = capture(scenario, raw_dir)
            app = side_by_side([round_frame(Image.open(f)) for f in frames])
            app.save(out / f'{scenario}-app.png')
            row = []
            for shot in ['face', 'face-ambient'] + [f'tile-{tile}' for tile in TILES]:
                image = round_frame(Image.open(raw_dir / f'{scenario}-{shot}.png'))
                image.save(out / f'{scenario}-{shot}.png')
                if shot != 'face-ambient':
                    row.append(image)
            rows.append(side_by_side(row))
            log(f'{watch} {scenario}: saved -face, -face-ambient, {", ".join(f"-tile-{t}" for t in TILES)}, -app ({len(frames)} app frames)')
        overview = stacked(rows)
        overview.save(out / 'overview.png')
        return overview
    finally:
        if started and not keep:
            adb('emu', 'kill', check=False)

if __name__ == '__main__':
    main()
