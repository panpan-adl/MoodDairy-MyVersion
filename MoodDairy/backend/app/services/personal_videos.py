"""User-owned video index. Never persists platform cookies or fabricates results."""
import asyncio
import json
import re
import sqlite3
from urllib.parse import urlparse, parse_qs, unquote
from pathlib import Path
from app.config import settings
from app.services.social_search.tikhub_provider import SocialSearchError


def normalize_source(platform, value):
    """Parse IDs locally; never follow untrusted links or forward pasted share text."""
    value = value.strip()
    if platform == "douyin" and re.fullmatch(r"MS4w[A-Za-z0-9_-]+", value):
        return value
    if platform == "bilibili" and re.fullmatch(r"[0-9]{1,30}", value):
        return value
    match = re.search(r"https?://[^\s]+", value)
    if match:
        parsed = urlparse(match.group(0))
        host = (parsed.hostname or "").lower()
        if platform == "douyin" and host in ("douyin.com", "www.douyin.com"):
            account = unquote(parsed.path).removeprefix("/user/").strip("/") if parsed.path.startswith("/user/") else ""
            if re.fullmatch(r"MS4w[A-Za-z0-9_-]+", account):
                return account
        if platform == "bilibili" and host == "space.bilibili.com":
            folder = parse_qs(parsed.query).get("fid", [""])[0]
            if re.fullmatch(r"[0-9]{1,30}", folder):
                return folder
    raise ValueError("请粘贴抖音完整个人主页链接或 MS4w 开头的账号编号（短链接暂不支持）" if platform == "douyin" else "请粘贴包含 fid 的 B站收藏夹网页链接或收藏夹编号")


def database_path():
    return Path(settings.upload_dir).resolve() / "personal_videos.sqlite3"


def connect():
    path = database_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path, timeout=10)
    db.execute("CREATE TABLE IF NOT EXISTS videos (user_id INTEGER, platform TEXT, video_id TEXT, payload TEXT NOT NULL, imported_at TEXT DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(user_id, platform, video_id))")
    return db


def save(user_id, items):
    with connect() as db:
        for item in items:
            db.execute("INSERT INTO videos(user_id,platform,video_id,payload) VALUES(?,?,?,?) ON CONFLICT(user_id,platform,video_id) DO UPDATE SET payload=excluded.payload, imported_at=CURRENT_TIMESTAMP", (user_id,item["platform"],item["note_id"],json.dumps(item,ensure_ascii=False)))
    return len(items)


def list_items(user_id, platform=None):
    with connect() as db:
        rows = db.execute("SELECT payload FROM videos WHERE user_id=? AND (? IS NULL OR platform=?) ORDER BY imported_at DESC", (user_id,platform,platform)).fetchall()
    return [json.loads(row[0]) for row in rows]


def delete_items(user_id, platform):
    with connect() as db:
        db.execute("DELETE FROM videos WHERE user_id=? AND platform=?",(user_id,platform))


def search(user_id, query, terms=(), platform=None, limit=6):
    # The assistant supplies synonyms from the description; search stays within this user's index.
    words = [w.strip().lower() for w in [query, *terms] if isinstance(w,str) and w.strip()]
    ranked=[]
    for item in list_items(user_id,platform):
        text=" ".join(str(item.get(k) or "") for k in ("title","description","author_name")).lower()
        score=sum(10 for w in words if w in text)
        grams={w[i:i+2] for w in words for i in range(len(w)-1) if not w[i:i+2].isspace()}
        score+=sum(1 for gram in grams if gram in text)
        if score:
            ranked.append((score,item))
    ranked.sort(key=lambda pair:pair[0],reverse=True)
    return [item for _,item in ranked[:limit]]


def unwrap(response):
    if response.get("code") not in (None,0,200):
        raise SocialSearchError("平台读取失败，请检查登录状态和可见权限", code="IMPORT_FAILED")
    data=response.get("data")
    if not isinstance(data,dict):
        raise SocialSearchError("平台返回格式不匹配，未导入任何视频",code="IMPORT_FAILED")
    if isinstance(data.get("data"),dict):
        if data.get("code") not in (None,0,200):
            raise SocialSearchError("平台拒绝读取，请检查可见权限",code="IMPORT_FAILED")
        data=data["data"]
    if data.get("status_code") not in (None,0):
        raise SocialSearchError("平台登录已失效或无读取权限",code="IMPORT_FAILED")
    return data


async def import_page(provider, user_id, platform, account, cookie="", cursor="0", folder_id=""):
    if platform=="douyin":
        if not cookie.strip():
            raise SocialSearchError("请在专用导入页面提供本人的抖音网页登录信息",code="LOGIN_REQUIRED")
        response=await provider._request("/api/v1/douyin/web/fetch_user_like_videos", method="POST", json_body={"sec_user_id":account,"max_cursor":int(cursor),"counts":20,"cookie":cookie})
        data=unwrap(response)
        records=data.get("aweme_list")
        if not isinstance(records,list):
            raise SocialSearchError("未能读取喜欢列表，请检查登录状态或隐私设置",code="IMPORT_FAILED")
        items=[provider._normalize_douyin_item({**record,"create_time":str(record.get("create_time") or "")}).model_dump(mode="json") for record in records]
        next_cursor=str(data.get("max_cursor",cursor)); has_more=bool(data.get("has_more"))
        source="抖音喜欢"
    else:
        response=await provider._request("/api/v1/bilibili/web/fetch_user_collection_videos",params={"folder_id":folder_id,"pn":max(1,int(cursor))})
        data=unwrap(response)
        records=data.get("medias")
        if not isinstance(records,list):
            raise SocialSearchError("收藏夹不可读取或返回格式不匹配，请确认是可访问的收藏夹",code="IMPORT_FAILED")
        items=[]
        for record in records:
            raw={**record,"pic":record.get("cover"),"author":(record.get("upper") or {}).get("name"),"description":record.get("intro")}
            items.append(provider._normalize_bilibili_item(raw).model_dump(mode="json"))
        next_cursor=str(max(1,int(cursor))+1); has_more=bool(data.get("has_more"))
        source="B站收藏"
    # A stuck cursor must not cause a batch to repeatedly purchase the same page.
    if has_more and (not next_cursor.isdigit() or next_cursor == str(int(cursor))):
        raise SocialSearchError("平台分页未前进，已停止导入，请稍后重试", code="IMPORT_FAILED")
    items=[{**item,"record_source":source} for item in items if item.get("note_id") and item.get("url")]
    await asyncio.to_thread(save,user_id,items)
    return {"imported":len(items),"has_more":has_more,"next_cursor":next_cursor,"source":source}
