package com.example.learn2earn2.child.time

import android.content.Context

/** One local snapshot keeps enforcement working when Firebase or the app process is unavailable. */
class ScreenTimeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadPolicy(): ScreenTimePolicy {
        val legacyMinutes = prefs.getInt(LEGACY_LIMIT_MINUTES, 0).coerceAtLeast(0)
        return ScreenTimePolicy(
            dailyFreeSeconds = prefs.getLong(POLICY_FREE_SECONDS, legacyMinutes * 60L).coerceAtLeast(0),
            // A bounded default keeps a misconfigured/legacy child from earning
            // unlimited time. Parents can raise it explicitly in device rules.
            dailyRewardCapSeconds = prefs.getLong(POLICY_REWARD_CAP_SECONDS, DEFAULT_REWARD_CAP_SECONDS)
                .coerceAtLeast(0),
            restrictionsEnabled = prefs.getBoolean(POLICY_RESTRICTIONS_ENABLED, false),
            blockEverything = prefs.getBoolean(POLICY_BLOCK_EVERYTHING, false),
            blockedPackages = prefs.getStringSet(POLICY_BLOCKED_PACKAGES, emptySet()).orEmpty().toSet(),
            exemptPackages = prefs.getStringSet(POLICY_EXEMPT_PACKAGES, emptySet()).orEmpty().toSet()
        )
    }

    fun savePolicy(policy: ScreenTimePolicy) {
        prefs.edit()
            .putLong(POLICY_FREE_SECONDS, policy.dailyFreeSeconds)
            .putLong(POLICY_REWARD_CAP_SECONDS, policy.dailyRewardCapSeconds)
            .putBoolean(POLICY_RESTRICTIONS_ENABLED, policy.restrictionsEnabled)
            .putBoolean(POLICY_BLOCK_EVERYTHING, policy.blockEverything)
            .putStringSet(POLICY_BLOCKED_PACKAGES, policy.blockedPackages)
            .putStringSet(POLICY_EXEMPT_PACKAGES, policy.exemptPackages)
            .apply()
    }

    fun loadState(): ScreenTimeState? {
        val dayKey = prefs.getString(STATE_DAY_KEY, null) ?: return null
        return ScreenTimeState(
            dayKey = dayKey,
            dailyFreeAllowanceSeconds = prefs.getLong(STATE_FREE_ALLOWANCE, 0).coerceAtLeast(0),
            freeRemainingSeconds = prefs.getLong(STATE_FREE_REMAINING, 0).coerceAtLeast(0),
            todayBonusRemainingSeconds = prefs.getLong(STATE_TODAY_BONUS, 0).coerceAtLeast(0),
            earnedBalanceSeconds = prefs.getLong(STATE_EARNED_BALANCE, 0).coerceAtLeast(0),
            earnedTodaySeconds = prefs.getLong(STATE_EARNED_TODAY, 0).coerceAtLeast(0),
            managedUsageTodaySeconds = prefs.getLong(STATE_MANAGED_USAGE, 0).coerceAtLeast(0),
            lastResetAtEpochMillis = prefs.getLong(STATE_LAST_RESET_AT, 0),
            maxObservedEpochMillis = prefs.getLong(STATE_MAX_OBSERVED_AT, 0),
            processedCommandIds = prefs.getStringSet(STATE_PROCESSED_COMMANDS, emptySet())
                .orEmpty().toSet()
        )
    }

    fun saveState(state: ScreenTimeState) {
        prefs.edit()
            .putString(STATE_DAY_KEY, state.dayKey)
            .putLong(STATE_FREE_ALLOWANCE, state.dailyFreeAllowanceSeconds)
            .putLong(STATE_FREE_REMAINING, state.freeRemainingSeconds)
            .putLong(STATE_TODAY_BONUS, state.todayBonusRemainingSeconds)
            .putLong(STATE_EARNED_BALANCE, state.earnedBalanceSeconds)
            .putLong(STATE_EARNED_TODAY, state.earnedTodaySeconds)
            .putLong(STATE_MANAGED_USAGE, state.managedUsageTodaySeconds)
            .putLong(STATE_LAST_RESET_AT, state.lastResetAtEpochMillis)
            .putLong(STATE_MAX_OBSERVED_AT, state.maxObservedEpochMillis)
            .putStringSet(STATE_PROCESSED_COMMANDS, state.processedCommandIds)
            .apply()
    }

    fun loadManualUnlockUntil(): Long = prefs.getLong(MANUAL_UNLOCK_UNTIL, 0)

    fun loadManualLockUntil(): Long = prefs.getLong(MANUAL_LOCK_UNTIL, 0)

    fun loadForceLocked(): Boolean = prefs.getBoolean(FORCE_LOCKED, false)

    fun saveOverrides(
        manualUnlockUntil: Long,
        manualLockUntil: Long,
        forceLocked: Boolean
    ) {
        prefs.edit()
            .remove(EMERGENCY_UNLOCK_UNTIL)
            .remove(APPROVED_UNLOCK_UNTIL)
            .putLong(MANUAL_UNLOCK_UNTIL, manualUnlockUntil)
            .putLong(MANUAL_LOCK_UNTIL, manualLockUntil)
            .putBoolean(FORCE_LOCKED, forceLocked)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "learn2earn_child"
        private const val LEGACY_LIMIT_MINUTES = "screen_time_limit_minutes"
        private const val POLICY_FREE_SECONDS = "time_policy_free_seconds"
        private const val POLICY_REWARD_CAP_SECONDS = "time_policy_reward_cap_seconds"
        private const val DEFAULT_REWARD_CAP_SECONDS = 120L * 60L
        private const val POLICY_RESTRICTIONS_ENABLED = "time_policy_restrictions_enabled"
        private const val POLICY_BLOCK_EVERYTHING = "time_policy_block_everything"
        private const val POLICY_BLOCKED_PACKAGES = "time_policy_blocked_packages"
        private const val POLICY_EXEMPT_PACKAGES = "time_policy_exempt_packages"
        private const val STATE_DAY_KEY = "time_state_day_key"
        private const val STATE_FREE_ALLOWANCE = "time_state_free_allowance_seconds"
        private const val STATE_FREE_REMAINING = "time_state_free_remaining_seconds"
        private const val STATE_TODAY_BONUS = "time_state_today_bonus_seconds"
        private const val STATE_EARNED_BALANCE = "time_state_earned_balance_seconds"
        private const val STATE_EARNED_TODAY = "time_state_earned_today_seconds"
        private const val STATE_MANAGED_USAGE = "time_state_managed_usage_seconds"
        private const val STATE_LAST_RESET_AT = "time_state_last_reset_at"
        private const val STATE_MAX_OBSERVED_AT = "time_state_max_observed_at"
        private const val STATE_PROCESSED_COMMANDS = "time_state_processed_command_ids"
        private const val EMERGENCY_UNLOCK_UNTIL = "cached_emergency_unlock_until"
        private const val APPROVED_UNLOCK_UNTIL = "locally_approved_unlock_until"
        private const val MANUAL_UNLOCK_UNTIL = "cached_manual_unlock_until"
        private const val MANUAL_LOCK_UNTIL = "cached_manual_lock_until"
        private const val FORCE_LOCKED = "cached_force_locked"
    }
}
