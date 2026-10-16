"""Run native acceptance tests and restore the developer's emulator afterwards."""
from pathlib import Path
import subprocess


def run_instrumentation(command, **kwargs):
    serial = command[command.index('-s') + 1]
    script = next((parent / 'restore_emulator_app.ps1'
                   for parent in Path(__file__).resolve().parents
                   if (parent / 'restore_emulator_app.ps1').is_file()), None)
    if script is None:
        raise RuntimeError('Missing emulator restore script; refusing to leave the app stopped after tests')
    try:
        return subprocess.run(command, **kwargs)
    finally:
        subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                        '-File', str(script), '-DeviceId', serial], check=True, timeout=60)
