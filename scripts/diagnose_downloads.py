#!/usr/bin/env python3
"""Record an Android browser download without reading CSV contents or changing settings.

    uv run scripts/diagnose_downloads.py record --seconds 120
    uv run scripts/diagnose_downloads.py analyze data/output/download-diagnostics/RUN

Reproduce the download while recording. Raw logcat is private and stays in the
gitignored output directory. The summary contains event names and error codes,
never log messages, URLs, cookies, account names or CSV contents.
"""
import argparse
from contextlib import closing
import hashlib
import json
import re
import subprocess
import sqlite3
import uuid
import time
from datetime import datetime
from pathlib import Path

from emulator_tools import ROOT, sdk_dir, sdk_tool


EVENTS = ('DownloadInProgress', 'DownloadCompleted', 'DownloadFailed')
FIREFOX_STATUS = {1: 'initiated', 2: 'downloading', 3: 'paused', 4: 'cancelled', 5: 'failed', 6: 'completed'}
FIREFOX_CONFIG = '/data/local/tmp/org.mozilla.firefox-geckoview-config.yaml'
FIREFOX_LOG = '/data/user/0/org.mozilla.firefox/files/clarity-network.log'
NETWORK_CONFIG = (f'env:\n  MOZ_LOG: timestamp,sync,rotate:5,nsHttp:3,nsSocketTransport:3\n'
                  f'  MOZ_LOG_FILE: {FIREFOX_LOG}\n').encode()
ERROR_CODE = re.compile(r'\b(?:NS_ERROR_[A-Z0-9_]+|NS_BINDING_[A-Z0-9_]+|0x80[0-9a-fA-F]{6})\b')
ANDROID_FAILURE = re.compile(r'\b(?:ForegroundServiceDidNotStartInTimeException|'
                             r'ForegroundServiceStartNotAllowedException|ForegroundServiceDidNotStopInTimeException|'
                             r'OutOfMemoryError)\b')
STAMP = re.compile(r'^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}')
MEDIA_ROW = re.compile(
    r'_id=(\d+), _display_name=(.*), _size=(\d+|NULL), '
    r'date_modified=(\d+|NULL), is_pending=([01])$')


def download_metadata(output):
    files = []
    for line in output.splitlines():
        match = MEDIA_ROW.search(line)
        if not match or not match[2].lower().endswith('.csv'):
            continue
        files.append(dict(id=match[1],
                          name_hash=hashlib.sha256(match[2].encode()).hexdigest()[:12],
                          bytes=int(match[3]) if match[3] != 'NULL' else None,
                          modified=int(match[4]) if match[4] != 'NULL' else None,
                          pending=match[5] == '1'))
    return files


