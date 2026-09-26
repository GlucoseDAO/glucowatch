#!/usr/bin/env python3
"""Capture the phone companion on a Galaxy S22-sized Android emulator.

    uv run scripts/phone_screenshots.py
    uv run scripts/phone_screenshots.py demo --keep
    uv run scripts/phone_screenshots.py --no-build

The debug APK takes source defaults from .env. No credential is sent through adb arguments.
Real-data captures stay under gitignored data/output/screenshots/phone-galaxy-s22/.
"""
import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from screenshots import dotenv, sdk_dir

ROOT = Path(__file__).resolve().parent.parent
SDK = sdk_dir()
ADB = str(SDK / 'platform-tools' / 'adb')
EMULATOR = str(SDK / 'emulator' / 'emulator')
AVDMANAGER = str(SDK / 'cmdline-tools' / 'latest' / 'bin' / 'avdmanager')
IMAGE = 'system-images;android-36;default;x86_64'
AVD = 'glucowatch_phone'
# 5588 is the watch6-classic-47 emulator's port in screenshots.py; keep the two apart.
PORT = 5590
SERIAL = f'emulator-{PORT}'
PKG = 'io.github.antonkulaga.glucowatch.phone'
OUT = ROOT / 'data/output/screenshots/phone-galaxy-s22'
SCENARIOS = ('demo', 'dexcom', 'nightscout')


def adb(*args, check=True, binary=False):
    result = subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True)
    if check and result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace').strip())
    return result.stdout if binary else result.stdout.decode(errors='replace')


def running():
    return f'{SERIAL}\tdevice' in subprocess.run([ADB, 'devices'], capture_output=True, text=True).stdout


def ensure_avd():
    avd_dir = Path.home() / '.android/avd' / f'{AVD}.avd'
    if not avd_dir.is_dir():
        if not (SDK / Path(*IMAGE.split(';'))).is_dir():
            sys.exit(f'Install the Android image: sdkmanager "{IMAGE}"')
        subprocess.run([AVDMANAGER, 'create', 'avd', '-n', AVD, '-k', IMAGE,
                        '-d', 'pixel_7', '--force'], input=b'no\n', check=True, capture_output=True)
    # Galaxy S22: 1080 × 2340. Logical density approximates the phone's 425 ppi panel.
    config = avd_dir / 'config.ini'
    desired = {'hw.lcd.width': '1080', 'hw.lcd.height': '2340', 'hw.lcd.density': '425'}
    lines = [line for line in config.read_text().splitlines()
             if line.split('=')[0].strip() not in desired]
    config.write_text('\n'.join(lines + [f'{k} = {v}' for k, v in desired.items()]) + '\n')


