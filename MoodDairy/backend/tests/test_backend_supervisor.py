import importlib.util
import json
from pathlib import Path
import pytest

SOURCE = [p / 'start_backend_worker.py' for p in Path(__file__).resolve().parents
          if (p / 'start_backend_worker.py').is_file()][-1]
spec = importlib.util.spec_from_file_location('local_backend_supervisor', SOURCE)
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class Child:
    def __init__(self, pid):
        self.pid, self.code = pid, None
    def poll(self): return self.code
    def terminate(self): self.code = -15
    def wait(self, timeout): return self.code


def manager(tmp_path, port_check=lambda: True):
    children = []
    now = [0.0]
    def popen(command, **kwargs):
        child = Child(len(children) + 100)
        children.append((command, child))
        return child
    return worker.Supervisor(tmp_path, popen, lambda: now[0], port_check), children, now


def test_start_and_api_exit_restart_without_duplicate_renderer(tmp_path):
    supervisor, children, now = manager(tmp_path)
    (tmp_path / 'logs/server.stdout.log').write_text('old history\n')
    supervisor.step(); supervisor.step()
    assert len(children) == 2
    old_api = supervisor.children['api']['process']
    renderer = supervisor.children['renderer']['process']
    old_api.code = 7
    supervisor.step()
    assert len(children) == 2
    now[0] = 1
    supervisor.step()
    assert len(children) == 3
    assert supervisor.children['renderer']['process'] is renderer
    assert (tmp_path / 'logs/server.stdout.log').read_text() == 'old history\n'
    events = [json.loads(line) for line in (tmp_path / 'logs/backend-supervisor.log').read_text().splitlines()]
    assert any(e['event'] == 'child_exited' and e['exit_code'] == 7 for e in events)


def test_repeated_failures_back_off_to_thirty_seconds(tmp_path):
    supervisor, children, now = manager(tmp_path)
    supervisor.step()
    delays = []
    for _ in range(8):
        supervisor.children['api']['process'].code = 1
        supervisor.step()
        delays.append(supervisor.children['api']['due'] - now[0])
        now[0] = supervisor.children['api']['due']
        supervisor.step()
    assert delays == [1, 2, 4, 8, 16, 30, 30, 30]


def test_explicit_stop_stops_both_children_and_disables_restart(tmp_path):
    supervisor, children, now = manager(tmp_path)
    supervisor.step()
    supervisor.stop()
    now[0] = 200
    supervisor.step()
    assert len(children) == 2
    assert all(child.poll() == -15 for _, child in children)
    state = json.loads((tmp_path / 'logs/backend-supervisor.json').read_text())
    assert state['stopping'] and state['api_pid'] is None and state['renderer_pid'] is None


def test_unknown_listener_is_left_untouched(tmp_path):
    supervisor, children, _ = manager(tmp_path, port_check=lambda: False)
    supervisor.step()
    assert len(children) == 1
    assert children[0][0][-1].endswith('memory_worker.py')
    assert 'left_other_listener_untouched' in (tmp_path / 'logs/backend-supervisor.log').read_text()


def test_instance_lock_rejects_second_owner_and_releases_on_close(tmp_path):
    first = worker.InstanceLock(tmp_path / 'instance.lock')
    try:
        with pytest.raises(OSError): worker.InstanceLock(tmp_path / 'instance.lock')
    finally:
        first.close()
    second = worker.InstanceLock(tmp_path / 'instance.lock')
    second.close()
