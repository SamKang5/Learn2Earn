import assert from "node:assert/strict";
import test from "node:test";
import {
  parseAnswers,
  parseQuiz,
  reviewQuiz,
  rewardForScore,
  scoreQuiz,
  toChildQuiz,
} from "../src/learning.ts";

const quiz = parseQuiz({
  title: "Fractions",
  subject: "Mathematics",
  grade: "Grade 5",
  questions: [
    {
      prompt: "Which equals one half?",
      allowMultipleAnswers: false,
      choices: [
        { text: "1/2", isCorrect: true },
        { text: "1/3", isCorrect: false },
      ],
    },
    {
      prompt: "Select the even numbers.",
      allowMultipleAnswers: true,
      choices: [
        { text: "2", isCorrect: true },
        { text: "3", isCorrect: false },
        { text: "4", isCorrect: true },
      ],
    },
  ],
});

test("scores exact single and multiple-choice answers", () => {
  assert.equal(scoreQuiz(quiz.questions, parseAnswers([[0], [2, 0]], quiz.questions)), 100);
  assert.equal(scoreQuiz(quiz.questions, parseAnswers([[0], [0]], quiz.questions)), 50);
});

test("never exposes answer keys to child payloads", () => {
  const childJson = JSON.stringify(toChildQuiz(quiz));
  assert.equal(childJson.includes("isCorrect"), false);
});

test("rejects duplicate or out-of-range selections", () => {
  assert.throws(() => parseAnswers([[0], [0, 0]], quiz.questions), /repeats a choice/);
  assert.throws(() => parseAnswers([[2], [0]], quiz.questions), /invalid choice/);
});

test("uses highest matching score band for one final quiz result", () => {
  const tiers = [
    { minimumScorePercent: 90, rewardMinutes: 30 },
    { minimumScorePercent: 70, rewardMinutes: 15 },
  ];
  assert.equal(rewardForScore(tiers, 95), 30);
  assert.equal(rewardForScore(tiers, 75), 15);
  assert.equal(rewardForScore(tiers, 69), 0);
});

test("finished quiz reviews include answer key", () => {
  assert.equal(JSON.stringify(reviewQuiz(quiz, true)).includes("isCorrect"), true);
});
