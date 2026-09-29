-- ============================================================
-- 迁移脚本: 003 - 更新 diary_summaries 表结构
-- 日期: 2026-01-17
-- 描述: 修改 keywords 和 main_topics 为 TEXT[] 数组，添加独立的情绪字段
-- ============================================================

-- 修改字段类型（从 JSONB 改为 TEXT[]，提升查询性能）
-- 注意：需要先备份数据，然后转换
DO $$
BEGIN
    -- 检查 keywords 列是否为 JSONB 类型
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'diary_summaries' 
        AND column_name = 'keywords' 
        AND data_type = 'jsonb'
    ) THEN
        -- 创建临时列
        ALTER TABLE diary_summaries ADD COLUMN keywords_temp TEXT[];
        
        -- 转换数据（从 JSONB 数组转为 TEXT[]）
        UPDATE diary_summaries 
        SET keywords_temp = ARRAY(
            SELECT jsonb_array_elements_text(keywords)
        )
        WHERE keywords IS NOT NULL AND jsonb_typeof(keywords) = 'array';
        
        -- 删除旧列
        ALTER TABLE diary_summaries DROP COLUMN keywords;
        
        -- 重命名新列
        ALTER TABLE diary_summaries RENAME COLUMN keywords_temp TO keywords;
    END IF;
    
    -- 检查 main_topics 列是否为 JSONB 类型
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'diary_summaries' 
        AND column_name = 'main_topics' 
        AND data_type = 'jsonb'
    ) THEN
        -- 创建临时列
        ALTER TABLE diary_summaries ADD COLUMN main_topics_temp TEXT[];
        
        -- 转换数据（从 JSONB 数组转为 TEXT[]）
        UPDATE diary_summaries 
        SET main_topics_temp = ARRAY(
            SELECT jsonb_array_elements_text(main_topics)
        )
        WHERE main_topics IS NOT NULL AND jsonb_typeof(main_topics) = 'array';
        
        -- 删除旧列
        ALTER TABLE diary_summaries DROP COLUMN main_topics;
        
        -- 重命名新列
        ALTER TABLE diary_summaries RENAME COLUMN main_topics_temp TO main_topics;
    END IF;
END $$;

-- 添加独立的情绪字段（从 emotion_analysis JSONB 中提取到独立列，便于索引和查询）
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
    -- 添加情绪评分约束
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'chk_emotion_score'
    ) THEN
        ALTER TABLE diary_summaries ADD CONSTRAINT chk_emotion_score 
            CHECK (emotion_score IS NULL OR (emotion_score >= 1 AND emotion_score <= 100));
    END IF;
    
    -- 添加主要情绪枚举约束
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'chk_primary_emotion'
    ) THEN
        ALTER TABLE diary_summaries ADD CONSTRAINT chk_primary_emotion 
            CHECK (primary_emotion IS NULL OR primary_emotion IN ('开心', '平静', '焦虑', '愤怒', '低落', '兴奋', '复杂', '中性'));
    END IF;
    
    -- 添加情绪强度枚举约束
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'chk_emotion_intensity'
    ) THEN
        ALTER TABLE diary_summaries ADD CONSTRAINT chk_emotion_intensity 
            CHECK (emotion_intensity IS NULL OR emotion_intensity IN ('轻微', '中等', '强烈'));
    END IF;
END $$;

-- 添加注释
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

-- 验证迁移
SELECT 
    column_name, 
    data_type, 
    column_default 
FROM information_schema.columns 
WHERE table_name = 'diary_summaries' 
  AND column_name IN ('keywords', 'main_topics', 'primary_emotion', 'emotion_score', 'emotion_intensity', 'emotion_distribution', 'extract_version', 'updated_at')
ORDER BY ordinal_position;
