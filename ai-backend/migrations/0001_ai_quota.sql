CREATE TABLE IF NOT EXISTS quiz_quota (
    quota_key TEXT PRIMARY KEY,
    used INTEGER NOT NULL,
    period TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ip_rate_limit (
    rate_key TEXT PRIMARY KEY,
    used INTEGER NOT NULL,
    period TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);
