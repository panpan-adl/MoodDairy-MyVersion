"""Paid search requires platform selection in the real user's current message."""
import re
from contextvars import ContextVar
from .schemas import Platform

user_search_message = ContextVar("user_search_message", default="")
search_charge_confirmed = ContextVar("search_charge_confirmed", default=False)
PAID_NAMES = {
    Platform.XHS: ("小红书", "xiaohongshu", "xhs"),
    Platform.DOUYIN: ("抖音", "douyin"),
    Platform.KUAISHOU: ("快手", "kuaishou"),
    Platform.WEIBO: ("微博", "weibo"),
}

def allowed_paid_platform(platform, message):
    names = PAID_NAMES.get(platform)
    if not names:
        return True
    # Fail closed for negated mentions: never purchase a search based on AI args alone.
    for name in names:
        for match in re.finditer(re.escape(name), message.lower()):
            prefix = message[max(0, match.start()-8):match.start()]
            if not re.search(r"(?:不要|别|不搜|不用|不想|排除|不去|无需|不在|不是|don.t|not).*?$", prefix, re.I):
                return True
    return False

def prepare_search_arguments(arguments, message):
    cleaned = dict(arguments)
    aliases = {"小红书":"xhs", "xiaohongshu":"xhs", "抖音":"douyin", "b站":"bilibili", "bili":"bilibili", "哔哩哔哩":"bilibili", "知乎":"zhihu", "快手":"kuaishou", "ks":"kuaishou", "微博":"weibo", "wb":"weibo"}
    raw = str(cleaned.get("platform") or "auto").strip().lower()
    raw = aliases.get(raw, raw)
    try:
        platform = Platform(raw)
    except ValueError:
        platform = None
    cleaned["platform"] = platform.value if platform and allowed_paid_platform(platform, message) else "auto"
    cleaned["keyword"] = str(cleaned.get("keyword") or cleaned.get("query") or "").strip()[:200]
    try:
        cleaned["limit"] = max(1, min(20, int(cleaned.get("limit", 8))))
    except (TypeError, ValueError, OverflowError):
        cleaned["limit"] = 8
    return {key:cleaned[key] for key in ("platform", "keyword", "limit")}
