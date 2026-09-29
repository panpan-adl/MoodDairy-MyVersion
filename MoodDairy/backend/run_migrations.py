"""
数据库迁移脚本执行器
运行 migrations 目录下的所有 SQL 迁移脚本
"""
import os
import sys
import asyncio
from pathlib import Path

from dotenv import load_dotenv
from sqlalchemy.ext.asyncio import create_async_engine


load_dotenv()

PRIMARY_DATABASE_URL = os.getenv("DATABASE_URL")
MIGRATION_DATABASE_URL = (
    os.getenv("DATABASE_URL_ADMIN")
    or os.getenv("DATABASE_URL_FALLBACK")
    or PRIMARY_DATABASE_URL
)

if not MIGRATION_DATABASE_URL:
    print("错误: 未找到 DATABASE_URL 环境变量")
    sys.exit(1)

if MIGRATION_DATABASE_URL.startswith("postgresql://"):
    MIGRATION_DATABASE_URL = MIGRATION_DATABASE_URL.replace(
        "postgresql://",
        "postgresql+asyncpg://",
        1,
    )


def split_sql_statements(sql_content: str) -> list[str]:
    """按语句切分 SQL，保留 DO $$ ... END $$ 块。"""
    statements: list[str] = []
    current_statement: list[str] = []
    in_do_block = False

    for raw_line in sql_content.splitlines():
        line = raw_line.rstrip()
        stripped = line.strip()

        if not stripped or stripped.startswith("--"):
            continue

        upper = stripped.upper()
        current_statement.append(line)

        if "DO $$" in upper or upper.startswith("DO $"):
            in_do_block = True

        if in_do_block:
            if "END $$" in upper or upper.startswith("END $"):
                in_do_block = False
                statements.append("\n".join(current_statement).strip())
                current_statement = []
            continue

        if stripped.endswith(";"):
            statements.append("\n".join(current_statement).strip())
            current_statement = []

    if current_statement:
        statements.append("\n".join(current_statement).strip())

    return [statement for statement in statements if statement]


async def run_migration_file(engine, filepath: Path):
    """运行单个迁移文件。"""
    print(f"\n{'=' * 60}")
    print(f"执行迁移: {filepath.name}")
    print(f"{'=' * 60}")

    sql_content = filepath.read_text(encoding="utf-8")
    statements = split_sql_statements(sql_content)

    async with engine.begin() as conn:
        try:
            for index, statement in enumerate(statements, 1):
                try:
                    result = await conn.exec_driver_sql(statement)

                    if result.returns_rows:
                        rows = result.fetchall()
                        if rows:
                            print("\n查询结果:")
                            for row in rows:
                                print(f"  {dict(row._mapping)}")
                except Exception as exc:
                    sql_preview = " ".join(statement.split())
                    if len(sql_preview) > 240:
                        sql_preview = sql_preview[:237] + "..."

                    print(f"\n✗ 语句 {index} 执行失败: {exc}")
                    print(f"SQL: {sql_preview}")
                    raise

            print(f"\n✓ 迁移 {filepath.name} 执行成功")

        except Exception as exc:
            print(f"\n✗ 迁移 {filepath.name} 执行失败: {exc}")
            raise


async def run_all_migrations():
    """运行所有迁移文件。"""
    print("\n" + "=" * 60)
    print("开始执行数据库迁移")
    print("=" * 60)

    engine = create_async_engine(MIGRATION_DATABASE_URL, echo=False)

    try:
        migrations_dir = Path(__file__).parent / "migrations"
        if not migrations_dir.exists():
            print(f"错误: migrations 目录不存在: {migrations_dir}")
            return

        migration_files = sorted(migrations_dir.glob("*.sql"))
        if not migration_files:
            print("未找到迁移文件")
            return

        print(f"\n找到 {len(migration_files)} 个迁移文件:")
        for migration_file in migration_files:
            print(f"  - {migration_file.name}")

        for filepath in migration_files:
            await run_migration_file(engine, filepath)

        print("\n" + "=" * 60)
        print("所有迁移执行完成！")
        print("=" * 60)

    except Exception as exc:
        print(f"\n迁移执行失败: {exc}")
        sys.exit(1)
    finally:
        await engine.dispose()


if __name__ == "__main__":
    asyncio.run(run_all_migrations())
