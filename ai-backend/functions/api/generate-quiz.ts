import { decodeProtectedHeader, importX509, jwtVerify } from "jose";

interface Env {
  AI: Ai;
  AI_QUOTA_DB: D1Database;
  CLOUDFLARE_ACCOUNT_ID?: string;
  CLOUDFLARE_AI_GATEWAY_TOKEN?: string;
  AI_GATEWAY_ID?: string;
  FIREBASE_PROJECT_ID?: string;
  SAFETY_SALT: string;
}

type QuizRequest = {
  subject: string;
  grade: string;
  topic: string;
  note: string;
  difficulty: "Easy" | "Balanced" | "Challenging";
  questionCount: number;
  quizCount: number;
};

type QuizDraft = {
  title: string;
  subject: string;
  grade: string;
  questions: Array<{
    prompt: string;
    allowMultipleAnswers: false;
    choices: Array<{ text: string; isCorrect: boolean }>;
  }>;
};

const FIREBASE_CERTIFICATES = "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";
const IP_REQUESTS_PER_DAY = 30;
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
              properties: {
                text: { type: "string" },
                isCorrect: { type: "boolean" },
              },
            },
          },
        },
      },
    },
  },
} as const;

export const onRequestPost: PagesFunction<Env> = async ({ request, env }) => {
  try {
    if (!env.SAFETY_SALT?.trim()) {
      throw new RequestError(503, "AI_NOT_CONFIGURED", "AI quiz generation is not configured on the service yet.");
    }
    const token = bearerToken(request);
    const identity = await verifyFirebaseToken(token, env.FIREBASE_PROJECT_ID?.trim() || "learn2earn-bc2bc");
    const quizRequest = await parseRequest(request);
    const accountLimit = identity.isAnonymous ? 3 : 10;
    if (!identity.isAnonymous && identity.emailVerified !== true) {
      return error(403, "EMAIL_VERIFICATION_REQUIRED", "Verify your email before using AI quizzes.");
    }

    const ipHash = await sha256(`${env.SAFETY_SALT}:${request.headers.get("CF-Connecting-IP") ?? "unknown"}`);
    const ipReserved = await reserve(env.AI_QUOTA_DB, "ip_rate_limit", `ip:${ipHash}`, utcDay(), IP_REQUESTS_PER_DAY, quizRequest.quizCount);
    if (!ipReserved) return error(429, "RATE_LIMITED", "Try again tomorrow.");

    const quotaKey = `${identity.isAnonymous ? "guest" : "account"}:${identity.uid}`;
    const quotaPeriod = identity.isAnonymous ? "lifetime" : utcMonth();
    const quotaReserved = await reserve(env.AI_QUOTA_DB, "quiz_quota", quotaKey, quotaPeriod, accountLimit, quizRequest.quizCount);
    if (!quotaReserved) return error(429, "QUOTA_EXHAUSTED", identity.isAnonymous
      ? "Your 3 guest AI quizzes are used. Create an account to continue."
      : "Your 10 AI quizzes for this month are used.");

    try {
      const drafts = await Promise.all(Array.from({ length: quizRequest.quizCount }, (_, index) =>
        generateDraft(env, identity.uid, { ...quizRequest, batchIndex: index + 1 })
      ));
      return Response.json({ drafts });
    } catch (cause) {
      if (cause instanceof RequestError) throw cause;
      console.error("Quiz generation failed", cause);
      await release(env.AI_QUOTA_DB, "quiz_quota", quotaKey, quotaPeriod, quizRequest.quizCount);
      return providerError(cause);
    }
  } catch (cause) {
    if (cause instanceof RequestError) return error(cause.status, cause.code, cause.message);
    return error(401, "UNAUTHENTICATED", "Your session could not be verified. Sign in again and retry.");
  }
};

export const onRequest: PagesFunction<Env> = async () => error(405, "METHOD_NOT_ALLOWED", "Use POST.");

async function generateDraft(env: Env, uid: string, request: QuizRequest & { batchIndex: number }): Promise<QuizDraft> {
  const response = await env.AI.run("@cf/meta/llama-3.3-70b-instruct-fp8-fast", {
    messages: [
      {
        role: "system",
        content: "Create an age-appropriate English multiple-choice learning quiz. The parent controls subject, grade, difficulty, topic and curriculum note. If topic is blank, choose a suitable age-appropriate topic from the curriculum note. Batch quizzes must cover different subtopics. Silently check each answer is factual, unambiguous and has exactly one correct choice. Return only valid JSON with title, subject, grade, and questions. Each question needs prompt, allowMultipleAnswers false, and exactly four choices with text and isCorrect. No markdown or explanations.",
      },
      { role: "user", content: JSON.stringify({ ...request, safetyIdentifier: await sha256(`${env.SAFETY_SALT}:${uid}`) }) },
    ],
    response_format: { type: "json_object" },
    temperature: 0.2,
    max_tokens: Math.min(3500, 450 + request.questionCount * 220),
  });
  const output = (response as { response?: unknown }).response;
  if (typeof output !== "string") throw new Error("Workers AI returned no quiz content.");
  return validateDraft(JSON.parse(output), request);
}

