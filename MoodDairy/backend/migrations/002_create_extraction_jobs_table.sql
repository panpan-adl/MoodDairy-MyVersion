-- ============================================================
-- 迁移脚本: 002 - 创建 extraction_jobs 表
-- 日期: 2026-01-17
-- 描述: 创建提取任务记录表，用于追踪提取任务的执行情况
-- ============================================================

-- 创建 extraction_jobs 表
CREATE TABLE IF NOT EXISTS extraction_jobs (
    id BIGSERIAL PRIMARY KEY,
    diary_id BIGINT NOT NULL REFERENCES diaries(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,              -- pending/processing/succeeded/failed
    attempts INTEGER DEFAULT 0,               -- 重试次数
    error_message TEXT,                       -- 错误信息
    error_code VARCHAR(50),                   -- 错误代码
    started_at TIMESTAMP,                     -- 开始时间
    completed_at TIMESTAMP,                   -- 完成时间
    execution_time_ms INTEGER,                -- 执行时间（毫秒）
    llm_tokens_used INTEGER,                  -- LLM 使用的 token 数
    content_hash VARCHAR(64),                 -- 提取时的内容哈希
    extract_version INTEGER,                  -- 提取时的版本号
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 添加注释
COMMENT ON TABLE extraction_jobs IS '提取任务记录表';
COMMENT ON COLUMN extraction_jobs.status IS '任务状态: pending/processing/succeeded/failed';
COMMENT ON COLUMN extraction_jobs.attempts IS '重试次数';
COMMENT ON COLUMN extraction_jobs.error_message IS '错误信息（失败时记录）';
COMMENT ON COLUMN extraction_jobs.error_code IS '错误代码（失败时记录）';
COMMENT ON COLUMN extraction_jobs.started_at IS '任务开始时间';
COMMENT ON COLUMN extraction_jobs.completed_at IS '任务完成时间';
COMMENT ON COLUMN extraction_jobs.execution_time_ms IS '执行时间（毫秒）';
COMMENT ON COLUMN extraction_jobs.llm_tokens_used IS 'LLM 使用的 token 数';
COMMENT ON COLUMN extraction_jobs.content_hash IS '提取时的内容哈希值';
COMMENT ON COLUMN extraction_jobs.extract_version IS '提取时的算法版本号';

-- 创建索引以提升查询性能
CREATE INDEX IF NOT EXISTS idx_extraction_jobs_diary_id ON extraction_jobs(diary_id);
CREATE INDEX IF NOT EXISTS idx_extraction_jobs_status ON extraction_jobs(status);
CREATE INDEX IF NOT EXISTS idx_extraction_jobs_created_at ON extraction_jobs(created_at);
CREATE INDEX IF NOT EXISTS idx_extraction_jobs_diary_status ON extraction_jobs(diary_id, status);

-- 验证迁移
SELECT 
    table_name, 
    column_name, 
    data_type 
FROM information_schema.columns 
WHERE table_name = 'extraction_jobs' 
ORDER BY ordinal_position;
