PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS learning_accounts (
    uid TEXT PRIMARY KEY,
    role TEXT NOT NULL CHECK (role IN ('parent', 'child')),
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS pairing_codes (
    code TEXT PRIMARY KEY,
    parent_uid TEXT NOT NULL REFERENCES learning_accounts(uid) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS pairing_codes_expires_at
    ON pairing_codes(expires_at);

CREATE TABLE IF NOT EXISTS family_links (
    parent_uid TEXT NOT NULL REFERENCES learning_accounts(uid) ON DELETE CASCADE,
    child_uid TEXT NOT NULL UNIQUE REFERENCES learning_accounts(uid) ON DELETE CASCADE,
    daily_earned_cap_minutes INTEGER NOT NULL DEFAULT 120
        CHECK (daily_earned_cap_minutes BETWEEN 0 AND 1440),
    paired_at INTEGER NOT NULL,
    PRIMARY KEY (parent_uid, child_uid),
    CHECK (parent_uid <> child_uid)
);

CREATE TABLE IF NOT EXISTS learning_quizzes (
    quiz_id TEXT PRIMARY KEY,
    parent_uid TEXT NOT NULL REFERENCES learning_accounts(uid) ON DELETE CASCADE,
    title TEXT NOT NULL,
    subject TEXT NOT NULL,
    grade TEXT NOT NULL,
    questions_json TEXT NOT NULL,
    question_count INTEGER NOT NULL CHECK (question_count BETWEEN 1 AND 20),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS learning_quizzes_parent
    ON learning_quizzes(parent_uid, updated_at DESC);

CREATE TABLE IF NOT EXISTS quiz_assignments (
    assignment_id TEXT PRIMARY KEY,
    quiz_id TEXT NOT NULL REFERENCES learning_quizzes(quiz_id),
    parent_uid TEXT NOT NULL,
    child_uid TEXT NOT NULL,
    minimum_score_percent INTEGER NOT NULL CHECK (minimum_score_percent BETWEEN 0 AND 100),
    reward_minutes INTEGER NOT NULL CHECK (reward_minutes BETWEEN 0 AND 1440),
    max_attempts INTEGER NOT NULL CHECK (max_attempts BETWEEN 1 AND 10),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    best_score_percent INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'completed')),
    created_at INTEGER NOT NULL,
    completed_at INTEGER,
    FOREIGN KEY (parent_uid, child_uid) REFERENCES family_links(parent_uid, child_uid)
);

CREATE INDEX IF NOT EXISTS quiz_assignments_child_status
    ON quiz_assignments(child_uid, status, created_at);

CREATE INDEX IF NOT EXISTS quiz_assignments_parent_child
    ON quiz_assignments(parent_uid, child_uid, created_at DESC);

CREATE TABLE IF NOT EXISTS quiz_attempts (
    attempt_id TEXT PRIMARY KEY,
    assignment_id TEXT NOT NULL REFERENCES quiz_assignments(assignment_id),
    child_uid TEXT NOT NULL REFERENCES learning_accounts(uid),
    attempt_number INTEGER NOT NULL,
    answers_json TEXT NOT NULL,
    score_percent INTEGER NOT NULL CHECK (score_percent BETWEEN 0 AND 100),
    passed INTEGER NOT NULL CHECK (passed IN (0, 1)),
    reward_minutes INTEGER NOT NULL DEFAULT 0 CHECK (reward_minutes BETWEEN 0 AND 1440),
    created_at INTEGER NOT NULL,
    UNIQUE (assignment_id, attempt_number)
);

CREATE INDEX IF NOT EXISTS quiz_attempts_assignment
    ON quiz_attempts(assignment_id, attempt_number DESC);

CREATE TABLE IF NOT EXISTS time_wallets (
    child_uid TEXT PRIMARY KEY REFERENCES learning_accounts(uid) ON DELETE CASCADE,
    earned_minutes INTEGER NOT NULL DEFAULT 0 CHECK (earned_minutes >= 0),
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS daily_reward_usage (
    child_uid TEXT NOT NULL REFERENCES learning_accounts(uid) ON DELETE CASCADE,
    day TEXT NOT NULL,
    minutes INTEGER NOT NULL DEFAULT 0 CHECK (minutes >= 0),
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (child_uid, day)
);

CREATE TABLE IF NOT EXISTS reward_ledger (
    ledger_id TEXT PRIMARY KEY,
    child_uid TEXT NOT NULL REFERENCES learning_accounts(uid),
    assignment_id TEXT NOT NULL UNIQUE REFERENCES quiz_assignments(assignment_id),
    delta_minutes INTEGER NOT NULL CHECK (delta_minutes >= 0),
    reason TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS reward_ledger_child
    ON reward_ledger(child_uid, created_at DESC);