def summarize(folder):
    events, codes, android_failures = [], set(), set()
    # Only emit allowlisted event names/codes. Exception messages can contain tokens.
    for file in sorted(folder.iterdir()):
        if not file.is_file() or not (file.name.endswith('.log') or '.moz_log' in file.name):
            continue
        with file.open(encoding='utf-8', errors='replace') as stream:
            for line in stream:
                for event in EVENTS:
                    if 'AppAction$' + event in line:
                        stamp = STAMP.match(line)
                        events.append(dict(time=stamp[0] if stamp else None, event=event))
                codes.update(ERROR_CODE.findall(line))
                android_failures.update(ANDROID_FAILURE.findall(line))
    samples_file = folder / 'samples.jsonl'
    latest, changes = {}, []
    if samples_file.exists():
        for line in samples_file.read_text(encoding='utf-8').splitlines():
            sample = json.loads(line)
            for file in sample.get('files', []):
                if latest.get(file['id']) != file:
                    changes.append(dict(time=sample['time'], **file))
                latest[file['id']] = file
    result = dict(events=events, network_error_codes=sorted(codes), android_failures=sorted(android_failures), file_changes=changes,
                  note='A stable or indexed CSV is not proof of success; network codes may belong to background requests.')
    browser = folder / 'firefox-downloads.json'
    if browser.exists():
        result['firefox_downloads'] = json.loads(browser.read_text(encoding='utf-8'))
    capture = folder / 'capture.json'
    if capture.exists():
        result['capture'] = json.loads(capture.read_text(encoding='utf-8'))
    journal = folder / 'app-export.jsonl'
    if journal.exists():
        app_events = []
        for line in journal.read_text(encoding='utf-8', errors='replace').splitlines():
            try:
                data = json.loads(line)
                safe = {key: value for key, value in data.items()
                        if key in ('time', 'bytes') and isinstance(value, int) or
                        key in ('event', 'portal', 'stage', 'errorType', 'fileKey') and
                        isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_]{1,80}', value)}
                app_events.append(safe)
            except (ValueError, AttributeError):
                continue
        result['app_events'] = app_events
    (folder / 'summary.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


def adb_command(serial, *args, check=True):
    return subprocess.run([sdk_tool(sdk_dir(), 'platform-tools/adb'), '-s', serial, *args],
                          capture_output=True, timeout=15, check=check)


def firefox_logging(serial, enabled):
    # These operations are for disposable development emulators, never a user's phone.
    if not re.fullmatch(r'emulator-\d+', serial):
        raise RuntimeError('Firefox network logging is supported only on an Android emulator.')
    marker = ROOT / f'data/output/download-diagnostics/firefox-{serial}.json'
    if enabled:
        if marker.exists():
            raise RuntimeError('Logging is already enabled. Use firefox-logging off first.')
        if adb_command(serial, 'shell', 'test', '-e', FIREFOX_CONFIG, check=False).returncode == 0:
            raise RuntimeError('An existing Gecko debug configuration must be preserved; disable it separately first.')
        previous = adb_command(serial, 'shell', 'settings', 'get', 'global', 'debug_app').stdout.decode().strip()
        if previous not in ('null', ''):
            raise RuntimeError('Another Android debug app is configured; leaving it unchanged.')
        marker.parent.mkdir(parents=True, exist_ok=True)
        config = marker.with_suffix('.yaml')
        config.write_bytes(NETWORK_CONFIG)
        # Save recovery information before changing the emulator.
        marker.write_text(json.dumps(dict(serial=serial, config_hash=hashlib.sha256(NETWORK_CONFIG).hexdigest())),
                          encoding='utf-8')
        adb_command(serial, 'push', str(config), FIREFOX_CONFIG)
        adb_command(serial, 'shell', 'sync')
        actual = adb_command(serial, 'exec-out', 'cat', FIREFOX_CONFIG).stdout
        if actual != NETWORK_CONFIG:
            raise RuntimeError('Gecko configuration verification failed. Use firefox-logging off to clean up.')
        adb_command(serial, 'shell', 'am', 'set-debug-app', '--persistent', 'org.mozilla.firefox')
        adb_command(serial, 'shell', 'am', 'force-stop', 'org.mozilla.firefox')
        print('[diagnostics] Network logging enabled. Open Firefox and reproduce the download.')
    else:
        if not marker.exists():
            raise RuntimeError('No diagnostic logging state found; leaving emulator settings unchanged.')
        state = json.loads(marker.read_text(encoding='utf-8'))
        actual = adb_command(serial, 'exec-out', 'cat', FIREFOX_CONFIG, check=False)
        if actual.returncode == 0:
            if hashlib.sha256(actual.stdout).hexdigest() != state['config_hash']:
                raise RuntimeError('Gecko configuration changed; leaving it unchanged.')
            adb_command(serial, 'shell', 'rm', FIREFOX_CONFIG)
        current = adb_command(serial, 'shell', 'settings', 'get', 'global', 'debug_app').stdout.decode().strip()
        if current == 'org.mozilla.firefox':
            adb_command(serial, 'shell', 'am', 'clear-debug-app')
        adb_command(serial, 'shell', 'am', 'force-stop', 'org.mozilla.firefox')
        adb_command(serial, 'shell', 'sync')
        marker.unlink()
        marker.with_suffix('.yaml').unlink(missing_ok=True)
        print('[diagnostics] Network logging disabled. Firefox is stopped; saved browser data is retained.')


def collect_firefox_logs(serial, folder):
    if not re.fullmatch(r'emulator-\d+', serial):
        return
    # The AOSP emulator's su avoids restarting adbd or enabling persistent root access.
    listing = adb_command(serial, 'shell', 'su', '0', 'find',
                          '/data/user/0/org.mozilla.firefox/files', '-maxdepth', '1',
                          '-name', 'clarity-network.log*', check=False)
    if listing.returncode:
        return
    for remote in listing.stdout.decode(errors='replace').splitlines():
        if re.fullmatch(re.escape(FIREFOX_LOG) + r'[.a-zA-Z0-9_-]*', remote):
            log = adb_command(serial, 'exec-out', 'su', '0', 'cat', remote, check=False)
            if log.returncode == 0:
                (folder / Path(remote).name).write_bytes(log.stdout)
    # A release Firefox build may omit lifecycle logcat events. Its local download
    # database still records failed/completed status. Never retain URLs or names.
    remote_db = '/data/user/0/org.mozilla.firefox/databases/mozac_downloads_database'
    directory = folder / ('firefox-state-' + uuid.uuid4().hex)
    directory.mkdir()
    local = directory / 'downloads.sqlite'
    try:
        for suffix in ('', '-wal'):
            part = adb_command(serial, 'exec-out', 'su', '0', 'cat', remote_db + suffix, check=False)
            if part.returncode == 0:
                Path(str(local) + suffix).write_bytes(part.stdout)
        if local.exists():
            try:
                with closing(sqlite3.connect(local)) as database:
                    rows = database.execute('SELECT file_name, status, content_length, created_at FROM downloads '
                                            "WHERE file_name LIKE '%.csv' ORDER BY created_at DESC LIMIT 20").fetchall()
                data = [dict(name_hash=hashlib.sha256(name.encode()).hexdigest()[:12], status_code=status,
                             status=FIREFOX_STATUS.get(status, 'unknown'),
                             expected_bytes=size, created_at=created) for name, status, size, created in rows]
                (folder / 'firefox-downloads.json').write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
            except sqlite3.Error:
                # A live writer can make a database/WAL copy inconsistent. Do not
                # report guessed state or stop the recording when that happens.
                pass
    finally:
        for suffix in ('', '-wal', '-shm', '-journal'):
            Path(str(local) + suffix).unlink(missing_ok=True)
        directory.rmdir()


def record(serial, seconds):
    adb = sdk_tool(sdk_dir(), 'platform-tools/adb')
    folder = ROOT / 'data/output/download-diagnostics' / datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    folder.mkdir(parents=True)

    def device(*args):
        return subprocess.run([adb, '-s', serial, *args], capture_output=True,
                              text=True, timeout=10, check=True).stdout

    device('get-state')
    print(f'[diagnostics] Recording for {seconds}s. Reproduce the download now.', flush=True)
    print(f'[diagnostics] Private output: {folder}', flush=True)
    # -T 1 starts at the newest entry without clearing anybody else's log buffer.
    with (folder / 'android.log').open('wb') as log, (folder / 'samples.jsonl').open('w', encoding='utf-8') as samples:
        process = subprocess.Popen([adb, '-s', serial, 'logcat', '-v', 'threadtime', '-T', '1'],
                                   stdout=log, stderr=subprocess.STDOUT)
        stopped = 'duration_elapsed'
        try:
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                sample = dict(time=datetime.now().astimezone().isoformat())
                try:
                    output = device('shell', 'content', 'query', '--uri', 'content://media/external/downloads',
                                    '--projection', '_id:_display_name:_size:date_modified:is_pending')
                    sample['files'] = download_metadata(output)
                    if output.lstrip().startswith(('Error', 'Exception')):
                        sample['metadata_error'] = 'provider_error'
                except (subprocess.SubprocessError, OSError) as error:
                    sample['metadata_error'] = type(error).__name__
                samples.write(json.dumps(sample) + '\n'); samples.flush()
                if process.poll() is not None:
                    stopped = 'logcat_disconnected'
                    break
                time.sleep(min(1, max(0, deadline - time.monotonic())))
        except KeyboardInterrupt:
            stopped = 'interrupted'
            print('[diagnostics] Stopping recording.', flush=True)
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
    (folder / 'capture.json').write_text(json.dumps(dict(stopped=stopped)) + '\n', encoding='utf-8')
    try:
        collect_firefox_logs(serial, folder)
    except (subprocess.SubprocessError, OSError):
        print('[diagnostics] Firefox private logs unavailable; Android log and file samples retained.')
    try:
        journal = adb_command(serial, 'exec-out', 'run-as', 'io.github.antonkulaga.glucowatch.phone',
                              'cat', 'files/export-diagnostics.jsonl', check=False)
        if journal.returncode == 0:
            (folder / 'app-export.jsonl').write_bytes(journal.stdout)
    except (subprocess.SubprocessError, OSError):
        print('[diagnostics] App journal unavailable; recorded evidence retained.')
    report = summarize(folder)
    for event in report['events']:
        print(f"[diagnostics] {event['time']} {event['event']}")
    print(f'[diagnostics] Summary: {folder / "summary.json"}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    capture = commands.add_parser('record')
    capture.add_argument('--serial', default='emulator-5554')
    capture.add_argument('--seconds', type=int, default=120)
    analyze = commands.add_parser('analyze')
    analyze.add_argument('folder', type=Path)
    network = commands.add_parser('firefox-logging')
    network.add_argument('state', choices=('on', 'off'))
    network.add_argument('--serial', default='emulator-5554')
    args = parser.parse_args()
    if args.command == 'record':
        if not 1 <= args.seconds <= 3600:
            parser.error('--seconds must be between 1 and 3600')
        record(args.serial, args.seconds)
    elif args.command == 'analyze':
        print(json.dumps(summarize(args.folder), indent=2))
    else:
        firefox_logging(args.serial, args.state == 'on')


if __name__ == '__main__':
    main()
