#!/bin/bash

# ============================================================
# 媒体 OSS 迁移一键回滚脚本
# 版本: 1.0
# 日期: 2026-01-23
# 使用方法: ./rollback_oss.sh
# ============================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 配置变量（根据实际环境修改）
BACKEND_DIR="/path/to/diary/backend"
SERVICE_NAME="diary-backend"
DB_USER="app_user"
DB_NAME="app_db"

echo ""
echo "=========================================="
echo "媒体 OSS 迁移回滚脚本"
echo "=========================================="
echo ""
echo "此脚本将执行以下操作:"
echo "  1. 备份当前配置"
echo "  2. 禁用 OSS (设置 OSS_ENABLED=false)"
echo "  3. 重启后端服务"
echo "  4. 验证服务状态"
echo ""

# 确认回滚
read -p "确认要回滚 OSS 迁移吗？(yes/no): " confirm
if [ "$confirm" != "yes" ]; then
    echo -e "${YELLOW}回滚已取消${NC}"
    exit 0
fi

echo ""
echo "开始回滚..."
echo ""

# 1. 备份当前配置
echo "步骤 1/5: 备份当前配置..."
cd "$BACKEND_DIR"

if [ -f .env ]; then
    BACKUP_FILE=".env.before_rollback.$(date +%Y%m%d_%H%M%S)"
    cp .env "$BACKUP_FILE"
    echo -e "${GREEN}✓ 配置已备份到: $BACKUP_FILE${NC}"
else
    echo -e "${RED}✗ .env 文件不存在${NC}"
    exit 1
fi

# 2. 禁用 OSS
echo ""
echo "步骤 2/5: 禁用 OSS..."

if grep -q "^OSS_ENABLED=true" .env; then
    sed -i 's/^OSS_ENABLED=true/OSS_ENABLED=false/' .env
    echo -e "${GREEN}✓ OSS 已禁用 (OSS_ENABLED=false)${NC}"
elif grep -q "^OSS_ENABLED=false" .env; then
    echo -e "${YELLOW}⚠ OSS 已经是禁用状态${NC}"
else
    echo -e "${YELLOW}⚠ 未找到 OSS_ENABLED 配置，添加配置...${NC}"
    echo "OSS_ENABLED=false" >> .env
fi

# 验证配置修改
if grep -q "^OSS_ENABLED=false" .env; then
    echo -e "${GREEN}✓ 配置验证成功${NC}"
else
    echo -e "${RED}✗ 配置修改失败${NC}"
    exit 1
fi

# 3. 重启服务
echo ""
echo "步骤 3/5: 重启服务..."

if command -v supervisorctl &> /dev/null; then
    sudo supervisorctl restart "$SERVICE_NAME"
    sleep 3
    echo -e "${GREEN}✓ 服务已重启${NC}"
else
    echo -e "${YELLOW}⚠ supervisorctl 未找到，请手动重启服务${NC}"
    echo "  命令: sudo supervisorctl restart $SERVICE_NAME"
    read -p "按 Enter 继续（确认已手动重启服务）..."
fi

# 4. 验证服务状态
echo ""
echo "步骤 4/5: 验证服务状态..."

# 检查服务是否运行
if command -v supervisorctl &> /dev/null; then
    if sudo supervisorctl status "$SERVICE_NAME" | grep -q "RUNNING"; then
        echo -e "${GREEN}✓ 服务运行正常${NC}"
    else
        echo -e "${RED}✗ 服务未运行${NC}"
        sudo supervisorctl status "$SERVICE_NAME"
        exit 1
    fi
fi

# 检查健康端点
echo "  检查健康端点..."
if curl -s http://localhost:8000/health | grep -q "ok"; then
    echo -e "${GREEN}✓ 健康检查通过${NC}"
else
    echo -e "${YELLOW}⚠ 健康检查失败，请检查服务日志${NC}"
fi

# 5. 验证 OSS 状态
echo ""
echo "步骤 5/5: 验证 OSS 状态..."

if command -v supervisorctl &> /dev/null; then
    LOG_OUTPUT=$(sudo supervisorctl tail "$SERVICE_NAME" 2>/dev/null | tail -50)
    
    if echo "$LOG_OUTPUT" | grep -q "OSS Enabled: False\|OSS 未启用\|使用本地存储"; then
        echo -e "${GREEN}✓ OSS 已成功禁用${NC}"
    else
        echo -e "${YELLOW}⚠ 无法从日志确认 OSS 状态${NC}"
        echo "  请手动检查日志: sudo supervisorctl tail $SERVICE_NAME"
    fi
fi

# 显示回滚摘要
echo ""
echo "=========================================="
echo -e "${GREEN}回滚完成！${NC}"
echo "=========================================="
echo ""
echo "回滚摘要:"
echo "  - 配置备份: $BACKUP_FILE"
echo "  - OSS 状态: 已禁用"
echo "  - 服务状态: 运行中"
echo "  - 存储模式: 本地存储"
echo ""
echo "后续步骤:"
echo "  1. 测试文件上传功能"
echo "  2. 检查服务日志: sudo supervisorctl tail -f $SERVICE_NAME"
echo "  3. 监控本地存储空间: df -h $BACKEND_DIR/uploads"
echo "  4. 查看回滚文档: migrations/ROLLBACK_GUIDE.md"
echo ""
echo "如需重新启用 OSS:"
echo "  1. 编辑 .env 文件，设置 OSS_ENABLED=true"
echo "  2. 重启服务: sudo supervisorctl restart $SERVICE_NAME"
echo ""

# 可选：测试上传功能
read -p "是否测试文件上传功能？(yes/no): " test_upload
if [ "$test_upload" = "yes" ]; then
    echo ""
    echo "测试文件上传..."
    
    # 创建测试文件
    TEST_FILE="/tmp/test_rollback_$(date +%s).m4a"
    echo "test content" > "$TEST_FILE"
    
    # 上传测试（需要有效的认证令牌）
    echo "请手动测试上传:"
    echo "  curl -X POST http://localhost:8000/api/media/upload \\"
    echo "    -F \"file=@$TEST_FILE\" \\"
    echo "    -F \"media_type=audio\" \\"
    echo "    -H \"Authorization: Bearer YOUR_TOKEN\""
    echo ""
    echo "预期结果: URL 应为本地路径 (如 /media/2026/01/23/audio/uuid.m4a)"
fi

echo ""
echo "回滚脚本执行完毕"
echo ""
