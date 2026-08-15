export type LearningPlan = {
  grade: string;
  subjects: string[];
  curriculumNotes: string;
  strengths: string;
  weakAreas: string;
  difficulties: Difficulty[];
  minimumAvailable: number;
  refillCount: number;
  assignmentMode: "auto_assign" | "parent_review";
  minimumScorePercent: number;
  rewardMinutes: number;
  rewardTiers: RewardTier[];
};

export type Difficulty = "Easy" | "Balanced" | "Challenging";
export type RewardTier = { minimumScorePercent: number; rewardMinutes: number };

export class LearningPlanValidationError extends Error {}

type PlanRow = {
  age: number;
  grade: string;
  subjects_json: string;
  curriculum_notes: string;
  strengths: string;
  weak_areas: string;
  difficulty: Difficulty;
  difficulties_json?: string;
  minimum_available: number;
  refill_count: number;
  assignment_mode: LearningPlan["assignmentMode"];
  minimum_score_percent: number;
  reward_minutes: number;
  reward_tiers_json?: string;
};

export function parseLearningPlan(value: unknown): LearningPlan {
  if (!isRecord(value)) throw new LearningPlanValidationError("Learning plan is required.");
  const subjects = Array.isArray(value.subjects) ? value.subjects.map((subject, index) =>
    text(subject, `Subject ${index + 1}`, 80)
  ) : [];
  if (subjects.length < 1 || subjects.length > 8 || new Set(subjects.map(subject => subject.toLowerCase())).size !== subjects.length) {
    throw new LearningPlanValidationError("Choose 1 to 8 different subjects.");
  }
  const difficulties = parseDifficulties(value.difficulties ?? value.difficulty);
  if (difficulties.length === 0) {
    throw new LearningPlanValidationError("Difficulty is not valid.");
  }
  const assignmentMode = value.assignmentMode;
  if (assignmentMode !== "auto_assign" && assignmentMode !== "parent_review") {
    throw new LearningPlanValidationError("Assignment mode is not valid.");
  }
  return {
    grade: text(value.grade, "Grade", 40),
    subjects,
    curriculumNotes: optionalText(value.curriculumNotes, "Curriculum notes", 500),
    strengths: optionalText(value.strengths, "Strengths", 300),
    weakAreas: optionalText(value.weakAreas, "Weak areas", 300),
    difficulties,
    minimumAvailable: integer(value.minimumAvailable, "Minimum available quizzes", 1, 10),
    refillCount: integer(value.refillCount, "Refill count", 1, 5),
    assignmentMode,
    minimumScorePercent: integer(value.minimumScorePercent, "Passing score", 1, 100),
    rewardMinutes: integer(value.rewardMinutes, "Reward minutes", 1, 240),
    rewardTiers: parseRewardTiers(value.rewardTiers, value.minimumScorePercent, value.rewardMinutes),
  };
}

export function planFromRow(row: PlanRow): LearningPlan {
  return parseLearningPlan({
    grade: row.grade,
    subjects: JSON.parse(row.subjects_json),
    curriculumNotes: row.curriculum_notes,
    strengths: row.strengths,
    weakAreas: row.weak_areas,
    difficulties: parseJson(row.difficulties_json, [row.difficulty]),
    minimumAvailable: row.minimum_available,
    refillCount: row.refill_count,
    assignmentMode: row.assignment_mode,
    minimumScorePercent: row.minimum_score_percent,
    rewardMinutes: row.reward_minutes,
    rewardTiers: parseJson(row.reward_tiers_json, [{ minimumScorePercent: row.minimum_score_percent, rewardMinutes: row.reward_minutes }]),
  });
}

export function refillNeeded(plan: LearningPlan, activeAssignments: number): number {
  return activeAssignments < plan.minimumAvailable
    ? Math.max(plan.refillCount, plan.minimumAvailable - activeAssignments)
    : 0;
}

