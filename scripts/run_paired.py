#!/usr/bin/env python3
"""Open the phone and watch emulators bonded over Bluetooth, with GlucoWatch paired to GlucoPhone.

    uv run scripts/run_paired.py               # build, start or reuse both, bond and pair them
    uv run scripts/run_paired.py --no-build    # reuse the debug APKs
    uv run scripts/run_paired.py --demo        # the phone shows demo data and relays it to the watch
    uv run scripts/run_paired.py --repair      # pair the apps again even when they already are

Both emulators share the SDK's netsim Bluetooth radio, so the watch AVD bonds with the phone AVD
the way a watch bonds with a phone, and GlucoWatch reaches GlucoPhone over RFCOMM. The bond stays
in the AVDs' data and the pairing in the apps' data, so later runs start both and check that a
sync gets through. The script confirms each pairing code on both screens only after checking that
they match. Checked with the glucowatch_phone (API 36) and glucowatch_gw6c (Wear OS 6) AVDs on
emulator 37.1. See docs/phone-link.md.
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

from emulator_tools import sdk_dir, sdk_tool

SCRIPTS = Path(__file__).resolve().parent
ADB = sdk_tool(sdk_dir(), 'platform-tools/adb')
PHONE, WATCH = 'emulator-5554', 'emulator-5580'
PHONE_PKG = 'io.github.antonkulaga.glucowatch.phone'
WATCH_PKG = 'io.github.antonkulaga.glucowatch'


def log(message):
    print(f'[paired] {message}', flush=True)


def adb(serial, *args, check=True, timeout=60):
    result = subprocess.run([ADB, '-s', serial, *args], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'adb -s {serial} {" ".join(args)} failed: {result.stderr.strip()}')
    return result.stdout


def screen(serial):
    """The labelled elements on screen as (label, x, y)."""
    xml = adb(serial, 'exec-out', 'uiautomator', 'dump', '/dev/tty', check=False)
    found = []
    for node in re.findall(r'<node [^>]*>', xml):
        attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', node))
        label = (attrs.get('text') or attrs.get('content-desc') or '').replace('&#10;', '\n').replace('&amp;', '&')
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', attrs.get('bounds', '[0,0][0,0]')))
        if label:
            found.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
    return found


def wait_for(serial, text, timeout=30, exact=False):
    deadline = time.monotonic() + timeout
    while True:
        labels = screen(serial)
        for label, x, y in labels:
            if label.lower() == text.lower() if exact else text.lower() in label.lower():
                return label, x, y
        if time.monotonic() >= deadline:
            raise RuntimeError(f'{serial}: {text!r} did not appear; on screen: {[label for label, _, _ in labels]}')
        time.sleep(1)


def tap(serial, text, timeout=30, exact=False):
    label, x, y = wait_for(serial, text, timeout, exact)
    adb(serial, 'shell', 'input', 'tap', str(x), str(y))
    return label


def code_on(serial, pattern, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for label, _, _ in screen(serial):
            match = re.search(pattern, label)
            if match:
                return match.group(0)
        time.sleep(1)
    raise RuntimeError(f'{serial}: no pairing code appeared')


def confirm_codes(pattern, phone_button, watch_button, exact):
    """Both devices must show the same code before either is confirmed."""
    phone_code, watch_code = code_on(PHONE, pattern), code_on(WATCH, pattern)
    if phone_code != watch_code:
        raise RuntimeError(f'The codes differ (phone {phone_code}, watch {watch_code}); not confirming')
    log(f'  both show {phone_code}; confirming')
    tap(PHONE, phone_button, exact=exact)
    tap(WATCH, watch_button, exact=exact)


def scroll_to(serial, texts, swipes=12):
    """Swipes up until one of [texts] is on screen and taps it."""
    width, height = map(int, re.findall(r'\d+', adb(serial, 'shell', 'wm', 'size').splitlines()[-1])[-2:])
    for _ in range(swipes):
        for label, x, y in screen(serial):
            if label in texts:
                adb(serial, 'shell', 'input', 'tap', str(x), str(y))
                return label
        adb(serial, 'shell', 'input', 'swipe', str(width // 2), str(height * 85 // 100),
            str(width // 2), str(height * 30 // 100), '300')
    raise RuntimeError(f'{serial}: none of {texts} found')


def bonded_names(serial):
    dump = adb(serial, 'shell', 'dumpsys', 'bluetooth_manager')
    if 'Bonded devices:' not in dump:
        return set()
    section = dump.split('Bonded devices:', 1)[1].split('\n\n', 1)[0]
    return {line.rsplit(']', 1)[-1].strip() for line in section.splitlines() if ']' in line}


def tap_while_scanning(serial, name, timeout=90):
    """Taps [name] once a scan lists it, restarting the watch's scan whenever it stops."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        labels = screen(serial)
        for label, x, y in labels:
            if label == name or label == 'Scan for nearby devices':
                adb(serial, 'shell', 'input', 'tap', str(x), str(y))
                if label == name:
                    return
                break
        time.sleep(1)
    raise RuntimeError(f'{serial}: {name!r} was not found by the Bluetooth scan')


def watch_avd():
    return adb(WATCH, 'emu', 'avd', 'name', check=False).splitlines()[0].strip() or 'glucowatch_gw6c'


