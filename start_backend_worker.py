"""Detached local API entrypoint; launched through Windows process management."""
from pathlib import Path
import os
import runpy
import sys
import subprocess
import threading
import time

backend = Path(sys.argv[1]).resolve()
logs = backend / "logs"
logs.mkdir(exist_ok=True)
sys.stdout = (logs / "server.stdout.log").open("w", encoding="utf-8", buffering=1)
sys.stderr = (logs / "server.stderr.log").open("w", encoding="utf-8", buffering=1)
os.chdir(backend)
sys.path.insert(0, str(backend))
worker_out=(logs / 'memory-worker.log').open('a',encoding='utf-8',buffering=1)
def supervise_renderer():
    while True:
        child=subprocess.Popen([sys.executable,'-u',str(backend/'scripts/memory_worker.py')],cwd=str(backend),stdout=worker_out,stderr=worker_out,
                               creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        child.wait()
        print('Memory renderer exited; restarting',flush=True)
        time.sleep(2)
threading.Thread(target=supervise_renderer,name='memory-renderer-supervisor',daemon=True).start()
runpy.run_path(str(backend / "run.py"), run_name="__main__")