async function verifyFirebaseToken(token: string, projectId: string) {
  const header = decodeProtectedHeader(token);
  if (header.alg !== "RS256" || typeof header.kid !== "string") throw new RequestError(401, "UNAUTHENTICATED", "Invalid session.");
  const certificates = await fetch(FIREBASE_CERTIFICATES).then(async response => {
    if (!response.ok) throw new RequestError(503, "AUTH_UNAVAILABLE", "Could not verify your session. Try again.");
    return response.json() as Promise<Record<string, string>>;
  });
  const certificate = certificates[header.kid];
  if (!certificate) throw new RequestError(401, "UNAUTHENTICATED", "Expired session.");
  const key = await importX509(certificate, "RS256");
  const { payload } = await jwtVerify(token, key, {
    algorithms: ["RS256"],
    audience: projectId,
    issuer: `https://securetoken.google.com/${projectId}`,
  });
  if (!payload.sub) throw new RequestError(401, "UNAUTHENTICATED", "Invalid session.");
  const firebase = payload.firebase as Record<string, unknown> | undefined;
  return {
    uid: payload.sub,
    isAnonymous: firebase?.sign_in_provider === "anonymous",
    emailVerified: payload.email_verified === true,
  };
}

async function parseRequest(request: Request): Promise<QuizRequest> {
  const value = await request.json<Partial<QuizRequest>>().catch(() => null);
  if (!value || typeof value.subject !== "string" || typeof value.grade !== "string") {
    throw new RequestError(422, "INVALID_INPUT", "Complete every quiz field.");
  }
  const topic = typeof value.topic === "string" ? value.topic.trim() : "";
  const note = typeof value.note === "string" ? value.note.trim() : "";
  const questionCount = value.questionCount as number;
  const quizCount = value.quizCount as number;
  if (topic.length > 200 || note.length > 500 || !["Easy", "Balanced", "Challenging"].includes(value.difficulty ?? "") || !Number.isInteger(questionCount) || questionCount < 1 || questionCount > 20 || !Number.isInteger(quizCount) || quizCount < 1 || quizCount > 5) {
    throw new RequestError(422, "INVALID_INPUT", "Quiz details are not valid.");
  }
  return { ...value, topic, note, questionCount, quizCount } as QuizRequest;
}

function validateDraft(value: unknown, request: QuizRequest): QuizDraft {
  const raw = value as Partial<QuizDraft>;
  if (!raw || typeof raw.title !== "string" || !Array.isArray(raw.questions) || raw.questions.length !== request.questionCount) throw new Error("Invalid AI draft");
  const questions = raw.questions.map(question => {
    if (!question || typeof question.prompt !== "string" || question.prompt.trim().length === 0 || !Array.isArray(question.choices) || question.choices.length !== 4) throw new Error("Invalid question");
    const choices = question.choices.map(choice => {
      if (!choice || typeof choice.text !== "string" || choice.text.trim().length === 0 || typeof choice.isCorrect !== "boolean") throw new Error("Invalid choice");
      return { text: choice.text.trim(), isCorrect: choice.isCorrect };
    });
    if (choices.filter(choice => choice.isCorrect).length !== 1) throw new Error("Invalid answers");
    return { prompt: question.prompt.trim(), allowMultipleAnswers: false as const, choices };
  });
  return { title: raw.title.trim().slice(0, 100) || `${request.topic} quiz`, subject: request.subject, grade: request.grade, questions };
}

async function reserve(db: D1Database, table: "quiz_quota" | "ip_rate_limit", key: string, period: string, limit: number, amount: number): Promise<boolean> {
  const result = await db.prepare(`INSERT INTO ${table} (${table === "quiz_quota" ? "quota_key" : "rate_key"}, used, period, updated_at)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(${table === "quiz_quota" ? "quota_key" : "rate_key"}) DO UPDATE SET
      used = CASE WHEN ${table}.period = excluded.period THEN ${table}.used + excluded.used ELSE excluded.used END,
      period = excluded.period,
      updated_at = excluded.updated_at
    WHERE ${table}.period != excluded.period OR ${table}.used <= ? - excluded.used
    RETURNING used`).bind(key, amount, period, Date.now(), limit).first();
  return result !== null;
}

async function release(db: D1Database, table: "quiz_quota", key: string, period: string, amount: number) {
  await db.prepare("UPDATE quiz_quota SET used = MAX(0, used - ?), updated_at = ? WHERE quota_key = ? AND period = ?")
    .bind(amount, Date.now(), key, period).run();
}

function bearerToken(request: Request): string {
  const value = request.headers.get("Authorization");
  if (!value?.startsWith("Bearer ")) throw new RequestError(401, "UNAUTHENTICATED", "Sign in or continue as guest first.");
  return value.substring("Bearer ".length);
}

function utcDay() { return new Date().toISOString().slice(0, 10); }
function utcMonth() { return new Date().toISOString().slice(0, 7); }
async function sha256(value: string) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
}
function error(status: number, code: string, message: string) {
  return Response.json({ error: { code, message } }, { status });
}

function providerError(cause: unknown) {
  const provider = typeof cause === "object" && cause !== null ? cause as {
    status?: unknown;
    code?: unknown;
    type?: unknown;
    message?: unknown;
  } : {};
  const status = typeof provider.status === "number" ? provider.status : 0;
  const code = typeof provider.code === "string" ? provider.code : "";
  const type = typeof provider.type === "string" ? provider.type : "";
  const diagnostic = code || type || `HTTP_${status || "UNKNOWN"}`;
  console.error("OpenAI provider rejection", {
    status,
    code,
    type,
    message: typeof provider.message === "string" ? provider.message.slice(0, 300) : "",
  });
  return status === 401
    ? error(503, "AI_CREDENTIALS_INVALID", `OpenAI rejected the API key (${diagnostic}).`)
    : status === 403
      ? error(503, "AI_ACCESS_DENIED", `OpenAI denied this request (${diagnostic}).`)
    : status === 429
      ? error(429, "AI_LIMIT_REACHED", "AI quiz service has reached its rate or spending limit. Try again later.")
      : status === 400 || status === 404
        ? error(503, "AI_CONFIGURATION_INVALID", "AI quiz service configuration needs attention.")
        : error(502, "GENERATION_FAILED", "AI could not create a quiz right now. Try again.");
}

class RequestError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) { super(message); }
}
