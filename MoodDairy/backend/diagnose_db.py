"""Async PostgreSQL connectivity diagnostics based on backend/.env."""

from __future__ import annotations

import asyncio
import os
from pathlib import Path
from urllib.parse import unquote, urlparse

from dotenv import load_dotenv


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


async def test_connection() -> None:
    config = _load_database_config()

    print("=" * 50)
    print("PostgreSQL 数据库连接诊断")
    print("=" * 50)

    print("\n1. 检查 asyncpg 模块...")
    try:
        import asyncpg

        print("   OK: asyncpg 已安装")
    except ImportError:
        print("   ERROR: asyncpg 未安装，请先执行 `pip install asyncpg`")
        return

    print("\n2. 尝试直连 PostgreSQL...")
    print("   连接参数:")
    print(f"   - 主机: {config['host']}")
    print(f"   - 端口: {config['port']}")
    print(f"   - 用户: {config['user']}")
    print(f"   - 数据库: {config['database']}")

    try:
        conn = await asyncpg.connect(
            host=str(config["host"]),
            port=int(config["port"]),
            user=str(config["user"]),
            password=str(config["password"]),
            database=str(config["database"]),
            timeout=10,
        )
        print("   OK: 连接成功")

        version = await conn.fetchval("SELECT version()")
        print(f"   PostgreSQL 版本: {version}")

        tables = await conn.fetch(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
            ORDER BY table_name
            """
        )
        print(f"\n3. public schema 中的表 ({len(tables)} 个):")
        for table in tables:
            print(f"   - {table['table_name']}")

        await conn.close()
        print("\nOK: 数据库连接检测通过")

    except asyncpg.InvalidCatalogNameError:
        print(f"   ERROR: 数据库 `{config['database']}` 不存在")
    except asyncpg.InvalidPasswordError:
        print(f"   ERROR: 用户 `{config['user']}` 的密码不正确")
    except asyncpg.InvalidAuthorizationSpecificationError as exc:
        print(f"   ERROR: 认证失败: {exc}")
    except OSError as exc:
        print(f"   ERROR: 无法连接到数据库服务器: {exc}")
        print("\n   建议检查:")
        print(f"   1. 服务器是否可达: {config['host']}:{config['port']}")
        print("   2. 安全组 / 防火墙是否放行 PostgreSQL 端口")
        print("   3. PostgreSQL 是否允许当前客户端 IP 访问")
    except Exception as exc:
        print(f"   ERROR: 连接失败: {type(exc).__name__}: {exc}")


if __name__ == "__main__":
    asyncio.run(test_connection())
