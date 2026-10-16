"""Verify real music mux/upload/download with a synthetic clip, without paid generation."""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import re
import subprocess
import sys
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from dotenv import load_dotenv
load_dotenv(ROOT / '.env')
import httpx
import imageio_ffmpeg
from app.drawing import video_audio_mixer as mixer


def main():
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    oss = mixer._get_oss_service()
    if oss is None:
        raise RuntimeError('配乐上传服务不可用')
    keys = []
    upload = oss.upload_bytes

    def tracked_upload(**kwargs):
        result = upload(**kwargs)
        keys.append(result['key'])
        return result

    oss.upload_bytes = tracked_upload
    result = {'passed': False}
    try:
        with tempfile.TemporaryDirectory(prefix='music_acceptance_') as directory:
            folder = Path(directory)
            source = folder / 'synthetic.mp4'
            subprocess.run([ffmpeg, '-y', '-f', 'lavfi', '-i', 'color=c=lightblue:s=160x160:r=12',
                            '-t', '3', '-an', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', str(source)],
                           check=True, capture_output=True, timeout=30)
            server = ThreadingHTTPServer(('127.0.0.1', 0), partial(SimpleHTTPRequestHandler, directory=directory))
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                url = mixer._mix_sync(f'http://127.0.0.1:{server.server_port}/synthetic.mp4')
                response = httpx.get(url, timeout=30, trust_env=False)
                response.raise_for_status()
                saved = ROOT / 'logs' / 'video-music-acceptance.mp4'
                saved.write_bytes(response.content)
                probe = subprocess.run([ffmpeg, '-i', str(saved), '-map', '0:a:0', '-af', 'volumedetect',
                                        '-f', 'null', '-'], capture_output=True, text=True, timeout=30)
                volume = re.search(r'mean_volume:\s*([-\d.]+) dB', probe.stderr)
                duration = mixer._probe_duration_seconds(saved)
                assert probe.returncode == 0 and 'Audio: aac' in probe.stderr
                assert volume and float(volume.group(1)) > -65
                assert duration and abs(duration - 3) < 0.5
                result.update(passed=True, duration_seconds=duration, mean_volume_db=float(volume.group(1)),
                              verified='real mux, OSS upload/download, AAC decode and non-silent music')
            finally:
                server.shutdown()
                server.server_close()
    finally:
        oss.upload_bytes = upload
        for key in keys:
            oss.bucket.delete_object(key)  # Only objects uploaded by this invocation.
        result['temporary_uploads_removed'] = len(keys)
        (ROOT / 'logs' / 'video-music-acceptance.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(result, ensure_ascii=True), flush=True)


if __name__ == '__main__':
    main()
