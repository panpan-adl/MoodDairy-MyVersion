"""Run native acceptance tests and restore the developer's emulator afterwards."""
from pathlib import Path
import subprocess
import os
import json


def run_connected_tests(test_class, arguments, serial='emulator-5554', **kwargs):
    """Use the shared Gradle entry, keeping synthetic account tokens out of argv."""
    script = next((parent / 'test_latest.ps1' for parent in Path(__file__).resolve().parents
                   if (parent / 'test_latest.ps1').is_file()), None)
    if script is None:
        raise RuntimeError('Missing shared device test entry')
    environment = dict(kwargs.pop('env', os.environ))
    environment['MOODDIARY_TEST_ARGUMENTS'] = json.dumps(arguments)
    command = ['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy',
        'Bypass', '-File', str(script), '-DeviceId', serial]
    if test_class:
        command += ['-TestClass', test_class]
    return subprocess.run(command, env=environment, **kwargs)


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
