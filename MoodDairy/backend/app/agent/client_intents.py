"""Heuristic matcher for explicit in-app navigation commands.

Phase-1 agent turn runs without tools and returns on any text, so the LLM never
reaches tool execution. Short navigation intents are routed here so client_action
tools run deterministically.

覆盖自然口语说法（「写一篇日记」「记个日记」「帮我记一下待办」），
同时用否定/疑问词挡住「不想写日记」「日记怎么写」这类聊天。
"""

from __future__ import annotations

from datetime import date
from typing import Any, Dict, Optional, Tuple

MatchResult = Tuple[str, Dict[str, Any]]

# 唤醒词常被连在指令里（「团子打开日记」），先摘掉再匹配
_WAKE_WORDS = ("小团子", "团子", "团仔", "团紫", "团纸", "团只", "弹子", "坛子", "小嘉然")

# 否定/疑问说法不是导航指令，交给 LLM 聊天
_NEGATIONS = ("不要", "不想", "不", "没", "吗", "?", "？", "怎么", "怎样", "如何", "什么", "为啥", "为什么")

# 祈使句开头词（长句必须以这些开头才算指令）
_IMPERATIVES = (
    "打开", "请打开", "帮我打开", "我要打开", "去", "请帮", "帮我", "给我",
    "跳转", "进入", "写", "记", "我要", "我想", "想", "新建", "开始",
    "启动", "放点", "来点", "放", "做", "看看", "看", "播放", "播", "听",
)

# 白噪音声音名映射：用户说的 → WhiteNoiseSoundList 里的名字
_SOUND_NAMES = (
    "水滴", "雨声", "鸟鸣", "篝火", "海浪", "森林", "风声", "雷声",
    "猫咪呼噜", "猫叫", "钢琴", "白噪音", "粉红噪音", "棕噪音",
    "大雨", "小雨", "毛毛雨", "雷暴", "瀑布", "河流", "湖泊",
    "田野", "丛林", "咖啡厅", "图书馆", "办公室", "厨房",
    "飞机", "火车", "地铁", "人群", "交通", "玻璃雨声",
)


def _compact(s: str) -> str:
    return "".join(s.split())


def _extract_sound_name(text: str) -> Optional[str]:
    """从文本中提取白噪音声音名称（「播放水滴声」→ 水滴）"""
    for name in _SOUND_NAMES:
        if name in text:
            return name
    return None


