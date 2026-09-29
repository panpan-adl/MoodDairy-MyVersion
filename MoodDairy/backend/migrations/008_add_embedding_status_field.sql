-- ============================================================
-- 迁移脚本: 008 - 添加向量化相关字段到 diaries 表
-- 日期: 2026-03-23
-- 描述: 为日记表添加 embedding_status 字段，用于跟踪日记向量化状态
-- ============================================================

-- 添加向量化状态字段
ALTER TABLE diaries ADD COLUMN IF NOT EXISTS embedding_status VARCHAR(20) DEFAULT 'pending';

-- 添加注释
COMMENT ON COLUMN diaries.embedding_status IS '向量化状态: pending/processing/succeeded/failed';

-- 创建索引以提升查询性能
CREATE INDEX IF NOT EXISTS idx_diaries_embedding_status ON diaries(embedding_status);
CREATE INDEX IF NOT EXISTS idx_diaries_user_embedding ON diaries(user_id, embedding_status);

-- 验证迁移
SELECT
    column_name,
    data_type,
    column_default
FROM information_schema.columns
WHERE table_name = 'diaries'
  AND column_name = 'embedding_status';
