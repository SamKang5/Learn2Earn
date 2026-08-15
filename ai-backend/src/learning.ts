export type StoredChoice = {
  text: string;
  isCorrect: boolean;
  imageData?: string;
};

export type StoredQuestion = {
  prompt: string;
  allowMultipleAnswers: boolean;
  choices: StoredChoice[];
  imageData?: string;
};

export type StoredQuiz = {
  title: string;
  subject: string;
  grade: string;
  questions: StoredQuestion[];
  coverImageData?: string;
};

export type ChildQuiz = {
  title: string;
  subject: string;
  grade: string;
  coverImageData?: string;
  questions: Array<{
    prompt: string;
    allowMultipleAnswers: boolean;
    imageData?: string;
    choices: Array<{ text: string; imageData?: string }>;
  }>;
};

export type RewardTier = { minimumScorePercent: number; rewardMinutes: number };

export function parseQuiz(value: unknown): StoredQuiz {
  if (!isRecord(value)) throw new ValidationError("Quiz is required.");
  const title = requiredText(value.title, "Quiz title", 100);
  const subject = requiredText(value.subject, "Subject", 80);
  const grade = requiredText(value.grade, "Grade", 40);
  if (!Array.isArray(value.questions) || value.questions.length < 1 || value.questions.length > 20) {
    throw new ValidationError("Quiz needs 1 to 20 questions.");
  }
  const coverImageData = optionalImage(value.coverImageData, "Cover image");
  const questions = value.questions.map((item, questionIndex) => parseQuestion(item, questionIndex));
  const mediaSize = (coverImageData?.length ?? 0) + questions.reduce((total, question) =>
    total + (question.imageData?.length ?? 0) + question.choices.reduce((choiceTotal, choice) => choiceTotal + (choice.imageData?.length ?? 0), 0), 0);
  if (mediaSize > 650_000) throw new ValidationError("Quiz images are too large.");
  return { title, subject, grade, questions, coverImageData };
}

export function parseAnswers(value: unknown, questions: StoredQuestion[]): number[][] {
  if (!Array.isArray(value) || value.length !== questions.length) {
    throw new ValidationError("Submit one answer list for every question.");
  }
  return value.map((item, questionIndex) => {
    if (!Array.isArray(item)) throw new ValidationError(`Question ${questionIndex + 1} has invalid answers.`);
    const answers = item.map(choiceIndex => {
      if (!Number.isInteger(choiceIndex) || (choiceIndex as number) < 0 || (choiceIndex as number) >= questions[questionIndex].choices.length) {
        throw new ValidationError(`Question ${questionIndex + 1} has an invalid choice.`);
      }
      return choiceIndex as number;
    });
    if (new Set(answers).size !== answers.length) {
      throw new ValidationError(`Question ${questionIndex + 1} repeats a choice.`);
    }
    return answers;
  });
}

export function scoreQuiz(questions: StoredQuestion[], answers: number[][]): number {
  const correct = questions.reduce((count, question, questionIndex) => {
    const expected = question.choices.flatMap((choice, choiceIndex) => choice.isCorrect ? [choiceIndex] : []);
    const selected = answers[questionIndex];
    return count + (sameIndexes(expected, selected) ? 1 : 0);
  }, 0);
  return Math.round((correct * 100) / questions.length);
}

export function toChildQuiz(quiz: StoredQuiz): ChildQuiz {
  return {
    ...quiz,
    questions: quiz.questions.map(question => ({
      prompt: question.prompt,
      allowMultipleAnswers: question.allowMultipleAnswers,
      imageData: question.imageData,
      choices: question.choices.map(choice => ({ text: choice.text, imageData: choice.imageData })),
    })),
    coverImageData: quiz.coverImageData,
  };
}

export function rewardForScore(tiers: RewardTier[], scorePercent: number): number {
  return [...tiers]
    .sort((left, right) => right.minimumScorePercent - left.minimumScorePercent)
    .find(tier => scorePercent >= tier.minimumScorePercent)?.rewardMinutes ?? 0;
}

export function reviewQuiz(quiz: StoredQuiz, exposeAnswerKey: boolean): StoredQuiz | ChildQuiz {
  return exposeAnswerKey ? quiz : toChildQuiz(quiz);
}

function parseQuestion(value: unknown, questionIndex: number): StoredQuestion {
  if (!isRecord(value)) throw new ValidationError(`Question ${questionIndex + 1} is invalid.`);
  const prompt = requiredText(value.prompt, `Question ${questionIndex + 1}`, 500);
  if (typeof value.allowMultipleAnswers !== "boolean") {
    throw new ValidationError(`Question ${questionIndex + 1} needs an answer mode.`);
  }
  if (!Array.isArray(value.choices) || value.choices.length < 2 || value.choices.length > 6) {
    throw new ValidationError(`Question ${questionIndex + 1} needs 2 to 6 choices.`);
  }
  const choices = value.choices.map((item, choiceIndex) => {
    if (!isRecord(item) || typeof item.isCorrect !== "boolean") {
      throw new ValidationError(`Question ${questionIndex + 1}, choice ${choiceIndex + 1} is invalid.`);
    }
    return {
      text: requiredText(item.text, `Question ${questionIndex + 1}, choice ${choiceIndex + 1}`, 300),
      isCorrect: item.isCorrect,
      imageData: optionalImage(item.imageData, `Question ${questionIndex + 1}, choice ${choiceIndex + 1} image`),
    };
  });
  const correctCount = choices.filter(choice => choice.isCorrect).length;
  if (correctCount < 1 || (!value.allowMultipleAnswers && correctCount !== 1)) {
    throw new ValidationError(`Question ${questionIndex + 1} has an invalid answer key.`);
  }
  return { prompt, allowMultipleAnswers: value.allowMultipleAnswers, choices, imageData: optionalImage(value.imageData, `Question ${questionIndex + 1} image`) };
}

function requiredText(value: unknown, name: string, maxLength: number): string {
  if (typeof value !== "string") throw new ValidationError(`${name} is required.`);
  const text = value.trim();
  if (!text || text.length > maxLength) throw new ValidationError(`${name} is not valid.`);
  return text;
}

function optionalImage(value: unknown, name: string): string | undefined {
  if (value == null) return undefined;
  if (typeof value !== "string" || value.length > 100_000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) {
    throw new ValidationError(`${name} is invalid.`);
  }
  return value;
}

function sameIndexes(left: number[], right: number[]): boolean {
  return left.length === right.length && left.every(value => right.includes(value));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export class ValidationError extends Error {}
