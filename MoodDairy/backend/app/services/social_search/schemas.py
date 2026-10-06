"""Social search data models."""

from __future__ import annotations

from enum import Enum
from typing import List, Optional

from pydantic import BaseModel


class Platform(str, Enum):
    """Social media platform."""

    XHS = "xhs"            # 小红书
    DOUYIN = "douyin"      # 抖音
    BILIBILI = "bilibili"  # B站
    ZHIHU = "zhihu"        # 知乎
    KUAISHOU = "kuaishou"  # 快手
    WEIBO = "weibo"        # 微博


class SocialSearchItem(BaseModel):
    """Single search result item."""

    platform: Platform
    note_id: str
    title: str
    description: str = ""
    cover_url: Optional[str] = None
    url: str
    author_name: str = ""
    author_avatar: Optional[str] = None
    like_count: int = 0
    comment_count: int = 0
    share_count: int = 0
    publish_time: Optional[str] = None
    content_type: str = "normal"  # normal | video


class SocialSearchResult(BaseModel):
    """Search result container."""

    platform: Platform
    keyword: str
    items: List[SocialSearchItem]
    total: int
