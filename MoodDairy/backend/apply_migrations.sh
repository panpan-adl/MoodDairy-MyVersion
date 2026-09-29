#!/bin/bash
# 数据库迁移脚本执行器（使用 psql）
# 使用方法: ./apply_migrations.sh

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "============================================================"
echo "开始执行数据库迁移"
echo "============================================================"

# 检查 psql 是否可用
if ! command -v psql &> /dev/null; then
    echo -e "${RED}错误: 未找到 psql 命令${NC}"
    echo "请安装 PostgreSQL 客户端工具"
    exit 1
fi

# 从 .env 文件读取数据库连接信息
if [ -f .env ]; then
    export $(cat .env | grep -v '^#' | xargs)
fi

# 检查 DATABASE_URL
if [ -z "$DATABASE_URL" ]; then
    echo -e "${RED}错误: 未找到 DATABASE_URL 环境变量${NC}"
    exit 1
fi

echo -e "${GREEN}找到数据库连接: $DATABASE_URL${NC}"
echo ""

# 获取迁移文件列表
MIGRATION_DIR="migrations"
if [ ! -d "$MIGRATION_DIR" ]; then
    echo -e "${RED}错误: migrations 目录不存在${NC}"
    exit 1
fi

# 执行每个迁移文件
for file in $(ls $MIGRATION_DIR/*.sql | sort); do
    echo "============================================================"
    echo -e "${YELLOW}执行迁移: $(basename $file)${NC}"
    echo "============================================================"
    
    psql "$DATABASE_URL" -f "$file"
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ 迁移 $(basename $file) 执行成功${NC}"
    else
        echo -e "${RED}✗ 迁移 $(basename $file) 执行失败${NC}"
        exit 1
    fi
    echo ""
done

echo "============================================================"
echo -e "${GREEN}所有迁移执行完成！${NC}"
echo "============================================================"
