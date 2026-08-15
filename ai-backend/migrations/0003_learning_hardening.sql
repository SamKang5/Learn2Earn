ALTER TABLE pairing_codes
    ADD COLUMN timezone_offset_minutes INTEGER NOT NULL DEFAULT 0
        CHECK (timezone_offset_minutes BETWEEN -840 AND 840);

ALTER TABLE family_links
    ADD COLUMN timezone_offset_minutes INTEGER NOT NULL DEFAULT 0
        CHECK (timezone_offset_minutes BETWEEN -840 AND 840);

ALTER TABLE quiz_attempts
    ADD COLUMN submission_id TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS quiz_attempts_submission
    ON quiz_attempts(child_uid, submission_id)
    WHERE submission_id IS NOT NULL;
