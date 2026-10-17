"""Manage local API/renderer children independently and retain exit diagnostics."""
from pathlib import Path
import hashlib
import json
import os
import signal
import socket
import subprocess
import sys
import threading
import time


class InstanceLock:
    def __init__(self, path):
        self.file = open(path, 'a+b')
        self.file.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                if not self.file.read(1):
                    self.file.write(b'0'); self.file.flush()
                self.file.seek(0)
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            raise

    def close(self):
        self.file.close()


def port_available():
    with socket.socket() as probe:
        try:
            probe.bind(('127.0.0.1', 8000))
            return True
        except OSError:
            return False


class Supervisor:
    def __init__(self, backend, popen=subprocess.Popen, clock=time.monotonic, port_check=port_available):
        self.backend = Path(backend)
        self.logs = self.backend / 'logs'
        self.logs.mkdir(exist_ok=True)
        self.popen, self.clock, self.port_check = popen, clock, port_check
        self.stopping = False
        self.children = {
            'api': dict(script='run.py', out='server.stdout.log', err='server.stderr.log'),
            'renderer': dict(script='scripts/memory_worker.py', out='memory-worker.log', err='memory-worker.log'),
        }
        for child in self.children.values():
            child.update(process=None, failures=0, due=0, started=0)

    def log(self, event, **fields):
        record = dict(time=time.strftime('%Y-%m-%dT%H:%M:%S%z'), supervisor_pid=os.getpid(), event=event, **fields)
        with (self.logs / 'backend-supervisor.log').open('a', encoding='utf-8') as file:
            file.write(json.dumps(record, ensure_ascii=False) + '\n')

    def state(self):
        record = dict(pid=os.getpid(), backend=str(self.backend), stopping=self.stopping,
                      supervisor_revision=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                      **{name + '_pid': child['process'].pid if child['process'] else None
                         for name, child in self.children.items()})
        temporary = self.logs / 'backend-supervisor.json.tmp'
        temporary.write_text(json.dumps(record), encoding='utf-8')
        os.replace(temporary, self.logs / 'backend-supervisor.json')

    def step(self):
        if self.stopping:
            return
        for name, child in self.children.items():
            process = child['process']
            if process is not None:
                code = process.poll()
                if code is None:
                    continue
                lifetime = self.clock() - child['started']
                child['process'] = None
                child['failures'] = 1 if lifetime >= 60 else child['failures'] + 1
                delay = min(30, 2 ** min(child['failures'] - 1, 5))
                child['due'] = self.clock() + delay
                self.log('child_exited', child=name, pid=process.pid, exit_code=code,
                         lifetime_seconds=round(lifetime, 2), restart_delay_seconds=delay)
                self.state()
            if self.clock() < child['due']:
                continue
            if name == 'api' and not self.port_check():
                self.log('port_occupied', port=8000, action='left_other_listener_untouched')
                child['due'] = self.clock() + 30
                continue
            try:
                with (self.logs / child['out']).open('a', encoding='utf-8') as out, \
                        (self.logs / child['err']).open('a', encoding='utf-8') as err:
                    process = self.popen([sys.executable, '-u', str(self.backend / child['script'])],
                        cwd=str(self.backend), stdout=out, stderr=err,
                        env={**os.environ, 'BACKEND_RELOAD': 'false'},
                        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                child.update(process=process, started=self.clock())
                self.log('child_started', child=name, pid=process.pid)
                self.state()
            except OSError as error:
                child['failures'] += 1
                delay = min(30, 2 ** min(child['failures'] - 1, 5))
                child['due'] = self.clock() + delay
                self.log('start_failed', child=name, error_type=type(error).__name__, restart_delay_seconds=delay)

    def stop(self):
        self.stopping = True
        self.log('stop_requested')
        for name, child in self.children.items():
            process = child['process']
            if process is not None:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill(); process.wait(timeout=5)
                self.log('child_stopped', child=name, pid=process.pid, exit_code=process.poll())
                child['process'] = None
        self.state()


def main(backend):
    backend = Path(backend).resolve()
    logs = backend / 'logs'
    logs.mkdir(exist_ok=True)
    try:
        lock = InstanceLock(logs / 'backend-supervisor.lock')
    except OSError:
        return 0
    manager = Supervisor(backend)
    stop_file = logs / 'backend.stop'
    stop_file.unlink(missing_ok=True)
    stopping = threading.Event()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: stopping.set())
    sys.path.insert(0, str(backend))
    from scripts.android_port_forward import supervise_connections
    threading.Thread(target=supervise_connections, daemon=True).start()
    manager.log('supervisor_started', backend=str(backend))
    manager.state()
    try:
        while not stopping.is_set() and not stop_file.exists():
            manager.step()
            stopping.wait(.5)
    except Exception as error:
        manager.log('supervisor_failed', error_type=type(error).__name__)
        raise
    finally:
        try:
            manager.stop()
        finally:
            lock.close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1]))
