from pathlib import Path
import hashlib

def revision():
    root=Path(__file__).resolve().parents[1]
    files=sorted((root/'app').rglob('*.py'))+[root/'scripts/memory_worker.py']
    for parent in root.parents:
        launcher=parent/'start_backend_worker.py'
        if launcher.exists(): files.append(launcher); break
    digest=hashlib.sha256()
    for file in files:
        label=str(file.relative_to(root)) if file.is_relative_to(root) else file.name
        digest.update(label.replace('\\','/').encode()); digest.update(file.read_bytes())
    return digest.hexdigest()[:16]

if __name__=='__main__': print(revision())
