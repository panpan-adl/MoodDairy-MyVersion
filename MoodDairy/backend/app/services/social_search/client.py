"""Social search client with caching and rate limiting."""

from __future__ import annotations

import asyncio
import logging
import time
from collections import defaultdict
from typing import Dict, List, Optional, Tuple

from app.config import settings

from .base import BaseSocialSearchProvider
from .schemas import Platform, SocialSearchItem, SocialSearchResult
from .tikhub_provider import SocialSearchError, TikHubProvider

logger = logging.getLogger(__name__)


class TokenBucket:
    """Simple token bucket rate limiter."""

    def __init__(self, rate_per_minute: int):
        self._rate = rate_per_minute
        self._tokens = rate_per_minute
        self._last_update = time.monotonic()
        self._lock = asyncio.Lock()

    async def acquire(self) -> bool:
        """Try to acquire a token. Returns True if allowed."""
        async with self._lock:
            now = time.monotonic()
            elapsed = now - self._last_update
            self._tokens = min(self._rate, self._tokens + elapsed * (self._rate / 60))
            self._last_update = now

            if self._tokens >= 1:
                self._tokens -= 1
                return True
            return False


class SocialSearchClient:
    """Unified social search client with caching and rate limiting."""

    def __init__(self, provider: BaseSocialSearchProvider, settings):
        self._provider = provider
        self._settings = settings
        self._cache: Dict[str, Tuple[float, List[SocialSearchItem]]] = {}
        self._rate_limiter = TokenBucket(settings.social_search_rate_limit_per_minute)
        self._cache_lock = asyncio.Lock()

    def _make_cache_key(self, keyword: str, platform: Optional[Platform], limit: int) -> str:
        """Generate cache key."""
        platform_str = platform.value if platform else "auto"
        return f"{platform_str}:{keyword}:{limit}"

    async def _get_from_cache(self, key: str) -> Optional[List[SocialSearchItem]]:
        """Get cached result if not expired."""
        async with self._cache_lock:
            if key in self._cache:
                timestamp, items = self._cache[key]
                if time.time() - timestamp < self._settings.social_search_cache_ttl_seconds:
                    logger.info(f"Cache hit for social search: {key}")
                    return items
                else:
                    del self._cache[key]
        return None

    async def _set_cache(self, key: str, items: List[SocialSearchItem]) -> None:
        """Store result in cache."""
        async with self._cache_lock:
            self._cache[key] = (time.time(), items)

    async def search(
        self,
        keyword: str,
        platform: Optional[Platform] = None,
        limit: int = 8,
    ) -> List[SocialSearchItem]:
        """
        Search social content.

        Args:
            keyword: Search keyword
            platform: Target platform (None = 自动组合：仅免费平台 B站/知乎)
            limit: Max results per platform

        Returns:
            Combined list of search results
        """
        # Check rate limit
        if not await self._rate_limiter.acquire():
            logger.warning("Social search rate limit exceeded")
            raise SocialSearchError("Rate limit exceeded")

        # Check cache
        cache_key = self._make_cache_key(keyword, platform, limit)
        cached = await self._get_from_cache(cache_key)
        if cached is not None:
            return cached

        # 默认自动组合只含免费平台（B站+知乎，走 TikHub 免费额度）；
        # 小红书/抖音/快手/微博均为付费接口，只有用户/模型明确指定平台时才请求，避免预期外扣费。
        targets: List[Platform] = [platform] if platform else [
            Platform.BILIBILI,
            Platform.ZHIHU,
        ]
        provider_methods = {
            Platform.XHS: self._provider.search_xhs,
            Platform.DOUYIN: self._provider.search_douyin,
            Platform.BILIBILI: self._provider.search_bilibili,
            Platform.ZHIHU: self._provider.search_zhihu,
            Platform.KUAISHOU: self._provider.search_kuaishou,
            Platform.WEIBO: self._provider.search_weibo,
        }

        # Perform search
        results: List[SocialSearchItem] = []
        balance_errors: List[str] = []

        async def _run_one(coro):
            try:
                res = await coro
                return res.items
            except SocialSearchError as e:
                if e.code == "NO_BALANCE":
                    balance_errors.append(str(e))
                else:
                    logger.error(f"Social search provider error [{e.code}]: {e}")
                return []
            except Exception as e:  # noqa: BLE001
                logger.error(f"Social search failed: {e}")
                return []

        for target in targets:
            method = provider_methods.get(target)
            if method is not None:
                results.extend(await _run_one(method(keyword, limit)))

        # 余额不足：不兜底 mock，直接上抛（前端引导用户真实充值）
        if not results and balance_errors:
            raise SocialSearchError(balance_errors[0], code="NO_BALANCE")

        # Sort by like count (descending)
        results.sort(key=lambda x: x.like_count, reverse=True)

        # Mock 兜底：网络/解析等非余额类失败时返回演示数据
        if not results:
            logger.info(f"Social search returned empty for '{keyword}', using mock demo data")
            results = _build_mock_results(keyword, platform, limit)

        # Cache result
        await self._set_cache(cache_key, results)

        logger.info(f"Social search completed: {len(results)} results for '{keyword}'")
        return results

    async def close(self):
        """Close the client and cleanup resources."""
        pass

    async def get_balance(self) -> Dict[str, Any]:
        """查询上游账户余额（透传 Provider）。"""
        return await self._provider.get_balance()


