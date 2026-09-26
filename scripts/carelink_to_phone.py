#!/usr/bin/env python3
"""Move a desktop CareLink sign-in into a debug phone app, without tokens in adb arguments.

    uv run scripts/carelink_to_phone.py --serial emulator-5590

The default combines Dexcom Share glucose (.env debug defaults) with CareLink insulin.
Use --source CARELINK --also '' for CareLink alone. After the app confirms the import,
the desktop token is deleted: only the phone may refresh that session thereafter.
"""
import argparse
import json
import shlex
import subprocess
import time
from pathlib import Path

from screenshots import sdk_dir

PKG = 'io.github.antonkulaga.glucowatch.phone'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5590')
    parser.add_argument('--token', type=Path, default=Path.home() / '.config/glucowatch/carelink-token.json')
    parser.add_argument('--source', choices=['SHARE', 'NIGHTSCOUT', 'CARELINK'], default='SHARE')
    parser.add_argument('--also', default='CARELINK')
    args = parser.parse_args()
    payload = args.token.read_bytes()
    token = json.loads(payload)
    if not all(token.get(key) for key in ('country', 'client_id', 'access_token', 'refresh_token')):
        raise SystemExit('The CareLink token file is incomplete; sign in again.')
    if any(name not in ('', 'CARELINK', 'NIGHTSCOUT') for name in args.also.split(',')):
        raise SystemExit('--also accepts CARELINK and/or NIGHTSCOUT')
    adb = [str(sdk_dir() / 'platform-tools/adb'), '-s', args.serial]

    def call(*parts, **kwargs):
        result = subprocess.run(adb + list(parts), capture_output=True, **kwargs)
        if result.returncode:
            raise SystemExit('adb could not access the debug phone app. Install a debug build and unlock the emulator.')
        return result.stdout

    call('shell', 'am', 'force-stop', PKG)
    call('shell', 'run-as', PKG, 'mkdir', '-p', 'files')
    command = 'umask 077; cat > files/carelink-import.json'
    call('shell', f'run-as {PKG} sh -c {shlex.quote(command)}', input=payload)
    call('shell', 'am', 'start', '-W', '-n', f'{PKG}/.MainActivity', '--ez', 'importCareLink', 'true',
         '--es', 'source', args.source, '--es', 'also', shlex.quote(args.also))
    check = 'test -s shared_prefs/carelink.xml && test ! -e files/carelink-import.json'
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        result = subprocess.run(adb + ['shell', f'run-as {PKG} sh -c {shlex.quote(check)}'], capture_output=True)
        if result.returncode == 0:
            args.token.unlink()
            print('CareLink sign-in moved to the phone. The desktop copy was removed. The combined chart is open.')
            return
        time.sleep(0.5)
    raise SystemExit('The phone did not confirm importing the sign-in; the desktop token was kept. Do not use both copies.')


if __name__ == '__main__':
    main()
