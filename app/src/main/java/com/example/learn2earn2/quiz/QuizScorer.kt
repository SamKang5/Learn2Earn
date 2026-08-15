package com.example.learn2earn2.quiz

data class RewardTier(val minimumScorePercent: Int, val rewardMinutes: Int)

data class QuizRewardPolicy(
    val passingScorePercent: Int = 80,
    val rewardMinutes: Int = 15,
    val maxAttempts: Int = 1,
    val scoreImproveCooldownMinutes: Int = 60,
    val repeatIntervalMinutes: Int = -1,
    val retryWhenFailed: Boolean = true,
    val allowPracticeDuringCooldown: Boolean = true,
    val rewardTiers: List<RewardTier> = listOf(RewardTier(passingScorePercent, rewardMinutes))
) {
    fun normalized() = copy(
        passingScorePercent = passingScorePercent.coerceIn(1, 100),
        rewardMinutes = rewardMinutes.coerceIn(1, 240),
        maxAttempts = maxAttempts.coerceIn(1, 5),
        scoreImproveCooldownMinutes = scoreImproveCooldownMinutes.coerceIn(0, 10_080),
        repeatIntervalMinutes = repeatIntervalMinutes.takeIf {
            it in setOf(-1, 0, 60, 1_440, 10_080, 43_200)
        } ?: -1
    )

    fun normalizedTiers() = rewardTiers.map { RewardTier(it.minimumScorePercent.coerceIn(1, 100), it.rewardMinutes.coerceIn(1, 240)) }
        .distinctBy { it.minimumScorePercent }.sortedByDescending { it.minimumScorePercent }

    fun rewardFor(scorePercent: Int): Int = normalizedTiers().firstOrNull { scorePercent >= it.minimumScorePercent }?.rewardMinutes ?: 0
}

data class QuizAnswer(
    val correctChoiceIndexes: Set<Int>,
    val selectedChoiceIndexes: Set<Int>
)

data class QuizScore(
    val correctQuestions: Int,
    val totalQuestions: Int,
    val percent: Int
)

object QuizScorer {
    fun score(answers: List<QuizAnswer>): QuizScore {
        val correct = answers.count {
            it.correctChoiceIndexes.isNotEmpty() &&
                it.selectedChoiceIndexes == it.correctChoiceIndexes
        }
        val percent = if (answers.isEmpty()) 0 else correct * 100 / answers.size
        return QuizScore(correct, answers.size, percent)
    }
}