# Singleton instance
_social_search_client: Optional[SocialSearchClient] = None


def get_social_search_client() -> Optional[SocialSearchClient]:
    """Get or create social search client singleton."""
    global _social_search_client

    if _social_search_client is None:
        if not settings.social_search_enabled:
            logger.info("Social search is disabled")
            return None

        if not settings.tikhub_api_key:
            logger.warning("TIKHUB_API_KEY not configured")
            return None

        provider = TikHubProvider(
            api_key=settings.tikhub_api_key,
            base_url=settings.tikhub_base_url,
            timeout=settings.tikhub_timeout_seconds,
        )
        _social_search_client = SocialSearchClient(provider, settings)
        logger.info("Social search client initialized")

    return _social_search_client


def reset_social_search_client():
    """Reset the singleton (for testing)."""
    global _social_search_client
    _social_search_client = None


# ============================================================================
# 按用户自己的 API Key 构建/缓存 client（BYOK 模式：每个用户用各自的 TikHub 账户）
# ============================================================================
_user_clients: Dict[str, "SocialSearchClient"] = {}


def get_social_search_client_for_key(api_key: str) -> "SocialSearchClient":
    """根据指定的 TikHub API Key 获取（或创建并缓存）搜索 client。"""
    client = _user_clients.get(api_key)
    if client is None:
        provider = TikHubProvider(
            api_key=api_key,
            base_url=settings.tikhub_base_url,
            timeout=settings.tikhub_timeout_seconds,
        )
        client = SocialSearchClient(provider, settings)
        _user_clients[api_key] = client
    return client


def drop_user_client(api_key: str) -> None:
    """用户解绑/更换密钥时清掉缓存。"""
    _user_clients.pop(api_key, None)


# ============================================================================
# Mock 演示数据（真实 API 不可用时兜底，仅用于学生项目演示）
# ============================================================================
_MOCK_COVERS = [
    "https://picsum.photos/seed/mood1/400/300",
    "https://picsum.photos/seed/mood2/400/300",
    "https://picsum.photos/seed/mood3/400/300",
    "https://picsum.photos/seed/mood4/400/300",
    "https://picsum.photos/seed/mood5/400/300",
    "https://picsum.photos/seed/mood6/400/300",
]

