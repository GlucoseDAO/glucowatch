#!/usr/bin/env python3
"""Ensure the test phone has real Google Chrome; keep every downloaded binary out of Git.

Chrome is extracted from Google's checksum-verified API 36 x86_64 emulator image.
No operating-system or app dependency is changed. The APK cache survives AVD resets.
The first provisioning downloads about 1.9 GB; subsequent runs use the APK cache.
"""
import argparse
import gzip
import hashlib
import shutil
import struct
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

from emulator_tools import ROOT, sdk_dir, sdk_tool

CACHE = ROOT / 'data/output/testing/browser'
CATALOG = 'https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-1.xml'
IMAGE = 'system-images;android-36;google_apis;x86_64'
CHROME = CACHE / 'chrome-google-x86_64.apk'
LIBRARY = CACHE / 'trichrome-google-x86_64.apk'


def extract():
    # Test tooling only. uv caches this open-source filesystem reader outside the app build.
    try:
        from dissect.extfs import ExtFS
        from dissect.util.stream import RangeStream
    except ImportError:
        subprocess.run(['uv', 'run', '--with', 'dissect.extfs', 'python', str(__file__), '--extract-only'],
                       cwd=ROOT, check=True)
        return
    CACHE.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(CATALOG, timeout=60) as response:
        catalog = ET.fromstring(response.read())
    package = next(p for p in catalog if p.attrib.get('path') == IMAGE)
    expected = next(e.text for e in package.iter() if e.tag == 'checksum')
    remote = next(e.text for e in package.iter() if e.tag == 'url')
    archive = CACHE / 'google-apis-36.zip'

    def checksum(path):
        digest = hashlib.sha1()
        with path.open('rb') as source:
            for block in iter(lambda: source.read(4 * 1024 * 1024), b''):
                digest.update(block)
        return digest.hexdigest()

    if not archive.is_file() or checksum(archive) != expected:
        print('[browser] Downloading official Google image (~1.9 GB) for Chrome.', flush=True)
        partial = archive.with_suffix('.partial')
        try:
            with urllib.request.urlopen(CATALOG.rsplit('/', 1)[0] + '/' + remote, timeout=60) as source, partial.open('wb') as output:
                total = 0
                last = time.monotonic()
                while block := source.read(1024 * 1024):
                    output.write(block)
                    total += len(block)
                    if time.monotonic() - last >= 25:
                        print(f'[browser] {total // 1048576} MiB downloaded', flush=True)
                        last = time.monotonic()
            if checksum(partial) != expected:
                raise RuntimeError('Google image checksum mismatch')
            partial.replace(archive)
        finally:
            partial.unlink(missing_ok=True)
    print('[browser] Official image checksum verified.', flush=True)
    image = CACHE / 'google-system.img'
    try:
        with zipfile.ZipFile(archive) as bundle, bundle.open('x86_64/system.img') as source, image.open('wb') as output:
            shutil.copyfileobj(source, output, 4 * 1024 * 1024)
        with image.open('rb') as source:
            # GPT wraps an Android dynamic-partition super image. Read its logical product extent.
            source.seek(512)
            header = source.read(512)
            if header[:8] != b'EFI PART':
                raise RuntimeError('Unexpected Google image partition format')
            entry_lba = struct.unpack_from('<Q', header, 72)[0]
            count, size = struct.unpack_from('<II', header, 80)
            source.seek(entry_lba * 512)
            entries = source.read(count * size)
            super_start = None
            for index in range(count):
                entry = entries[index * size:(index + 1) * size]
                if entry[56:128].decode('utf-16-le').rstrip('\0') == 'super':
                    super_start = struct.unpack_from('<Q', entry, 32)[0] * 512
                    break
            if super_start is None:
                raise RuntimeError('No super partition in Google image')
            source.seek(super_start + 12288)
            header = source.read(128)
            if header[:4] != b'0PLA':
                raise RuntimeError('Unexpected Android logical partition header')
            header_size = struct.unpack_from('<I', header, 8)[0]
            table_size = struct.unpack_from('<I', header, 44)[0]
            source.seek(super_start + 12288 + header_size)
            table = source.read(table_size)
            part_offset, part_count, part_size = struct.unpack_from('<III', header, 80)
            extent_offset, _, extent_size = struct.unpack_from('<III', header, 92)
            filesystem = None
            for index in range(part_count):
                partition = table[part_offset + index * part_size:part_offset + (index + 1) * part_size]
                if partition[:36].rstrip(b'\0') != b'product':
                    continue
                _, first, count, _ = struct.unpack_from('<IIII', partition, 36)
                if count != 1:
                    raise RuntimeError('Google product image has multiple extents; extractor needs updating')
                sectors, kind, start, device = struct.unpack_from('<QIQI', table, extent_offset + first * extent_size)
                if kind != 0 or device != 0:
                    raise RuntimeError('Unexpected product extent type')
                filesystem = ExtFS(RangeStream(source, super_start + start * 512, sectors * 512))
                break
            if filesystem is None:
                raise RuntimeError('No product partition in Google image')
            for directory, destination in (('Chrome', CHROME), ('TrichromeLibrary', LIBRARY)):
                node = filesystem.get(f'/app/{directory}/{directory}.apk.gz')
                partial = destination.with_suffix('.partial')
                try:
                    with node.open() as compressed, gzip.GzipFile(fileobj=compressed) as apk, partial.open('wb') as output:
                        shutil.copyfileobj(apk, output)
                    partial.replace(destination)
                finally:
                    partial.unlink(missing_ok=True)
    finally:
        # Only these named, task-owned cache files are removed. Preserve the reusable APKs.
        image.unlink(missing_ok=True)
    print('[browser] Chrome APKs extracted into the ignored test cache.', flush=True)


def ensure_chrome(adb, serial):
    def device(*args, timeout=120, check=True):
        return subprocess.run([adb, '-s', serial, *args], capture_output=True, text=True,
                              check=check, timeout=timeout).stdout.strip()
    installed = device('shell', 'pm', 'path', 'com.android.chrome', check=False)
    if not installed:
        if not CHROME.is_file() or not LIBRARY.is_file():
            extract()
        for apk in (LIBRARY, CHROME):
            device('install', '-r', str(apk))
        if not device('shell', 'pm', 'path', 'com.android.chrome'):
            raise RuntimeError('Chrome installation did not complete')
        print('[browser] Google Chrome installed.', flush=True)
    else:
        print('[browser] Google Chrome is installed.', flush=True)
    device('shell', 'pm', 'enable', '--user', '0', 'com.android.chrome')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--extract-only', action='store_true')
    args = parser.parse_args()
    if args.extract_only:
        extract()
    else:
        ensure_chrome(sdk_tool(sdk_dir(), 'platform-tools/adb'), args.serial)
