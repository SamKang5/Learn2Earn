import { decodeProtectedHeader, importX509, jwtVerify } from "jose";

const FIREBASE_CERTIFICATES = "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";
const MAX_JSON_BYTES = 768 * 1024;
let certificateCache: { values: Record<string, string>; expiresAt: number } | null = null;
let certificateFetch: Promise<Record<string, string>> | null = null;

export type FirebaseIdentity = {
  uid: string;
  isAnonymous: boolean;
  emailVerified: boolean;
};

export async function authenticate(request: Request, projectId: string): Promise<FirebaseIdentity> {
  const token = bearerToken(request);
  let header: ReturnType<typeof decodeProtectedHeader>;
  try {
    header = decodeProtectedHeader(token);
  } catch {
    throw new ApiError(401, "UNAUTHENTICATED", "Invalid session.");
  }
  if (header.alg !== "RS256" || typeof header.kid !== "string") {
    throw new ApiError(401, "UNAUTHENTICATED", "Invalid session.");
  }
  let certificates: Record<string, string>;
  try {
    certificates = await loadCertificates();
  } catch {
    throw new ApiError(503, "AUTH_UNAVAILABLE", "Could not verify your session. Try again.");
  }
  const certificate = certificates[header.kid];
  if (!certificate) throw new ApiError(401, "UNAUTHENTICATED", "Expired session.");
  let key: CryptoKey;
  try {
    key = await importX509(certificate, "RS256");
  } catch {
    throw new ApiError(503, "AUTH_UNAVAILABLE", "Could not verify your session. Try again.");
  }
  let payload: Awaited<ReturnType<typeof jwtVerify>>["payload"];
  try {
    payload = (await jwtVerify(token, key, {
      algorithms: ["RS256"],
      audience: projectId,
      issuer: `https://securetoken.google.com/${projectId}`,
    })).payload;
  } catch {
    throw new ApiError(401, "UNAUTHENTICATED", "Session expired. Sign in again.");
  }
  if (!payload.sub) throw new ApiError(401, "UNAUTHENTICATED", "Invalid session.");
  const firebase = isRecord(payload.firebase) ? payload.firebase : undefined;
  return {
    uid: payload.sub,
    isAnonymous: firebase?.sign_in_provider === "anonymous",
    emailVerified: payload.email_verified === true,
  };
}

async function loadCertificates(): Promise<Record<string, string>> {
  if (certificateCache && certificateCache.expiresAt > Date.now()) {
    return certificateCache.values;
  }
  if (!certificateFetch) {
    certificateFetch = (async () => {
      const response = await fetch(FIREBASE_CERTIFICATES);
      if (!response.ok) throw new Error("certificate request failed");
      const values: unknown = await response.json();
      if (!isStringRecord(values)) throw new Error("certificate response invalid");
      const cacheControl = response.headers.get("cache-control") ?? "";
      const maxAge = Number(cacheControl.match(/max-age=(\d+)/i)?.[1] ?? 300);
      certificateCache = {
        values,
        expiresAt: Date.now() + Math.max(60, maxAge) * 1000,
      };
      return values;
    })().finally(() => {
      certificateFetch = null;
    });
  }
  return certificateFetch;
}

export async function readJson(request: Request): Promise<unknown> {
  const contentType = request.headers.get("Content-Type") ?? "";
  if (!contentType.toLowerCase().includes("application/json")) {
    throw new ApiError(415, "JSON_REQUIRED", "Send an application/json request.");
  }
  const declaredLength = Number(request.headers.get("Content-Length") ?? 0);
  if (declaredLength > MAX_JSON_BYTES) throw new ApiError(413, "REQUEST_TOO_LARGE", "Request is too large.");
  const reader = request.body?.getReader();
  if (!reader) throw new ApiError(422, "INVALID_INPUT", "Request body is required.");
  const chunks: Uint8Array[] = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_JSON_BYTES) {
      await reader.cancel();
      throw new ApiError(413, "REQUEST_TOO_LARGE", "Request is too large.");
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    throw new ApiError(422, "INVALID_JSON", "Request JSON is invalid.");
  }
}

export function jsonError(cause: unknown): Response {
  if (cause instanceof ApiError) {
    return Response.json(
      { error: { code: cause.code, message: cause.message } },
      { status: cause.status, headers: { "Cache-Control": "no-store" } },
    );
  }
  console.error(JSON.stringify({
    message: "learning API request failed",
    error: cause instanceof Error ? cause.message : String(cause),
  }));
  return Response.json(
    { error: { code: "INTERNAL_ERROR", message: "Request could not be completed." } },
    { status: 500, headers: { "Cache-Control": "no-store" } },
  );
}

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function bearerToken(request: Request): string {
  const value = request.headers.get("Authorization");
  if (!value?.startsWith("Bearer ")) {
    throw new ApiError(401, "UNAUTHENTICATED", "Sign in before using learning features.");
  }
  return value.substring("Bearer ".length);
}

function isStringRecord(value: unknown): value is Record<string, string> {
  return isRecord(value) && Object.values(value).every(item => typeof item === "string");
}
