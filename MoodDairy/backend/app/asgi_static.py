"""支持 Range 请求的纯 ASGI 静态文件分发。

背景：项目锁定 starlette==0.35.x（FastAPI 0.109 约束），该版本
``StaticFiles/FileResponse`` 不支持 HTTP Range（始终 200 回完整文件）。
ExoPlayer 播放 OGG 时需要读取容器尾页计算时长，缺少 206 支持时只能
顺序拉流并在长连接上做尾部定位，经模拟器 NAT / 反向代理时容易收到
"unexpected end of stream"。

本模块实现最小化的单段 Range（``bytes=start-end``）文件服务，供
``StaticMediaBypassMiddleware`` 在最外层短路调用，完全绕过
BaseHTTPMiddleware 链。
"""

from __future__ import annotations

import mimetypes
import os
from urllib.parse import unquote

from anyio import to_thread

_CHUNK_SIZE = 128 * 1024


def _http_date(timestamp: float) -> str:
    from email.utils import formatdate

    return formatdate(timestamp, usegmt=True)


class RangeStaticServer:
    def __init__(self, root: str) -> None:
        self._root = os.path.realpath(root)

    async def handle(self, scope, receive, send, relative_path: str) -> None:
        method = scope.get("method")
        if method not in ("GET", "HEAD"):
            await self._send_simple(send, 405, b"Method Not Allowed", {})
            return

        relative_path = unquote(relative_path.split("?", 1)[0].split("#", 1)[0]).lstrip("/")
        full_path = os.path.realpath(os.path.join(self._root, relative_path))
        if not full_path.startswith(self._root + os.sep) or not os.path.isfile(full_path):
            await self._send_simple(send, 404, b"Not Found", {})
            return

        stat_result = await to_thread.run_sync(os.stat, full_path)
        file_size = stat_result.st_size
        content_type = mimetypes.guess_type(full_path)[0] or "application/octet-stream"

        base_headers = {
            "content-type": content_type,
            "accept-ranges": "bytes",
            "last-modified": _http_date(stat_result.st_mtime),
        }

        range_value = self._header(scope, b"range")
        start, end = self._parse_range(range_value, file_size)

        if start is not None:
            if start > end or start >= file_size:
                headers = dict(base_headers)
                headers["content-range"] = f"bytes */{file_size}"
                await self._send_simple(send, 416, b"Range Not Satisfiable", headers)
                return
            if end >= file_size:
                end = file_size - 1
            length = end - start + 1
            headers = dict(base_headers)
            headers["content-length"] = str(length)
            headers["content-range"] = f"bytes {start}-{end}/{file_size}"
            await self._start(send, 206, headers)
            if method == "GET":
                await self._send_file(send, full_path, start, length)
        else:
            headers = dict(base_headers)
            headers["content-length"] = str(file_size)
            await self._start(send, 200, headers)
            if method == "GET":
                await self._send_file(send, full_path, 0, file_size)

        await send({"type": "http.response.body", "body": b"", "more_body": False})

    # ------------------------------------------------------------------

    @staticmethod
    def _header(scope, name: bytes) -> str | None:
        wanted = name.lower()
        for key, value in scope.get("headers", []):
            if key.lower() == wanted:
                return value.decode("latin1")
        return None

    @staticmethod
    def _parse_range(value: str | None, file_size: int) -> tuple[int | None, int]:
        """解析 ``bytes=start-end``，仅支持单段；返回 (start, end)。"""
        if not value or not value.startswith("bytes="):
            return None, file_size - 1
        spec = value[len("bytes="):].split(",", 1)[0].strip()
        if "-" not in spec:
            return None, file_size - 1
        start_text, end_text = spec.split("-", 1)
        try:
            if start_text == "":
                # 后缀形式 bytes=-N：最后 N 字节
                suffix = int(end_text)
                if suffix <= 0:
                    return None, file_size - 1
                start = max(0, file_size - suffix)
                return start, file_size - 1
            start = int(start_text)
            end = int(end_text) if end_text else file_size - 1
            return start, end
        except ValueError:
            return None, file_size - 1

    @staticmethod
    async def _start(send, status: int, headers: dict[str, str]) -> None:
        await send(
            {
                "type": "http.response.start",
                "status": status,
                "headers": [
                    (key.encode("latin1"), value.encode("latin1"))
                    for key, value in headers.items()
                ],
            }
        )

    @staticmethod
    async def _send_simple(send, status: int, body: bytes, headers: dict[str, str]) -> None:
        response_headers = dict(headers)
        response_headers.setdefault("content-type", "text/plain; charset=utf-8")
        response_headers["content-length"] = str(len(body))
        await RangeStaticServer._start(send, status, response_headers)
        await send({"type": "http.response.body", "body": body, "more_body": False})

    @staticmethod
    async def _send_file(send, path: str, offset: int, length: int) -> None:
        def read_chunk(handle, remaining: int) -> bytes:
            return handle.read(min(_CHUNK_SIZE, remaining))

        def _open():
            handle = open(path, "rb")
            handle.seek(offset)
            return handle

        # 阻塞 IO 放到 anyio 线程池，逐块通过 ASGI 发送
        handle = await to_thread.run_sync(_open)
        try:
            remaining = length
            while remaining > 0:
                chunk = await to_thread.run_sync(read_chunk, handle, remaining)
                if not chunk:
                    break
                remaining -= len(chunk)
                await send(
                    {
                        "type": "http.response.body",
                        "body": chunk,
                        "more_body": remaining > 0,
                    }
                )
        finally:
            await to_thread.run_sync(handle.close)