export async function queueLearningPlanRefill(
  db: D1Database,
  parentUid: string,
  childUid: string,
  plan: LearningPlan,
): Promise<{ queued: boolean; quizCount: number }> {
  const active = await db.prepare(`SELECT COUNT(*) AS count FROM quiz_assignments
    WHERE parent_uid = ? AND child_uid = ? AND status = 'active'`)
    .bind(parentUid, childUid)
    .first<{ count: number }>();
  const quizCount = refillNeeded(plan, active?.count ?? 0);
  if (quizCount === 0) return { queued: false, quizCount: 0 };
  const now = Date.now();
  const result = await db.prepare(`INSERT OR IGNORE INTO quiz_generation_jobs
    (job_id, parent_uid, child_uid, plan_snapshot_json, quiz_count, status, next_run_at, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, 'queued', ?, ?, ?)`)
    .bind(crypto.randomUUID(), parentUid, childUid, JSON.stringify(plan), quizCount, now, now, now)
    .run();
  return { queued: (result.meta.changes ?? 0) > 0, quizCount };
}

export async function readLearningPlan(
  db: D1Database,
  parentUid: string,
  childUid: string,
): Promise<LearningPlan | null> {
  const row = await db.prepare(`SELECT age, grade, subjects_json, curriculum_notes, strengths, weak_areas,
    difficulty, difficulties_json, minimum_available, refill_count, assignment_mode, minimum_score_percent, reward_minutes, reward_tiers_json
    FROM learning_plans WHERE parent_uid = ? AND child_uid = ?`)
    .bind(parentUid, childUid)
    .first<PlanRow>();
  return row ? planFromRow(row) : null;
}

function text(value: unknown, name: string, maxLength: number): string {
  if (typeof value !== "string") throw new LearningPlanValidationError(`${name} is required.`);
  const result = value.trim();
  if (!result || result.length > maxLength) throw new LearningPlanValidationError(`${name} is not valid.`);
  return result;
}

function optionalText(value: unknown, name: string, maxLength: number): string {
  if (value == null) return "";
  if (typeof value !== "string" || value.trim().length > maxLength) throw new LearningPlanValidationError(`${name} is not valid.`);
  return value.trim();
}

function integer(value: unknown, name: string, min: number, max: number): number {
  if (!Number.isInteger(value) || (value as number) < min || (value as number) > max) {
    throw new LearningPlanValidationError(`${name} is not valid.`);
  }
  return value as number;
}

function parseDifficulties(value: unknown): Difficulty[] {
  const values = Array.isArray(value) ? value : [value];
  const valid = values.filter((difficulty): difficulty is Difficulty =>
    difficulty === "Easy" || difficulty === "Balanced" || difficulty === "Challenging");
  return [...new Set(valid)];
}

function parseRewardTiers(value: unknown, score: unknown, minutes: unknown): RewardTier[] {
  const fallback = [{ minimumScorePercent: integer(score, "Passing score", 1, 100), rewardMinutes: integer(minutes, "Reward minutes", 1, 240) }];
  if (!Array.isArray(value) || value.length === 0) return fallback;
  const tiers = value.map((tier, index) => {
    if (!isRecord(tier)) throw new LearningPlanValidationError(`Reward tier ${index + 1} is not valid.`);
    return {
      minimumScorePercent: integer(tier.minimumScorePercent, "Reward tier score", 1, 100),
      rewardMinutes: integer(tier.rewardMinutes, "Reward tier minutes", 1, 240),
    };
  });
  if (tiers.length > 6 || new Set(tiers.map(tier => tier.minimumScorePercent)).size !== tiers.length) {
    throw new LearningPlanValidationError("Choose up to 6 reward tiers with different scores.");
  }
  return tiers.sort((left, right) => right.minimumScorePercent - left.minimumScorePercent);
}

function parseJson<T>(value: string | undefined, fallback: T): T {
  try { return value ? JSON.parse(value) as T : fallback; } catch { return fallback; }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
