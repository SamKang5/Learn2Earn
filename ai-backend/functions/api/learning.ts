import { ApiError, authenticate, isRecord, jsonError, readJson } from "../../src/firebase-auth";
import {
  parseAnswers,
  parseQuiz,
  reviewQuiz,
  rewardForScore,
  scoreQuiz,
  StoredQuiz,
  toChildQuiz,
  ValidationError,
} from "../../src/learning";
import {
  LearningPlan,
  LearningPlanValidationError,
  parseLearningPlan,
  queueLearningPlanRefill,
  readLearningPlan,
} from "../../src/learning-plan";

interface Env {
  AI_QUOTA_DB: D1Database;
  FIREBASE_PROJECT_ID: string;
}

type RoleRow = { role: "parent" | "child" };
type PairingRow = {
  parent_uid: string;
  expires_at: number;
  timezone_offset_minutes: number;
};
type AssignmentRow = {
  assignment_id: string;
  parent_uid: string;
  child_uid: string;
  questions_json: string;
  timezone_offset_minutes: number;
  minimum_score_percent: number;
  reward_minutes: number;
  reward_tiers_json: string;
  prize_pool_minutes: number;
  reward_earned_minutes: number;
  pending_reward_minutes: number;
  score_improve_cooldown_minutes: number;
  max_attempts: number;
  attempt_count: number;
  best_score_percent: number;
  cycle_attempt_count: number;
  repeat_interval_minutes: number;
  retry_when_failed: number;
  allow_practice_during_cooldown: number;
  next_reward_at: number;
  status: "active" | "completed";
};
type AttemptRow = {
  attempt_id: string;
  submission_id: string | null;
  attempt_number: number;
  score_percent: number;
  passed: number;
  reward_minutes: number;
};
type BalanceRow = { earned_minutes: number };
type CatalogRow = {
  assignment_id: string;
  title: string;
  subject: string;
  grade: string;
  questions_json: string;
  question_count: number;
  minimum_score_percent: number;
  reward_minutes: number;
  reward_tiers_json: string;
  prize_pool_minutes: number;
  reward_earned_minutes: number;
  pending_reward_minutes: number;
  score_improve_cooldown_minutes: number;
  max_attempts: number;
  attempt_count: number;
  cycle_attempt_count: number;
  created_at: number;
  status: "active" | "completed";
  best_score_percent: number;
  completed_at: number | null;
  repeat_interval_minutes: number;
  retry_when_failed: number;
  allow_practice_during_cooldown: number;
  next_reward_at: number;
};

const PAIRING_CODE_TTL_MS = 10 * 60 * 1000;
const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

export const onRequestPost: PagesFunction<Env> = async ({ request, env }) => {
  try {
    const identity = await authenticate(request, env.FIREBASE_PROJECT_ID);
    const body = await readJson(request);
    if (!isRecord(body) || typeof body.action !== "string") {
      throw new ApiError(422, "INVALID_INPUT", "Action is required.");
    }
    switch (body.action) {
      case "createPairingCode":
        requireParentIdentity(identity);
        return json(await createPairingCode(env.AI_QUOTA_DB, identity.uid, body), 201);
      case "claimPairingCode":
        return json(await claimPairingCode(env.AI_QUOTA_DB, identity.uid, body.code));
      case "setRewardPolicy":
        requireParentIdentity(identity);
        return json(await setRewardPolicy(env.AI_QUOTA_DB, identity.uid, body));
      case "assignQuiz":
        requireParentIdentity(identity);
        return json(await assignQuiz(env.AI_QUOTA_DB, identity.uid, body), 201);
      case "syncQuizCover":
        requireParentIdentity(identity);
        return json(await syncQuizCover(env.AI_QUOTA_DB, identity.uid, body));
      case "setLearningPlan":
        requireParentIdentity(identity);
        return json(await setLearningPlan(env.AI_QUOTA_DB, identity.uid, body));
      case "reviewLearningPlanDraft":
        requireParentIdentity(identity);
        return json(await reviewLearningPlanDraft(env.AI_QUOTA_DB, identity.uid, body));
      case "unpairChild":
        requireParentIdentity(identity);
        return json(await unpairChild(env.AI_QUOTA_DB, identity.uid, body));
      case "submitQuiz":
        return json(await submitQuiz(env.AI_QUOTA_DB, identity.uid, body));
      default:
        throw new ApiError(422, "INVALID_ACTION", "Action is not supported.");
    }
  } catch (cause) {
    return jsonError(normalizeValidationError(cause));
  }
};

export const onRequestGet: PagesFunction<Env> = async ({ request, env }) => {
  try {
    const identity = await authenticate(request, env.FIREBASE_PROJECT_ID);
    const url = new URL(request.url);
    switch (url.searchParams.get("view")) {
      case "nextQuiz":
        return json(await nextQuiz(env.AI_QUOTA_DB, identity.uid, url.searchParams.get("assignmentId")));
      case "quizCatalog":
        return json(await quizCatalog(env.AI_QUOTA_DB, identity.uid));
      case "quizReview":
        return json(await quizReview(env.AI_QUOTA_DB, identity.uid, url.searchParams.get("assignmentId")));
      case "balance":
        return json(await balance(env.AI_QUOTA_DB, identity.uid));
      case "childSummary":
        requireParentIdentity(identity);
        return json(await childSummary(env.AI_QUOTA_DB, identity.uid, url.searchParams.get("childUid")));
      case "learningPlan":
        requireParentIdentity(identity);
        return json(await learningPlan(env.AI_QUOTA_DB, identity.uid, url.searchParams.get("childUid")));
      default:
        throw new ApiError(422, "INVALID_VIEW", "View is not supported.");
    }
  } catch (cause) {
    return jsonError(normalizeValidationError(cause));
  }
};

export const onRequest: PagesFunction<Env> = async () =>
  jsonError(new ApiError(405, "METHOD_NOT_ALLOWED", "Use GET or POST."));

async function createPairingCode(
  db: D1Database,
  parentUid: string,
  body: Record<string, unknown>
) {
  await ensureRole(db, parentUid, "parent");
  const now = Date.now();
  const timezoneOffsetMinutes = timezoneOffset(body.timezoneOffsetMinutes);
  await db.prepare("DELETE FROM pairing_codes WHERE parent_uid = ? OR expires_at < ?")
    .bind(parentUid, now)
    .run();
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const code = randomCode();
    try {
      await db.prepare(`INSERT INTO pairing_codes
        (code, parent_uid, expires_at, created_at, timezone_offset_minutes)
        VALUES (?, ?, ?, ?, ?)`)
        .bind(code, parentUid, now + PAIRING_CODE_TTL_MS, now, timezoneOffsetMinutes)
        .run();
      return {
        code,
        expiresAt: now + PAIRING_CODE_TTL_MS,
        timezoneOffsetMinutes,
      };
    } catch (cause) {
      if (!isUniqueConstraint(cause)) throw cause;
    }
  }
  throw new ApiError(503, "PAIRING_UNAVAILABLE", "Could not create a pairing code. Try again.");
}

