import OpenAI from "openai";
import { parseQuiz, StoredQuiz } from "./learning";
import { LearningPlan, parseLearningPlan, planFromRow, queueLearningPlanRefill } from "./learning-plan";

export interface LearningPlanWorkerEnv {
  AI_QUOTA_DB: D1Database;
  OPENAI_API_KEY: string;
  OPENAI_QUIZ_MODEL?: string;
  SAFETY_SALT: string;
}

type JobRow = {
  job_id: string;
  parent_uid: string;
  child_uid: string;
  plan_snapshot_json: string;
  quiz_count: number;
  attempt_count: number;
};

const MONTHLY_AUTO_QUIZ_LIMIT = 10;
const quizSchema = {
  type: "object",
  additionalProperties: false,
  required: ["title", "subject", "grade", "questions"],
  properties: {
    title: { type: "string" },
    subject: { type: "string" },
    grade: { type: "string" },
    questions: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        required: ["prompt", "allowMultipleAnswers", "choices"],
        properties: {
          prompt: { type: "string" },
          allowMultipleAnswers: { type: "boolean" },
          choices: {
            type: "array",
            items: {
              type: "object",
              additionalProperties: false,
              required: ["text", "isCorrect"],
              properties: { text: { type: "string" }, isCorrect: { type: "boolean" } },
            },
          },
        },
      },
    },
  },
} as const;

export async function runLearningPlanRefills(env: LearningPlanWorkerEnv): Promise<{ processed: number }> {
  await reconcileLearningPlans(env.AI_QUOTA_DB);
  const jobs = await env.AI_QUOTA_DB.prepare(`SELECT job_id, parent_uid, child_uid, plan_snapshot_json,
      quiz_count, attempt_count FROM quiz_generation_jobs
    WHERE status IN ('queued', 'retrying') AND next_run_at <= ?
    ORDER BY created_at ASC LIMIT 3`)
    .bind(Date.now())
    .all<JobRow>();
  for (const job of jobs.results) await processJob(env, job);
  return { processed: jobs.results.length };
}

async function reconcileLearningPlans(db: D1Database) {
  const plans = await db.prepare(`SELECT parent_uid, child_uid, age, grade, subjects_json, curriculum_notes,
      strengths, weak_areas, difficulty, difficulties_json, minimum_available, refill_count, assignment_mode,
      minimum_score_percent, reward_minutes, reward_tiers_json FROM learning_plans`)
    .all<Parameters<typeof planFromRow>[0] & { parent_uid: string; child_uid: string }>();
  for (const row of plans.results) {
    await queueLearningPlanRefill(db, row.parent_uid, row.child_uid, planFromRow(row));
  }
}

async function processJob(env: LearningPlanWorkerEnv, job: JobRow) {
  const now = Date.now();
  const claimed = await env.AI_QUOTA_DB.prepare(`UPDATE quiz_generation_jobs
    SET status = 'running', attempt_count = attempt_count + 1, updated_at = ?, last_error = NULL
    WHERE job_id = ? AND status IN ('queued', 'retrying') AND next_run_at <= ?
    RETURNING job_id, parent_uid, child_uid, plan_snapshot_json, quiz_count, attempt_count`)
    .bind(now, job.job_id, now)
    .first<JobRow>();
  if (!claimed) return;
  try {
    const plan = parseLearningPlan(JSON.parse(claimed.plan_snapshot_json));
    const quotaKey = `account:${claimed.parent_uid}`;
    const period = utcMonth();
    const reserved = await reserveQuota(env.AI_QUOTA_DB, quotaKey, period, claimed.quiz_count);
    if (!reserved) throw new Error("Monthly AI quiz limit reached.");
    try {
      const recent = await recentTitles(env.AI_QUOTA_DB, claimed.parent_uid, claimed.child_uid);
      for (let index = 0; index < claimed.quiz_count; index += 1) {
        if (await hasStoredJobQuiz(env.AI_QUOTA_DB, claimed.job_id, index)) continue;
        const quiz = await generateQuiz(env, claimed.parent_uid, plan, plan.subjects[index % plan.subjects.length], recent);
        if (plan.assignmentMode === "auto_assign") {
          await createAssignment(env.AI_QUOTA_DB, claimed.parent_uid, claimed.child_uid, quiz, plan, claimed.job_id, index);
        } else {
          await env.AI_QUOTA_DB.prepare(`INSERT INTO learning_plan_drafts
            (draft_id, job_id, job_quiz_index, parent_uid, child_uid, quiz_json, plan_snapshot_json, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?)`)
            .bind(crypto.randomUUID(), claimed.job_id, index, claimed.parent_uid, claimed.child_uid,
              JSON.stringify(quiz), claimed.plan_snapshot_json, Date.now())
            .run();
        }
      }
      await env.AI_QUOTA_DB.prepare(`UPDATE quiz_generation_jobs
        SET status = 'completed', updated_at = ? WHERE job_id = ? AND status = 'running'`)
        .bind(Date.now(), claimed.job_id)
        .run();
    } catch (cause) {
      await releaseQuota(env.AI_QUOTA_DB, quotaKey, period, claimed.quiz_count);
      throw cause;
    }
  } catch (cause) {
    const message = cause instanceof Error ? cause.message.slice(0, 500) : "Quiz generation failed.";
    const attempt = claimed.attempt_count;
    const retrying = attempt < 3 && message !== "Monthly AI quiz limit reached.";
    await env.AI_QUOTA_DB.prepare(`UPDATE quiz_generation_jobs
      SET status = ?, next_run_at = ?, last_error = ?, updated_at = ? WHERE job_id = ? AND status = 'running'`)
      .bind(retrying ? "retrying" : "failed", retrying ? Date.now() + attempt * 5 * 60_000 : Date.now(), message, Date.now(), claimed.job_id)
      .run();
    console.error(JSON.stringify({ event: "learning_plan_refill_failed", jobId: claimed.job_id, message }));
  }
}

