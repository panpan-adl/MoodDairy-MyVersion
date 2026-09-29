-- ============================================================
-- 更新现有数据库到 V1 提取功能版本
-- 日期: 2026-01-17
-- 说明: 如果你已经有运行中的数据库，使用此脚本更新到最新版本
-- 用途: 在现有数据库上执行，添加 V1 提取功能所需的字段和表
-- ============================================================

-- ============================================================
-- 第一部分: 更新 diaries 表
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
COMMENT ON COLUMN diaries.is_extracted IS '是否已结构化提取：0否/1是（向后兼容）';

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_diaries_extraction_status ON diaries(extraction_status);
CREATE INDEX IF NOT EXISTS idx_diaries_content_hash ON diaries(content_hash);
CREATE INDEX IF NOT EXISTS idx_diaries_extract_version ON diaries(extract_version);
CREATE INDEX IF NOT EXISTS idx_diaries_user_extraction ON diaries(user_id, extraction_status);

-- ============================================================
-- 第二部分: 创建 extraction_jobs 表
-- ============================================================

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
COMMENT ON COLUMN extraction_jobs.execution_time_ms IS '执行时间（毫秒）';

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_extraction_jobs_diary_id ON extraction_jobs(diary_id);
CREATE INDEX IF NOT EXISTS idx_extraction_jobs_status ON extraction_jobs(status);
CREATE INDEX IF NOT EXISTS idx_extraction_jobs_created_at ON extraction_jobs(created_at);
CREATE INDEX IF NOT EXISTS idx_extraction_jobs_diary_status ON extraction_jobs(diary_id, status);

-- ============================================================
-- 第三部分: 更新 diary_summaries 表
-- ============================================================

-- 修改字段类型（从 JSONB 改为 TEXT[]）
DO $$
BEGIN
    -- 转换 keywords
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'diary_summaries' 
        AND column_name = 'keywords' 
        AND data_type = 'jsonb'
    ) THEN
        ALTER TABLE diary_summaries ADD COLUMN keywords_temp TEXT[];
        
        UPDATE diary_summaries 
        SET keywords_temp = ARRAY(
            SELECT jsonb_array_elements_text(keywords)
        )
        WHERE keywords IS NOT NULL AND jsonb_typeof(keywords) = 'array';
        
        ALTER TABLE diary_summaries DROP COLUMN keywords;
        ALTER TABLE diary_summaries RENAME COLUMN keywords_temp TO keywords;
    END IF;
    
    -- 转换 main_topics
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'diary_summaries' 
        AND column_name = 'main_topics' 
        AND data_type = 'jsonb'
    ) THEN
        ALTER TABLE diary_summaries ADD COLUMN main_topics_temp TEXT[];
        
        UPDATE diary_summaries 
        SET main_topics_temp = ARRAY(
            SELECT jsonb_array_elements_text(main_topics)
        )
        WHERE main_topics IS NOT NULL AND jsonb_typeof(main_topics) = 'array';
        
        ALTER TABLE diary_summaries DROP COLUMN main_topics;
        ALTER TABLE diary_summaries RENAME COLUMN main_topics_temp TO main_topics;
    END IF;
END $$;

-- 添加独立的情绪字段
ALTER TABLE diary_summaries ADD COLUMN IF NOT EXISTS primary_emotion VARCHAR(20);
ALTER TABLE diary_summaries ADD COLUMN IF NOT EXISTS emotion_score INTEGER;
ALTER TABLE diary_summaries ADD COLUMN IF NOT EXISTS emotion_intensity VARCHAR(20);
ALTER TABLE diary_summaries ADD COLUMN IF NOT EXISTS emotion_distribution JSONB;

-- 添加元数据字段
ALTER TABLE diary_summaries ADD COLUMN IF NOT EXISTS extract_version INTEGER DEFAULT 1;
ALTER TABLE diary_summaries ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT NOW();

-- 添加约束
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'chk_emotion_score'
    ) THEN
        ALTER TABLE diary_summaries ADD CONSTRAINT chk_emotion_score 
            CHECK (emotion_score IS NULL OR (emotion_score >= 1 AND emotion_score <= 100));
    END IF;
    
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'chk_primary_emotion'
    ) THEN
        ALTER TABLE diary_summaries ADD CONSTRAINT chk_primary_emotion 
            CHECK (primary_emotion IS NULL OR primary_emotion IN ('开心', '平静', '焦虑', '愤怒', '低落', '兴奋', '复杂', '中性'));
    END IF;
    
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'chk_emotion_intensity'
    ) THEN
        ALTER TABLE diary_summaries ADD CONSTRAINT chk_emotion_intensity 
            CHECK (emotion_intensity IS NULL OR emotion_intensity IN ('轻微', '中等', '强烈'));
    END IF;