async function claimPairingCode(db: D1Database, childUid: string, rawCode: unknown) {
  const code = requiredText(rawCode, "Pairing code", 6).toUpperCase();
  if (!/^[A-HJ-NP-Z2-9]{6}$/.test(code)) throw new ApiError(422, "INVALID_CODE", "Pairing code is invalid.");
  const now = Date.now();
  const existingLink = await db.prepare(
    "SELECT parent_uid FROM family_links WHERE child_uid = ?"
  ).bind(childUid).first<{ parent_uid: string }>();
  const pairing = await db.prepare(
    "SELECT parent_uid, expires_at, timezone_offset_minutes FROM pairing_codes WHERE code = ?"
  )
    .bind(code)
    .first<PairingRow>();
  // A successful D1 claim can be followed by a transient RTDB failure. Returning
  // the existing link makes the client retry safe, but never for another parent's
  // still-live code.
  if (existingLink && !pairing) {
    return { parentUid: existingLink.parent_uid, recovered: true };
  }
  if (!pairing || pairing.expires_at < now) throw new ApiError(404, "PAIRING_CODE_NOT_FOUND", "Pairing code is invalid or expired.");
  if (existingLink && existingLink.parent_uid !== pairing.parent_uid) {
    throw new ApiError(409, "ALREADY_PAIRED", "Child account is already paired with another parent.");
  }
  const role = await db.prepare("SELECT role FROM learning_accounts WHERE uid = ?").bind(childUid).first<RoleRow>();
  if (role?.role === "parent") throw new ApiError(409, "ROLE_CONFLICT", "Parent account cannot be paired as a child.");

  await db.batch([
    db.prepare("INSERT INTO learning_accounts (uid, role, created_at) VALUES (?, 'child', ?) ON CONFLICT(uid) DO NOTHING")
      .bind(childUid, now),
    db.prepare(`INSERT INTO family_links
      (parent_uid, child_uid, paired_at, timezone_offset_minutes)
      SELECT parent_uid, ?, ?, timezone_offset_minutes
      FROM pairing_codes WHERE code = ? AND expires_at >= ?
      ON CONFLICT(child_uid) DO NOTHING`)
      .bind(childUid, now, code, now),
    db.prepare("INSERT INTO time_wallets (child_uid, earned_minutes, updated_at) VALUES (?, 0, ?) ON CONFLICT(child_uid) DO NOTHING")
      .bind(childUid, now),
    db.prepare(`DELETE FROM pairing_codes
      WHERE code = ? AND EXISTS (
        SELECT 1 FROM family_links
        WHERE child_uid = ? AND parent_uid = pairing_codes.parent_uid
      )`)
      .bind(code, childUid),
  ]);

  const link = await db.prepare("SELECT parent_uid FROM family_links WHERE child_uid = ?").bind(childUid).first<{ parent_uid: string }>();
  if (!link) throw new ApiError(409, "PAIRING_CONFLICT", "Pairing code was already used.");
  if (link.parent_uid !== pairing.parent_uid) {
    throw new ApiError(409, "ALREADY_PAIRED", "Child account is already paired with another parent.");
  }
  return { parentUid: link.parent_uid };
}

async function setRewardPolicy(db: D1Database, parentUid: string, body: Record<string, unknown>) {
  await requireRole(db, parentUid, "parent");
  const childUid = requiredText(body.childUid, "Child ID", 128);
  const dailyCap = requiredInteger(body.dailyEarnedCapMinutes, "Daily earned cap", 0, 1440);
  const timezoneOffsetMinutes = timezoneOffset(body.timezoneOffsetMinutes);
  const result = await db.prepare(`UPDATE family_links SET daily_earned_cap_minutes = ?
    , timezone_offset_minutes = ?
    WHERE parent_uid = ? AND child_uid = ?`)
    .bind(dailyCap, timezoneOffsetMinutes, parentUid, childUid)
    .run();
  if ((result.meta.changes ?? 0) === 0) throw new ApiError(404, "CHILD_NOT_FOUND", "Paired child was not found.");
  return { childUid, dailyEarnedCapMinutes: dailyCap, timezoneOffsetMinutes };
}

async function unpairChild(db: D1Database, parentUid: string, body: Record<string, unknown>) {
  await requireRole(db, parentUid, "parent");
  const childUid = requiredText(body.childUid, "Child ID", 128);
  await requireLink(db, parentUid, childUid);
  const assignmentIds = await db.prepare(
    "SELECT assignment_id FROM quiz_assignments WHERE parent_uid = ? AND child_uid = ?"
  ).bind(parentUid, childUid).all<{ assignment_id: string }>();
  const statements: D1PreparedStatement[] = [];
  for (const assignment of assignmentIds.results) {
    statements.push(
      db.prepare("DELETE FROM reward_ledger WHERE assignment_id = ?").bind(assignment.assignment_id),
      db.prepare("DELETE FROM quiz_reward_events WHERE assignment_id = ?").bind(assignment.assignment_id),
      db.prepare("DELETE FROM quiz_attempts WHERE assignment_id = ?").bind(assignment.assignment_id),
      db.prepare("DELETE FROM quiz_assignments WHERE assignment_id = ?").bind(assignment.assignment_id),
    );
  }
  statements.push(
    db.prepare("DELETE FROM daily_reward_usage WHERE child_uid = ?").bind(childUid),
    db.prepare("DELETE FROM time_wallets WHERE child_uid = ?").bind(childUid),
    db.prepare("DELETE FROM family_links WHERE parent_uid = ? AND child_uid = ?").bind(parentUid, childUid),
  );
  await db.batch(statements);
  return { childUid, unpaired: true };
}

async function assignQuiz(db: D1Database, parentUid: string, body: Record<string, unknown>) {
  await requireRole(db, parentUid, "parent");
  const childUid = requiredText(body.childUid, "Child ID", 128);
  await requireLink(db, parentUid, childUid);
  const quiz = parseQuiz(body.quiz);
  const sourceQuizId = optionalText(body.sourceQuizId, 128);
  const minimumScorePercent = requiredInteger(body.minimumScorePercent, "Minimum score", 0, 100);
  const rewardMinutes = requiredInteger(body.rewardMinutes, "Reward minutes", 0, 240);
  const rewardTiers = rewardTiersFromBody(body.rewardTiers, minimumScorePercent, rewardMinutes);
  const scoreImproveCooldownMinutes = requiredInteger(
    body.scoreImproveCooldownMinutes ?? 60,
    "Score-improvement cooldown",
    0,
    10_080
  );
  if (sourceQuizId) {
    const existing = await db.prepare(`SELECT qa.assignment_id, qa.quiz_id, qa.source_quiz_id FROM quiz_assignments qa
      JOIN learning_quizzes lq ON lq.quiz_id = qa.quiz_id
      WHERE qa.parent_uid = ? AND qa.child_uid = ?
        AND (qa.source_quiz_id = ? OR (lq.title = ? AND lq.subject = ? AND lq.grade = ?))
      ORDER BY CASE WHEN qa.source_quiz_id = ? THEN 0 ELSE 1 END, qa.created_at DESC LIMIT 1`)
      .bind(parentUid, childUid, sourceQuizId, quiz.title, quiz.subject, quiz.grade, sourceQuizId)
      .first<{ assignment_id: string; quiz_id: string }>();
    if (existing) {
      // The parent catalog is the media source of truth. Refreshing an already-assigned
      // quiz backfills covers/images created before portable image data existed.
      await db.prepare(`UPDATE learning_quizzes SET title = ?, subject = ?, grade = ?, questions_json = ?,
        question_count = ?, updated_at = ? WHERE quiz_id = ? AND parent_uid = ?`)
        .bind(quiz.title, quiz.subject, quiz.grade, JSON.stringify(quiz), quiz.questions.length, Date.now(), existing.quiz_id, parentUid)
        .run();
      await db.prepare("UPDATE quiz_assignments SET source_quiz_id = ? WHERE assignment_id = ? AND source_quiz_id IS NULL")
        .bind(sourceQuizId, existing.assignment_id)
        .run();
      return { assignmentId: existing.assignment_id, quizId: existing.quiz_id, alreadyAssigned: true, refreshed: true };
    }
  }
  return createAssignment(db, parentUid, childUid, quiz, minimumScorePercent, rewardMinutes, rewardTiers, scoreImproveCooldownMinutes, sourceQuizId);
}

