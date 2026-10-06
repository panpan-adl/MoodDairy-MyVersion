"""TikHub API provider for social search.

Updated 2026-10-05 to use the current TikHub endpoint series:
- Xiaohongshu: App V2 (`/api/v1/xiaohongshu/app_v2/search_notes`, GET)
  The legacy Web V2/V3 and App V1 series were permanently taken offline 2026-06-17.
- Douyin: Search API v5 (`/api/v1/douyin/search/fetch_video_search_v5`, POST)
- Base URL: `https://api.tikhub.dev` for mainland-China users (api.tikhub.io is GFW-blocked).
"""

from __future__ import annotations

import logging
import re
from typing import Any, Dict, List, Optional

import httpx

from .base import BaseSocialSearchProvider
from .schemas import Platform, SocialSearchItem, SocialSearchResult

logger = logging.getLogger(__name__)


class SocialSearchError(Exception):
    """Social search API error."""

    def __init__(self, message: str, code: str = "PROVIDER_ERROR"):
        super().__init__(message)
        self.code = code


_TAG_RE = re.compile(r"<[^>]+>")


def _strip_html(text: Any) -> str:
    """去掉搜索结果标题/摘要里的高亮标签（如 <em class=keyword>）并做基本清理。"""
    if not text:
        return ""
    clean = _TAG_RE.sub("", str(text))
    return clean.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').strip()


def _as_int(value: Any, default: int = 0) -> int:
    """宽松转 int：快手等平台的计数可能是字符串，脏数据兜底为 0。"""
    try:
        if value is None or value == "":
            return default
        return int(float(str(value).replace(",", "").strip()))
    except (TypeError, ValueError):
        return default


def _first_present(raw: dict, *keys: str) -> Any:
    """按候选 key 顺序取第一个非空值。"""
    for key in keys:
        value = raw.get(key)
        if value not in (None, "", [], {}):
            return value
    return None


