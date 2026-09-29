"""Manual HTTP smoke test for a running backend service.

This module is intentionally skipped by automated pytest runs because it
expects a real server and a configured local environment.
"""

from __future__ import annotations

import base64
import os
import time
from datetime import date

import pytest
import requests

pytestmark = pytest.mark.skip(reason="manual end-to-end smoke script; run directly with a live server")

BASE_URL = os.getenv("SMOKE_BASE_URL", "http://localhost:8000").rstrip("/")
ENABLE_CHAT = os.getenv("SMOKE_CHAT", "0").strip().lower() in {"1", "true", "yes", "on"}
CHAT_TIMEOUT_SECONDS = int(os.getenv("SMOKE_CHAT_TIMEOUT", "100"))

# 1x1 transparent PNG
PNG_BYTES = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5r8AAAAASUVORK5CYII="
)


def preview_text(value: str, limit: int = 300) -> str:
    return value.encode("unicode_escape").decode("ascii")[:limit]


def check_health() -> bool:
    response = requests.get(f"{BASE_URL}/health", timeout=10)
    print("health:", response.status_code, response.json())
    return response.status_code == 200 and response.json().get("status") == "healthy"


def register_user():
    username = f"testuser_{int(time.time())}"
    response = requests.post(
        f"{BASE_URL}/api/users/register",
        json={"username": username, "password": "test123456", "nickname": "test-user"},
        timeout=10,
    )
    print("register:", response.status_code, preview_text(response.text, 200))
    return username, response


def login_user(username: str, password: str = "test123456"):
    response = requests.post(
        f"{BASE_URL}/api/users/login",
        json={"username": username, "password": password},
        timeout=10,
    )
    print("login:", response.status_code, preview_text(response.text, 200))
    return response


def get_user(user_id: int):
    response = requests.get(
        f"{BASE_URL}/api/users/{user_id}",
        timeout=10,
    )
    print("get_user:", response.status_code, preview_text(response.text, 200))
    return response


def create_diary(user_id: int):
    today = date.today().isoformat()
    response = requests.post(
        f"{BASE_URL}/diaries/",
        json={
            "user_id": user_id,
            "title": "integration smoke diary",
            "content": "created by the manual HTTP smoke test",
            "diary_date": today,
            "weather": "sunny",
            "location": "local-smoke",
            "mood_score": 80,
        },
        timeout=10,
    )
    print("create_diary:", response.status_code, preview_text(response.text, 300))
    return response


def get_diary_by_date(user_id: int):
    today = date.today().isoformat()
    response = requests.get(
        f"{BASE_URL}/diaries/{today}",
        params={"user_id": user_id},
        timeout=10,
    )
    print("get_diary_by_date:", response.status_code, preview_text(response.text, 300))
    return response


def get_diary_by_id(diary_id: int):
    response = requests.get(
        f"{BASE_URL}/diaries/id/{diary_id}",
        timeout=10,
    )
    print("get_diary_by_id:", response.status_code, preview_text(response.text, 300))
    return response


def get_dates_with_diary(user_id: int):
    today = date.today()
    response = requests.get(
        f"{BASE_URL}/diaries/dates-with-diary",
        params={"user_id": user_id, "year": today.year, "month": today.month},
        timeout=10,
    )
    print("get_dates_with_diary:", response.status_code, preview_text(response.text, 300))
    return response


def upload_media(diary_id: int):
    response = requests.post(
        f"{BASE_URL}/media/upload",
        data={"diary_id": str(diary_id), "media_type": "image"},
        files={"file": ("tiny.png", PNG_BYTES, "image/png")},
        timeout=20,
    )
    print("upload_media:", response.status_code, preview_text(response.text, 300))
    return response


def sync_media(diary_id: int, asset_id: int, file_size: int):
    response = requests.put(
        f"{BASE_URL}/diaries/{diary_id}/media",
        json={
            "items": [
                {
                    "asset_id": asset_id,
                    "media_type": "image",
                    "file_size": file_size,
                    "sort_order": 0,
                }
            ]
        },
        timeout=15,
    )
    print("sync_media:", response.status_code, preview_text(response.text, 300))
    return response


