-- ============================================================
-- 媒体 OSS 迁移 - 数据库回滚脚本
-- 版本: 001_rollback
-- 日期: 2026-01-23
-- 描述: 回滚 media_files 表创建
-- 警告: 此操作会删除 media_files 表及其所有数据！
-- ============================================================

-- 开始事务
BEGIN;

-- 备份提示
DO $$
BEGIN
    RAISE NOTICE '========================================';
    RAISE NOTICE '警告: 即将删除 media_files 表';
    RAISE NOTICE '========================================';
    RAISE NOTICE '';
    RAISE NOTICE '此操作将删除:';
    RAISE NOTICE '  - media_files 表';
    RAISE NOTICE '  - 所有相关索引';
    RAISE NOTICE '  - 表中的所有数据';
    RAISE NOTICE '';
    RAISE NOTICE '请确保已备份数据:';
    RAISE NOTICE '  pg_dump -U app_user -d app_db -t media_files -F c -f media_files_backup.dump';
    RAISE NOTICE '';
    RAISE NOTICE '按 Ctrl+C 取消，或等待 5 秒后继续...';
    RAISE NOTICE '';
    PERFORM pg_sleep(5);
    RAISE NOTICE '开始回滚...';
END $$;

-- 显示当前表信息
DO $$
DECLARE
    row_count INTEGER;
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = 'public' 
        AND table_name = 'media_files'
    ) THEN
        SELECT COUNT(*) INTO row_count FROM media_files;
        RAISE NOTICE '当前 media_files 表包含 % 条记录', row_count;
    ELSE
        RAISE NOTICE 'media_files 表不存在，无需回滚';
    END IF;
END $$;

-- 删除索引
DROP INDEX IF EXISTS idx_media_files_created_at;
RAISE NOTICE '✓ 已删除索引: idx_media_files_created_at';

DROP INDEX IF EXISTS idx_media_files_type;
RAISE NOTICE '✓ 已删除索引: idx_media_files_type';

DROP INDEX IF EXISTS idx_media_files_diary_id;
RAISE NOTICE '✓ 已删除索引: idx_media_files_diary_id';

-- 删除表
DROP TABLE IF EXISTS media_files CASCADE;
RAISE NOTICE '✓ 已删除表: media_files';

-- 验证删除
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = 'public' 
        AND table_name = 'media_files'
    ) THEN
        RAISE NOTICE '';
        RAISE NOTICE '========================================';
        RAISE NOTICE '✓ 回滚成功';
        RAISE NOTICE '========================================';
        RAISE NOTICE '';
        RAISE NOTICE 'media_files 表及其索引已完全删除';
        RAISE NOTICE '';
        RAISE NOTICE '注意事项:';
        RAISE NOTICE '  1. OSS 中的文件未被删除';
        RAISE NOTICE '  2. 如需恢复，请重新执行迁移脚本';
        RAISE NOTICE '  3. 建议检查应用配置 (OSS_ENABLED=false)';
        RAISE NOTICE '';
    ELSE
        RAISE EXCEPTION '✗ media_files 表删除失败';
    END IF;
END $$;

-- 提交事务
COMMIT;

-- 显示最终状态
SELECT 
    '✓ 回滚完成' as status,
    'media_files 表及其索引已删除' as message,
    NOW() as rollback_time;
