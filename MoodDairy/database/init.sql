-- ============================================================
-- 智能日记系统 - PostgreSQL 数据库完整初始化脚本
-- 数据库: smart_diary
-- 编码: UTF-8
-- 版本: V2 (包含结构化提取功能)
-- 日期: 2026-01-23
-- ============================================================

-- 启用必要的扩展
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- 1. 用户模块
-- ============================================================

-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50),
    avatar VARCHAR(500),
    phone VARCHAR(20) UNIQUE,
    email VARCHAR(100) UNIQUE,
    gender SMALLINT DEFAULT 0,
    birthday DATE,
    status SMALLINT DEFAULT 1,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE users IS '用户表';
COMMENT ON COLUMN users.gender IS '性别：0未知/1男/2女';
COMMENT ON COLUMN users.status IS '状态：0禁用/1正常';

-- ============================================================
-- 2. 日记模块
-- ============================================================

-- 日记表
CREATE TABLE IF NOT EXISTS diaries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(200),
    content TEXT,
    diary_date DATE NOT NULL,
    weather VARCHAR(50),
    location VARCHAR(200),
    mood_score INTEGER CHECK (mood_score >= 1 AND mood_score <= 100),
    mood_type VARCHAR(50),
    is_private SMALLINT DEFAULT 1,
    is_extracted SMALLINT DEFAULT 0,
    is_highlight SMALLINT DEFAULT 0,
    is_little_joy SMALLINT DEFAULT 0,
    word_count INTEGER DEFAULT 0,
    -- V2 提取相关字段
    extraction_status VARCHAR(20) DEFAULT 'pending',
    content_hash VARCHAR(64),
    extract_version INTEGER DEFAULT 1,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE diaries IS '日记表';
COMMENT ON COLUMN diaries.is_private IS '是否私密：0公开/1私密';
COMMENT ON COLUMN diaries.is_extracted IS '是否已结构化提取：0否/1是（向后兼容）';
COMMENT ON COLUMN diaries.is_highlight IS '是否高光时刻：0否/1是';
COMMENT ON COLUMN diaries.is_little_joy IS '是否小确幸：0否/1是';
COMMENT ON COLUMN diaries.extraction_status IS '提取状态: pending/processing/succeeded/failed';
COMMENT ON COLUMN diaries.content_hash IS '内容哈希值（SHA256），用于幂等性检查';
COMMENT ON COLUMN diaries.extract_version IS '提取算法版本号，升级时递增';

-- 待办表（按日任务）
CREATE TABLE IF NOT EXISTS todos (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    todo_date DATE NOT NULL,
    title VARCHAR(200) NOT NULL,
    note TEXT,
    is_done SMALLINT NOT NULL DEFAULT 0,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT chk_todos_is_done CHECK (is_done IN (0, 1))
);

COMMENT ON TABLE todos IS '待办表（按日期管理任务）';
COMMENT ON COLUMN todos.todo_date IS '任务所属日期';
COMMENT ON COLUMN todos.is_done IS '完成状态：0未完成/1已完成';
COMMENT ON COLUMN todos.sort_order IS '同一天内排序';

