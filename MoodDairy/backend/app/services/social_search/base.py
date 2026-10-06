"""Base class for social search providers."""

from __future__ import annotations

from abc import ABC, abstractmethod

from .schemas import SocialSearchResult


class BaseSocialSearchProvider(ABC):
    """Abstract base class for social search providers."""

    name: str

    @abstractmethod
    async def search_xhs(self, keyword: str, limit: int = 8) -> SocialSearchResult:
        """Search Xiaohongshu (Little Red Book)."""
        ...

    @abstractmethod
    async def search_douyin(self, keyword: str, limit: int = 8) -> SocialSearchResult:
        """Search Douyin (TikTok China)."""
        ...
