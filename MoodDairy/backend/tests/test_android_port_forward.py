import subprocess
from types import SimpleNamespace

from scripts.android_port_forward import ensure_connections


def test_restores_missing_forward_after_reconnect_without_replacing_existing():
    calls = []
    mappings = {'emulator-5554': '', 'phone': 'UsbFfs tcp:8000 tcp:8000\n'}

    def run(args, **kwargs):
        calls.append(args[1:])
        if args[1:] == ['devices']:
            return SimpleNamespace(stdout='List of devices attached\nemulator-5554\tdevice\nphone\tdevice\noffline\toffline\n')
        if args[-1] == '--list':
            return SimpleNamespace(stdout=mappings[args[2]])
        mappings[args[2]] = 'host tcp:8000 tcp:8000\n'
        return SimpleNamespace(stdout='8000')

    ensure_connections('adb', run)
    ensure_connections('adb', run)
    assert calls.count(['-s', 'emulator-5554', 'reverse', 'tcp:8000', 'tcp:8000']) == 1
    mappings['emulator-5554'] = ''  # Emulator restart drops reverse mappings.
    ensure_connections('adb', run)
    assert calls.count(['-s', 'emulator-5554', 'reverse', 'tcp:8000', 'tcp:8000']) == 2
    assert ['-s', 'phone', 'reverse', 'tcp:8000', 'tcp:8000'] not in calls


def test_disconnected_device_does_not_block_other_devices():
    calls = []

    def run(args, **kwargs):
        calls.append(args[1:])
        if args[1:] == ['devices']:
            return SimpleNamespace(stdout='lost\tdevice\nready\tdevice\n')
        if args[2] == 'lost':
            raise subprocess.CalledProcessError(1, args)
        return SimpleNamespace(stdout='')

    ensure_connections('adb', run)
    assert ['-s', 'ready', 'reverse', 'tcp:8000', 'tcp:8000'] in calls
