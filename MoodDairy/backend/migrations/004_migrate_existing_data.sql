-- ============================================================
-- 迁移脚本: 004 - 迁移现有数据
-- 日期: 2026-01-17
-- 描述: 为现有日记计算 content_hash，映射 is_extracted 到 extraction_status，
--       迁移 emotion_analysis JSONB 到独立字段
-- ============================================================

-- 启用 pgcrypto 扩展（用于 digest 函数）
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. 为现有日记计算 content_hash
-- 包含标题、正文、媒体内容、语音转录
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
-- is_extracted = 1 -> extraction_status = 'succeeded'
-- is_extracted = 0 -> extraction_status = 'pending'
UPDATE diaries 
SET extraction_status = CASE 
    WHEN is_extracted = 1 THEN 'succeeded'
    ELSE 'pending'
END
WHERE extraction_status = 'pending' AND is_extracted IS NOT NULL;

-- 3. 迁移 diary_summaries 中的情绪数据
-- 从 emotion_analysis JSONB 提取到独立字段
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

-- 4. 验证迁移结果
-- 检查 diaries 表的 content_hash 是否已填充
SELECT 
    COUNT(*) as total_diaries,
    COUNT(content_hash) as diaries_with_hash,
    COUNT(*) - COUNT(content_hash) as diaries_without_hash
FROM diaries;

-- 检查 extraction_status 的分布
SELECT 
    extraction_status,
    COUNT(*) as count
FROM diaries
GROUP BY extraction_status
ORDER BY extraction_status;

-- 检查 diary_summaries 的情绪字段迁移情况
SELECT 
    COUNT(*) as total_summaries,
    COUNT(primary_emotion) as summaries_with_emotion,
    COUNT(*) - COUNT(primary_emotion) as summaries_without_emotion
FROM diary_summaries;

-- 显示迁移完成信息
SELECT '数据迁移完成！' as message,
       (SELECT COUNT(*) FROM diaries) as total_diaries,
       (SELECT COUNT(*) FROM diary_summaries) as total_summaries;
