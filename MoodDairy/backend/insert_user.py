"""Insert a test user using the configured PostgreSQL connection."""

from __future__ import annotations

import os
from pathlib import Path
from urllib.parse import unquote, urlparse

from dotenv import load_dotenv

os.environ["PGCLIENTENCODING"] = "UTF8"


def _normalize_database_url(database_url: str) -> str:
    if database_url.startswith("postgresql+asyncpg://"):
        return database_url.replace("postgresql+asyncpg://", "postgresql://", 1)
    if database_url.startswith("postgresql+psycopg://"):
        return database_url.replace("postgresql+psycopg://", "postgresql://", 1)
    return database_url


def _load_database_config() -> dict[str, str | int]:
    project_root = Path(__file__).resolve().parent
    load_dotenv(project_root / ".env")

    database_url = os.getenv("DATABASE_URL")
    if not database_url:
        raise RuntimeError("DATABASE_URL is not configured in backend/.env")

    parsed = urlparse(_normalize_database_url(database_url))
    database_name = parsed.path.lstrip("/")
    if not parsed.hostname or not parsed.username or not database_name:
        raise RuntimeError(f"Invalid DATABASE_URL: {database_url}")

    return {
        "host": parsed.hostname,
        "port": parsed.port or 5432,
        "user": unquote(parsed.username),
        "password": unquote(parsed.password or ""),
        "database": database_name,
    }


def insert_test_user() -> None:
    print("插入测试用户...")
    try:
        import psycopg2

        config = _load_database_config()
        conn = psycopg2.connect(
            host=str(config["host"]),
            port=int(config["port"]),
            user=str(config["user"]),
            password=str(config["password"]),
            database=str(config["database"]),
            connect_timeout=10,
            client_encoding="UTF8",
        )
        cur = conn.cursor()

        cur.execute(
            """
            INSERT INTO users (username, password, nickname, status, created_at, updated_at)
            VALUES ('1', '111111', '测试用户', 1, NOW(), NOW())
            ON CONFLICT (username) DO NOTHING
            """
        )
        conn.commit()

        cur.execute("SELECT id, username, nickname FROM users WHERE username = '1'")
        user = cur.fetchone()
        if user:
            print("OK: 测试用户创建成功")
            print(f"   ID: {user[0]}")
            print(f"   用户名: {user[1]}")
            print(f"   昵称: {user[2]}")
        else:
            print("ERROR: 未查询到测试用户")

        cur.close()
        conn.close()

    except ImportError:
        print("ERROR: psycopg2 未安装，请先执行 `pip install psycopg2-binary`")
    except Exception as exc:
        print(f"ERROR: {exc}")


if __name__ == "__main__":
    insert_test_user()
