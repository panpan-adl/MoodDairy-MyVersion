"""Authenticated functional smoke test. Uses a dedicated QA account and removes its diary/todo."""
import io
import json
import os
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path

import requests
from PIL import Image

BASE = os.getenv("SMOKE_BASE_URL", "http://127.0.0.1:8001").rstrip("/")
S = requests.Session()
results = []

def check(label, method, path, expected=(200,), validate=None, **kwargs):
    started = time.monotonic()
    try:
        response = S.request(method, BASE + path, timeout=kwargs.pop("timeout", 30), **kwargs)
        ok = response.status_code in expected
        if ok and validate:
            ok = bool(validate(response))
        item = {"feature": label, "passed": ok, "status": response.status_code,
                "seconds": round(time.monotonic() - started, 2)}
        if not ok:
            item["error"] = response.text[:500]
        results.append(item)
        print(json.dumps(item, ensure_ascii=True), flush=True)
        return response
    except Exception as exc:
        item = {"feature": label, "passed": False, "error": type(exc).__name__ + ": " + str(exc)}
        results.append(item)
        print(json.dumps(item), flush=True)
        return None

def main():
    today = datetime.now().astimezone().date()
    username = "qa_" + uuid.uuid4().hex[:16]
    password = uuid.uuid4().hex
    diary_id = todo_id = None
    uid = None
    try:
        check("health/database", "GET", "/health", validate=lambda r:r.json()["database"] == "connected")
        check("authentication required", "GET", "/todos/", (401,), params={"date": str(today)})
        registered = check("registration", "POST", "/api/users/register", (201,), json={"username": username, "password": password, "nickname": "Functional QA"})
        if registered is None or registered.status_code != 201:
            return
        data = registered.json()
        uid = data["id"]
        S.headers["Authorization"] = "Bearer " + data["access_token"]
        check("duplicate registration", "POST", "/api/users/register", (400,409), json={"username": username,"password":password})
        check("wrong password", "POST", "/api/users/login", (401,), json={"username":username,"password":"incorrect_password"})
        check("login", "POST", "/api/users/login", json={"username":username,"password":password},validate=lambda r:bool(r.json().get("access_token")))
        check("read profile", "GET", f"/api/users/{uid}")
        check("update profile", "PUT", f"/api/users/{uid}", json={"nickname":"QA Updated"},validate=lambda r:r.json()["nickname"] == "QA Updated")
        check("cross-user profile denied", "GET", f"/api/users/{uid+100000}", (403,404))
        created = check("create diary", "POST", "/diaries/", (201,), json={"user_id":uid,"diary_date":str(today),"title":"QA diary","content":"今天很开心，functional_probe。","mood_score":80})
        if created is not None and created.status_code == 201:
            diary_id = created.json()["id"]
            check("decrypt diary", "GET", f"/diaries/id/{diary_id}",validate=lambda r:r.json()["content"] == "今天很开心，functional_probe。")
            check("date diary list", "GET", f"/diaries/{today}/all",validate=lambda r:len(r.json()) >= 1)
            check("calendar diary dates", "GET", "/diaries/dates-with-diary",params={"year":today.year,"month":today.month})
            check("update and mark highlights", "PUT", f"/diaries/{diary_id}", json={"title":"Updated QA diary","is_highlight":1,"is_little_joy":1},validate=lambda r:r.json()["title"] == "Updated QA diary")
            check("highlights", "GET", "/diaries/highlights")
            check("little joys", "GET", "/diaries/little-joys")
            check("keyword diary search", "GET", "/diaries/search",params={"query":"functional_probe"},validate=lambda r:any(x["id"]==diary_id for x in r.json()))
            check("summaries", "GET", "/diaries/summaries")
            check("extract status", "GET", f"/diaries/{diary_id}/extract/status")
            check("diary insight", "GET", f"/insights/diaries/{diary_id}")
            check("drawing prompt recommendations", "GET", f"/drawing/{diary_id}/prompt-recommendations")
            image=io.BytesIO()
            Image.new("RGB", (16,16), (60,120,180)).save(image,"PNG")
            upload=check("image upload", "POST", "/media/upload", (200,201),data={"diary_id":str(diary_id),"media_type":"image"},files={"file":("qa.png",image.getvalue(),"image/png")})
            if upload is not None and upload.status_code in (200,201):
                u=upload.json()
                check("sync diary media", "PUT", f"/diaries/{diary_id}/media",json={"items":[{"asset_id":u["media_id"],"media_type":"image","file_size":u["file_size"],"sort_order":0}]})
                check("aggregated diary detail", "GET", f"/diaries/id/{diary_id}/detail",validate=lambda r:bool(r.json().get("media_items_aggregated")))
                url=u.get("media_url") or u.get("url") or u.get("file_url")
                if url and url.startswith("/"):
                    check("static media HEAD", "HEAD",url)
                    check("static media suffix range", "GET",url,(206,),headers={"Range":"bytes=-10"},validate=lambda r:len(r.content)==10)
        check("future diary rejected", "POST", "/diaries/", (422,),json={"user_id":uid,"diary_date":str(today+timedelta(days=1))})
        check("text emotion analysis", "POST", "/voice/analyze-emotion",data={"text":"今天很开心"})
        check("summary export", "GET", "/diaries/summaries/export",params={"year":today.year,"month":today.month,"format":"json"})
        todo=check("create todo", "POST", "/todos/",(201,),json={"user_id":uid,"todo_date":str(today),"title":"QA task"})
        if todo is not None and todo.status_code == 201:
            todo_id=todo.json()["id"]
            check("list todos", "GET", "/todos/",params={"date":str(today)},validate=lambda r:any(x["id"]==todo_id for x in r.json()))
            check("complete todo", "PUT", f"/todos/{todo_id}",json={"is_done":1},validate=lambda r:r.json()["is_done"]==1)
        check("invalid todo date", "GET", "/todos/", (400,422),params={"date":"invalid"})
        check("growth portrait", "GET", "/insights/growth-portrait")
        check("invalid growth window", "GET", "/insights/growth-portrait", (400,422),params={"days":1})
        check("face emotion report", "POST", "/face-emotion/report",json={"emotion":"happy","confidence":0.95,"captured_at":datetime.now().astimezone().isoformat(),"facial_expression":"smile"})
        check("invalid face label", "POST", "/face-emotion/report",(422,),json={"emotion":"invalid","confidence":0.9,"captured_at":datetime.now().astimezone().isoformat()})
        check("music recommendations upstream", "GET", "/music/recommendations",params={"mood":"calm","limit":2},validate=lambda r:len(r.json()["tracks"])>0)
        for path in ["/drawing/mode","/drawing/enabled","/drawing/sd-enabled","/social-search/status","/personal-videos"]:
            check(path,"GET",path)
        check("personal import needs account", "POST", "/personal-videos/import",(400,422),json={"platform":"douyin"})
        if os.getenv("SMOKE_CHAT") == "1":
            check("real AI chat", "POST", "/chat",json={"user_id":uid,"conversation_id":"qa_"+uuid.uuid4().hex,"message":"你好，请简短回复一句问候。"},timeout=120)
    finally:
        if todo_id:
            check("delete todo", "DELETE",f"/todos/{todo_id}",(204,))
        if diary_id:
            check("delete diary", "DELETE",f"/diaries/{diary_id}",(204,))
        output={"base_url":BASE,"qa_user_id":uid,"results":results,"passed":sum(x["passed"] for x in results),"failed":sum(not x["passed"] for x in results)}
        Path("logs/functional-smoke.json").write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding="utf-8")
        print(json.dumps({k:output[k] for k in ["passed","failed"]}),flush=True)

if __name__ == "__main__":
    main()
