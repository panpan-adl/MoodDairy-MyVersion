"""Social search module for Xiaohongshu and Douyin content."""

from .client import SocialSearchClient, get_social_search_client
from .schemas import Platform, SocialSearchItem, SocialSearchResult

__all__ = [
    "Platform",
    "SocialSearchItem",
    "SocialSearchResult",
    "SocialSearchClient",
    "get_social_search_client",
]
