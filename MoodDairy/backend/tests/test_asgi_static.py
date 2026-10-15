"""RangeStaticServer 的 ASGI 层测试：200/206/416/404/HEAD/路径穿越。"""

from __future__ import annotations

import pytest

from app.asgi_static import RangeStaticServer


class ASGICall:
    """最小 ASGI 调用器，收集 start/body 消息。"""

    def __init__(self, server: RangeStaticServer, path: str, method: str = "GET", headers=None):
        self.server = server
        self.scope = {
            "type": "http",
            "method": method,
            "path": path,
            "headers": [(k.encode(), v.encode()) for k, v in (headers or {}).items()],
        }
        self.status: int | None = None
        self.response_headers: list[tuple[bytes, bytes]] = []
        self.chunks: list[bytes] = []

    async def _send(self, message):
        if message["type"] == "http.response.start":
            self.status = message["status"]
            self.response_headers = message["headers"]
        elif message["type"] == "http.response.body":
            self.chunks.append(message.get("body", b""))

    async def run(self, relative_path: str):
        await self.server.handle(self.scope, None, self._send, relative_path)

    @property
    def body(self) -> bytes:
        return b"".join(self.chunks)

    def header(self, name: str) -> str | None:
        name = name.encode()
        for key, value in self.response_headers:
            if key == name:
                return value.decode()
        return None


@pytest.fixture()
def server_and_file(tmp_path):
    payload = bytes(range(256)) * 40  # 10240 字节，内容可定位
    file_path = tmp_path / "music" / "sample.ogg"
    file_path.parent.mkdir(parents=True)
    file_path.write_bytes(payload)
    server = RangeStaticServer(str(tmp_path))
    return server, file_path, payload


@pytest.mark.asyncio
async def test_full_get_returns_200_and_accept_ranges(server_and_file):
    server, _file_path, payload = server_and_file
    call = ASGICall(server, "/media/music/sample.ogg")
    await call.run("music/sample.ogg")

    assert call.status == 200
    assert call.body == payload
    assert call.header("content-length") == str(len(payload))
    assert call.header("accept-ranges") == "bytes"
    assert call.header("content-type") == "audio/ogg"


@pytest.mark.asyncio
async def test_range_mid_returns_206(server_and_file):
    server, _file_path, payload = server_and_file
    call = ASGICall(
        server,
        "/media/music/sample.ogg",
        headers={"Range": "bytes=100-199"},
    )
    await call.run("music/sample.ogg")

    assert call.status == 206
    assert call.body == payload[100:200]
    assert call.header("content-length") == "100"
    assert call.header("content-range") == f"bytes 100-199/{len(payload)}"


@pytest.mark.asyncio
async def test_open_ended_range_to_tail(server_and_file):
    server, _file_path, payload = server_and_file
    call = ASGICall(
        server,
        "/media/music/sample.ogg",
        headers={"Range": "bytes=10000-"},
    )
    await call.run("music/sample.ogg")

    assert call.status == 206
    assert call.body == payload[10000:]
    assert call.header("content-range") == f"bytes 10000-{len(payload) - 1}/{len(payload)}"


@pytest.mark.asyncio
async def test_suffix_range_last_n_bytes(server_and_file):
    server, _file_path, payload = server_and_file
    call = ASGICall(
        server,
        "/media/music/sample.ogg",
        headers={"Range": "bytes=-240"},
    )
    await call.run("music/sample.ogg")

    assert call.status == 206
    assert call.body == payload[-240:]


@pytest.mark.asyncio
async def test_unsatisfiable_range_returns_416(server_and_file):
    server, _file_path, payload = server_and_file
    call = ASGICall(
        server,
        "/media/music/sample.ogg",
        headers={"Range": f"bytes={len(payload)}-"},
    )
    await call.run("music/sample.ogg")

    assert call.status == 416
    assert call.header("content-range") == f"bytes */{len(payload)}"


@pytest.mark.asyncio
async def test_head_has_no_body(server_and_file):
    server, _file_path, payload = server_and_file
    call = ASGICall(server, "/media/music/sample.ogg", method="HEAD")
    await call.run("music/sample.ogg")

    assert call.status == 200
    assert call.body == b""
    assert call.header("content-length") == str(len(payload))


@pytest.mark.asyncio
async def test_missing_file_returns_404(server_and_file):
    server, _, _ = server_and_file
    call = ASGICall(server, "/media/music/missing.ogg")
    await call.run("music/missing.ogg")
    assert call.status == 404


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "relative",
    [
        "../main.py",
        "../../../../etc/passwd",
        "music/../../app/main.py",
    ],
)
async def test_path_traversal_rejected(server_and_file, relative):
    server, _, _ = server_and_file
    call = ASGICall(server, f"/media/{relative}")
    await call.run(relative)
    assert call.status == 404
