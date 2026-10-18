-- Preserve existing accounts; signatures start empty.
ALTER TABLE users ADD COLUMN IF NOT EXISTS signature VARCHAR(100);
