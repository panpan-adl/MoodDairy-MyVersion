"""Keep local Android development connections alive across emulator restarts."""
import os
from pathlib import Path
import shutil
import subprocess
import time


def find_adb():
    found = shutil.which('adb')
    if found:
        return found
    roots = [os.getenv('ANDROID_HOME'), os.getenv('ANDROID_SDK_ROOT')]
    if os.getenv('LOCALAPPDATA'):
        roots.append(str(Path(os.environ['LOCALAPPDATA']) / 'Android' / 'Sdk'))
    for root in filter(None, roots):
        candidate = Path(root) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
        if candidate.is_file():
            return str(candidate)
    return None


def ensure_connections(adb, run=subprocess.run):
    def command(*args):
        return run([adb, *args], capture_output=True, text=True, timeout=5,
                   check=True, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0)).stdout

    for line in command('devices').splitlines():
        parts = line.split()
        if len(parts) != 2 or parts[1] != 'device':
            continue
        serial = parts[0]
        try:
            mappings = command('-s', serial, 'reverse', '--list')
            if not any(row.split()[-2:] == ['tcp:8000', 'tcp:8000']
                       for row in mappings.splitlines()):
                command('-s', serial, 'reverse', 'tcp:8000', 'tcp:8000')
        except (OSError, subprocess.SubprocessError):
            # Another device may remain available while this one disconnects.
            continue


def supervise_connections():
    while True:
        try:
            adb = find_adb()
            if adb:
                ensure_connections(adb)
        except (OSError, subprocess.SubprocessError):
            pass  # A restarting adb server will be checked again next cycle.
        time.sleep(5)