END $$;

-- 添加注释
COMMENT ON TABLE diary_summaries IS '日记摘要表（V1增强版）';
COMMENT ON COLUMN diary_summaries.keywords IS '关键词数组（TEXT[]，便于索引）';
COMMENT ON COLUMN diary_summaries.main_topics IS '主要话题数组（TEXT[]，便于索引）';
COMMENT ON COLUMN diary_summaries.primary_emotion IS '主要情绪（枚举：开心/平静/焦虑/愤怒/低落/兴奋/复杂/中性）';
COMMENT ON COLUMN diary_summaries.emotion_score IS '情绪评分（1-100，50为中性）';
COMMENT ON COLUMN diary_summaries.emotion_intensity IS '情绪强度（轻微/中等/强烈）';
COMMENT ON COLUMN diary_summaries.emotion_distribution IS '情绪分布 {"开心": 0.6, "中性": 0.3, ...}';
COMMENT ON COLUMN diary_summaries.extract_version IS '提取算法版本号';

-- 创建 GIN 索引（支持数组和 JSONB 查询）
CREATE INDEX IF NOT EXISTS idx_diary_summaries_keywords ON diary_summaries USING GIN (keywords);
CREATE INDEX IF NOT EXISTS idx_diary_summaries_main_topics ON diary_summaries USING GIN (main_topics);
CREATE INDEX IF NOT EXISTS idx_diary_summaries_emotion_dist ON diary_summaries USING GIN (emotion_distribution);

-- 创建普通索引
CREATE INDEX IF NOT EXISTS idx_diary_summaries_primary_emotion ON diary_summaries(primary_emotion);
CREATE INDEX IF NOT EXISTS idx_diary_summaries_emotion_score ON diary_summaries(emotion_score);
CREATE INDEX IF NOT EXISTS idx_diary_summaries_created_at ON diary_summaries(created_at);

-- ============================================================
-- 第四部分: 创建 todos 表（待办）
-- ============================================================

CREATE TABLE IF NOT EXISTS todos (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    todo_date DATE NOT NULL,
    title VARCHAR(200) NOT NULL,
    note TEXT,
    is_done SMALLINT NOT NULL DEFAULT 0,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_todos_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE,
    CONSTRAINT chk_todos_is_done
        CHECK (is_done IN (0, 1))
);

CREATE INDEX IF NOT EXISTS idx_todos_user_date
    ON todos(user_id, todo_date);

CREATE INDEX IF NOT EXISTS idx_todos_user_date_done_sort
    ON todos(user_id, todo_date, is_done, sort_order);

COMMENT ON TABLE todos IS '待办表（按日期管理任务）';
COMMENT ON COLUMN todos.todo_date IS '任务所属日期';
COMMENT ON COLUMN todos.title IS '待办标题';
COMMENT ON COLUMN todos.note IS '待办备注';
COMMENT ON COLUMN todos.is_done IS '完成状态：0未完成/1已完成';
COMMENT ON COLUMN todos.sort_order IS '同一天内排序';

-- ============================================================
-- 第五部分(新增): 创建 chat_agent_plans 表（Agent 待确认计划）
-- ============================================================

CREATE TABLE IF NOT EXISTS public.chat_agent_plans (
    id BIGSERIAL PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    tool VARCHAR(64) NOT NULL,
    mode VARCHAR(20) NOT NULL,
    requires_confirmation SMALLINT NOT NULL DEFAULT 1,
    arguments JSONB NOT NULL DEFAULT '{}'::jsonb,
    request_message TEXT,
    context_snapshot TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    error_code VARCHAR(64),
    error_message TEXT,
    confirmed_at TIMESTAMP,
    executed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_agent_plans_user
        FOREIGN KEY (user_id)
        REFERENCES public.users(id)
        ON DELETE CASCADE,
    CONSTRAINT chk_chat_agent_plans_status
        CHECK (status IN ('pending', 'cancelled', 'executed', 'failed', 'expired')),
    CONSTRAINT chk_chat_agent_plans_mode
        CHECK (mode IN ('read_only', 'operation', 'client_action')),
    CONSTRAINT chk_chat_agent_plans_requires_confirmation
        CHECK (requires_confirmation IN (0, 1))
);

