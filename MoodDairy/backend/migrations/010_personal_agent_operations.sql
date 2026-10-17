-- Plan receipts replace diary-based idempotency; never persist the planning input.
CREATE TABLE IF NOT EXISTS personal_agent_operations (
    digest VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