-- 媒体上传表（必须在 diary_media 之前创建，因为 diary_media 有 FK 引用）
CREATE TABLE IF NOT EXISTS media_uploads (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_type VARCHAR(50) NOT NULL,
    file_size BIGINT NOT NULL,
    mime_type VARCHAR(100),
    thumbnail_path VARCHAR(500),
    duration INTEGER,
    width INTEGER,
    height INTEGER,
    checksum VARCHAR(64),
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE media_uploads IS '媒体上传表（存储上传的文件资源）';
COMMENT ON COLUMN media_uploads.file_type IS '文件类型：image/video/audio';
COMMENT ON COLUMN media_uploads.checksum IS '文件校验和（用于去重）';

-- 日记媒体表（日记内容块）
CREATE TABLE IF NOT EXISTS diary_media (
    id BIGSERIAL PRIMARY KEY,
    diary_id BIGINT NOT NULL REFERENCES diaries(id) ON DELETE CASCADE,
    asset_id BIGINT REFERENCES media_uploads(id) ON DELETE SET NULL,
    media_type VARCHAR(20) NOT NULL,
    content TEXT,
    media_url VARCHAR(500),
    thumbnail_url VARCHAR(500),
    duration INTEGER,
    file_size BIGINT,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE diary_media IS '日记媒体表（日记内容块）';
COMMENT ON COLUMN diary_media.media_type IS '媒体类型：text/image/video/audio';
COMMENT ON COLUMN diary_media.asset_id IS '关联的上传资源ID（可选）';

-- 媒体文件表（OSS 存储）
CREATE TABLE IF NOT EXISTS media_files (
    id BIGSERIAL PRIMARY KEY,
    diary_id BIGINT REFERENCES diaries(id) ON DELETE SET NULL,
    type VARCHAR(16) NOT NULL,
    url TEXT NOT NULL,
    oss_bucket TEXT,
    oss_key TEXT,
    content_type TEXT,
    size_bytes BIGINT,
    duration_ms INTEGER,
    created_at TIMESTAMPTZ DEFAULT now()
);

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

-- 语音转文字记录表
CREATE TABLE IF NOT EXISTS voice_transcriptions (
    id BIGSERIAL PRIMARY KEY,
    diary_id BIGINT NOT NULL REFERENCES diaries(id) ON DELETE CASCADE,
    media_id BIGINT REFERENCES diary_media(id) ON DELETE CASCADE,
    original_text TEXT,
    processed_text TEXT,
    confidence DECIMAL(5,2),
    detected_emotion VARCHAR(50),
    emotion_score DECIMAL(5,2),
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE voice_transcriptions IS '语音转文字记录表';

-- 提取任务记录表
CREATE TABLE IF NOT EXISTS extraction_jobs (
    id BIGSERIAL PRIMARY KEY,
    diary_id BIGINT NOT NULL REFERENCES diaries(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER DEFAULT 0,
    error_message TEXT,
    error_code VARCHAR(50),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    execution_time_ms INTEGER,
    llm_tokens_used INTEGER,
    content_hash VARCHAR(64),
    extract_version INTEGER,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE extraction_jobs IS '提取任务记录表';
COMMENT ON COLUMN extraction_jobs.status IS '任务状态: pending/processing/succeeded/failed';
COMMENT ON COLUMN extraction_jobs.attempts IS '重试次数';
COMMENT ON COLUMN extraction_jobs.execution_time_ms IS '执行时间（毫秒）';

-- 日记摘要表（V2 增强版）
CREATE TABLE IF NOT EXISTS diary_summaries (
    id BIGSERIAL PRIMARY KEY,
    diary_id BIGINT UNIQUE NOT NULL REFERENCES diaries(id) ON DELETE CASCADE,
    summary TEXT,
    keywords TEXT[],
    main_topics TEXT[],
    people_mentioned JSONB,
    places_mentioned JSONB,
    -- 情绪分析（独立字段）
    primary_emotion VARCHAR(20),
    emotion_score INTEGER CHECK (emotion_score IS NULL OR (emotion_score >= 1 AND emotion_score <= 100)),
    emotion_intensity VARCHAR(20),
    emotion_distribution JSONB,
    emotion_analysis JSONB,
    chat_analysis JSONB,
    test_analysis JSONB,
    artwork_analysis JSONB,
    has_small_happiness SMALLINT DEFAULT 0,
    small_happiness_content TEXT,
    has_highlight SMALLINT DEFAULT 0,
    highlight_summary TEXT,
    extract_version INTEGER DEFAULT 1,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    -- 约束
    CONSTRAINT chk_primary_emotion CHECK (primary_emotion IS NULL OR primary_emotion IN ('开心', '平静', '焦虑', '愤怒', '低落', '兴奋', '复杂', '中性')),
    CONSTRAINT chk_emotion_intensity CHECK (emotion_intensity IS NULL OR emotion_intensity IN ('轻微', '中等', '强烈'))
);

COMMENT ON TABLE diary_summaries IS '日记摘要表（V2增强版）';
COMMENT ON COLUMN diary_summaries.keywords IS '关键词数组（TEXT[]，便于索引）';
COMMENT ON COLUMN diary_summaries.main_topics IS '主要话题数组（TEXT[]，便于索引）';
COMMENT ON COLUMN diary_summaries.primary_emotion IS '主要情绪（枚举：开心/平静/焦虑/愤怒/低落/兴奋/复杂/中性）';
COMMENT ON COLUMN diary_summaries.emotion_score IS '情绪评分（1-100，50为中性）';
COMMENT ON COLUMN diary_summaries.emotion_intensity IS '情绪强度（轻微/中等/强烈）';
COMMENT ON COLUMN diary_summaries.emotion_distribution IS '情绪分布 {"开心": 0.6, "中性": 0.3, ...}';
COMMENT ON COLUMN diary_summaries.extract_version IS '提取算法版本号';

-- ============================================================
-- 3. 画作与视频模块
-- ============================================================

-- 画作表
CREATE TABLE IF NOT EXISTS artworks (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    diary_id BIGINT REFERENCES diaries(id) ON DELETE SET NULL,
    creation_type SMALLINT NOT NULL,
    original_image_url VARCHAR(500),
    enhanced_image_url VARCHAR(500),
    prompt TEXT,
    style VARCHAR(50),
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE artworks IS '画作表';
COMMENT ON COLUMN artworks.creation_type IS '创作类型：1自己画/2描述生成/3AI提取生成';

-- 视频表
CREATE TABLE IF NOT EXISTS emotion_videos (
    id BIGSERIAL PRIMARY KEY,
    artwork_id BIGINT NOT NULL REFERENCES artworks(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    video_url VARCHAR(500) NOT NULL,
    thumbnail_url VARCHAR(500),
    duration INTEGER,
    transition_type VARCHAR(100),
    start_emotion VARCHAR(50),
    end_emotion VARCHAR(50),
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE emotion_videos IS '情绪转变视频表';

-- ============================================================
-- 4. 虚拟宠物模块
-- ============================================================

-- 宠物表
CREATE TABLE IF NOT EXISTS pets (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT UNIQUE NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pet_type VARCHAR(50) NOT NULL,
    name VARCHAR(50),
    appearance JSONB,
    personality VARCHAR(100),
    level INTEGER DEFAULT 1,
    experience BIGINT DEFAULT 0,
    intimacy INTEGER DEFAULT 0,
    mood VARCHAR(50),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE pets IS '虚拟宠物表';

-- 宠物互动记录表
CREATE TABLE IF NOT EXISTS pet_interactions (
    id BIGSERIAL PRIMARY KEY,
    pet_id BIGINT NOT NULL REFERENCES pets(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    interaction_type VARCHAR(50) NOT NULL,
    content TEXT,
    reward_exp INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE pet_interactions IS '宠物互动记录表';

-- ============================================================
-- 5. 对话模块
-- ============================================================

-- 对话会话表
CREATE TABLE IF NOT EXISTS chat_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_type SMALLINT NOT NULL,
    trigger_reason VARCHAR(200),
    start_time TIMESTAMP DEFAULT NOW(),
    end_time TIMESTAMP,
    message_count INTEGER DEFAULT 0,
    status SMALLINT DEFAULT 0
);

COMMENT ON TABLE chat_sessions IS '对话会话表';
COMMENT ON COLUMN chat_sessions.session_type IS '会话类型：1主动对话/2AI触发/3宠物对话';
COMMENT ON COLUMN chat_sessions.status IS '状态：0进行中/1已结束';

-- 对话消息表
CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    sender_type SMALLINT NOT NULL,
    content TEXT NOT NULL,
    message_type VARCHAR(20) DEFAULT 'text',
    emotion_detected VARCHAR(50),
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE chat_messages IS '对话消息表';
COMMENT ON COLUMN chat_messages.sender_type IS '发送者类型：1用户/2AI/3宠物';

-- Agent 待确认计划表
CREATE TABLE IF NOT EXISTS chat_agent_plans (
    id BIGSERIAL PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
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
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_chat_agent_plans_status
        CHECK (status IN ('pending', 'cancelled', 'executed', 'failed', 'expired')),
    CONSTRAINT chk_chat_agent_plans_mode
        CHECK (mode IN ('read_only', 'operation', 'client_action')),
    CONSTRAINT chk_chat_agent_plans_requires_confirmation
        CHECK (requires_confirmation IN (0, 1))
);

COMMENT ON TABLE chat_agent_plans IS 'Chat Agent 待确认计划表';
COMMENT ON COLUMN chat_agent_plans.plan_id IS '返回给客户端的计划ID';
COMMENT ON COLUMN chat_agent_plans.expires_at IS '计划过期时间';

-- ============================================================
-- 6. 心理测试模块
-- ============================================================

-- 测试类型表
CREATE TABLE IF NOT EXISTS test_types (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    category VARCHAR(50),
    question_count INTEGER DEFAULT 0,
    estimated_time INTEGER,
    is_active SMALLINT DEFAULT 1,
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE test_types IS '测试类型表';

-- 测试题目表
CREATE TABLE IF NOT EXISTS test_questions (
    id BIGSERIAL PRIMARY KEY,
    test_type_id BIGINT NOT NULL REFERENCES test_types(id) ON DELETE CASCADE,
    question_text TEXT NOT NULL,
    question_order INTEGER NOT NULL,
    options JSONB NOT NULL,
    dimension VARCHAR(50),
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE test_questions IS '测试题目表';

-- 用户测试记录表
CREATE TABLE IF NOT EXISTS user_test_records (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    test_type_id BIGINT NOT NULL REFERENCES test_types(id) ON DELETE CASCADE,
    start_time TIMESTAMP DEFAULT NOW(),
    end_time TIMESTAMP,
    result_code VARCHAR(50),
    result_detail JSONB,
    score INTEGER,
    status SMALLINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE user_test_records IS '用户测试记录表';
COMMENT ON COLUMN user_test_records.status IS '状态：0进行中/1已完成';

-- 用户答案表
CREATE TABLE IF NOT EXISTS user_test_answers (
    id BIGSERIAL PRIMARY KEY,
    record_id BIGINT NOT NULL REFERENCES user_test_records(id) ON DELETE CASCADE,
    question_id BIGINT NOT NULL REFERENCES test_questions(id) ON DELETE CASCADE,
    answer VARCHAR(500) NOT NULL,
    score INTEGER,
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE user_test_answers IS '用户答案表';

-- ============================================================
-- 7. 情绪追踪模块
-- ============================================================

-- 情绪记录表
CREATE TABLE IF NOT EXISTS emotion_records (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_type VARCHAR(50) NOT NULL,
    source_id BIGINT,
    emotion_type VARCHAR(50) NOT NULL,
    emotion_score INTEGER CHECK (emotion_score >= 1 AND emotion_score <= 100),
    analysis TEXT,
    recorded_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE emotion_records IS '情绪记录表';
COMMENT ON COLUMN emotion_records.source_type IS '来源类型：diary/voice/chat';

-- ============================================================
-- 8. 高光时刻模块
-- ============================================================

-- 高光时刻表
CREATE TABLE IF NOT EXISTS highlight_moments (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    diary_id BIGINT NOT NULL REFERENCES diaries(id) ON DELETE CASCADE,
    title VARCHAR(200),
    summary TEXT,
    category VARCHAR(50),
    cover_image_url VARCHAR(500),
    highlight_date DATE NOT NULL,
    is_featured SMALLINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE highlight_moments IS '高光时刻表';

-- 小确幸表
CREATE TABLE IF NOT EXISTS small_happiness (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    diary_id BIGINT REFERENCES diaries(id) ON DELETE SET NULL,
    content TEXT NOT NULL,
    category VARCHAR(50),
    happiness_date DATE NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE small_happiness IS '小确幸表';

-- 周期性总结表
CREATE TABLE IF NOT EXISTS period_summaries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    period_type VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    summary TEXT,
    highlight_count INTEGER DEFAULT 0,
    happiness_count INTEGER DEFAULT 0,
    emotion_trend JSONB,
    key_moments JSONB,
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE period_summaries IS '周期性总结表';
COMMENT ON COLUMN period_summaries.period_type IS '周期类型：week/month/quarter/year';

-- ============================================================
-- 索引
-- ============================================================

-- 用户表索引
CREATE INDEX IF NOT EXISTS idx_users_phone ON users(phone);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- 日记表索引
CREATE INDEX IF NOT EXISTS idx_diaries_user_id ON diaries(user_id);
CREATE INDEX IF NOT EXISTS idx_diaries_diary_date ON diaries(diary_date);
CREATE INDEX IF NOT EXISTS idx_diaries_user_date ON diaries(user_id, diary_date);
CREATE INDEX IF NOT EXISTS idx_diaries_extraction_status ON diaries(extraction_status);
CREATE INDEX IF NOT EXISTS idx_diaries_content_hash ON diaries(content_hash);
CREATE INDEX IF NOT EXISTS idx_diaries_extract_version ON diaries(extract_version);
CREATE INDEX IF NOT EXISTS idx_diaries_user_extraction ON diaries(user_id, extraction_status);

-- 待办表索引
CREATE INDEX IF NOT EXISTS idx_todos_user_date ON todos(user_id, todo_date);
CREATE INDEX IF NOT EXISTS idx_todos_user_date_done_sort ON todos(user_id, todo_date, is_done, sort_order);

-- 媒体上传表索引
CREATE INDEX IF NOT EXISTS idx_media_uploads_user_id ON media_uploads(user_id);
CREATE INDEX IF NOT EXISTS idx_media_uploads_checksum ON media_uploads(checksum);

-- 日记媒体表索引
CREATE INDEX IF NOT EXISTS idx_diary_media_diary_id ON diary_media(diary_id);
CREATE INDEX IF NOT EXISTS idx_diary_media_asset_id ON diary_media(asset_id);

-- 媒体文件表索引
CREATE INDEX IF NOT EXISTS idx_media_files_diary_id ON media_files(diary_id);
CREATE INDEX IF NOT EXISTS idx_media_files_type ON media_files(type);
CREATE INDEX IF NOT EXISTS idx_media_files_created_at ON media_files(created_at);

-- 语音转文字记录表索引
CREATE INDEX IF NOT EXISTS idx_voice_transcriptions_diary_id ON voice_transcriptions(diary_id);

-- 提取任务记录表索引
CREATE INDEX IF NOT EXISTS idx_extraction_jobs_diary_id ON extraction_jobs(diary_id);
CREATE INDEX IF NOT EXISTS idx_extraction_jobs_status ON extraction_jobs(status);
CREATE INDEX IF NOT EXISTS idx_extraction_jobs_created_at ON extraction_jobs(created_at);
CREATE INDEX IF NOT EXISTS idx_extraction_jobs_diary_status ON extraction_jobs(diary_id, status);

-- 日记摘要表索引
CREATE INDEX IF NOT EXISTS idx_diary_summaries_diary_id ON diary_summaries(diary_id);
CREATE INDEX IF NOT EXISTS idx_diary_summaries_created_at ON diary_summaries(created_at);
CREATE INDEX IF NOT EXISTS idx_diary_summaries_primary_emotion ON diary_summaries(primary_emotion);
CREATE INDEX IF NOT EXISTS idx_diary_summaries_emotion_score ON diary_summaries(emotion_score);

-- GIN 索引（支持数组和 JSONB 查询）
CREATE INDEX IF NOT EXISTS idx_diary_summaries_keywords ON diary_summaries USING GIN (keywords);
CREATE INDEX IF NOT EXISTS idx_diary_summaries_main_topics ON diary_summaries USING GIN (main_topics);
CREATE INDEX IF NOT EXISTS idx_diary_summaries_emotion_dist ON diary_summaries USING GIN (emotion_distribution);

-- 画作表索引
CREATE INDEX IF NOT EXISTS idx_artworks_user_id ON artworks(user_id);
CREATE INDEX IF NOT EXISTS idx_artworks_diary_id ON artworks(diary_id);

-- 视频表索引
CREATE INDEX IF NOT EXISTS idx_emotion_videos_artwork_id ON emotion_videos(artwork_id);
CREATE INDEX IF NOT EXISTS idx_emotion_videos_user_id ON emotion_videos(user_id);

-- 宠物互动记录表索引
CREATE INDEX IF NOT EXISTS idx_pet_interactions_pet_id ON pet_interactions(pet_id);
CREATE INDEX IF NOT EXISTS idx_pet_interactions_user_id ON pet_interactions(user_id);

-- 对话会话表索引
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_id ON chat_sessions(user_id);

-- 对话消息表索引
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id ON chat_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_chat_agent_plans_user_conv_status
    ON chat_agent_plans(user_id, conversation_id, status);
CREATE INDEX IF NOT EXISTS idx_chat_agent_plans_expires_at
    ON chat_agent_plans(expires_at);
CREATE INDEX IF NOT EXISTS idx_chat_agent_plans_created_at
    ON chat_agent_plans(created_at);

-- 测试题目表索引
CREATE INDEX IF NOT EXISTS idx_test_questions_test_type_id ON test_questions(test_type_id);

-- 用户测试记录表索引
CREATE INDEX IF NOT EXISTS idx_user_test_records_user_id ON user_test_records(user_id);
CREATE INDEX IF NOT EXISTS idx_user_test_records_test_type_id ON user_test_records(test_type_id);

-- 用户答案表索引
CREATE INDEX IF NOT EXISTS idx_user_test_answers_record_id ON user_test_answers(record_id);

-- 情绪记录表索引
CREATE INDEX IF NOT EXISTS idx_emotion_records_user_id ON emotion_records(user_id);
CREATE INDEX IF NOT EXISTS idx_emotion_records_recorded_at ON emotion_records(recorded_at);

-- 高光时刻表索引
CREATE INDEX IF NOT EXISTS idx_highlight_moments_user_id ON highlight_moments(user_id);
CREATE INDEX IF NOT EXISTS idx_highlight_moments_highlight_date ON highlight_moments(highlight_date);

-- 小确幸表索引
CREATE INDEX IF NOT EXISTS idx_small_happiness_user_id ON small_happiness(user_id);
CREATE INDEX IF NOT EXISTS idx_small_happiness_happiness_date ON small_happiness(happiness_date);

-- 周期性总结表索引
CREATE INDEX IF NOT EXISTS idx_period_summaries_user_id ON period_summaries(user_id);
CREATE INDEX IF NOT EXISTS idx_period_summaries_period_type ON period_summaries(period_type);

-- ============================================================
-- 初始化测试用户
-- ============================================================

-- 插入默认测试用户 (用户名: 1, 密码: 111111)
-- 注意：生产环境中密码应该使用加密存储
INSERT INTO users (username, password, nickname, status, created_at, updated_at)
VALUES ('1', '111111', '测试用户', 1, NOW(), NOW())
ON CONFLICT (username) DO NOTHING;

-- ============================================================
-- 授予权限
-- ============================================================

-- 授予 DiarySQL 用户对所有表的权限
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO "DiarySQL";

-- 授予所有序列的权限（用于自增ID）
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "DiarySQL";

-- 设置默认权限（对未来创建的表和序列）
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO "DiarySQL";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO "DiarySQL";

-- ============================================================
-- 完成
-- ============================================================

SELECT 
    '✓ 数据库初始化完成！' as message,
    '共创建 26 张表（包含 todos、extraction_jobs、media_files 和 chat_agent_plans）' as tables,
    '已添加测试用户（用户名: 1, 密码: 111111）' as test_user,
    '已授予 DiarySQL 用户所有权限' as permissions;
