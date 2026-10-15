"""Narrow, deterministic support requests; ordinary emotional chat stays conversational."""
import re

TESTS = {
    "mbti_simplified_v1": ("MBTI 人格探索", ("mbti", "人格", "性格")),
    "animal_personality_v1": ("动物原型", ("动物",)),
    "phq9_v1": ("情绪自评 PHQ-9", ("phq", "抑郁", "情绪图谱")),
    "gad7_v1": ("焦虑自评 GAD-7", ("gad", "焦虑")),
    "holland_simplified_v1": ("兴趣与潜能探索", ("霍兰德", "holland", "潜能", "职业兴趣")),
    "daily_mood_v1": ("每日心情记录", ("每日", "心情", "打卡")),
}


def match_test_request(message):
    text = re.sub(r"\s+", "", message).lower()
    if re.search(r"(?:不想|不要|不用|不做|别|取消).{0,8}(?:测|mbti)", text):
        return None
    if not any(word in text for word in ("测", "mbti", "phq", "gad")):
        return None
    if not any(word in text for word in ("想", "我要", "帮", "打开", "做", "开始", "测一下", "测测", "测个", "测试中心", "历史")):
        return None
    if "历史" in text or "结果" in text:
        return {"history": True}
    matches = [key for key, (_, aliases) in TESTS.items() if any(alias in text for alias in aliases)]
    return {"test_id": matches[0]} if len(matches) == 1 else {}


def is_psychology_search(message, keyword=""):
    """Both a resource request and a psychological subject must be explicit."""
    text = (message or "").lower()
    if re.search(r"(?:不要|不用|别|不想|取消).{0,6}(?:搜|查找|找书|找纪录片)", text):
        return False
    resource = any(word in text for word in ("书", "纪录片", "科普", "视频", "教程", "文章", "链接", "资料", "片子", "片单"))
    request = any(word in text for word in ("搜", "找", "推荐", "有没有", "哪里", "看看", "想看", "想读", "想了解"))
    subject = any(word in text for word in ("心理", "情绪", "焦虑", "抑郁", "压力", "冥想", "放松", "助眠", "正念", "自我接纳", "自我成长", "疗愈", "治愈", "孤独", "亲密关系", "人际关系", "心理医生"))
    return resource and request and subject


def search_arguments(message):
    if not is_psychology_search(message):
        return None
    platforms = (("小红书", "xhs"), ("抖音", "douyin"), ("b站", "bilibili"), ("知乎", "zhihu"), ("快手", "kuaishou"), ("微博", "weibo"))
    platform = next((value for name, value in platforms if name in message.lower()), "auto")
    return {"keyword": message.strip()[:200], "platform": platform, "limit": 8}


def resource_label(title, description=""):
    """Conservative labels: a search hit is never assumed to be a full work."""
    text = f"{title} {description}"
    if "书评" in text or "读后感" in text:
        return "书评 / 阅读分享"
    if "书" in text or "阅读" in text:
        return "书籍介绍 / 相关内容"
    if "纪录片" in text:
        return "纪录片介绍 / 解说等相关内容"
    return "心理科普 / 放松相关内容"
