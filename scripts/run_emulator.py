#!/usr/bin/env python3
"""Build and open GlucoWatch for interactive testing, preserving app settings.

    uv run scripts/run_emulator.py phone
    uv run scripts/run_emulator.py watch
    uv run scripts/run_emulator.py phone --no-build --demo
    uv run scripts/run_emulator.py watch --dry-run

Windows uses software graphics, two virtual CPU cores and a cold boot. This
does not change Android Studio, the installed SDK, or the saved AVD configuration.
"""
import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

from emulator_tools import ROOT, emulator_command, sdk_dir, sdk_tool, start_emulator, wait_until_closed
from install_phone_browser import ensure_chrome

PACKAGES = {
    'phone': 'io.github.antonkulaga.glucowatch.phone',
    'watch': 'io.github.antonkulaga.glucowatch',
}


def avd_directory():
    if os.environ.get('ANDROID_AVD_HOME'):
        return Path(os.environ['ANDROID_AVD_HOME'])
    return Path(os.environ.get('ANDROID_USER_HOME', str(Path.home() / '.android'))) / 'avd'


def choose_avd(target, requested):
    directory = avd_directory()
    available = [path.stem for path in directory.glob('*.ini')]
    if requested:
        if requested not in available:
            raise RuntimeError(f'AVD {requested!r} not found. Available: {", ".join(available)}')
        return requested
    preferred = ('glucowatch_phone',) if target == 'phone' else ('Wear_OS_Large_Round', 'glucowatch_gw6c')
    for name in preferred:
        if name in available:
            return name
    raise RuntimeError('No default AVD found. Create one in Android Studio, then pass --avd NAME. '
                       f'Available: {", ".join(available)}')


def apk(module):
    folder = ROOT / module / 'build/outputs/apk/debug'
    for name in (f'{module}-debug.apk', f'glucowatch-{module}-debug.apk'):
        if (folder / name).is_file():
            return folder / name
    raise RuntimeError(f'Missing debug APK in {folder}. Run again without --no-build.')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('target', choices=PACKAGES)
    parser.add_argument('--avd', help='existing Android Studio AVD name')
    parser.add_argument('--no-build', action='store_true', help='reuse existing debug APKs')
    parser.add_argument('--demo', action='store_true', help='select demo data; does not erase app settings')
    parser.add_argument('--dry-run', action='store_true', help='print the launch command without building or starting anything')
    parser.add_argument('--headless', action='store_true', help='omit the window for a diagnostic boot')
    args = parser.parse_args()
    sdk = sdk_dir()
    avd = choose_avd(args.target, args.avd)
    port = 5554 if args.target == 'phone' else 5580
    serial = f'emulator-{port}'
    command = emulator_command(sdk, avd, port, headless=args.headless)
    print(f'[run] {subprocess.list2cmdline(command)}', flush=True)
    if args.dry_run:
        return
    adb = sdk_tool(sdk, 'platform-tools/adb')
    for tool in (adb, command[0]):
        if not Path(tool).is_file():
            raise RuntimeError(f'Missing SDK tool: {tool}')

    def device(*parts, timeout=30):
        return subprocess.run([adb, '-s', serial, *parts], capture_output=True,
                              text=True, timeout=timeout, check=True).stdout.strip()

    modules = ['phone'] if args.target == 'phone' else ['app', 'watchface']
    if not args.no_build:
        # Build before booting the VM and limit local build concurrency.
        wrapper = ROOT / ('gradlew.bat' if sys.platform == 'win32' else 'gradlew')
        subprocess.run([str(wrapper), '--console=plain', '--max-workers=2',
                        *[f':{module}:assembleDebug' for module in modules]], cwd=ROOT, check=True)
    apks = [apk(module) for module in modules]
    devices = subprocess.run([adb, 'devices'], capture_output=True, text=True, timeout=15, check=True).stdout
    process = None
    log_path = None
    ready = f'{serial}\tdevice' in devices
    if ready:
        name = device('emu', 'avd', 'name').splitlines()[0]
        if name != avd:
            raise RuntimeError(f'{serial} belongs to {name}, not {avd}; close it before switching devices')
        if not args.headless:
            print('[run] Reusing the running emulator; if it is headless, close it and rerun to get a window.', flush=True)
    else:
        process, log_path = start_emulator(sdk, avd, port, headless=args.headless)
    try:
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            if process is not None and process.poll() is not None:
                raise RuntimeError(f'Emulator exited with code {process.returncode}; see {log_path}')
            try:
                if device('shell', 'getprop', 'sys.boot_completed', timeout=10) == '1':
                    break
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                pass
            time.sleep(2)
        else:
            raise RuntimeError(f'Emulator did not boot within 4 minutes; see {log_path}')
        if args.target == 'phone':
            ensure_chrome(adb, serial)
        for path in apks:
            device('install', '-r', str(path), timeout=120)
        package = PACKAGES[args.target]
        if args.target == 'watch':
            device('shell', 'am', 'broadcast', '-a', 'com.google.android.wearable.app.DEBUG_SURFACE',
                   '--es', 'operation', 'set-watchface', '--es', 'watchFaceId', package + '.watchface')
            activity = package + '/.ui.MainActivity'
            if args.demo:
                device('shell', 'am', 'start', '-W', '-n', package + '/.ui.SettingsActivity',
                       '--es', 'source', 'DEMO', '--ez', 'save', 'true')
        else:
            activity = package + '/.MainActivity'
        parts = ['shell', 'am', 'start', '-S', '-W', '-n', activity]
        if args.demo and args.target == 'phone':
            parts += ['--es', 'source', 'DEMO']
        device(*parts)
    except BaseException:
        # Only stop the VM this invocation started, never somebody else's device.
        if process is not None and process.poll() is None:
            try:
                device('emu', 'kill', timeout=10)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                process.terminate()
            wait_until_closed(port)
        raise
    print(f'[run] App ready on {serial}. Leave the window open and rerun this command after editing.', flush=True)
    print(f'[run] Close the emulator window to stop it, or run: "{adb}" -s {serial} emu kill', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        sys.exit(f'[run] {error}')
