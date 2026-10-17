"""Paid search requires platform selection in the real user's current message."""
import re
from contextvars import ContextVar
from .schemas import Platform

user_search_message = ContextVar("user_search_message", default="")
search_charge_confirmed = ContextVar("search_charge_confirmed", default=False)
direct_companion_search = ContextVar("direct_companion_search", default=False)
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


def companion_search_arguments(message):
    """Only explicit searches use the companion's user-authorized direct API path."""
    text = (message or "").strip()
    if re.search(r"(?:不要|别|不用|不想|取消).{0,8}(?:搜|查|找)", text):
        return None
    if not re.search(r"搜索|搜一下|搜一搜|帮我搜|帮我找|找一下|找找|查一下|查查", text):
        return None
    if re.search(r"(?:搜|找|查).{0,8}日记", text):
        return None
    aliases = (("小红书", "xhs"), ("抖音", "douyin"), ("b站", "bilibili"),
               ("知乎", "zhihu"), ("快手", "kuaishou"), ("微博", "weibo"))
    platform = next((value for name, value in aliases if name in text.lower()), "auto")
    return prepare_search_arguments({"keyword": text, "platform": platform, "limit": 8}, text)