def get_diary_detail(diary_id: int, user_id: int):
    response = requests.get(
        f"{BASE_URL}/diaries/id/{diary_id}/detail",
        params={"user_id": user_id},
        timeout=15,
    )
    print("get_diary_detail:", response.status_code, preview_text(response.text, 300))
    return response


def delete_diary(diary_id: int, user_id: int):
    response = requests.delete(
        f"{BASE_URL}/diaries/{diary_id}",
        params={"user_id": user_id},
        timeout=15,
    )
    print("delete_diary:", response.status_code, preview_text(response.text, 200))
    return response


def chat(user_id: int):
    response = requests.post(
        f"{BASE_URL}/chat/",
        json={"user_id": user_id, "message": "manual smoke test message"},
        timeout=CHAT_TIMEOUT_SECONDS,
    )
    print("chat:", response.status_code, preview_text(response.text, 300))
    return response


def record_result(results: dict[str, int], ok: bool, label: str) -> None:
    results["passed" if ok else "failed"] += 1
    print(f"[{'PASS' if ok else 'FAIL'}] {label}")


def run_all_tests():
    print("=" * 60)
    print("Manual integration smoke test")
    print(f"BASE_URL={BASE_URL}")
    print(f"ENABLE_CHAT={ENABLE_CHAT}")
    print("=" * 60)

    results = {"passed": 0, "failed": 0, "skipped": 0}
    diary_id = None
    user_id = None

    try:
        try:
            record_result(results, check_health(), "health")
        except Exception as exc:
            print(f"health check failed: {exc}")
            results["failed"] += 1
            return results

        username, register_response = register_user()
        if register_response.status_code not in (200, 201):
            results["failed"] += 1
            return results

        results["passed"] += 1
        user_data = register_response.json()
        user_id = user_data.get("id") or user_data.get("user_id")

        login_response = login_user(username)
        record_result(results, login_response.status_code == 200, "login")

        user_response = get_user(user_id)
        record_result(results, user_response.status_code == 200, "get_user")

        create_response = create_diary(user_id)
        create_ok = create_response.status_code in (200, 201)
        record_result(results, create_ok, "create_diary")
        if not create_ok:
            return results

        diary_data = create_response.json()
        diary_id = diary_data["id"]

        by_date_response = get_diary_by_date(user_id)
        record_result(results, by_date_response.status_code == 200, "get_diary_by_date")

        by_id_response = get_diary_by_id(diary_id)
        record_result(results, by_id_response.status_code == 200, "get_diary_by_id")

        dates_response = get_dates_with_diary(user_id)
        record_result(results, dates_response.status_code == 200, "get_dates_with_diary")

        upload_response = upload_media(diary_id)
        upload_ok = upload_response.status_code in (200, 201)
        record_result(results, upload_ok, "upload_media")

        if upload_ok:
            upload_data = upload_response.json()
            asset_id = upload_data["media_id"]
            sync_response = sync_media(diary_id, asset_id, upload_data["file_size"])
            record_result(results, sync_response.status_code == 200, "sync_media")

            detail_response = get_diary_detail(diary_id, user_id)
            detail_ok = detail_response.status_code == 200 and bool(
                detail_response.json().get("media_items_aggregated")
            )
            record_result(results, detail_ok, "get_diary_detail")

        if ENABLE_CHAT:
            try:
                chat_response = chat(user_id)
                record_result(results, chat_response.status_code == 200, "chat")
            except requests.RequestException as exc:
                print(f"chat failed: {exc}")
                results["failed"] += 1
        else:
            print("chat skipped: set SMOKE_CHAT=1 to include the external LLM path")
            results["skipped"] += 1

        return results
    finally:
        if diary_id is not None and user_id is not None:
            try:
                delete_response = delete_diary(diary_id, user_id)
                record_result(results, delete_response.status_code == 204, "delete_diary")
            except Exception as exc:
                print(f"delete_diary cleanup failed: {exc}")
                results["failed"] += 1

        print(results)


if __name__ == "__main__":
    run_all_tests()