CREATE INDEX IF NOT EXISTS idx_chat_agent_plans_user_conv_status
    ON public.chat_agent_plans(user_id, conversation_id, status);

CREATE INDEX IF NOT EXISTS idx_chat_agent_plans_expires_at
    ON public.chat_agent_plans(expires_at);

CREATE INDEX IF NOT EXISTS idx_chat_agent_plans_created_at
    ON public.chat_agent_plans(created_at);

COMMENT ON TABLE public.chat_agent_plans IS 'Persisted pending plans for chat agent confirmation flow';
COMMENT ON COLUMN public.chat_agent_plans.plan_id IS 'Stable plan identifier returned to client';
COMMENT ON COLUMN public.chat_agent_plans.expires_at IS 'Plan expiration timestamp';

-- ============================================================
-- 第六部分: 迁移现有数据
-- ============================================================

-- 启用 pgcrypto 扩展（用于 digest 函数）
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. 为现有日记计算 content_hash
UPDATE diaries 
SET content_hash = encode(
    digest(
        COALESCE(title, '') || 
        COALESCE(content, '') || 
        COALESCE(
            (
                SELECT string_agg(COALESCE(dm.content, ''), '' ORDER BY dm.sort_order)
                FROM diary_media dm
                WHERE dm.diary_id = diaries.id
            ), 
            ''
        ) ||
        COALESCE(
            (
                SELECT string_agg(COALESCE(vt.processed_text, ''), '')
                FROM voice_transcriptions vt
                WHERE vt.diary_id = diaries.id
            ), 
            ''
        ),
        'sha256'
    ), 
    'hex'
)
WHERE content_hash IS NULL;

-- 2. 将 is_extracted 映射到 extraction_status
UPDATE diaries 
SET extraction_status = CASE 
    WHEN is_extracted = 1 THEN 'succeeded'
    ELSE 'pending'
END
WHERE extraction_status = 'pending' AND is_extracted IS NOT NULL;

-- 3. 迁移 diary_summaries 中的情绪数据
UPDATE diary_summaries
SET 
    primary_emotion = emotion_analysis->>'primary_emotion',
    emotion_score = CASE 
        WHEN emotion_analysis->>'emotion_score' IS NOT NULL 
        THEN (emotion_analysis->>'emotion_score')::INTEGER
        ELSE NULL
    END,
    emotion_intensity = emotion_analysis->>'emotion_intensity',
    emotion_distribution = emotion_analysis->'emotion_distribution'
WHERE emotion_analysis IS NOT NULL 
  AND primary_emotion IS NULL;

-- ============================================================
-- 验证迁移结果
-- ============================================================

-- 检查 diaries 表的新字段
SELECT 
    'diaries 表新字段' as check_type,
    COUNT(*) as total_diaries,
    COUNT(content_hash) as diaries_with_hash,
    COUNT(CASE WHEN extraction_status = 'succeeded' THEN 1 END) as extracted_diaries
FROM diaries;

-- 检查 extraction_jobs 表是否创建
SELECT 
    'extraction_jobs 表' as check_type,
    COUNT(*) as table_exists
FROM information_schema.tables 
WHERE table_name = 'extraction_jobs';

-- 检查 diary_summaries 表的更新
SELECT 
    'diary_summaries 表更新' as check_type,
    COUNT(*) as total_summaries,
    COUNT(primary_emotion) as summaries_with_emotion,
    COUNT(keywords) as summaries_with_keywords_array
FROM diary_summaries;

-- Check chat_agent_plans table exists
SELECT
    'chat_agent_plans table' as check_type,
    COUNT(*) as table_exists
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name = 'chat_agent_plans';

-- 显示完成信息
SELECT 
    '✓ 数据库迁移完成！' as message,
    (SELECT COUNT(*) FROM diaries) as total_diaries,
    (SELECT COUNT(*) FROM extraction_jobs) as total_jobs,
    (SELECT COUNT(*) FROM diary_summaries) as total_summaries,
    (SELECT COUNT(*) FROM chat_agent_plans) as total_agent_plans;
