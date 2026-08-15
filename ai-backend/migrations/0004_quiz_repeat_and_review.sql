ALTER TABLE quiz_assignments
    ADD COLUMN repeat_interval_minutes INTEGER NOT NULL DEFAULT -1;

ALTER TABLE quiz_assignments
    ADD COLUMN retry_when_failed INTEGER NOT NULL DEFAULT 1
        CHECK (retry_when_failed IN (0, 1));

ALTER TABLE quiz_assignments
    ADD COLUMN allow_practice_during_cooldown INTEGER NOT NULL DEFAULT 1
        CHECK (allow_practice_during_cooldown IN (0, 1));

ALTER TABLE quiz_assignments
    ADD COLUMN next_reward_at INTEGER NOT NULL DEFAULT 0;

ALTER TABLE quiz_assignments
    ADD COLUMN cycle_attempt_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS quiz_reward_events (
    event_id TEXT PRIMARY KEY,
    attempt_id TEXT NOT NULL UNIQUE REFERENCES quiz_attempts(attempt_id),
    child_uid TEXT NOT NULL REFERENCES learning_accounts(uid),
    assignment_id TEXT NOT NULL REFERENCES quiz_assignments(assignment_id),
    delta_minutes INTEGER NOT NULL CHECK (delta_minutes >= 0),
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS quiz_reward_events_child
    ON quiz_reward_events(child_uid, created_at DESC);
