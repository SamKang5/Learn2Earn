CREATE TABLE IF NOT EXISTS learning_plans (
    parent_uid TEXT NOT NULL REFERENCES learning_accounts(uid) ON DELETE CASCADE,
    child_uid TEXT NOT NULL REFERENCES learning_accounts(uid) ON DELETE CASCADE,
    age INTEGER NOT NULL CHECK (age BETWEEN 3 AND 21),
    grade TEXT NOT NULL,
    subjects_json TEXT NOT NULL,
    curriculum_notes TEXT NOT NULL DEFAULT '',
    strengths TEXT NOT NULL DEFAULT '',
    weak_areas TEXT NOT NULL DEFAULT '',
    difficulty TEXT NOT NULL CHECK (difficulty IN ('Easy', 'Balanced', 'Challenging')),
    minimum_available INTEGER NOT NULL CHECK (minimum_available BETWEEN 1 AND 10),
    refill_count INTEGER NOT NULL CHECK (refill_count BETWEEN 1 AND 5),
    assignment_mode TEXT NOT NULL CHECK (assignment_mode IN ('auto_assign', 'parent_review')),
    minimum_score_percent INTEGER NOT NULL CHECK (minimum_score_percent BETWEEN 1 AND 100),
    reward_minutes INTEGER NOT NULL CHECK (reward_minutes BETWEEN 1 AND 240),
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (parent_uid, child_uid),
    FOREIGN KEY (parent_uid, child_uid) REFERENCES family_links(parent_uid, child_uid) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS quiz_generation_jobs (
    job_id TEXT PRIMARY KEY,
    parent_uid TEXT NOT NULL REFERENCES learning_accounts(uid) ON DELETE CASCADE,
    child_uid TEXT NOT NULL REFERENCES learning_accounts(uid) ON DELETE CASCADE,
    plan_snapshot_json TEXT NOT NULL,
    quiz_count INTEGER NOT NULL CHECK (quiz_count BETWEEN 1 AND 5),
    status TEXT NOT NULL CHECK (status IN ('queued', 'running', 'retrying', 'completed', 'failed')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_run_at INTEGER NOT NULL,
    last_error TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (parent_uid, child_uid) REFERENCES family_links(parent_uid, child_uid) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS quiz_generation_jobs_one_active_per_child
    ON quiz_generation_jobs(parent_uid, child_uid)
    WHERE status IN ('queued', 'running', 'retrying');

CREATE INDEX IF NOT EXISTS quiz_generation_jobs_ready
    ON quiz_generation_jobs(status, next_run_at);

CREATE TABLE IF NOT EXISTS learning_plan_drafts (
    draft_id TEXT PRIMARY KEY,
    job_id TEXT NOT NULL REFERENCES quiz_generation_jobs(job_id) ON DELETE CASCADE,
    parent_uid TEXT NOT NULL REFERENCES learning_accounts(uid) ON DELETE CASCADE,
    child_uid TEXT NOT NULL REFERENCES learning_accounts(uid) ON DELETE CASCADE,
    quiz_json TEXT NOT NULL,
    plan_snapshot_json TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'rejected')),
    created_at INTEGER NOT NULL,
    reviewed_at INTEGER
);

CREATE INDEX IF NOT EXISTS learning_plan_drafts_parent_child
    ON learning_plan_drafts(parent_uid, child_uid, status, created_at DESC);
