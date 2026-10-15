"""Shared SDK paths and conservative emulator launches for developer tools."""
import os
import ipaddress
import socket
import subprocess
import sys
import time
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


def port_in_use(number):
    """True when the port cannot be bound, which a live emulator always causes."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        if sys.platform == 'win32':
            # Refuse any socket bound to the port. Windows already lets a bind pass over
            # TIME_WAIT, and SO_REUSEADDR there would let the probe share a live port.
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        else:
            # Linux and macOS refuse a plain bind for about a minute after an emulator
            # closes, while its connections wait in TIME_WAIT. SO_REUSEADDR passes over
            # those when the closed listener allowed reuse, as the emulator's do, and
            # still refuses a port that a running emulator listens on.
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('127.0.0.1', number))
        return False
    except OSError:
        return True
    finally:
        probe.close()


def port_listening(number):
    """True when something accepts connections on the port, as a running or offline emulator does."""
    try:
        with socket.create_connection(('127.0.0.1', number), timeout=2):
            return True
    except OSError:
        return False


def wait_for_port(number, timeout=240):
    """Refuses a port a live emulator holds at once; waits out connections a closed one left.

    The probe passes over those leftovers only when the closed listener allowed reuse, so a
    port that refuses the bind but accepts no connection is waited for, not reported busy.
    """
    deadline = time.monotonic() + timeout
    waiting = False
    while port_in_use(number):
        if port_listening(number):
            raise RuntimeError(f'Emulator port {number} is occupied; close the existing emulator first')
        if time.monotonic() >= deadline:
            raise RuntimeError(f'Emulator port {number} is still held by closed connections after {timeout} s; try again shortly')
        if not waiting:
            print(f'[emulator] port {number} is held by connections from a closed emulator; waiting for its release', flush=True)
            waiting = True
        time.sleep(1)


def wait_until_closed(port, timeout=60):
    """`adb emu kill` returns while the emulator is still shutting down. Wait until it stops
    listening, so a run started next does not find it half closed and try to reuse it."""
    deadline = time.monotonic() + timeout
    while port_listening(port) or port_listening(port + 1):
        if time.monotonic() >= deadline:
            print(f'[emulator] the emulator on port {port} is still running {timeout} s after it was told to close', flush=True)
            return False
        time.sleep(0.5)
    return True


def start_emulator(sdk, avd, port, *, headless=True, disable_guest_angle=False):
    command = emulator_command(sdk, avd, port, headless=headless, disable_guest_angle=disable_guest_angle)
    # An offline emulator still owns its ports. Do not spawn a second copy just
    # because `adb devices` did not report it as ready.
    for number in (port, port + 1):
        wait_for_port(number)
    folder = ROOT / 'data/output/emulator'
    folder.mkdir(parents=True, exist_ok=True)
    log_path = folder / f'{avd}-{port}-{datetime.now():%Y%m%d-%H%M%S}.log'
    with log_path.open('wb') as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=True,
                                   creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == 'win32' else 0)
    print(f'[emulator] log: {log_path}', flush=True)
    return process, log_path
