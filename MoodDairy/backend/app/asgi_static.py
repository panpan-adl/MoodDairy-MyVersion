"""支持 HTTP Range 请求的简单静态文件服务器

用于 ExoPlayer 等客户端顺序拉取音频/视频文件，
支持 206 Partial Content 响应，避免 BaseHTTPMiddleware
在 Windows/uvicorn 下对大文件分块体的截断问题。
"""

import os
import re
import mimetypes
from pathlib import Path
from typing import Tuple


class RangeStaticServer:
    """纯 ASGI 静态文件服务，支持 Range 头"""

    def __init__(self, root_dir: str):
        self.root = Path(root_dir).resolve()

    async def handle(self, scope, receive, send, path: str):
        """处理请求，path 为相对路径（如 'xxx.mp3'）"""
        # 防止路径遍历攻击
        try:
            file_path = (self.root / path).resolve()
            if not file_path.is_relative_to(self.root):
                await self._send_error(send, 403, "Forbidden")
                return
        except Exception:
            await self._send_error(send, 403, "Forbidden")
            return

        if not file_path.exists() or not file_path.is_file():
            await self._send_error(send, 404, "Not Found")
            return

        file_size = file_path.stat().st_size
        content_type = mimetypes.guess_type(str(file_path))[0] or "application/octet-stream"

        # 解析 Range 头
        range_header = None
        for name, value in scope.get("headers", []):
            if name.lower() == b"range":
                range_header = value.decode("latin-1")
                break

        start, end = 0, file_size - 1
        status_code = 200
        headers = [
            (b"content-type", content_type.encode()),
            (b"accept-ranges", b"bytes"),
        ]

        if range_header:
            match = re.match(r"bytes=(\d+)-(\d*)", range_header)
            if match:
                start = int(match.group(1))
                if match.group(2):
                    end = int(match.group(2))
                if start >= file_size:
                    await self._send_error(send, 416, "Range Not Satisfiable")
                    return
                end = min(end, file_size - 1)
                status_code = 206
                headers.append((b"content-range", f"bytes {start}-{end}/{file_size}".encode()))

        content_length = end - start + 1
        headers.append((b"content-length", str(content_length).encode()))

        await send({
            "type": "http.response.start",
            "status": status_code,
            "headers": headers,
        })

        # 发送文件内容（分块读取避免内存溢出）
        chunk_size = 64 * 1024  # 64KB
        with open(file_path, "rb") as f:
            f.seek(start)
            remaining = content_length
            while remaining > 0:
                chunk = f.read(min(chunk_size, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                await send({
                    "type": "http.response.body",
                    "body": chunk,
                    "more_body": remaining > 0,
                })

    async def _send_error(self, send, status: int, message: str):
        """发送错误响应"""
        body = message.encode("utf-8")
        await send({
            "type": "http.response.start",
            "status": status,
            "headers": [(b"content-type", b"text/plain; charset=utf-8")],
        })
        await send({
            "type": "http.response.body",
            "body": body,
        })
