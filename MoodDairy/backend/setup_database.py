"""Set up the configured PostgreSQL role/database using backend/.env."""

from __future__ import annotations

import os
from pathlib import Path
from urllib.parse import unquote, urlparse

from dotenv import load_dotenv
from psycopg2.extensions import ISOLATION_LEVEL_AUTOCOMMIT

os.environ["PGCLIENTENCODING"] = "UTF8"


def _normalize_database_url(database_url: str) -> str:
    if database_url.startswith("postgresql+asyncpg://"):
        return database_url.replace("postgresql+asyncpg://", "postgresql://", 1)
    if database_url.startswith("postgresql+psycopg://"):
        return database_url.replace("postgresql+psycopg://", "postgresql://", 1)
    return database_url


def _load_database_config(env_name: str, fallback: str | None = None) -> dict[str, str | int]:
    project_root = Path(__file__).resolve().parent
    load_dotenv(project_root / ".env")

    database_url = os.getenv(env_name) or fallback
    if not database_url:
        raise RuntimeError(f"{env_name} is not configured in backend/.env")

    parsed = urlparse(_normalize_database_url(database_url))
    database_name = parsed.path.lstrip("/")
    if not parsed.hostname or not parsed.username or not database_name:
        raise RuntimeError(f"Invalid {env_name}: {database_url}")

    return {
        "host": parsed.hostname,
        "port": parsed.port or 5432,
        "user": unquote(parsed.username),
        "password": unquote(parsed.password or ""),
        "database": database_name,
    }


def setup_database() -> bool:
    import psycopg2

    target_config = _load_database_config("DATABASE_URL")
    admin_fallback = os.getenv("DATABASE_URL_FALLBACK") or os.getenv("DATABASE_URL")
    admin_config = _load_database_config("DATABASE_URL_ADMIN", admin_fallback)

    print("=" * 50)
    print("PostgreSQL 数据库初始化")
    print("=" * 50)

    print("\n1. 连接管理数据库...")
    try:
        conn = psycopg2.connect(
            host=str(admin_config["host"]),
            port=int(admin_config["port"]),
            user=str(admin_config["user"]),
            password=str(admin_config["password"]),
            database=str(admin_config["database"]),
            connect_timeout=10,
            client_encoding="UTF8",
        )
        conn.set_isolation_level(ISOLATION_LEVEL_AUTOCOMMIT)
        cur = conn.cursor()
        print("   OK: 管理连接成功")
    except Exception as exc:
        print(f"   ERROR: 管理连接失败: {exc}")
        print("   如需创建数据库或角色，请在 .env 中配置 DATABASE_URL_ADMIN")
        return False

    print(f"\n2. 检查用户 {target_config['user']}...")
    try:
        cur.execute("SELECT 1 FROM pg_roles WHERE rolname = %s", (str(target_config["user"]),))
        if cur.fetchone():
            print(f"   用户 {target_config['user']} 已存在")
        elif target_config["password"]:
            cur.execute(
                f'CREATE USER "{target_config["user"]}" WITH PASSWORD %s',
                (str(target_config["password"]),),
            )
            print(f"   OK: 用户 {target_config['user']} 创建成功")
        else:
            print("   WARNING: 未提供用户密码，跳过创建用户")
    except Exception as exc:
        print(f"   WARNING: 创建/检查用户失败: {exc}")

    print(f"\n3. 检查数据库 {target_config['database']}...")
    try:
        cur.execute("SELECT 1 FROM pg_database WHERE datname = %s", (str(target_config["database"]),))
        if cur.fetchone():
            print(f"   数据库 {target_config['database']} 已存在")
        else:
            cur.execute(
                f'CREATE DATABASE "{target_config["database"]}" OWNER "{target_config["user"]}" ENCODING \'UTF8\''
            )
            print(f"   OK: 数据库 {target_config['database']} 创建成功")
    except Exception as exc:
        print(f"   WARNING: 创建/检查数据库失败: {exc}")

    print("\n4. 授权数据库访问...")
    try:
        cur.execute(
            f'GRANT ALL PRIVILEGES ON DATABASE "{target_config["database"]}" TO "{target_config["user"]}"'
        )
        print("   OK: 数据库权限授予成功")
    except Exception as exc:
        print(f"   WARNING: 授权失败: {exc}")

    cur.close()
    conn.close()

    print(f"\n5. 验证目标数据库连接 ({target_config['database']})...")
    try:
        conn = psycopg2.connect(
            host=str(target_config["host"]),
            port=int(target_config["port"]),
            user=str(target_config["user"]),
            password=str(target_config["password"]),
            database=str(target_config["database"]),
            connect_timeout=10,
            client_encoding="UTF8",
        )
        cur = conn.cursor()

        sql_file = Path(__file__).resolve().parent.parent / "init_database.sql"
        if sql_file.exists():
            with open(sql_file, "r", encoding="utf-8") as handle:
                cur.execute(handle.read())
            conn.commit()
            print("   OK: init_database.sql 已执行")
        else:
            print("   INFO: 未找到 init_database.sql，跳过初始化 SQL")

        cur.execute(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
            ORDER BY table_name
            """
        )
        tables = cur.fetchall()
        print(f"\n   public schema 中共有 {len(tables)} 个表:")
        for table in tables:
            print(f"   - {table[0]}")

        cur.close()
        conn.close()

    except Exception as exc:
        print(f"   ERROR: 目标数据库验证失败: {exc}")
        return False

    print("\n" + "=" * 50)
    print("OK: 数据库初始化流程完成")
    print("=" * 50)
    return True


if __name__ == "__main__":
    setup_database()