_MOCK_PLATFORM_META = {
    Platform.XHS: {
        "authors": ["治愈系小日常", "心情研究所", "慢生活日记", "光与盐", "云上漫步"],
        "url": "https://www.xiaohongshu.com/explore/mock_{pfx}_{i}",
        "title": "{keyword}｜第{n}篇治愈笔记",
        "desc": "关于{keyword}的一些心得体会，希望能给你带来一点温暖。",
        "video": False,
    },
    Platform.DOUYIN: {
        "authors": ["情绪治愈馆", "心理疗愈师", "晚安电台", "正念冥想", "暖心日常"],
        "url": "https://www.douyin.com/video/mock_{pfx}_{i}",
        "title": "{keyword} 治愈视频 #{n}",
        "desc": "一个关于{keyword}的短视频，愿你今天也被温柔以待。",
        "video": True,
    },
    Platform.BILIBILI: {
        "authors": ["冥想自习室", "心理学科普站", "放松白噪音", "治愈放映厅"],
        "url": "https://www.bilibili.com/video/mock_{pfx}_{i}",
        "title": "【{keyword}】完整引导版 #{n}",
        "desc": "关于{keyword}的长视频，慢慢看，陪你放松下来。",
        "video": True,
    },
    Platform.ZHIHU: {
        "authors": ["心理咨询师小然", "情绪观察者", "答主慢慢来", "树洞管理员"],
        "url": "https://www.zhihu.com/question/mock_{pfx}_{i}",
        "title": "关于{keyword}，我认真回答了 #{n}",
        "desc": "从心理学角度聊聊{keyword}，希望对你有帮助。",
        "video": False,
    },
    Platform.KUAISHOU: {
        "authors": ["暖心大侄子", "乡村慢生活", "快乐源泉", "睡前一笑"],
        "url": "https://www.kuaishou.com/short-video/mock_{pfx}_{i}",
        "title": "{keyword} 快手小视频 #{n}",
        "desc": "来自快手的{keyword}内容，简单真实，看着看着就放松了。",
        "video": True,
    },
    Platform.WEIBO: {
        "authors": ["每日治愈播报", "情绪树洞菌", "暖心收集站", "晚安博主"],
        "url": "https://m.weibo.cn/detail/mock_{pfx}_{i}",
        "title": "#{keyword}# 微博动态 #{n}",
        "desc": "关于{keyword}的一条微博，愿你被世界温柔以待。",
        "video": False,
    },
}

_MOCK_LIKES = [1280, 3560, 892, 5400, 2100, 6700]
_MOCK_COMMENTS = [86, 230, 45, 410, 156, 320]


def _build_mock_results(
    keyword: str,
    platform: Optional[Platform],
    limit: int,
) -> List[SocialSearchItem]:
    """生成演示用的搜索结果，封面用 picsum 占位图。"""
    if platform is not None:
        wanted = [platform]
    else:
        # 与真实调度保持一致：auto 只兜底免费平台
        wanted = [Platform.BILIBILI, Platform.ZHIHU]

    per_platform = max(1, limit // len(wanted)) if len(wanted) > 1 else limit

    items: List[SocialSearchItem] = []
    for pf in wanted:
        meta = _MOCK_PLATFORM_META[pf]
        for i in range(per_platform):
            pfx = pf.value
            items.append(
                SocialSearchItem(
                    platform=pf,
                    note_id=f"mock_{pfx}_{i}",
                    title=meta["title"].format(keyword=keyword, n=i + 1),
                    description=meta["desc"].format(keyword=keyword),
                    cover_url=_MOCK_COVERS[(len(items)) % len(_MOCK_COVERS)],
                    url=meta["url"].format(pfx=pfx, i=i),
                    author_name=meta["authors"][i % len(meta["authors"])],
                    like_count=_MOCK_LIKES[i % len(_MOCK_LIKES)],
                    comment_count=_MOCK_COMMENTS[i % len(_MOCK_COMMENTS)],
                    content_type="video" if meta["video"] else "normal",
                )
            )

    items.sort(key=lambda x: x.like_count, reverse=True)
    return items[:limit]
