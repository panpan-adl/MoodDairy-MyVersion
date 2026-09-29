-- ============================================================
-- 媒体 OSS 迁移 - 数据库迁移脚本
-- 版本: 001
-- 日期: 2026-01-23
-- 描述: 创建 media_files 表用于存储 OSS 媒体元数据
-- ============================================================

-- 创建 media_files 表
CREATE TABLE IF NOT EXISTS media_files (
    id            BIGSERIAL PRIMARY KEY,
    diary_id      BIGINT REFERENCES diaries(id) ON DELETE SET NULL,
    type          VARCHAR(16) NOT NULL,
    url           TEXT NOT NULL,
    oss_bucket    TEXT,
    oss_key       TEXT,
    content_type  TEXT,
    size_bytes    BIGINT,
    duration_ms   INTEGER,
    created_at    TIMESTAMPTZ DEFAULT now()
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_media_files_diary_id ON media_files(diary_id);
CREATE INDEX IF NOT EXISTS idx_media_files_type ON media_files(type);
CREATE INDEX IF NOT EXISTS idx_media_files_created_at ON media_files(created_at);

-- 添加注释
COMMENT ON TABLE media_files IS '媒体文件表（OSS 存储）- 仅存储元数据';
COMMENT ON COLUMN media_files.id IS '主键ID';
COMMENT ON COLUMN media_files.diary_id IS '关联的日记ID（可为空，后续关联）';
COMMENT ON COLUMN media_files.type IS '媒体类型：image/audio/video';
COMMENT ON COLUMN media_files.url IS '完整访问 URL（OSS 或本地）';
COMMENT ON COLUMN media_files.oss_bucket IS 'OSS 存储桶名称（仅 OSS 模式）';
COMMENT ON COLUMN media_files.oss_key IS 'OSS 对象键（仅 OSS 模式）';
COMMENT ON COLUMN media_files.content_type IS 'MIME 类型（如 audio/m4a）';
COMMENT ON COLUMN media_files.size_bytes IS '文件大小（字节）';
COMMENT ON COLUMN media_files.duration_ms IS '媒体时长（毫秒，仅音频/视频）';
COMMENT ON COLUMN media_files.created_at IS '创建时间';

-- 验证表创建
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = 'public' 
        AND table_name = 'media_files'
    ) THEN
        RAISE NOTICE '✓ media_files 表创建成功';
    ELSE
        RAISE EXCEPTION '✗ media_files 表创建失败';
    END IF;
    
    -- 验证索引创建
    IF EXISTS (
        SELECT 1 FROM pg_indexes 
        WHERE schemaname = 'public' 
        AND tablename = 'media_files' 
        AND indexname = 'idx_media_files_diary_id'
    ) THEN
        RAISE NOTICE '✓ idx_media_files_diary_id 索引创建成功';
    END IF;
    
    IF EXISTS (
        SELECT 1 FROM pg_indexes 
        WHERE schemaname = 'public' 
        AND tablename = 'media_files' 
        AND indexname = 'idx_media_files_type'
    ) THEN
        RAISE NOTICE '✓ idx_media_files_type 索引创建成功';
    END IF;
END $$;

-- 显示表结构
SELECT 
    '✓ 迁移完成' as status,
    'media_files 表已创建，包含 3 个索引' as message,
    COUNT(*) as row_count 
FROM media_files;
