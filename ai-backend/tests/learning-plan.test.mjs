import assert from "node:assert/strict";
import test from "node:test";
import { parseLearningPlan, refillNeeded } from "../src/learning-plan.ts";

const plan = parseLearningPlan({
  grade: "Grade 4",
  subjects: ["Science", "Mathematics"],
  curriculumNotes: "Local primary curriculum",
  strengths: "Addition",
  weakAreas: "Fractions",
  difficulties: ["Balanced"],
  minimumAvailable: 3,
  refillCount: 2,
  assignmentMode: "parent_review",
  minimumScorePercent: 80,
  rewardMinutes: 15,
});

test("refills only when available assignments fall below the plan minimum", () => {
  assert.equal(refillNeeded(plan, 3), 0);
  assert.equal(refillNeeded(plan, 2), 2);
  assert.equal(refillNeeded(plan, 0), 3);
});

test("rejects child-unfriendly plan values and duplicate subjects", () => {
  assert.throws(() => parseLearningPlan({ ...plan, grade: "" }), /Grade is not valid/);
  assert.throws(() => parseLearningPlan({ ...plan, subjects: ["Science", "science"] }), /different subjects/);
  assert.throws(() => parseLearningPlan({ ...plan, assignmentMode: "child_choice" }), /Assignment mode is not valid/);
});

test("accepts multiple difficulties and reward thresholds", () => {
  const choices = parseLearningPlan({
    ...plan,
    difficulties: ["Easy", "Challenging"],
    rewardTiers: [
      { minimumScorePercent: 90, rewardMinutes: 20 },
      { minimumScorePercent: 70, rewardMinutes: 10 },
    ],
  });
  assert.deepEqual(choices.difficulties, ["Easy", "Challenging"]);
  assert.equal(choices.rewardTiers.length, 2);
});