async function createAssignment(
  db: D1Database,
  parentUid: string,
  childUid: string,
  quiz: StoredQuiz,
  minimumScorePercent: number,
  rewardMinutes: number,
  rewardTiers: RewardTier[] = [{ minimumScorePercent, rewardMinutes }],
  scoreImproveCooldownMinutes = 60,
  sourceQuizId: string | null = null,
) {
  const quizId = crypto.randomUUID();
  const assignmentId = crypto.randomUUID();
  const now = Date.now();
  await db.batch([
    db.prepare(`INSERT INTO learning_quizzes
      (quiz_id, parent_uid, title, subject, grade, questions_json, question_count, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`)
      .bind(
        quizId,
        parentUid,
        quiz.title,
        quiz.subject,
        quiz.grade,
        JSON.stringify(quiz),
        quiz.questions.length,
        now,
        now,
      ),
    db.prepare(`INSERT INTO quiz_assignments
      (assignment_id, quiz_id, parent_uid, child_uid, source_quiz_id, minimum_score_percent, reward_minutes, reward_tiers_json,
       prize_pool_minutes, score_improve_cooldown_minutes,
       max_attempts, repeat_interval_minutes, retry_when_failed,
       allow_practice_during_cooldown, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
      .bind(
        assignmentId, quizId, parentUid, childUid, sourceQuizId, minimumScorePercent, rewardMinutes,
        JSON.stringify(rewardTiers), rewardTiers[0].rewardMinutes, scoreImproveCooldownMinutes,
        1, -1, 0, 0, now
      ),
  ]);
  return { assignmentId, quizId };
}

async function setLearningPlan(db: D1Database, parentUid: string, body: Record<string, unknown>) {
  await requireRole(db, parentUid, "parent");
  const childUid = requiredText(body.childUid, "Child ID", 128);
  await requireLink(db, parentUid, childUid);
  const plan = parseLearningPlan(body.plan);
  const now = Date.now();
  await db.prepare(`INSERT INTO learning_plans
    (parent_uid, child_uid, age, grade, subjects_json, curriculum_notes, strengths, weak_areas,
     difficulty, difficulties_json, minimum_available, refill_count, assignment_mode, minimum_score_percent, reward_minutes, reward_tiers_json, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(parent_uid, child_uid) DO UPDATE SET
      age = excluded.age, grade = excluded.grade, subjects_json = excluded.subjects_json,
      curriculum_notes = excluded.curriculum_notes, strengths = excluded.strengths,
      weak_areas = excluded.weak_areas, difficulty = excluded.difficulty, difficulties_json = excluded.difficulties_json,
      minimum_available = excluded.minimum_available, refill_count = excluded.refill_count,
      assignment_mode = excluded.assignment_mode, minimum_score_percent = excluded.minimum_score_percent,
      reward_minutes = excluded.reward_minutes, reward_tiers_json = excluded.reward_tiers_json, updated_at = excluded.updated_at`)
    .bind(
      parentUid, childUid, ageForGrade(plan.grade), plan.grade, JSON.stringify(plan.subjects), plan.curriculumNotes,
      plan.strengths, plan.weakAreas, plan.difficulties[0], JSON.stringify(plan.difficulties), plan.minimumAvailable, plan.refillCount,
      plan.assignmentMode, plan.minimumScorePercent, plan.rewardMinutes, JSON.stringify(plan.rewardTiers), now,
    )
    .run();
  const refill = await queueLearningPlanRefill(db, parentUid, childUid, plan);
  return { childUid, plan, refillQueued: refill.queued, refillCount: refill.quizCount };
}

async function learningPlan(db: D1Database, parentUid: string, rawChildUid: string | null) {
  await requireRole(db, parentUid, "parent");
  const childUid = requiredText(rawChildUid, "Child ID", 128);
  await requireLink(db, parentUid, childUid);
  const plan = await readLearningPlan(db, parentUid, childUid);
  const drafts = await db.prepare(`SELECT draft_id, quiz_json, plan_snapshot_json, created_at
    FROM learning_plan_drafts WHERE parent_uid = ? AND child_uid = ? AND status = 'pending'
    ORDER BY created_at DESC`)
    .bind(parentUid, childUid)
    .all<{ draft_id: string; quiz_json: string; plan_snapshot_json: string; created_at: number }>();
  const pendingJob = await db.prepare(`SELECT status, last_error FROM quiz_generation_jobs
    WHERE parent_uid = ? AND child_uid = ? AND status IN ('queued', 'running', 'retrying')
    ORDER BY created_at DESC LIMIT 1`)
    .bind(parentUid, childUid)
    .first<{ status: string; last_error: string | null }>();
  const assigned = await db.prepare(`SELECT qa.source_quiz_id, lq.title, lq.subject, lq.grade FROM quiz_assignments qa
    JOIN learning_quizzes lq ON lq.quiz_id = qa.quiz_id
    WHERE qa.parent_uid = ? AND qa.child_uid = ?`)
    .bind(parentUid, childUid)
    .all<{ source_quiz_id: string | null; title: string; subject: string; grade: string }>();
  return {
    childUid,
    plan,
    generationPending: pendingJob !== null,
    generationError: pendingJob?.last_error ?? null,
    assignedQuizSourceIds: assigned.results.flatMap(row => row.source_quiz_id ? [row.source_quiz_id] : []),
    assignedQuizKeys: assigned.results.map(row => `${row.title}\u0000${row.subject}\u0000${row.grade}`),
    drafts: drafts.results.map(draft => ({
      id: draft.draft_id,
      quiz: JSON.parse(draft.quiz_json),
      plan: JSON.parse(draft.plan_snapshot_json),
      createdAt: draft.created_at,
    })),
  };
}

async function reviewLearningPlanDraft(db: D1Database, parentUid: string, body: Record<string, unknown>) {
  await requireRole(db, parentUid, "parent");
  const draftId = requiredText(body.draftId, "Draft ID", 64);
  if (typeof body.approve !== "boolean") throw new ApiError(422, "INVALID_INPUT", "Draft decision is required.");
  const draft = await db.prepare(`SELECT draft_id, child_uid, quiz_json, plan_snapshot_json, status
    FROM learning_plan_drafts WHERE draft_id = ? AND parent_uid = ?`)
    .bind(draftId, parentUid)
    .first<{ draft_id: string; child_uid: string; quiz_json: string; plan_snapshot_json: string; status: string }>();
  if (!draft) throw new ApiError(404, "DRAFT_NOT_FOUND", "Quiz draft was not found.");
  if (draft.status !== "pending") throw new ApiError(409, "DRAFT_REVIEWED", "Quiz draft was already reviewed.");
  if (!body.approve) {
    await db.prepare("UPDATE learning_plan_drafts SET status = 'rejected', reviewed_at = ? WHERE draft_id = ? AND status = 'pending'")
      .bind(Date.now(), draftId)
      .run();
    return { draftId, approved: false };
  }
  const plan = parseLearningPlan(JSON.parse(draft.plan_snapshot_json));
  const quiz = parseQuiz(JSON.parse(draft.quiz_json));
  const claimed = await db.prepare(`UPDATE learning_plan_drafts SET status = 'approved', reviewed_at = ?
    WHERE draft_id = ? AND status = 'pending' RETURNING draft_id`)
    .bind(Date.now(), draftId)
    .first<{ draft_id: string }>();
  if (!claimed) throw new ApiError(409, "DRAFT_REVIEWED", "Quiz draft was already reviewed.");
  try {
    const assignment = await createAssignment(
      db, parentUid, draft.child_uid, quiz, plan.minimumScorePercent, plan.rewardMinutes,
      plan.rewardTiers,
    );
    return { draftId, approved: true, ...assignment };
  } catch (cause) {
    await db.prepare("UPDATE learning_plan_drafts SET status = 'pending', reviewed_at = NULL WHERE draft_id = ? AND status = 'approved'")
      .bind(draftId)
      .run();
    throw cause;
  }
}

async function nextQuiz(db: D1Database, childUid: string, requestedAssignmentId: string | null = null) {
  await requireRole(db, childUid, "child");
  await settlePendingRewards(db, childUid);
  const query = requestedAssignmentId
    ? `SELECT
      qa.assignment_id, qa.parent_uid, qa.child_uid, lq.questions_json,
      fl.timezone_offset_minutes,
      qa.minimum_score_percent, qa.reward_minutes, qa.reward_tiers_json,
      qa.prize_pool_minutes, qa.reward_earned_minutes, qa.pending_reward_minutes, qa.score_improve_cooldown_minutes,
      qa.max_attempts, qa.attempt_count, qa.best_score_percent,
      qa.cycle_attempt_count, qa.repeat_interval_minutes, qa.retry_when_failed,
      qa.allow_practice_during_cooldown, qa.next_reward_at, qa.status
    FROM quiz_assignments qa
    JOIN learning_quizzes lq ON lq.quiz_id = qa.quiz_id
    JOIN family_links fl ON fl.parent_uid = qa.parent_uid AND fl.child_uid = qa.child_uid
    WHERE qa.child_uid = ? AND qa.assignment_id = ?
      AND (qa.next_reward_at <= ? OR qa.best_score_percent >= 100)
    LIMIT 1`
    : `SELECT
      qa.assignment_id, qa.parent_uid, qa.child_uid, lq.questions_json,
      fl.timezone_offset_minutes,
      qa.minimum_score_percent, qa.reward_minutes, qa.reward_tiers_json,
      qa.prize_pool_minutes, qa.reward_earned_minutes, qa.pending_reward_minutes, qa.score_improve_cooldown_minutes,
      qa.max_attempts, qa.attempt_count, qa.best_score_percent,
      qa.cycle_attempt_count, qa.repeat_interval_minutes, qa.retry_when_failed,
      qa.allow_practice_during_cooldown, qa.next_reward_at, qa.status
    FROM quiz_assignments qa
    JOIN learning_quizzes lq ON lq.quiz_id = qa.quiz_id
    JOIN family_links fl ON fl.parent_uid = qa.parent_uid AND fl.child_uid = qa.child_uid
    WHERE qa.child_uid = ? AND qa.status = 'active' AND qa.next_reward_at <= ?
    ORDER BY qa.created_at ASC
    LIMIT 1`;
  const statement = db.prepare(query);
  const row = await (requestedAssignmentId
    ? statement.bind(childUid, requestedAssignmentId, Date.now())
    : statement.bind(childUid, Date.now()))
    .first<AssignmentRow>();
  if (!row) return { assignment: null };
  const quiz = storedQuiz(row.questions_json);
  return {
    assignment: {
      id: row.assignment_id,
      quiz: toChildQuiz(quiz),
      minimumScorePercent: row.minimum_score_percent,
      rewardMinutes: row.reward_minutes,
      rewardTiers: rewardTiersFromJson(row.reward_tiers_json, row.minimum_score_percent, row.reward_minutes),
      prizePoolMinutes: prizePool(row),
      rewardEarnedMinutes: row.reward_earned_minutes,
      pendingRewardMinutes: row.pending_reward_minutes,
      scoreImproveCooldownMinutes: row.score_improve_cooldown_minutes,
      attemptCount: row.attempt_count,
      maxAttempts: 1,
      rewardEligible: row.status === "active" && row.next_reward_at <= Date.now(),
      nextRewardAt: row.next_reward_at,
      retryWhenFailed: row.retry_when_failed === 1,
      allowPracticeDuringCooldown: row.allow_practice_during_cooldown === 1,
    },
  };
}

async function quizCatalog(db: D1Database, childUid: string) {
  await requireRole(db, childUid, "child");
  await settlePendingRewards(db, childUid);
  const energy = await studyEnergy(db, childUid);
  const rows = await db.prepare(`SELECT
      qa.assignment_id, lq.title, lq.subject, lq.grade, lq.questions_json, lq.question_count,
      qa.minimum_score_percent, qa.reward_minutes, qa.reward_tiers_json,
      qa.prize_pool_minutes, qa.reward_earned_minutes, qa.pending_reward_minutes, qa.score_improve_cooldown_minutes,
      qa.max_attempts, qa.attempt_count,
      qa.cycle_attempt_count, qa.created_at, qa.status, qa.best_score_percent, qa.completed_at,
      qa.repeat_interval_minutes, qa.retry_when_failed,
      qa.allow_practice_during_cooldown, qa.next_reward_at
    FROM quiz_assignments qa
    JOIN learning_quizzes lq ON lq.quiz_id = qa.quiz_id
    WHERE qa.child_uid = ?
    ORDER BY qa.created_at DESC`)
    .bind(childUid)
    .all<CatalogRow>();
  return {
    studyEnergy: energy,
    learningPlanPending: await hasPendingLearningPlanWork(db, childUid),
    assignments: rows.results.map(row => {
      return {
        id: row.assignment_id,
        title: row.title,
        subject: row.subject,
        grade: row.grade,
        coverImageData: coverImageFromJson(row.questions_json),
        questionCount: row.question_count,
        minimumScorePercent: row.minimum_score_percent,
        rewardMinutes: row.reward_minutes,
        rewardTiers: rewardTiersFromJson(row.reward_tiers_json, row.minimum_score_percent, row.reward_minutes),
        prizePoolMinutes: prizePool(row),
        rewardEarnedMinutes: row.reward_earned_minutes,
        pendingRewardMinutes: row.pending_reward_minutes,
        scoreImproveCooldownMinutes: row.score_improve_cooldown_minutes,
        maxAttempts: 1,
        attemptCount: row.attempt_count,
        createdAt: row.created_at,
        status: row.status,
        finished: row.status === "completed",
        bestScorePercent: row.best_score_percent,
        completedAt: row.completed_at,
        repeatIntervalMinutes: -1,
        retryWhenFailed: true,
        allowPracticeDuringCooldown: false,
        nextRewardAt: row.next_reward_at,
        rewardEligible: row.status === "active" && row.next_reward_at <= Date.now(),
        canStart: row.next_reward_at <= Date.now() || row.best_score_percent >= 100,
        canReview: row.attempt_count > 0,
        fullReviewAvailable: row.best_score_percent >= 100,
      };
    }),
  };
}

async function quizReview(db: D1Database, childUid: string, rawAssignmentId: string | null) {
  await requireRole(db, childUid, "child");
  const assignmentId = requiredText(rawAssignmentId, "Assignment ID", 64);
  const row = await assignment(db, assignmentId, childUid);
  if (!row) throw new ApiError(404, "ASSIGNMENT_NOT_FOUND", "Quiz assignment was not found.");
  const attempt = await db.prepare(`SELECT
      attempt_number, answers_json, score_percent, passed, reward_minutes, created_at
    FROM quiz_attempts
    WHERE assignment_id = ? AND child_uid = ?
    ORDER BY attempt_number DESC LIMIT 1`)
    .bind(assignmentId, childUid)
    .first<{
      attempt_number: number;
      answers_json: string;
      score_percent: number;
      passed: number;
      reward_minutes: number;
      created_at: number;
    }>();
  if (!attempt) throw new ApiError(404, "REVIEW_NOT_FOUND", "Finish an attempt before reviewing this quiz.");
  const exposeAnswerKey = row.best_score_percent >= 100;
  const history = await db.prepare(`SELECT attempt_number, answers_json, score_percent, reward_minutes, created_at
    FROM quiz_attempts WHERE assignment_id = ? AND child_uid = ? ORDER BY attempt_number DESC`)
    .bind(assignmentId, childUid)
    .all<{ attempt_number: number; answers_json: string; score_percent: number; reward_minutes: number; created_at: number }>();
  return {
    review: {
      assignmentId,
      quiz: reviewQuiz(storedQuiz(row.questions_json), exposeAnswerKey),
      answers: JSON.parse(attempt.answers_json),
      attemptNumber: attempt.attempt_number,
      scorePercent: attempt.score_percent,
      passed: attempt.passed === 1,
      rewardMinutes: attempt.reward_minutes,
      createdAt: attempt.created_at,
      answerKeyAvailable: exposeAnswerKey,
      retryAllowed: row.next_reward_at <= Date.now() || row.best_score_percent >= 100,
      history: history.results.map(item => ({
        attemptNumber: item.attempt_number,
        answers: JSON.parse(item.answers_json),
        scorePercent: item.score_percent,
        rewardMinutes: item.reward_minutes,
        createdAt: item.created_at,
      })),
    },
  };
}

async function submitQuiz(db: D1Database, childUid: string, body: Record<string, unknown>) {
  await requireRole(db, childUid, "child");
  await settlePendingRewards(db, childUid);
  const assignmentId = requiredText(body.assignmentId, "Assignment ID", 64);
  const row = await assignment(db, assignmentId, childUid);
  if (!row) throw new ApiError(404, "ASSIGNMENT_NOT_FOUND", "Quiz assignment was not found.");
  const submissionId = requiredText(body.submissionId, "Submission ID", 80);
  const priorAttempt = await db.prepare(`SELECT
      attempt_id, submission_id, attempt_number, score_percent, passed, reward_minutes
    FROM quiz_attempts
    WHERE assignment_id = ? AND child_uid = ? AND submission_id = ?`)
    .bind(assignmentId, childUid, submissionId)
    .first<AttemptRow>();
  if (priorAttempt) return submissionResult(db, row, priorAttempt, true);
  const now = Date.now();
  if (row.next_reward_at > now && row.best_score_percent < 100) {
    throw new ApiError(409, "COOLDOWN_ACTIVE", "Score improvement is available after the cooldown.");
  }

  const quiz = storedQuiz(row.questions_json);
  const answers = parseAnswers(body.answers, quiz.questions);
  const scorePercent = scoreQuiz(quiz.questions, answers);
  const targetRewardMinutes = rewardForAssignment(row.reward_tiers_json, row.minimum_score_percent, row.reward_minutes, scorePercent);
  const prizePoolMinutes = prizePool(row);
  const requestedRewardMinutes = Math.max(0, Math.min(targetRewardMinutes, prizePoolMinutes) - row.reward_earned_minutes - row.pending_reward_minutes);
  const energy = await studyEnergy(db, childUid);
  const paidRewardMinutes = Math.min(requestedRewardMinutes, energy.remainingMinutes);
  const pendingRewardMinutes = requestedRewardMinutes - paidRewardMinutes;
  const nextRewardEarned = row.reward_earned_minutes + paidRewardMinutes;
  const nextPendingReward = row.pending_reward_minutes + pendingRewardMinutes;
  const nextBestScore = Math.max(row.best_score_percent, scorePercent);
  const rewardComplete = row.status === "completed" ||
    (nextRewardEarned >= prizePoolMinutes && nextPendingReward === 0);
  const nextRewardAt = nextBestScore >= 100 ? 0 : now + row.score_improve_cooldown_minutes * 60_000;
  const passed = targetRewardMinutes > 0;
  const attemptNumber = row.attempt_count + 1;
  const attemptId = crypto.randomUUID();

  const statements: D1PreparedStatement[] = [
    db.prepare(`INSERT INTO quiz_attempts
      (attempt_id, assignment_id, child_uid, attempt_number, submission_id, answers_json, score_percent, passed, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`)
      .bind(
        attemptId,
        assignmentId,
        childUid,
        attemptNumber,
        submissionId,
        JSON.stringify(answers),
        scorePercent,
        passed ? 1 : 0,
        now,
      ),
    db.prepare(`UPDATE quiz_assignments SET
      attempt_count = ?, best_score_percent = MAX(best_score_percent, ?),
      reward_earned_minutes = ?, pending_reward_minutes = ?,
      cycle_attempt_count = ?, status = ?, completed_at = ?, next_reward_at = ?
      WHERE assignment_id = ? AND child_uid = ? AND attempt_count = ?
        AND next_reward_at <= ?`)
      .bind(
        attemptNumber,
        scorePercent,
        nextRewardEarned,
        nextPendingReward,
        attemptNumber,
        rewardComplete ? "completed" : "active",
        rewardComplete ? now : null,
        nextRewardAt,
        assignmentId,
        childUid,
        row.attempt_count,
        now,
      ),
  ];

  if (paidRewardMinutes > 0) {
    const day = dayForOffset(row.timezone_offset_minutes);
    const eventId = crypto.randomUUID();
    statements.push(
      db.prepare(`INSERT INTO daily_reward_usage (child_uid, day, minutes, updated_at)
        VALUES (?, ?, 0, ?) ON CONFLICT(child_uid, day) DO NOTHING`)
        .bind(childUid, day, now),
      db.prepare(`INSERT INTO quiz_reward_events
        (event_id, attempt_id, child_uid, assignment_id, delta_minutes, created_at)
        SELECT ?, ?, ?, ?, MAX(0, MIN(?, fl.daily_earned_cap_minutes - dru.minutes)), ?
        FROM family_links fl
        JOIN daily_reward_usage dru ON dru.child_uid = fl.child_uid AND dru.day = ?
        WHERE fl.child_uid = ?`)
        .bind(eventId, attemptId, childUid, assignmentId, paidRewardMinutes, now, day, childUid),
      db.prepare(`UPDATE daily_reward_usage SET
        minutes = minutes + (SELECT delta_minutes FROM quiz_reward_events WHERE attempt_id = ?),
        updated_at = ?
        WHERE child_uid = ? AND day = ?`)
        .bind(attemptId, now, childUid, day),
      db.prepare(`INSERT INTO time_wallets (child_uid, earned_minutes, updated_at)
        VALUES (?, 0, ?) ON CONFLICT(child_uid) DO NOTHING`)
        .bind(childUid, now),
      db.prepare(`UPDATE time_wallets SET
        earned_minutes = earned_minutes + (SELECT delta_minutes FROM quiz_reward_events WHERE attempt_id = ?),
        updated_at = ?
        WHERE child_uid = ?`)
        .bind(attemptId, now, childUid),
      db.prepare(`UPDATE quiz_attempts SET
        reward_minutes = (SELECT delta_minutes FROM quiz_reward_events WHERE attempt_id = ?)
        WHERE attempt_id = ?`)
        .bind(attemptId, attemptId),
    );
  }

  try {
    await db.batch(statements);
  } catch {
    const retryAttempt = await db.prepare(`SELECT
        attempt_id, submission_id, attempt_number, score_percent, passed, reward_minutes
      FROM quiz_attempts
      WHERE assignment_id = ? AND child_uid = ? AND submission_id = ?`)
      .bind(assignmentId, childUid, submissionId)
      .first<AttemptRow>();
    if (retryAttempt) return submissionResult(db, row, retryAttempt, true);
    const latest = await assignment(db, assignmentId, childUid);
    if (latest && latest.next_reward_at > Date.now() && latest.best_score_percent < 100) {
      throw new ApiError(409, "COOLDOWN_ACTIVE", "Score improvement is available after the cooldown.");
    }
    throw new ApiError(409, "SUBMISSION_CONFLICT", "Quiz was submitted from another session. Refresh and retry.");
  }
  const updated = await assignment(db, assignmentId, childUid);
  if (!updated) throw new ApiError(500, "ASSIGNMENT_LOST", "Quiz result could not be loaded.");
  await queueLearningPlanRefillForChild(db, childUid);
  return completedSubmission(db, updated, false);
}

async function queueLearningPlanRefillForChild(db: D1Database, childUid: string) {
  const link = await db.prepare("SELECT parent_uid FROM family_links WHERE child_uid = ?")
    .bind(childUid)
    .first<{ parent_uid: string }>();
  if (!link) return;
  const plan = await readLearningPlan(db, link.parent_uid, childUid);
  if (plan) await queueLearningPlanRefill(db, link.parent_uid, childUid, plan);
}

async function hasPendingLearningPlanWork(db: D1Database, childUid: string): Promise<boolean> {
  const pending = await db.prepare(`SELECT 1 AS pending FROM quiz_generation_jobs
    WHERE child_uid = ? AND status IN ('queued', 'running', 'retrying') LIMIT 1`)
    .bind(childUid)
    .first<{ pending: number }>();
  return pending !== null;
}

async function completedSubmission(db: D1Database, row: AssignmentRow, idempotent: boolean) {
  const attempt = await db.prepare(`SELECT
      attempt_id, submission_id, attempt_number, score_percent, passed, reward_minutes
    FROM quiz_attempts WHERE assignment_id = ? ORDER BY attempt_number DESC LIMIT 1`)
    .bind(row.assignment_id)
    .first<AttemptRow>();
  if (attempt) return submissionResult(db, row, attempt, idempotent);
  return submissionResult(db, row, {
    attempt_id: "",
    submission_id: null,
    attempt_number: row.attempt_count,
    score_percent: 0,
    passed: 0,
    reward_minutes: 0,
  }, idempotent);
}

async function submissionResult(
  db: D1Database,
  row: AssignmentRow,
  attempt: AttemptRow,
  idempotent: boolean
) {
  const wallet = await db.prepare("SELECT earned_minutes FROM time_wallets WHERE child_uid = ?")
    .bind(row.child_uid)
    .first<BalanceRow>();
  const energy = await studyEnergy(db, row.child_uid);
  return {
    assignmentId: row.assignment_id,
    scorePercent: attempt.score_percent,
    passed: attempt.passed === 1,
    rewardMinutes: attempt.reward_minutes,
    earnedMinutes: wallet?.earned_minutes ?? 0,
    attemptCount: attempt.attempt_number,
    prizePoolMinutes: prizePool(row),
    rewardEarnedMinutes: row.reward_earned_minutes,
    pendingRewardMinutes: row.pending_reward_minutes,
    completed: row.status === "completed",
    retryWhenFailed: true,
    retryAllowed: row.next_reward_at <= Date.now() || row.best_score_percent >= 100,
    nextRewardAt: row.next_reward_at,
    studyEnergy: energy,
    idempotent,
  };
}

async function balance(db: D1Database, childUid: string) {
  await requireRole(db, childUid, "child");
  await settlePendingRewards(db, childUid);
  const wallet = await db.prepare("SELECT earned_minutes FROM time_wallets WHERE child_uid = ?")
    .bind(childUid)
    .first<BalanceRow>();
  return { earnedMinutes: wallet?.earned_minutes ?? 0, studyEnergy: await studyEnergy(db, childUid) };
}

async function studyEnergy(db: D1Database, childUid: string) {
  const link = await db.prepare(`SELECT daily_earned_cap_minutes, timezone_offset_minutes
    FROM family_links WHERE child_uid = ?`).bind(childUid).first<{
      daily_earned_cap_minutes: number;
      timezone_offset_minutes: number;
    }>();
  if (!link) return { capMinutes: 0, usedMinutes: 0, remainingMinutes: 0 };
  const usage = await db.prepare(`SELECT minutes FROM daily_reward_usage
    WHERE child_uid = ? AND day = ?`).bind(childUid, dayForOffset(link.timezone_offset_minutes))
    .first<{ minutes: number }>();
  const usedMinutes = usage?.minutes ?? 0;
  return {
    capMinutes: link.daily_earned_cap_minutes,
    usedMinutes,
    remainingMinutes: Math.max(0, link.daily_earned_cap_minutes - usedMinutes),
  };
}

async function settlePendingRewards(db: D1Database, childUid: string) {
  const energy = await studyEnergy(db, childUid);
  if (energy.remainingMinutes <= 0) return;
  const pending = await db.prepare(`SELECT assignment_id, prize_pool_minutes, reward_earned_minutes,
      pending_reward_minutes, status, best_score_percent
    FROM quiz_assignments
    WHERE child_uid = ? AND pending_reward_minutes > 0
    ORDER BY created_at ASC`)
    .bind(childUid)
    .all<{
      assignment_id: string;
      prize_pool_minutes: number;
      reward_earned_minutes: number;
      pending_reward_minutes: number;
      status: "active" | "completed";
      best_score_percent: number;
    }>();
  let remaining = energy.remainingMinutes;
  const now = Date.now();
  const day = dayForOffset((await db.prepare("SELECT timezone_offset_minutes FROM family_links WHERE child_uid = ?")
    .bind(childUid).first<{ timezone_offset_minutes: number }>())?.timezone_offset_minutes ?? 0);
  for (const row of pending.results) {
    if (remaining <= 0) break;
    const payout = Math.min(row.pending_reward_minutes, remaining);
    const earned = row.reward_earned_minutes + payout;
    const left = row.pending_reward_minutes - payout;
    const completed = earned >= row.prize_pool_minutes && left === 0;
    await db.batch([
      db.prepare(`INSERT INTO daily_reward_usage (child_uid, day, minutes, updated_at)
        VALUES (?, ?, 0, ?) ON CONFLICT(child_uid, day) DO NOTHING`)
        .bind(childUid, day, now),
      db.prepare(`UPDATE daily_reward_usage SET minutes = minutes + ?, updated_at = ?
        WHERE child_uid = ? AND day = ?`)
        .bind(payout, now, childUid, day),
      db.prepare(`INSERT INTO time_wallets (child_uid, earned_minutes, updated_at)
        VALUES (?, 0, ?) ON CONFLICT(child_uid) DO NOTHING`)
        .bind(childUid, now),
      db.prepare(`UPDATE time_wallets SET earned_minutes = earned_minutes + ?, updated_at = ?
        WHERE child_uid = ?`)
        .bind(payout, now, childUid),
      db.prepare(`UPDATE quiz_assignments SET reward_earned_minutes = ?, pending_reward_minutes = ?,
        status = ?, completed_at = ?
        WHERE assignment_id = ? AND child_uid = ? AND pending_reward_minutes >= ?`)
        .bind(
          earned,
          left,
          completed ? "completed" : row.status,
          completed ? now : null,
          row.assignment_id,
          childUid,
          payout,
        ),
    ]);
    remaining -= payout;
  }
}

async function childSummary(db: D1Database, parentUid: string, rawChildUid: string | null) {
  await requireRole(db, parentUid, "parent");
  const childUid = requiredText(rawChildUid, "Child ID", 128);
  const summary = await db.prepare(`SELECT
      fl.daily_earned_cap_minutes,
      COALESCE(tw.earned_minutes, 0) AS earned_minutes,
      (SELECT COUNT(*) FROM quiz_assignments qa WHERE qa.parent_uid = fl.parent_uid AND qa.child_uid = fl.child_uid) AS assignment_count,
      (SELECT COUNT(*) FROM quiz_assignments qa WHERE qa.parent_uid = fl.parent_uid AND qa.child_uid = fl.child_uid AND qa.status = 'active') AS active_count
    FROM family_links fl
    LEFT JOIN time_wallets tw ON tw.child_uid = fl.child_uid
    WHERE fl.parent_uid = ? AND fl.child_uid = ?`)
    .bind(parentUid, childUid)
    .first<{
      daily_earned_cap_minutes: number;
      earned_minutes: number;
      assignment_count: number;
      active_count: number;
    }>();
  if (!summary) throw new ApiError(404, "CHILD_NOT_FOUND", "Paired child was not found.");
  const attempts = await db.prepare(`SELECT
      qa.assignment_id, lq.title, qat.attempt_number, qat.score_percent, qat.passed, qat.reward_minutes, qat.created_at
    FROM quiz_attempts qat
    JOIN quiz_assignments qa ON qa.assignment_id = qat.assignment_id
    JOIN learning_quizzes lq ON lq.quiz_id = qa.quiz_id
    WHERE qa.parent_uid = ? AND qa.child_uid = ?
    ORDER BY qat.created_at DESC
    LIMIT 20`)
    .bind(parentUid, childUid)
    .all<{
      assignment_id: string;
      title: string;
      attempt_number: number;
      score_percent: number;
      passed: number;
      reward_minutes: number;
      created_at: number;
    }>();
  return {
    childUid,
    dailyEarnedCapMinutes: summary.daily_earned_cap_minutes,
    earnedMinutes: summary.earned_minutes,
    assignmentCount: summary.assignment_count,
    activeCount: summary.active_count,
    attempts: attempts.results.map(attempt => ({
      assignmentId: attempt.assignment_id,
      title: attempt.title,
      attemptNumber: attempt.attempt_number,
      scorePercent: attempt.score_percent,
      passed: attempt.passed === 1,
      rewardMinutes: attempt.reward_minutes,
      createdAt: attempt.created_at,
    })),
  };
}

async function assignment(db: D1Database, assignmentId: string, childUid: string) {
  return db.prepare(`SELECT
      qa.assignment_id, qa.parent_uid, qa.child_uid, lq.questions_json,
      fl.timezone_offset_minutes,
      qa.minimum_score_percent, qa.reward_minutes, qa.reward_tiers_json,
      qa.prize_pool_minutes, qa.reward_earned_minutes, qa.pending_reward_minutes, qa.score_improve_cooldown_minutes,
      qa.max_attempts, qa.attempt_count, qa.best_score_percent,
      qa.cycle_attempt_count, qa.repeat_interval_minutes, qa.retry_when_failed,
      qa.allow_practice_during_cooldown, qa.next_reward_at, qa.status
    FROM quiz_assignments qa
    JOIN learning_quizzes lq ON lq.quiz_id = qa.quiz_id
    JOIN family_links fl ON fl.parent_uid = qa.parent_uid AND fl.child_uid = qa.child_uid
    WHERE qa.assignment_id = ? AND qa.child_uid = ?`)
    .bind(assignmentId, childUid)
    .first<AssignmentRow>();
}

async function ensureRole(db: D1Database, uid: string, role: "parent" | "child") {
  const result = await db.prepare(`INSERT INTO learning_accounts (uid, role, created_at)
    VALUES (?, ?, ?)
    ON CONFLICT(uid) DO UPDATE SET uid = excluded.uid
    WHERE learning_accounts.role = excluded.role
    RETURNING role`)
    .bind(uid, role, Date.now())
    .first<RoleRow>();
  if (!result) throw new ApiError(409, "ROLE_CONFLICT", "Account is already registered for another role.");
}

async function requireRole(db: D1Database, uid: string, role: "parent" | "child") {
  const account = await db.prepare("SELECT role FROM learning_accounts WHERE uid = ?").bind(uid).first<RoleRow>();
  if (!account) throw new ApiError(403, "PAIRING_REQUIRED", "Complete secure pairing first.");
  if (account.role !== role) throw new ApiError(403, "FORBIDDEN", `Only ${role} accounts can do that.`);
}

async function requireLink(db: D1Database, parentUid: string, childUid: string) {
  const linked = await db.prepare("SELECT 1 AS linked FROM family_links WHERE parent_uid = ? AND child_uid = ?")
    .bind(parentUid, childUid)
    .first<{ linked: number }>();
  if (!linked) throw new ApiError(404, "CHILD_NOT_FOUND", "Paired child was not found.");
}

function storedQuiz(jsonValue: string): StoredQuiz {
  try {
    return parseQuiz(JSON.parse(jsonValue));
  } catch {
    throw new ApiError(500, "QUIZ_DATA_INVALID", "Stored quiz is invalid.");
  }
}

type RewardTier = { minimumScorePercent: number; rewardMinutes: number };

function rewardTiersFromBody(
  value: unknown,
  fallbackScore: number,
  fallbackMinutes: number
): RewardTier[] {
  if (value === undefined || value === null) {
    return [{ minimumScorePercent: fallbackScore, rewardMinutes: fallbackMinutes }];
  }
  if (!Array.isArray(value) || value.length < 1 || value.length > 5) {
    throw new ApiError(422, "INVALID_INPUT", "Reward tiers are not valid.");
  }
  const tiers = value.map((item) => {
    if (!isRecord(item)) throw new ApiError(422, "INVALID_INPUT", "Reward tiers are not valid.");
    return {
      minimumScorePercent: requiredInteger(item.minimumScorePercent, "Score threshold", 1, 100),
      rewardMinutes: requiredInteger(item.rewardMinutes, "Reward minutes", 1, 240),
    };
  });
  if (new Set(tiers.map(tier => tier.minimumScorePercent)).size !== tiers.length) {
    throw new ApiError(422, "INVALID_INPUT", "Each score threshold must be different.");
  }
  const ascending = [...tiers].sort((left, right) => left.minimumScorePercent - right.minimumScorePercent);
  if (ascending.some((tier, index) => index > 0 && tier.rewardMinutes <= ascending[index - 1].rewardMinutes)) {
    throw new ApiError(422, "INVALID_INPUT", "Higher score thresholds must earn more time.");
  }
  return tiers.sort((left, right) => right.minimumScorePercent - left.minimumScorePercent);
}

function rewardTiersFromJson(
  value: string,
  fallbackScore: number,
  fallbackMinutes: number
): RewardTier[] {
  try {
    return rewardTiersFromBody(JSON.parse(value), fallbackScore, fallbackMinutes);
  } catch (cause) {
    if (cause instanceof ApiError) throw cause;
    return [{ minimumScorePercent: fallbackScore, rewardMinutes: fallbackMinutes }];
  }
}

function rewardForAssignment(
  value: string,
  fallbackScore: number,
  fallbackMinutes: number,
  scorePercent: number
): number {
  return rewardForScore(rewardTiersFromJson(value, fallbackScore, fallbackMinutes), scorePercent);
}

function prizePool(row: {
  prize_pool_minutes: number;
  reward_tiers_json: string;
  minimum_score_percent: number;
  reward_minutes: number;
}): number {
  return row.prize_pool_minutes > 0
    ? row.prize_pool_minutes
    : rewardTiersFromJson(row.reward_tiers_json, row.minimum_score_percent, row.reward_minutes)[0].rewardMinutes;
}

function requiredText(value: unknown, name: string, maxLength: number): string {
  if (typeof value !== "string") throw new ApiError(422, "INVALID_INPUT", `${name} is required.`);
  const text = value.trim();
  if (!text || text.length > maxLength) throw new ApiError(422, "INVALID_INPUT", `${name} is not valid.`);
  return text;
}

async function syncQuizCover(db: D1Database, parentUid: string, body: Record<string, unknown>) {
  const sourceQuizId = requiredText(body.sourceQuizId, "Quiz source", 128);
  const title = requiredText(body.title, "Quiz title", 100);
  const coverImageData = body.coverImageData;
  if (typeof coverImageData !== "string" || coverImageData.length > 100_000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(coverImageData)) {
    throw new ApiError(422, "INVALID_INPUT", "Cover image is not valid.");
  }
  const rows = await db.prepare(`SELECT qa.quiz_id, lq.questions_json FROM quiz_assignments qa
    JOIN learning_quizzes lq ON lq.quiz_id = qa.quiz_id
    WHERE qa.parent_uid = ? AND (qa.source_quiz_id = ? OR (qa.source_quiz_id IS NULL AND lq.title = ?))`)
    .bind(parentUid, sourceQuizId, title)
    .all<{ quiz_id: string; questions_json: string }>();
  if (rows.results.length) {
    await db.batch(rows.results.map(row => {
      const quiz = storedQuiz(row.questions_json);
      quiz.coverImageData = coverImageData;
      return db.prepare("UPDATE learning_quizzes SET questions_json = ?, updated_at = ? WHERE quiz_id = ? AND parent_uid = ?")
        .bind(JSON.stringify(quiz), Date.now(), row.quiz_id, parentUid);
    }));
  }
  return { updatedAssignments: rows.results.length };
}

function optionalText(value: unknown, maxLength: number): string | null {
  if (value == null) return null;
  if (typeof value !== "string" || value.trim().length === 0 || value.trim().length > maxLength) {
    throw new ApiError(422, "INVALID_INPUT", "Quiz source is not valid.");
  }
  return value.trim();
}

function coverImageFromJson(value: string): string | undefined {
  try {
    const cover = (JSON.parse(value) as { coverImageData?: unknown }).coverImageData;
    return typeof cover === "string" ? cover : undefined;
  } catch { return undefined; }
}

function ageForGrade(grade: string): number {
  if (grade === "Preschool") return 4;
  const level = Number(grade.match(/^Grade (\d{1,2})$/)?.[1]);
  return Number.isInteger(level) ? Math.max(3, Math.min(21, level + 5)) : 8;
}

function requiredInteger(value: unknown, name: string, minimum: number, maximum: number): number {
  if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw new ApiError(422, "INVALID_INPUT", `${name} is not valid.`);
  }
  return value as number;
}

function requireParentIdentity(identity: { isAnonymous: boolean }) {
  if (identity.isAnonymous) {
    throw new ApiError(
      403,
      "PARENT_ACCOUNT_REQUIRED",
      "Create a parent account before using secure learning features."
    );
  }
}

function timezoneOffset(value: unknown): number {
  if (value === undefined || value === null) return 0;
  return requiredInteger(value, "Timezone offset", -840, 840);
}

function repeatInterval(value: unknown): number {
  const interval = value === undefined ? -1 : requiredInteger(value, "Repeat interval", -1, 43_200);
  if (![-1, 0, 60, 1_440, 10_080, 43_200].includes(interval)) {
    throw new ApiError(422, "INVALID_INPUT", "Repeat interval is not valid.");
  }
  return interval;
}

function optionalBoolean(value: unknown, fallback: boolean): boolean {
  if (value === undefined || value === null) return fallback;
  if (typeof value !== "boolean") {
    throw new ApiError(422, "INVALID_INPUT", "Quiz policy option is not valid.");
  }
  return value;
}

function randomCode(): string {
  const bytes = new Uint8Array(6);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, byte => CODE_ALPHABET[byte & 31]).join("");
}

function dayForOffset(offsetMinutes: number): string {
  return new Date(Date.now() + offsetMinutes * 60_000).toISOString().slice(0, 10);
}

function json(value: unknown, status = 200): Response {
  return Response.json(value, { status, headers: { "Cache-Control": "no-store" } });
}

function normalizeValidationError(cause: unknown): unknown {
  return cause instanceof ValidationError || cause instanceof LearningPlanValidationError
    ? new ApiError(422, "INVALID_INPUT", cause.message)
    : cause;
}

function isUniqueConstraint(cause: unknown): boolean {
  return cause instanceof Error && cause.message.includes("UNIQUE constraint failed");
}