async function generateQuiz(
  env: LearningPlanWorkerEnv,
  parentUid: string,
  plan: LearningPlan,
  subject: string,
  recentTitles: string[],
): Promise<StoredQuiz> {
  const model = env.OPENAI_QUIZ_MODEL?.trim();
  if (!model) throw new Error("OPENAI_QUIZ_MODEL is not configured.");
  const response = await new OpenAI({ apiKey: env.OPENAI_API_KEY }).responses.create({
    model,
    reasoning: { effort: "low" },
    safety_identifier: await sha256(`${env.SAFETY_SALT}:${parentUid}`),
    input: [
      {
        role: "developer",
        content: "Create an age-appropriate English multiple-choice learning quiz. Follow the parent-controlled learning plan exactly. Cover a fresh subtopic, avoiding the recent quiz titles. Use exactly five questions, four concise text choices each, and exactly one correct choice. Return no images, markdown, explanations, personal data, or unsafe content.",
      },
      {
        role: "user",
        content: JSON.stringify({
          grade: plan.grade,
          subject,
          curriculumNotes: plan.curriculumNotes,
          strengths: plan.strengths,
          weakAreas: plan.weakAreas,
          difficulty: plan.difficulties[Math.floor(Math.random() * plan.difficulties.length)],
          recentTitles,
        }),
      },
    ],
    tools: plan.curriculumNotes ? [{ type: "web_search", search_context_size: "low" }] : [],
    tool_choice: plan.curriculumNotes ? "required" : "auto",
    text: { format: { type: "json_schema", name: "learning_plan_quiz", strict: true, schema: quizSchema } },
  });
  const quiz = parseQuiz(JSON.parse(response.output_text));
  return { ...quiz, subject, grade: plan.grade };
}

async function createAssignment(
  db: D1Database,
  parentUid: string,
  childUid: string,
  quiz: StoredQuiz,
  plan: LearningPlan,
  jobId: string,
  jobIndex: number,
) {
  const quizId = crypto.randomUUID();
  const assignmentId = crypto.randomUUID();
  const now = Date.now();
  await db.batch([
    db.prepare(`INSERT INTO learning_quizzes
      (quiz_id, parent_uid, title, subject, grade, questions_json, question_count, learning_plan_job_id, learning_plan_job_index, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
      .bind(quizId, parentUid, quiz.title, quiz.subject, quiz.grade, JSON.stringify(quiz), quiz.questions.length, jobId, jobIndex, now, now),
    db.prepare(`INSERT INTO quiz_assignments
      (assignment_id, quiz_id, parent_uid, child_uid, minimum_score_percent, reward_minutes, reward_tiers_json,
       prize_pool_minutes, score_improve_cooldown_minutes, max_attempts, repeat_interval_minutes,
       retry_when_failed, allow_practice_during_cooldown, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, -1, 0, 0, ?)`)
      .bind(assignmentId, quizId, parentUid, childUid, plan.minimumScorePercent, plan.rewardMinutes,
        JSON.stringify(plan.rewardTiers), plan.rewardTiers[0].rewardMinutes, 60, now),
  ]);
}

async function hasStoredJobQuiz(db: D1Database, jobId: string, index: number): Promise<boolean> {
  const stored = await db.prepare(`SELECT 1 AS stored FROM learning_quizzes
    WHERE learning_plan_job_id = ? AND learning_plan_job_index = ?
    UNION ALL
    SELECT 1 AS stored FROM learning_plan_drafts WHERE job_id = ? AND job_quiz_index = ?
    LIMIT 1`)
    .bind(jobId, index, jobId, index)
    .first<{ stored: number }>();
  return stored !== null;
}

async function recentTitles(db: D1Database, parentUid: string, childUid: string): Promise<string[]> {
  const rows = await db.prepare(`SELECT lq.title FROM quiz_assignments qa
    JOIN learning_quizzes lq ON lq.quiz_id = qa.quiz_id
    WHERE qa.parent_uid = ? AND qa.child_uid = ? ORDER BY qa.created_at DESC LIMIT 12`)
    .bind(parentUid, childUid)
    .all<{ title: string }>();
  return rows.results.map(row => row.title);
}

async function reserveQuota(db: D1Database, key: string, period: string, amount: number): Promise<boolean> {
  const result = await db.prepare(`INSERT INTO quiz_quota (quota_key, used, period, updated_at)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(quota_key) DO UPDATE SET
      used = CASE WHEN quiz_quota.period = excluded.period THEN quiz_quota.used + excluded.used ELSE excluded.used END,
      period = excluded.period, updated_at = excluded.updated_at
    WHERE quiz_quota.period != excluded.period OR quiz_quota.used <= ? - excluded.used
    RETURNING used`)
    .bind(key, amount, period, Date.now(), MONTHLY_AUTO_QUIZ_LIMIT)
    .first();
  return result !== null;
}

async function releaseQuota(db: D1Database, key: string, period: string, amount: number) {
  await db.prepare("UPDATE quiz_quota SET used = MAX(0, used - ?), updated_at = ? WHERE quota_key = ? AND period = ?")
    .bind(amount, Date.now(), key, period)
    .run();
}

function utcMonth() {
  return new Date().toISOString().slice(0, 7);
}

async function sha256(value: string) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
}
