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
        # Check cache
        cache_key = self._make_cache_key(keyword, platform, limit)
        cached = await self._get_from_cache(cache_key)
        if cached is not None:
            return cached

        if not await self._rate_limiter.acquire():
            raise SocialSearchError("搜索过于频繁，请稍后重试", code="RATE_LIMITED")

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
        errors: List[SocialSearchError] = []

        async def _run_one(coro):
            try:
                res = await coro
                return res.items
            except SocialSearchError as e:
                errors.append(e)
                return []
            except Exception as e:  # noqa: BLE001
                errors.append(SocialSearchError("平台搜索暂时失败，请稍后重试", code="PROVIDER_ERROR"))
                return []

        for target in targets:
            method = provider_methods.get(target)
            if method is not None:
                results.extend(await _run_one(method(keyword, limit)))

        # 余额不足：不兜底 mock，直接上抛（前端引导用户真实充值）
        if not results and errors:
            raise errors[0]

        # Sort by like count (descending)
        results.sort(key=lambda x: x.like_count, reverse=True)

        # Cache result
        if not errors:
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