def bond():
    phone_name = adb(PHONE, 'shell', 'settings', 'get', 'secure', 'bluetooth_name').strip()
    watch_name = adb(WATCH, 'shell', 'settings', 'get', 'secure', 'bluetooth_name').strip()
    phone_has, watch_has = watch_name in bonded_names(PHONE), phone_name in bonded_names(WATCH)
    if phone_has and watch_has:
        log(f'Bluetooth: {watch_name} and {phone_name} are already bonded')
        return
    if watch_has:
        # The phone forgot the watch, but the watch keeps the old key: it leaves the phone out of
        # its scan and fails a pairing the phone starts ("incorrect PIN or passkey"). Wear OS has no
        # Forget for its phone, and a user build has no root, so only a reset of the watch clears it.
        raise RuntimeError(f'{phone_name} forgot {watch_name}, but the watch still holds the old bond. '
                           'Reset the watch emulator once, then run this again: close it and start '
                           f'"emulator -avd {watch_avd()} -wipe-data", or Settings → System → Reset on the watch.')
    log(f'Bluetooth: bonding {watch_name} with {phone_name}')
    # The phone's pairing screen makes it discoverable; the watch scans from its own.
    adb(PHONE, 'shell', 'am', 'start', '-W', '-a', 'android.settings.BLUETOOTH_PAIRING_SETTINGS')
    adb(WATCH, 'shell', 'am', 'start', '-W', '-a', 'android.settings.BLUETOOTH_SETTINGS')
    tap(WATCH, 'Pair new device')
    tap_while_scanning(WATCH, phone_name)
    confirm_codes(r'\b\d{6}\b', 'Pair', 'OK', exact=True)
    deadline = time.monotonic() + 30
    while not (watch_name in bonded_names(PHONE) and phone_name in bonded_names(WATCH)):
        if time.monotonic() >= deadline:
            raise RuntimeError('The emulators did not bond; see Bluetooth in both Settings')
        time.sleep(1)
    for serial in (PHONE, WATCH):
        adb(serial, 'shell', 'input', 'keyevent', 'KEYCODE_HOME')


def apps_paired():
    """Each side holds a pairing. Neither file is printed: they contain the pairing keys."""
    watch = adb(WATCH, 'shell', 'run-as', WATCH_PKG, 'cat', 'shared_prefs/settings.xml', check=False)
    phone = adb(PHONE, 'shell', 'run-as', PHONE_PKG, 'cat', 'shared_prefs/watches.xml', check=False)
    return bool(re.search(r'name="phoneId">[^<]+<', watch)) and '<string name=' in phone


def pair_apps():
    log('GlucoWatch: pairing the watch app with GlucoPhone')
    for serial, package in ((PHONE, PHONE_PKG), (WATCH, WATCH_PKG)):
        for permission in ('BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS'):
            adb(serial, 'shell', 'pm', 'grant', package, f'android.permission.{permission}', check=False)
    adb(PHONE, 'shell', 'am', 'start', '-W', '-n', f'{PHONE_PKG}/.MainActivity')
    tap(PHONE, 'Watch', exact=True)
    tap(PHONE, 'Pair a watch', exact=True)
    wait_for(PHONE, 'Waiting for watch')
    # With the Phone app source selected, Settings shows Pair with phone.
    adb(WATCH, 'shell', 'am', 'start', '-S', '-W', '--activity-clear-task', '-n',
        f'{WATCH_PKG}/.ui.SettingsActivity', '--es', 'source', 'PHONE')
    scroll_to(WATCH, ('Pair with phone', 'Pair again'))
    confirm_codes(r'\b\d{3} \d{3}\b', 'Codes match', 'Codes match', exact=False)
    wait_for(WATCH, 'Paired with')


def fetched_at():
    xml = adb(WATCH, 'shell', 'run-as', WATCH_PKG, 'cat', 'shared_prefs/cache.xml', check=False)
    match = re.search(r'name="fetchedAt" value="(\d+)"', xml)
    return int(match.group(1)) if match else 0


def synced(timeout=60):
    """Saves Phone app as the watch's source, which fetches from the phone at once."""
    before = fetched_at()
    adb(WATCH, 'shell', 'am', 'start', '-S', '-W', '--activity-clear-task', '-n',
        f'{WATCH_PKG}/.ui.SettingsActivity', '--es', 'source', 'PHONE', '--ez', 'save', 'true')
    deadline = time.monotonic() + timeout
    while fetched_at() <= before:
        if time.monotonic() >= deadline:
            return False
        time.sleep(1)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--no-build', action='store_true', help='reuse existing debug APKs')
    parser.add_argument('--demo', action='store_true', help='select demo data on the phone; the watch shows the same')
    parser.add_argument('--repair', action='store_true', help='pair the apps again even when they already are')
    args = parser.parse_args()
    for target in ('phone', 'watch'):
        flags = (['--no-build'] if args.no_build else []) + (['--demo'] if args.demo and target == 'phone' else [])
        subprocess.run([sys.executable, str(SCRIPTS / 'run_emulator.py'), target, *flags], check=True)
    bond()
    paired_now = args.repair or not apps_paired()
    if paired_now:
        pair_apps()
    else:
        log('GlucoWatch is already paired with GlucoPhone')
    if not synced():
        if paired_now:
            raise RuntimeError('The watch did not sync from the phone; see Settings → Phone on the watch')
        log('The stored pairing did not sync; pairing again')
        pair_apps()
        if not synced():
            raise RuntimeError('The watch did not sync from the phone; see Settings → Phone on the watch')
    log('The watch synced from the phone')
    adb(PHONE, 'shell', 'am', 'start', '-W', '-n', f'{PHONE_PKG}/.MainActivity')
    adb(WATCH, 'shell', 'am', 'start', '-W', '-n', f'{WATCH_PKG}/.ui.MainActivity')
    log('Ready. Rerun this command after editing; the bond and the pairing are kept.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        sys.exit(f'[paired] {error}')