def boot():
    if not running():
        ensure_avd()
        print(f'[phone screenshots] booting {AVD} on {SERIAL}', flush=True)
        subprocess.Popen([EMULATOR, '-avd', AVD, '-port', str(PORT), '-no-window',
                          '-no-audio', '-no-boot-anim', '-no-snapshot'],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    deadline = time.time() + 240
    while time.time() < deadline:
        if running() and adb('shell', 'getprop', 'sys.boot_completed', check=False).strip() == '1':
            break
        time.sleep(2)
    else:
        sys.exit('Phone emulator did not boot within 4 minutes')
    adb('shell', 'wm', 'size', '1080x2340')
    adb('shell', 'wm', 'density', '425')
    # Wait for Android's window manager to finish applying the override before capturing.
    deadline = time.time() + 20
    while time.time() < deadline:
        frame = adb('exec-out', 'screencap', '-p', binary=True)
        from io import BytesIO
        if Image.open(BytesIO(frame)).size == (1080, 2340):
            break
        time.sleep(1)
    else:
        raise RuntimeError('Phone emulator did not apply the Galaxy S22 display size')
    adb('shell', 'settings', 'put', 'system', 'time_12_24', '24', check=False)
    adb('shell', 'dumpsys', 'battery', 'unplug', check=False)
    adb('shell', 'dumpsys', 'battery', 'set', 'level', '80', check=False)


def install(build):
    if build:
        subprocess.run([str(ROOT / 'gradlew'), '-q', '--console=plain', ':phone:assembleDebug'],
                       cwd=ROOT, check=True)
    debug_dir = ROOT / 'phone/build/outputs/apk/debug'
    apk = next((debug_dir / name for name in ('phone-debug.apk', 'glucowatch-phone-debug.apk')
                if (debug_dir / name).is_file()), debug_dir / 'phone-debug.apk')
    if not apk.is_file():
        sys.exit(f'Missing {apk}; build the debug APK first')
    adb('install', '-r', str(apk))
    adb('shell', 'pm', 'clear', PKG)


def fetched_at():
    xml = adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/cache.xml', check=False)
    match = re.search(r'name="fetchedAt" value="(\d+)"', xml)
    return int(match.group(1)) if match else 0


def start_source(scenario):
    source = {'demo': 'DEMO', 'dexcom': 'SHARE', 'nightscout': 'NIGHTSCOUT'}[scenario]
    before = fetched_at()
    adb('shell', 'am', 'start', '-S', '-W', '-n', f'{PKG}/.MainActivity', '--es', 'source', source)
    if scenario != 'demo':
        deadline = time.time() + 50
        while time.time() < deadline and fetched_at() <= before:
            time.sleep(1)
    time.sleep(3)


def select_tab(name):
    adb('shell', 'uiautomator', 'dump', '/sdcard/window.xml')
    tree = ET.fromstring(adb('shell', 'cat', '/sdcard/window.xml'))
    for node in tree.iter('node'):
        if node.attrib.get('text') == name:
            nums = list(map(int, re.findall(r'\d+', node.attrib['bounds'])))
            adb('shell', 'input', 'tap', str((nums[0] + nums[2]) // 2), str((nums[1] + nums[3]) // 2))
            time.sleep(1)
            return
    raise RuntimeError(f'Could not find {name} tab')


def shot(name):
    path = OUT / f'{name}.png'
    path.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    size = Image.open(path).size
    if size != (1080, 2340):
        raise RuntimeError(f'Expected Galaxy S22-size 1080x2340 capture, got {size}')
    print(f'[phone screenshots] {path.relative_to(ROOT)}', flush=True)


def overview(names):
    width = 330
    height = round(2340 * width / 1080)
    gap = 24
    canvas = Image.new('RGB', (gap + len(names) * (width + gap), height + 100), (8, 20, 31))
    draw = ImageDraw.Draw(canvas)
    font_path = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
    font = ImageFont.truetype(font_path, 22)
    for i, name in enumerate(names):
        image = Image.open(OUT / f'{name}.png').convert('RGB').resize((width, height), Image.Resampling.LANCZOS)
        x = gap + i * (width + gap)
        draw.text((x, 25), name.replace('-', ' ').title(), font=font, fill=(230, 237, 240))
        canvas.paste(image, (x, 75))
    canvas.save(OUT / 'overview.png', optimize=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('scenarios', nargs='*', choices=SCENARIOS)
    parser.add_argument('--no-build', action='store_true')
    parser.add_argument('--keep', action='store_true')
    args = parser.parse_args()
    env = dotenv()
    scenarios = args.scenarios or ['demo', 'dexcom', 'nightscout']
    available = []
    for scenario in scenarios:
        if scenario == 'dexcom' and not all(env.get(key) for key in ('DEXCOM_USERNAME', 'DEXCOM_PASSWORD')):
            print('[phone screenshots] skipping Dexcom: username or password is missing')
        elif scenario == 'nightscout' and not env.get('NIGHTSCOUT_URL'):
            print('[phone screenshots] skipping Nightscout: URL is missing')
        else:
            available.append(scenario)
    OUT.mkdir(parents=True, exist_ok=True)
    boot()
    try:
        install(not args.no_build)
        captured = []
        for scenario in available:
            start_source(scenario)
            shot(f'{scenario}-today')
            captured.append(f'{scenario}-today')
            if scenario == 'demo':
                adb('shell', 'input', 'swipe', '540', '1910', '540', '900', '450')
                time.sleep(1)
                shot('demo-today-bottom')
                adb('shell', 'input', 'swipe', '540', '600', '540', '2030', '350')
                adb('shell', 'input', 'swipe', '540', '600', '540', '2030', '350')
                time.sleep(1)
                for tab in ('Connect', 'Model', 'Watch'):
                    select_tab(tab)
                    shot(f'demo-{tab.lower()}')
        overview(captured)
    finally:
        if not args.keep:
            adb('emu', 'kill', check=False)


if __name__ == '__main__':
    main()
