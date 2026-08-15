ALTER TABLE quiz_assignments
    ADD COLUMN reward_tiers_json TEXT NOT NULL DEFAULT '[{"minimumScorePercent":80,"rewardMinutes":15}]';
