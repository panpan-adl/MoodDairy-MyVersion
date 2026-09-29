-- Create chat_agent_plans table for persisted tool confirmation workflow

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
