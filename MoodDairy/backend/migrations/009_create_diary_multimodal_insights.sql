CREATE TABLE IF NOT EXISTS diary_multimodal_insights (
    id BIGSERIAL PRIMARY KEY,
    diary_id BIGINT NOT NULL UNIQUE REFERENCES diaries(id) ON DELETE CASCADE,
    text_analysis JSONB,
    voice_analysis JSONB,
    image_analysis JSONB,
    video_analysis JSONB,
    drawing_analysis JSONB,
    fusion_analysis JSONB,
    stress_score INTEGER CHECK (stress_score >= 0 AND stress_score <= 100),
    positive_event_score INTEGER CHECK (positive_event_score >= 0 AND positive_event_score <= 100),
    confidence_score INTEGER CHECK (confidence_score >= 0 AND confidence_score <= 100),
    analysis_version INTEGER DEFAULT 1,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_diary_multimodal_insights_diary_id
    ON diary_multimodal_insights(diary_id);
