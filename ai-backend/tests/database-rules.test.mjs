import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const rules = JSON.parse(readFileSync(new URL("../../database.rules.json", import.meta.url), "utf8"));
const pairingRule = rules.rules.pairing_codes.$code;

const now = 1_700_000_000_000;
const parentUid = "parent-uid";
const childUid = "child-uid";
const baseCode = {
  parentUid,
  createdAt: now - 1_000,
  expiresAt: now + 60_000,
  secureLearning: true,
  guestApprovalHash: "pbkdf2-sha256$120000$salt$hash",
};

function canClaim({ before = baseCode, after, authUid = childUid, currentTime = now }) {
  if (!authUid || !before || !after) return false;
  const allowedKeys = new Set(["parentUid", "createdAt", "expiresAt", "secureLearning", "guestApprovalHash", "claimedBy"]);
  if (Object.keys(after).some(key => !allowedKeys.has(key))) return false;
  if (typeof after.parentUid !== "string") return false;
  if (typeof after.expiresAt !== "number") return false;
  if (typeof after.secureLearning !== "boolean") return false;
  if ("guestApprovalHash" in after && typeof after.guestApprovalHash !== "string") return false;
  if ("createdAt" in after && typeof after.createdAt !== "number") return false;
  if (typeof after.claimedBy !== "string") return false;
  return before.expiresAt >= currentTime &&
    (!("claimedBy" in before) || before.claimedBy === authUid) &&
    after.claimedBy === authUid &&
    after.parentUid === before.parentUid &&
    after.expiresAt === before.expiresAt &&
    after.secureLearning === before.secureLearning &&
    after.guestApprovalHash === before.guestApprovalHash &&
    (!("createdAt" in before) || after.createdAt === before.createdAt);
}

test("pairing-code claim rule preserves secure fields and rejects extra keys", () => {
  const writeRule = pairingRule[".write"];
  assert.match(writeRule, /secureLearning'\)\.val\(\) == data\.child\('secureLearning/);
  assert.match(writeRule, /createdAt/);
  assert.match(writeRule, /guestApprovalHash/);
  assert.equal(pairingRule.$other[".validate"], false);
});

test("a removed child cannot be recreated by child background writes", () => {
  const childRules = rules.rules.users.$parentId.children.$childId;
  for (const path of ["installedApps", "runtime", "timeCommandAcks", "quizProgress"]) {
    assert.match(
      childRules[path][".write"],
      /root\.child\('users'\)\.child\(\$parentId\)\.child\('children'\)\.child\(\$childId\)\.exists\(\)/,
      `${path} must require an existing child record`,
    );
  }
});

test("allows a valid secure child claim", () => {
  assert.equal(canClaim({ after: { ...baseCode, claimedBy: childUid } }), true);
});

test("rejects secure-learning downgrade during claim", () => {
  assert.equal(canClaim({ after: { ...baseCode, secureLearning: false, claimedBy: childUid } }), false);
});

test("rejects parent replacement during claim", () => {
  assert.equal(canClaim({ after: { ...baseCode, parentUid: "attacker-parent", claimedBy: childUid } }), false);
});

test("rejects expiration extension during claim", () => {
  assert.equal(canClaim({ after: { ...baseCode, expiresAt: baseCode.expiresAt + 60_000, claimedBy: childUid } }), false);
});

test("rejects changing the guest approval hash during claim", () => {
  assert.equal(canClaim({ after: { ...baseCode, guestApprovalHash: "attacker-hash", claimedBy: childUid } }), false);
});

test("rejects unexpected pairing-code fields during claim", () => {
  assert.equal(canClaim({ after: { ...baseCode, claimedBy: childUid, role: "fallback" } }), false);
});

test("rejects expired pairing code", () => {
  assert.equal(canClaim({
    before: { ...baseCode, expiresAt: now - 1 },
    after: { ...baseCode, expiresAt: now - 1, claimedBy: childUid },
  }), false);
});

test("rejects a code already claimed by another child", () => {
  assert.equal(canClaim({
    before: { ...baseCode, claimedBy: "other-child" },
    after: { ...baseCode, claimedBy: childUid },
  }), false);
});
