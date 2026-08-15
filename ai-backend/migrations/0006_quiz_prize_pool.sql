ALTER TABLE quiz_assignments
    ADD COLUMN prize_pool_minutes INTEGER NOT NULL DEFAULT 0;

ALTER TABLE quiz_assignments
    ADD COLUMN reward_earned_minutes INTEGER NOT NULL DEFAULT 0;

ALTER TABLE quiz_assignments
    ADD COLUMN pending_reward_minutes INTEGER NOT NULL DEFAULT 0;

ALTER TABLE quiz_assignments
    ADD COLUMN score_improve_cooldown_minutes INTEGER NOT NULL DEFAULT 60;
