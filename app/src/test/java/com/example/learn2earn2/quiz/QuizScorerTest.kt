package com.example.learn2earn2.quiz

import org.junit.Assert.assertEquals
import org.junit.Test

class QuizScorerTest {
    @Test
    fun exactAnswerSetsScoreDeterministically() {
        val score = QuizScorer.score(
            listOf(
                QuizAnswer(setOf(1), setOf(1)),
                QuizAnswer(setOf(0, 2), setOf(0, 2)),
                QuizAnswer(setOf(3), setOf(2))
            )
        )

        assertEquals(2, score.correctQuestions)
        assertEquals(3, score.totalQuestions)
        assertEquals(66, score.percent)
    }

    @Test
    fun extraSelectionMakesMultiAnswerQuestionIncorrect() {
        val score = QuizScorer.score(
            listOf(QuizAnswer(setOf(0, 2), setOf(0, 1, 2)))
        )

        assertEquals(0, score.percent)
    }

    @Test
    fun rewardPolicyKeepsOnlySupportedRepeatCadences() {
        assertEquals(1_440, QuizRewardPolicy(repeatIntervalMinutes = 1_440).normalized().repeatIntervalMinutes)
        assertEquals(-1, QuizRewardPolicy(repeatIntervalMinutes = 17).normalized().repeatIntervalMinutes)
    }
}
