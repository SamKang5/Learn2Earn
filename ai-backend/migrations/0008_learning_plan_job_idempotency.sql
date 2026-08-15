ALTER TABLE learning_quizzes ADD COLUMN learning_plan_job_id TEXT;
ALTER TABLE learning_quizzes ADD COLUMN learning_plan_job_index INTEGER;

CREATE UNIQUE INDEX IF NOT EXISTS learning_quizzes_plan_job_item
    ON learning_quizzes(learning_plan_job_id, learning_plan_job_index)
    WHERE learning_plan_job_id IS NOT NULL;

ALTER TABLE learning_plan_drafts ADD COLUMN job_quiz_index INTEGER NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS learning_plan_drafts_plan_job_item
    ON learning_plan_drafts(job_id, job_quiz_index);