class TikHubProvider(BaseSocialSearchProvider):
    """TikHub API provider: Xiaohongshu, Douyin, Bilibili, Zhihu, Kuaishou, Weibo search."""

    name = "tikhub"

    def __init__(self, api_key: str, base_url: str = "https://api.tikhub.dev", timeout: float = 20.0):
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    async def _request(
        self,
        endpoint: str,
        params: Optional[Dict[str, Any]] = None,
        json_body: Optional[Dict[str, Any]] = None,
        method: str = "GET",
    ) -> dict:
        """Make HTTP request to TikHub API."""
        url = f"{self._base_url}{endpoint}"
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

        async with httpx.AsyncClient(timeout=self._timeout) as client:
            try:
                if method.upper() == "POST":
                    response = await client.post(url, headers=headers, json=json_body or {})
                else:
                    response = await client.get(url, headers=headers, params=params)
                response.raise_for_status()
                return response.json()
            except httpx.HTTPStatusError as e:
                status = e.response.status_code
                if status == 402:
                    raise SocialSearchError("账户余额不足，请先充值 TikHub", code="NO_BALANCE")
                if status == 401:
                    raise SocialSearchError("TikHub API Key 无效", code="INVALID_KEY")
                logger.error(f"TikHub API error {status}: {e.response.text[:300]}")
                raise SocialSearchError(f"API error: {status}")
            except httpx.HTTPError as e:
                logger.error(f"TikHub API network error: {e}")
                raise SocialSearchError(f"Network error: {e}")

    # ------------------------------------------------------------------
    # Xiaohongshu (App V2)
    # ------------------------------------------------------------------
    def _normalize_xhs_item(self, raw: dict) -> SocialSearchItem:
        note_id = raw.get("note_id") or raw.get("id") or ""
        url = raw.get("url") or f"https://www.xiaohongshu.com/explore/{note_id}"

        cover_url = None
        # App V2 结构：image_list: [{"url": ...}, ...] 或 display_id 等
        for field in ("image_list", "images"):
            image_list = raw.get(field) or []
            if isinstance(image_list, list) and image_list:
                cover_url = image_list[0].get("url") or image_list[0].get("url_default")
                break
        if not cover_url:
            cover = raw.get("cover") or {}
            if isinstance(cover, dict):
                cover_url = cover.get("url") or (cover.get("info_list") or [{}])[0].get("url")

        # interact_info 里放点赞/评论/收藏/分享
        interact = raw.get("interact_info") or {}
        liked_count = raw.get("liked_count", interact.get("liked_count", 0))
        comment_count = raw.get("comment_count", interact.get("comment_count", 0))
        share_count = raw.get("share_count", interact.get("share_count", 0))

        user = raw.get("user") or {}
        author_name = user.get("nickname") or user.get("name") or ""
        author_avatar = user.get("avatar")

        note_type = raw.get("type") or raw.get("note_type") or "normal"
        content_type = "video" if note_type == "video" else "normal"

        return SocialSearchItem(
            platform=Platform.XHS,
            note_id=str(note_id),
            title=raw.get("title", "") or raw.get("display_title", ""),
            description=raw.get("desc", "") or raw.get("description", ""),
            cover_url=cover_url,
            url=url,
            author_name=author_name,
            author_avatar=author_avatar,
            like_count=int(liked_count or 0),
            comment_count=int(comment_count or 0),
            share_count=int(share_count or 0),
            publish_time=raw.get("time") or raw.get("publish_time"),
            content_type=content_type,
        )

    async def search_xhs(self, keyword: str, limit: int = 8) -> SocialSearchResult:
        """Search Xiaohongshu notes via App V2 series."""
        endpoint = "/api/v1/xiaohongshu/app_v2/search_notes"
        params = {
            "keyword": keyword,
            "page": 1,
            "sort_type": "general",  # general | time_descending | popularity_descending
            "note_type": "不限",
            "time_filter": "不限",
        }

        data = await self._request(endpoint, params=params)

        items: List[SocialSearchItem] = []
        # App V2 search 返回的笔记列表可能在 data.notes / data.items / data 直接是列表
        raw_notes = (
            (data.get("data") or {}).get("notes")
            or (data.get("data") or {}).get("items")
            or (data.get("data") if isinstance(data.get("data"), list) else None)
            or []
        )

        for note in raw_notes[:limit]:
            try:
                items.append(self._normalize_xhs_item(note))
            except Exception as e:
                logger.warning(f"Failed to normalize XHS item: {e}")
                continue

        return SocialSearchResult(
            platform=Platform.XHS,
            keyword=keyword,
            items=items,
            total=len(items),
        )

    # ------------------------------------------------------------------
    # Douyin (Search API v5)
    # ------------------------------------------------------------------
    def _normalize_douyin_item(self, raw: dict) -> SocialSearchItem:
        aweme_id = raw.get("aweme_id") or raw.get("id") or ""
        url = f"https://www.douyin.com/video/{aweme_id}"

        cover_url = None
        video = raw.get("video") or {}
        if video:
            cover = video.get("cover") or {}
            if isinstance(cover, dict):
                url_list = cover.get("url_list") or []
                if url_list:
                    cover_url = url_list[0]
        if not cover_url:
            cover_url = raw.get("cover")

        statistics = raw.get("statistics") or {}
        author = raw.get("author") or {}

        return SocialSearchItem(
            platform=Platform.DOUYIN,
            note_id=str(aweme_id),
            title=raw.get("desc", "") or raw.get("title", ""),
            description=raw.get("desc", ""),
            cover_url=cover_url,
            url=url,
            author_name=author.get("nickname", ""),
            author_avatar=(author.get("avatar_thumb") or {}).get("url_list", [None])[0] if author.get("avatar_thumb") else None,
            like_count=int(statistics.get("digg_count", 0)),
            comment_count=int(statistics.get("comment_count", 0)),
            share_count=int(statistics.get("share_count", 0)),
            publish_time=raw.get("create_time"),
            content_type="video",
        )

    async def search_douyin(self, keyword: str, limit: int = 8) -> SocialSearchResult:
        """Search Douyin videos via Search API v5 (POST)."""
        endpoint = "/api/v1/douyin/search/fetch_video_search_v5"
        body = {
            "keyword": keyword,
            "page": 1,
            "offset": 0,
            "search_id": "",
            "backtrace": "",
        }

        data = await self._request(endpoint, json_body=body, method="POST")

        items: List[SocialSearchItem] = []
        raw_awemes = (
            (data.get("data") or {}).get("aweme_list")
            or (data.get("data") or {}).get("data")
            or (data.get("data") if isinstance(data.get("data"), list) else None)
            or []
        )

        for aweme in raw_awemes[:limit]:
            try:
                items.append(self._normalize_douyin_item(aweme))
            except Exception as e:
                logger.warning(f"Failed to normalize Douyin item: {e}")
                continue

        return SocialSearchResult(
            platform=Platform.DOUYIN,
            keyword=keyword,
            items=items,
            total=len(items),
        )

    # ------------------------------------------------------------------
    # Bilibili (Web general search)
    # ------------------------------------------------------------------
    def _normalize_bilibili_item(self, raw: dict) -> SocialSearchItem:
        bvid = raw.get("bvid") or ""
        url = (f"https://www.bilibili.com/video/{bvid}" if bvid else raw.get("arcurl")) or ""
        pic = raw.get("pic") or ""
        if pic and pic.startswith("//"):
            pic = "https:" + pic
        upic = raw.get("upic") or ""
        if upic and upic.startswith("//"):
            upic = "https:" + upic

        return SocialSearchItem(
            platform=Platform.BILIBILI,
            note_id=str(bvid or raw.get("aid") or ""),
            title=_strip_html(raw.get("title")),
            description=_strip_html(raw.get("description") or raw.get("tag")),
            cover_url=pic or None,
            url=url,
            author_name=raw.get("author") or "",
            author_avatar=upic or None,
            like_count=_as_int(raw.get("like")),
            comment_count=_as_int(raw.get("review")),
            share_count=0,
            publish_time=str(raw.get("pubdate") or ""),
            content_type="video",
        )

    async def search_bilibili(self, keyword: str, limit: int = 8) -> SocialSearchResult:
        """Search Bilibili videos via web general search (free-credit eligible)."""
        endpoint = "/api/v1/bilibili/web/fetch_general_search"
        params = {
            "keyword": keyword,
            "order": "totalrank",  # 综合排序
            "page": 1,
            "page_size": max(limit, 10),
        }
        data = await self._request(endpoint, params=params)

        raw_items = ((data.get("data") or {}).get("data") or {}).get("result") or []
        items: List[SocialSearchItem] = []
        for raw in raw_items:
            if (raw.get("result_type") or raw.get("type")) != "video":
                continue
            try:
                items.append(self._normalize_bilibili_item(raw))
            except Exception as e:  # noqa: BLE001
                logger.warning(f"Failed to normalize Bilibili item: {e}")
                continue
            if len(items) >= limit:
                break

        return SocialSearchResult(
            platform=Platform.BILIBILI,
            keyword=keyword,
            items=items,
            total=len(items),
        )

    # ------------------------------------------------------------------
    # Zhihu (Web article search V3 — answers + articles)
    # ------------------------------------------------------------------
    def _normalize_zhihu_item(self, obj: dict) -> SocialSearchItem:
        obj_type = obj.get("type")  # answer | article
        item_id = str(obj.get("id") or "")
        author = obj.get("author") or {}

        thumbs = ((obj.get("thumbnail_info") or {}).get("thumbnails")) or []
        cover_url = thumbs[0].get("url") if thumbs and isinstance(thumbs[0], dict) else None

        if obj_type == "answer":
            question = obj.get("question") or {}
            title = _strip_html(question.get("name")) or "知乎回答"
            url = f"https://www.zhihu.com/question/{question.get('id')}/answer/{item_id}"
            favorite_count = _as_int(obj.get("favorites_count"))
        else:
            title = _strip_html(obj.get("title")) or "知乎文章"
            url = f"https://zhuanlan.zhihu.com/p/{item_id}"
            favorite_count = _as_int(obj.get("zfav_count"))

        return SocialSearchItem(
            platform=Platform.ZHIHU,
            note_id=item_id,
            title=title,
            description=_strip_html(obj.get("excerpt")),
            cover_url=cover_url,
            url=url,
            author_name=author.get("name") or "",
            author_avatar=author.get("avatar_url"),
            like_count=_as_int(obj.get("voteup_count")),
            comment_count=_as_int(obj.get("comment_count")),
            share_count=favorite_count,
            publish_time=str(obj.get("created_time") or ""),
            content_type="normal",
        )

    async def search_zhihu(self, keyword: str, limit: int = 8) -> SocialSearchResult:
        """Search Zhihu answers/articles via web search V3 (free-credit eligible)."""
        endpoint = "/api/v1/zhihu/web/fetch_article_search_v3"
        params = {
            "keyword": keyword,
            "offset": "0",
            "limit": str(max(limit * 2, 10)),
            "show_all_topics": 0,
            "search_source": "Normal",
        }
        data = await self._request(endpoint, params=params)

        raw_items = (data.get("data") or {}).get("data") or []
        items: List[SocialSearchItem] = []
        for raw in raw_items:
            if raw.get("type") != "search_result":
                continue
            obj = raw.get("object") or {}
            if obj.get("type") not in ("answer", "article"):
                continue
            try:
                items.append(self._normalize_zhihu_item(obj))
            except Exception as e:  # noqa: BLE001
                logger.warning(f"Failed to normalize Zhihu item: {e}")
                continue
            if len(items) >= limit:
                break

        return SocialSearchResult(
            platform=Platform.ZHIHU,
            keyword=keyword,
            items=items,
            total=len(items),
        )

    # ------------------------------------------------------------------
    # Kuaishou (App comprehensive search — paid, balance required)
    # ------------------------------------------------------------------
    def _normalize_kuaishou_item(self, raw: dict) -> Optional[SocialSearchItem]:
        # 综合搜索的条目可能是 {"type":..,"photo":{...}}，视频搜索可能直接是作品体
        photo = raw.get("photo") if isinstance(raw.get("photo"), dict) else raw
        if not isinstance(photo, dict):
            return None

        item_id = str(_first_present(photo, "id", "photo_id") or "")
        caption = _strip_html(_first_present(photo, "caption", "title", "desc"))
        if not item_id and not caption:
            return None

        cover = photo.get("cover")
        cover_url = None
        if isinstance(cover, dict):
            cover_url = cover.get("url") or cover.get("uri")
        if not cover_url:
            cover_urls = photo.get("coverUrls") or photo.get("cover_urls") or []
            if isinstance(cover_urls, list) and cover_urls:
                first = cover_urls[0]
                cover_url = first.get("url") if isinstance(first, dict) else str(first)
        if not cover_url:
            cover_url = _first_present(photo, "thumbnailUrl", "thumbnail_url", "photoUrl", "cover_url")

        author = photo.get("author") or photo.get("user") or {}

        return SocialSearchItem(
            platform=Platform.KUAISHOU,
            note_id=item_id,
            title=caption[:60],
            description=caption,
            cover_url=cover_url,
            url=f"https://www.kuaishou.com/short-video/{item_id}" if item_id else "https://www.kuaishou.com",
            author_name=author.get("name") or author.get("user_name") or "",
            author_avatar=author.get("headerUrl") or author.get("header_url") or author.get("avatar"),
            like_count=_as_int(_first_present(photo, "realLikeCount", "likeCount", "like_count")),
            comment_count=_as_int(_first_present(photo, "commentCount", "comment_count")),
            share_count=_as_int(photo.get("shareCount")),
            publish_time=str(photo.get("timestamp") or photo.get("create_time") or ""),
            content_type="video",
        )

    async def search_kuaishou(self, keyword: str, limit: int = 8) -> SocialSearchResult:
        """Search Kuaishou via app comprehensive search (requires paid balance)."""
        endpoint = "/api/v1/kuaishou/app/search_comprehensive"
        params = {
            "keyword": keyword,
            "sort_type": "all",
            "publish_time": "all",
            "duration": "all",
        }
        data = await self._request(endpoint, params=params)

        payload = data.get("data")
        raw_items: List[dict] = []
        if isinstance(payload, list):
            raw_items = payload
        elif isinstance(payload, dict):
            for candidate in ("feeds", "items", "list", "videos"):
                node = payload.get(candidate)
                if isinstance(node, list):
                    raw_items = node
                    break

        items: List[SocialSearchItem] = []
        for raw in raw_items:
            try:
                item = self._normalize_kuaishou_item(raw)
                if item is not None:
                    items.append(item)
            except Exception as e:  # noqa: BLE001
                logger.warning(f"Failed to normalize Kuaishou item: {e}")
                continue
            if len(items) >= limit:
                break

        if not items and raw_items:
            logger.warning(f"Kuaishou normalized 0 items; sample keys: {list(raw_items[0].keys())[:20]}")

        return SocialSearchResult(
            platform=Platform.KUAISHOU,
            keyword=keyword,
            items=items,
            total=len(items),
        )

    # ------------------------------------------------------------------
    # Weibo (Web V2 realtime search — paid, balance required)
    # ------------------------------------------------------------------
    def _normalize_weibo_item(self, mblog: dict) -> SocialSearchItem:
        text = _strip_html(mblog.get("text"))
        item_id = str(mblog.get("id") or mblog.get("bid") or "")
        user = mblog.get("user") or {}

        cover_url = None
        pics = mblog.get("pics") or []
        if isinstance(pics, list) and pics:
            first_pic = pics[0]
            if isinstance(first_pic, dict):
                large = first_pic.get("large")
                cover_url = (large.get("url") if isinstance(large, dict) else None) or first_pic.get("url")

        title = text.split("\n")[0][:60] if text else "微博动态"
        is_video = bool(mblog.get("page_info") and (mblog.get("page_info") or {}).get("media_info"))

        return SocialSearchItem(
            platform=Platform.WEIBO,
            note_id=item_id,
            title=title,
            description=text,
            cover_url=cover_url,
            url=f"https://m.weibo.cn/detail/{item_id}" if item_id else "https://m.weibo.cn",
            author_name=user.get("screen_name") or "",
            author_avatar=user.get("profile_image_url") or user.get("avatar_hd"),
            like_count=_as_int(mblog.get("attitudes_count")),
            comment_count=_as_int(mblog.get("comments_count")),
            share_count=_as_int(mblog.get("reposts_count")),
            publish_time=str(mblog.get("created_at") or ""),
            content_type="video" if is_video else "normal",
        )

    async def search_weibo(self, keyword: str, limit: int = 8) -> SocialSearchResult:
        """Search Weibo realtime posts via web V2 (requires paid balance)."""
        endpoint = "/api/v1/weibo/web_v2/fetch_realtime_search"
        params = {"query": keyword, "page": 1}
        data = await self._request(endpoint, params=params)

        payload = data.get("data")
        cards = payload.get("cards") if isinstance(payload, dict) else None
        if not isinstance(cards, list):
            cards = payload if isinstance(payload, list) else []

        # card_type=9 直接是微博；部分卡片把微博放在 card_group 里
        mblogs: List[dict] = []
        for card in cards:
            if not isinstance(card, dict):
                continue
            if isinstance(card.get("mblog"), dict):
                mblogs.append(card["mblog"])
            for sub in card.get("card_group") or []:
                if isinstance(sub, dict) and isinstance(sub.get("mblog"), dict):
                    mblogs.append(sub["mblog"])

        items: List[SocialSearchItem] = []
        for mblog in mblogs:
            try:
                items.append(self._normalize_weibo_item(mblog))
            except Exception as e:  # noqa: BLE001
                logger.warning(f"Failed to normalize Weibo item: {e}")
                continue
            if len(items) >= limit:
                break

        if not items and cards:
            logger.warning(f"Weibo normalized 0 mblogs; sample card keys: {list(cards[0].keys())[:20]}")

        return SocialSearchResult(
            platform=Platform.WEIBO,
            keyword=keyword,
            items=items,
            total=len(items),
        )

    # ------------------------------------------------------------------
    # Account / balance
    # ------------------------------------------------------------------
    async def get_balance(self) -> Dict[str, Any]:
        """
        查询 TikHub 账户余额。
        返回: {balance, free_credit, total, email, key_status}
        """
        endpoint = "/api/v1/tikhub/user/get_user_info"
        data = await self._request(endpoint)

        user_data = data.get("user_data") or {}
        key_data = data.get("api_key_data") or {}
        balance = float(user_data.get("balance") or 0.0)
        free_credit = float(user_data.get("free_credit") or 0.0)
        return {
            "balance": balance,
            "free_credit": free_credit,
            "total": round(balance + free_credit, 4),
            "email": user_data.get("email"),
            "key_status": key_data.get("api_key_status"),
            "account_disabled": bool(user_data.get("account_disabled", False)),
        }
