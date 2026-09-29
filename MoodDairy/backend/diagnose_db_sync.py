"""Sync PostgreSQL diagnostics based on backend/.env."""

from __future__ import annotations

import os
import socket
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


def test_port(config: dict[str, str | int]) -> bool:
    """Test whether the target PostgreSQL port is reachable."""

    print(f"1. 测试 PostgreSQL 端口 ({config['host']}:{config['port']})...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(5)
    result = sock.connect_ex((str(config["host"]), int(config["port"])))
    sock.close()

    if result == 0:
        print("   OK: 端口可访问")
        return True

    print("   ERROR: 端口不可访问")
    print("   建议检查网络连通性、安全组和防火墙配置")
    return False


def test_psycopg2(config: dict[str, str | int]) -> bool:
    """Use psycopg2 to verify primary connectivity."""

    print("\n2. 尝试使用 psycopg2 连接...")
    try:
        import psycopg2

        print("   psycopg2 已安装")
        conn = psycopg2.connect(
            host=str(config["host"]),
            port=int(config["port"]),
            user=str(config["user"]),
            password=str(config["password"]),
            database=str(config["database"]),
            connect_timeout=10,
            client_encoding="UTF8",
        )
        print("   OK: psycopg2 连接成功")

        cur = conn.cursor()
        cur.execute("SELECT version()")
        version = cur.fetchone()[0]
        print(f"   PostgreSQL 版本: {version}")

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
        return True

    except ImportError:
        print("   ERROR: psycopg2 未安装，请先执行 `pip install psycopg2-binary`")
        return False
    except Exception as exc:
        print(f"   ERROR: 连接失败: {exc}")
        return False


def test_admin_visibility(
    target_config: dict[str, str | int], admin_config: dict[str, str | int]
) -> bool:
    """Check whether the target role and database are visible from the admin connection."""

    print("\n3. 检查目标角色和数据库...")
    try:
        import psycopg2

        conn = psycopg2.connect(
            host=str(admin_config["host"]),
            port=int(admin_config["port"]),
            user=str(admin_config["user"]),
            password=str(admin_config["password"]),
            database=str(admin_config["database"]),
            connect_timeout=10,
            client_encoding="UTF8",
        )
        cur = conn.cursor()

        cur.execute("SELECT current_user")
        current_user = cur.fetchone()[0]
        print(f"   当前检查账号: {current_user}")

        cur.execute("SELECT 1 FROM pg_roles WHERE rolname = %s", (str(target_config["user"]),))
        if cur.fetchone():
            print(f"   OK: 用户 {target_config['user']} 存在")
        else:
            print(f"   WARNING: 用户 {target_config['user']} 不存在，或当前账号无权查看")

        cur.execute("SELECT 1 FROM pg_database WHERE datname = %s", (str(target_config["database"]),))
        if cur.fetchone():
            print(f"   OK: 数据库 {target_config['database']} 存在")
        else:
            print(f"   WARNING: 数据库 {target_config['database']} 不存在，或当前账号无权查看")

        cur.close()
        conn.close()
        return True

    except Exception as exc:
        print(f"   WARNING: 无法通过管理连接检查角色/数据库: {exc}")
        return False


def test_pg_hba(config: dict[str, str | int]) -> None:
    """Print likely follow-up checks."""

    print("\n4. 进一步排查建议:")
    print("   a) 确认 PostgreSQL 服务正在运行")
    print("   b) 确认安全组/防火墙已放行 5432")
    print(f"   c) 确认用户 {config['user']} 有权限访问数据库 {config['database']}")
    print("   d) 如需更高权限检查，请在 .env 中配置 DATABASE_URL_ADMIN")


if __name__ == "__main__":
    primary_config = _load_database_config("DATABASE_URL")
    admin_fallback = os.getenv("DATABASE_URL_FALLBACK") or os.getenv("DATABASE_URL")
    admin_config = _load_database_config("DATABASE_URL_ADMIN", admin_fallback)

    print("=" * 50)
    print("PostgreSQL 数据库连接诊断（同步版）")
    print("=" * 50)
    print()

    if test_port(primary_config):
        test_psycopg2(primary_config)
        test_admin_visibility(primary_config, admin_config)

    test_pg_hba(primary_config)
