"""Shared SDK paths and conservative emulator launches for developer tools."""
import os
import ipaddress
import socket
import subprocess
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def sdk_dir():
    for name in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if os.environ.get(name):
            return Path(os.environ[name])
    props = ROOT / 'local.properties'
    if props.is_file():
        for line in props.read_text().splitlines():
            if line.startswith('sdk.dir='):
                # Gradle's Java properties escape both drive colons and backslashes.
                value = line.split('=', 1)[1].strip().replace('\\:', ':').replace('\\\\', '\\')
                return Path(value)
    if sys.platform == 'win32' and os.environ.get('LOCALAPPDATA'):
        sdk = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk'
        if sdk.is_dir():
            return sdk
    raise RuntimeError('Android SDK not found: set ANDROID_HOME or sdk.dir in local.properties')


def sdk_tool(sdk, relative):
    path = Path(sdk) / relative
    if sys.platform == 'win32':
        path = path.with_suffix('.bat' if path.name in ('avdmanager', 'sdkmanager') else '.exe')
    return str(path)


def emulator_dns():
    """Optional local DNS override; automatic SDK DNS remains the default."""
    value = os.environ.get('GLUCOWATCH_EMULATOR_DNS')
    props = ROOT / 'local.properties'
    if value is None and props.is_file():
        for line in props.read_text().splitlines():
            if line.startswith('glucowatch.emulator.dns='):
                value = line.split('=', 1)[1]
                break
    if not value or not value.strip():
        return None
    servers = [item.strip() for item in value.split(',')]
    if not 1 <= len(servers) <= 4:
        raise ValueError('Emulator DNS needs one to four comma-separated IP addresses')
    try:
        return ','.join(str(ipaddress.ip_address(item)) for item in servers)
    except ValueError as error:
        raise ValueError('Emulator DNS must contain IP addresses') from error


def emulator_command(sdk, avd, port, *, headless=True, disable_guest_angle=False):
    # Keep all devices within adb's traditional auto-discovery range, including
    # the last device's adb port (console + 1).
    if port % 2 or not 5554 <= port <= 5584:
        raise ValueError('Emulator console port must be even and between 5554 and 5584')
    command = [sdk_tool(sdk, 'emulator/emulator'), '-avd', avd, '-port', str(port),
               '-no-audio', '-no-boot-anim', '-no-snapshot']
    dns = emulator_dns()
    if dns:
        command += ['-dns-server', dns]
    if sys.platform == 'win32':
        # Auto selected host Vulkan/OpenGL on the machine with repeated full
        # Windows freezes. Disabling GuestAngle alone did not bypass that path.
        command += ['-gpu', 'software', '-cores', '2']
        # Software mode still advertises guest Vulkan. Avoid that browser rendering
        # path too: a CareLink Firefox ANR was followed by a stuck display/shutdown.
        command += ['-feature', '-Vulkan']
    if sys.platform == 'win32' or disable_guest_angle:
        command += ['-feature', '-GuestAngle']
    if headless:
        command.append('-no-window')
    return command


def start_emulator(sdk, avd, port, *, headless=True, disable_guest_angle=False):
    command = emulator_command(sdk, avd, port, headless=headless, disable_guest_angle=disable_guest_angle)
    # An offline emulator still owns its ports. Do not spawn a second copy just
    # because `adb devices` did not report it as ready.
    sockets = []
    try:
        for number in (port, port + 1):
            probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sockets.append(probe)
            if sys.platform == 'win32':
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            probe.bind(('127.0.0.1', number))
    except OSError as error:
        raise RuntimeError(f'Emulator port {number} is occupied; close the existing emulator first') from error
    finally:
        for probe in sockets:
            probe.close()
    folder = ROOT / 'data/output/emulator'
    folder.mkdir(parents=True, exist_ok=True)
    log_path = folder / f'{avd}-{port}-{datetime.now():%Y%m%d-%H%M%S}.log'
    with log_path.open('wb') as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=True,
                                   creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == 'win32' else 0)
    print(f'[emulator] log: {log_path}', flush=True)
    return process, log_path
