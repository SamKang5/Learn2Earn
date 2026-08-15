package com.example.learn2earn2.quiz

import android.content.Context

/**
 * Local hand-off for the screen-time engine. Firebase remains the shared-device record.
 */
object QuizRewardStore {
    const val KEY_PENDING_REWARD_MINUTES = "pending_quiz_reward_minutes"
    private const val PREFS = "learn2earn_child"
    private const val KEY_APPLIED_ATTEMPTS = "applied_quiz_reward_attempts"
    private const val KEY_PENDING_REWARDS = "pending_quiz_rewards"

    data class PendingReward(val attemptId: String, val minutes: Int)

    @Synchronized
    fun credit(context: Context, attemptId: String, minutes: Int): Boolean {
        if (attemptId.isBlank() || minutes <= 0) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val applied = prefs.getStringSet(KEY_APPLIED_ATTEMPTS, emptySet()).orEmpty().toMutableSet()
        if (!applied.add(attemptId)) return false
        val pending = prefs.getStringSet(KEY_PENDING_REWARDS, emptySet()).orEmpty().toMutableSet()
        pending += encode(attemptId, minutes)
        return prefs.edit()
            .putStringSet(KEY_APPLIED_ATTEMPTS, applied)
            .putStringSet(KEY_PENDING_REWARDS, pending)
            .putInt(
                KEY_PENDING_REWARD_MINUTES,
                prefs.getInt(KEY_PENDING_REWARD_MINUTES, 0) + minutes
            )
            .commit()
    }

    @Synchronized
    fun pendingRewards(context: Context): List<PendingReward> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queued = prefs.getStringSet(KEY_PENDING_REWARDS, emptySet()).orEmpty()
            .mapNotNull(::decode)
        // Migrate rewards written by the first MVP build. They were intentionally
        // aggregate-only, so one stable command is enough to avoid losing them.
        if (queued.isEmpty()) {
            val legacyMinutes = prefs.getInt(KEY_PENDING_REWARD_MINUTES, 0)
            if (legacyMinutes > 0) {
                return listOf(PendingReward("legacy-${prefs.getLong(KEY_LEGACY_GENERATION, 0L)}", legacyMinutes))
            }
        }
        return queued.sortedBy { it.attemptId }
    }

    @Synchronized
    fun acknowledge(context: Context, reward: PendingReward): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queued = prefs.getStringSet(KEY_PENDING_REWARDS, emptySet()).orEmpty().toMutableSet()
        val encoded = encode(reward.attemptId, reward.minutes)
        val removed = queued.remove(encoded)
        val currentMinutes = prefs.getInt(KEY_PENDING_REWARD_MINUTES, 0)
        val nextMinutes = (currentMinutes - reward.minutes).coerceAtLeast(0)
        val editor = prefs.edit().putStringSet(KEY_PENDING_REWARDS, queued)
            .putInt(KEY_PENDING_REWARD_MINUTES, nextMinutes)
        if (!removed && currentMinutes <= 0) return false
        return editor.commit()
    }

    fun pendingMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PENDING_REWARD_MINUTES, 0)

    /**
     * D1 stores cumulative awarded minutes. Persisting that watermark with the
     * local queue recovers a reward even when the submit response was lost.
     */
    @Synchronized
    fun reconcileServerTotal(context: Context, serverTotalMinutes: Int): Int {
        val total = serverTotalMinutes.coerceAtLeast(0)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val appliedTotal = prefs.getInt(KEY_APPLIED_SERVER_TOTAL, 0).coerceAtLeast(0)
        if (total <= appliedTotal) return 0
        val delta = total - appliedTotal
        val rewardId = "server-total:$total"
        val queued = prefs.getStringSet(KEY_PENDING_REWARDS, emptySet()).orEmpty().toMutableSet()
        queued += encode(rewardId, delta)
        val saved = prefs.edit()
            .putInt(KEY_APPLIED_SERVER_TOTAL, total)
            .putStringSet(KEY_PENDING_REWARDS, queued)
            .putInt(
                KEY_PENDING_REWARD_MINUTES,
                prefs.getInt(KEY_PENDING_REWARD_MINUTES, 0) + delta
            )
            .commit()
        return if (saved) delta else 0
    }

    private fun encode(attemptId: String, minutes: Int): String =
        "$attemptId|$minutes"

    private fun decode(value: String): PendingReward? {
        val separator = value.lastIndexOf('|')
        if (separator <= 0) return null
        val attemptId = value.substring(0, separator)
        val minutes = value.substring(separator + 1).toIntOrNull() ?: return null
        return PendingReward(attemptId, minutes).takeIf { it.minutes > 0 }
    }

    private const val KEY_LEGACY_GENERATION = "pending_quiz_reward_generation"
    private const val KEY_APPLIED_SERVER_TOTAL = "applied_server_reward_total_minutes"
}
