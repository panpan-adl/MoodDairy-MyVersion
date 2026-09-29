-- ============================================================
-- 迁移脚本: 001 - 添加提取相关字段到 diaries 表
-- 日期: 2026-01-17
-- 描述: 为日记表添加 extraction_status, content_hash, extract_version 字段
-- ============================================================

-- 添加提取状态字段
ALTER TABLE diaries ADD COLUMN IF NOT EXISTS extraction_status VARCHAR(20) DEFAULT 'pending';

-- 添加内容哈希字段（用于检测内容变化）
ALTER TABLE diaries ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

-- 添加提取版本字段（用于算法升级时强制重新提取）
ALTER TABLE diaries ADD COLUMN IF NOT EXISTS extract_version INTEGER DEFAULT 1;

-- 添加注释
COMMENT ON COLUMN diaries.extraction_status IS '提取状态: pending/processing/succeeded/failed';
COMMENT ON COLUMN diaries.content_hash IS '内容哈希值（SHA256），用于幂等性检查';
COMMENT ON COLUMN diaries.extract_version IS '提取算法版本号，升级时递增';

-- 创建索引以提升查询性能
CREATE INDEX IF NOT EXISTS idx_diaries_extraction_status ON diaries(extraction_status);
CREATE INDEX IF NOT EXISTS idx_diaries_content_hash ON diaries(content_hash);
CREATE INDEX IF NOT EXISTS idx_diaries_extract_version ON diaries(extract_version);
CREATE INDEX IF NOT EXISTS idx_diaries_user_extraction ON diaries(user_id, extraction_status);

-- 验证迁移
SELECT 
    column_name, 
    data_type, 
    column_default 
FROM information_schema.columns 
WHERE table_name = 'diaries' 
  AND column_name IN ('extraction_status', 'content_hash', 'extract_version');
