"""网易云音乐搜索服务：调用官方公开接口，免费、无需注册、无需 Key。

流程：/api/search/get 搜索拿歌曲 ID 列表 -> /api/song/detail/ 批量取专辑封面。
仅使用匿名可访问的公开接口，不涉及登录态。
"""

from __future__ import annotations

import logging
from typing import Any, Dict, List

import httpx

logger = logging.getLogger(__name__)

_SEARCH_URL = "https://music.163.com/api/search/get"
_DETAIL_URL = "https://music.163.com/api/song/detail/"
_SONG_PAGE = "https://music.163.com/song?id={}"


async def resolve_song_audio(song_id: str) -> str:
    """Resolve the public audio link; unavailable songs stay unavailable."""
    if not song_id.isascii() or not song_id.isdigit() or len(song_id) > 20 or int(song_id) <= 0:
        raise ValueError("Invalid song id")
    url = f"https://music.163.com/song/media/outer/url?id={song_id}.mp3"
    async with httpx.AsyncClient(timeout=15.0, follow_redirects=True) as client:
        # Public playback metadata respects unavailable/paid tracks; no login or
        # alternate-source substitution is involved.
        try:
            metadata = await client.get("https://music.163.com/api/song/enhance/player/url",
                params={"ids": f"[{song_id}]", "br": "320000"},
                headers={"Referer": "https://music.163.com/"})
            metadata.raise_for_status()
            entries = metadata.json().get("data") or []
            candidate = entries[0].get("url") if entries else None
            if candidate:
                audio_url = httpx.URL(candidate)
                if audio_url.host and (audio_url.host.endswith(".music.126.net") or audio_url.host.endswith(".music.163.com")):
                    url = str(audio_url.copy_with(scheme="https"))
        except (httpx.HTTPError, ValueError, TypeError, KeyError):
            pass  # Try the public media redirect when metadata is unavailable.
        # Inspect headers without downloading the song into the backend.
        async with client.stream("GET", url, headers={"Referer": "https://music.163.com/"}) as response:
            response.raise_for_status()
            content_type = response.headers.get("content-type", "").split(";", 1)[0].lower().strip()
            resolved = response.url
            if content_type not in {"audio/mpeg", "audio/mp3", "audio/mp4", "audio/aac", "audio/x-m4a", "application/octet-stream"}:
                raise ValueError("Song has no publicly playable audio")
            if resolved.scheme != "https" or not resolved.host or resolved.path.lower().endswith((".html", ".htm")):
                raise ValueError("Invalid audio URL")
            return str(resolved)

_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    ),
    "Referer": "https://music.163.com/search/",
}


async def _fetch_covers(client: httpx.AsyncClient, song_ids: List[int]) -> Dict[int, str]:
    """批量查询歌曲详情，返回 {song_id: 专辑封面 URL}。失败时返回空表。"""
    if not song_ids:
        return {}
    try:
        ids_param = "[" + ",".join(str(i) for i in song_ids) + "]"
        resp = await client.get(
            _DETAIL_URL,
            params={"id": str(song_ids[0]), "ids": ids_param},
            headers=_HEADERS,
        )
        resp.raise_for_status()
        covers: Dict[int, str] = {}
        for song in resp.json().get("songs") or []:
            pic = ((song.get("album") or {}).get("picUrl")) or None
            if song.get("id") and pic:
                if pic.startswith("http://"):
                    pic = "https://" + pic[len("http://"):]
                covers[int(song["id"])] = pic
        return covers
    except Exception as exc:
        logger.warning("NetEase cover fetch failed: %s", exc)
        return {}


async def search_songs(keyword: str, limit: int = 6) -> List[Dict[str, Any]]:
    """搜索网易云歌曲，返回与社交搜索卡片一致的字段结构（platform=netease）。"""
    keyword = (keyword or "").strip()
    if not keyword:
        return []
    limit = max(1, min(limit, 10))

    async with httpx.AsyncClient(timeout=10.0) as client:
        resp = await client.get(
            _SEARCH_URL,
            params={"s": keyword, "type": "1", "offset": "0", "limit": str(limit)},
            headers=_HEADERS,
        )
        resp.raise_for_status()
        songs = (resp.json().get("result") or {}).get("songs") or []

        song_ids = [int(s["id"]) for s in songs if s.get("id")]
        covers = await _fetch_covers(client, song_ids)

    items: List[Dict[str, Any]] = []
    for song in songs:
        song_id = song.get("id")
        if not song_id:
            continue
        artists = song.get("artists") or []
        artist_name = " / ".join(a.get("name", "") for a in artists if a.get("name"))
        album = song.get("album") or {}
        items.append(
            {
                "platform": "netease",
                "note_id": str(song_id),
                "title": str(song.get("name") or ""),
                "description": album.get("name") or "",
                "cover_url": covers.get(int(song_id)),
                "url": _SONG_PAGE.format(song_id),
                "author_name": artist_name,
                "author_avatar": None,
                "like_count": 0,
                "comment_count": 0,
                "share_count": 0,
                "publish_time": None,
                "content_type": "music",
            }
        )
    return items
