ALTER TABLE quiz_assignments ADD COLUMN source_quiz_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS quiz_assignments_one_source_per_child
    ON quiz_assignments(parent_uid, child_uid, source_quiz_id)
    WHERE source_quiz_id IS NOT NULL;

ALTER TABLE learning_plans ADD COLUMN difficulties_json TEXT NOT NULL DEFAULT '["Balanced"]';
ALTER TABLE learning_plans ADD COLUMN reward_tiers_json TEXT NOT NULL DEFAULT '[]';