def match_client_action_intent(message: str) -> Optional[MatchResult]:
    """Return (tool_name, arguments) for clear navigation-only commands, else None."""
    raw = message.strip()
    if not raw:
        return None
    c = _compact(raw)
    # 唤醒词只在句首才算（「团子打开日记」摘掉「团子」，但「和小嘉然聊天」里的「小嘉然」不是唤醒词）
    for w in _WAKE_WORDS:
        if c.startswith(w):
            c = c[len(w):]
    # 摘唤醒词后剩下的标点顺手去掉
    c = c.strip("，,.。！!？?：:、；;~～ ")
    if not c:
        return None

    from app.agent.support_intents import match_test_request, search_arguments
    test = match_test_request(raw)
    if test is not None:
        return ("open_test_hub", test)
    search = search_arguments(raw)
    if search:
        return ("search_social_content", search)
    if not any(word in c for word in ("不要", "不想", "不用", "取消")) and any(word in c for word in ("想听歌", "听点音乐", "放松一下", "打开音乐", "播放音乐", "推荐歌曲", "推荐几首")):
        return ("open_relax", {})

    today = date.today().isoformat()
    short = len(c) <= 24
    imperative = c.startswith(_IMPERATIVES) or (
        short and any(k in c for k in _IMPERATIVES)
    )
    # 短句直接看关键词；长句必须是祈使句（避免「今天天气不错顺便聊到日记」被误判）
    if not (short or imperative):
        return None
    # 否定/疑问不算指令（「我不想写日记」「白噪音是什么」）
    if any(k in c for k in _NEGATIONS):
        return None
    # 「别」只在句首才算否定，避免「性别」被误伤
    if c.startswith("别"):
        return None

    # ===== 顺序敏感：更具体的在前 =====

    # 否定白噪音/音乐指令（不要放白噪音）
    if any(word in c for word in ("不要", "不想", "不用", "取消")) and any(word in c for word in ("白噪音", "音乐", "听歌")):
        return None

    # Stop white noise
    if any(k in c for k in ("停止白噪音", "关闭白噪音", "别放了", "停止播放", "暂停播放", "停止声音")):
        return ("stop_white_noise", {})

    # Create diary with title/content（「创建日记叫小团」「写日记标题是小团」）
    if "日记" in c and any(k in c for k in ("创建", "写", "记", "新建")):
        import re
        content_match = re.search(r"(?:内容是|内容叫|正文是)(.+)", c)
        content = content_match.group(1).strip() if content_match else None
        title_text = c[:content_match.start()] if content_match else c
        title_match = re.search(r"(?:叫|标题是|标题叫|名称是|叫《)《?([一-龥A-Za-z0-9_\-]{1,20})》?", title_text)
        title = title_match.group(1) if title_match else None
        if title or content:
            payload: dict = {"diary_date": today}
            if title:
                payload["diary_title"] = title
            if content:
                payload["diary_content"] = content
            return ("open_diary_editor", payload)

    # Update profile
    if any(k in c for k in ("改", "修改", "换成", "变为")):
        import re
        m = re.search(r"(?:把)?昵称(?:改成叫|改成|改为|改叫|改)?[是为]?([一-龥A-Za-z0-9_]{1,15})", c)
        if m:
            return ("update_profile", {"field": "nickname", "value": m.group(1)})
        m = re.search(r"(?:把)?签名(?:改成叫|改成|改为|改叫|改)?[是为]?(.{1,50})", c)
        if m:
            return ("update_profile", {"field": "signature", "value": m.group(1).strip()})
        m = re.search(r"(?:把)?生日(?:改成|改为|改)?[是为]?(\d{4}[-年]\d{1,2}[-月]\d{1,2}日?)", c)
        if m:
            raw_birthday = m.group(1).replace("年", "-").replace("月", "-").replace("日", "")
            return ("update_profile", {"field": "birthday", "value": raw_birthday})
        m = re.search(r"(?:把)?性别(?:改成|改为|改)?[是为]?(男|女|保密)", c)
        if m:
            return ("update_profile", {"field": "gender", "value": m.group(1)})

    # Calendar / Home
    if any(k in c for k in ("回日历", "回到日历", "回首页", "回到首页", "回主页", "打开日历", "查看日历")):
        return ("open_calendar", {})

    # Chat with 小嘉然
    if any(k in c for k in ("和小嘉然聊", "打开聊天", "去聊天", "进入聊天", "和小嘉然说话")):
        return ("open_chat", {})

    # Pomodoro
    if any(k in c for k in ("番茄钟", "开始专注", "专注模式")):
        return ("open_pomodoro", {})

    # Highlights
    if any(k in c for k in ("高光时刻", "高光", "看看高光")):
        return ("open_highlights", {})

    # Little joys
    if any(k in c for k in ("小确幸", "小幸运", "看看小确幸")):
        return ("open_little_joys", {})

    # Growth portrait
    if any(k in c for k in ("成长画像", "成长报告", "我的成长", "看看成长")):
        return ("open_growth_portrait", {})

    # Live2D
    if any(k in c for k in ("live2d", "二次元", "虚拟形象")):
        return ("open_live2d", {})

    # Profile
    if any(k in c for k in ("个人主页", "我的资料", "个人信息", "打开资料")):
        return ("open_profile", {})

    # Diary search
    if any(k in c for k in ("搜索日记", "找日记", "查日记")):
        return ("open_diary_search", {})

    # Explore
    if any(k in c for k in ("打开探索", "去发现", "看看探索")):
        return ("open_explore", {})

    # White noise
    if any(k in c for k in ("白噪音", "白噪声", "白燥音", "雨声", "雨音")):
        sound_name = _extract_sound_name(c)
        open_only = any(k in c for k in ("打开", "进去", "跳转"))
        if sound_name and not open_only:
            return ("play_white_noise", {"sound_name": sound_name})
        return ("open_white_noise", {})
    sound_name = _extract_sound_name(c)
    if sound_name:
        return ("play_white_noise", {"sound_name": sound_name})

    # Test hub
    if any(k in c for k in ("测评", "心理测试", "测试中心", "测验", "测试")):
        return ("open_test_hub", {})

    # Drawing
    if any(k in c for k in ("画画", "画图", "画一张", "涂鸦", "简笔画", "绘画")):
        return ("open_drawing", {})

    # Diary editor — default today
    if any(k in c for k in ("日记", "日志", "日纪", "写日")):
        return ("open_diary_editor", {"diary_date": today})

    # Planning / todo
    if any(word in c for word in ("安排", "规划", "计划")) and any(word in c for word in ("学习", "工作", "今天", "任务")) and any(word in c for word in ("帮", "想", "请", "打开", "制定")):
        return ("open_todo_create", {"todo_date": today})

    # Todo create screen
    if any(k in c for k in ("待办", "代办", "待半", "待办界面", "待办页面", "打开待办", "新建待办", "待办事项", "待办列表")):
        return ("open_todo_create", {"todo_date": today})

    return None
