package com.example.learn2earn2.child.time

import kotlin.math.min

data class ScreenTimePolicy(
    val dailyFreeSeconds: Long = 0,
    val dailyRewardCapSeconds: Long = Long.MAX_VALUE,
    val restrictionsEnabled: Boolean = false,
    val blockEverything: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
    val exemptPackages: Set<String> = emptySet()
)

data class ScreenTimeState(
    val dayKey: String,
    val dailyFreeAllowanceSeconds: Long,
    val freeRemainingSeconds: Long,
    val todayBonusRemainingSeconds: Long = 0,
    val earnedBalanceSeconds: Long = 0,
    val earnedTodaySeconds: Long = 0,
    val managedUsageTodaySeconds: Long = 0,
    val lastResetAtEpochMillis: Long,
    val maxObservedEpochMillis: Long,
    val processedCommandIds: Set<String> = emptySet()
) {
    val totalRemainingSeconds: Long
        get() = freeRemainingSeconds + todayBonusRemainingSeconds + earnedBalanceSeconds
}

enum class ScreenTimeCreditType {
    GRANT_TODAY,
    ADJUST_TODAY,
    GRANT_BANK,
    SET_BANK,
    ADJUST_BANK,
    QUIZ_REWARD
}

data class ScreenTimeCommand(
    val id: String,
    val type: ScreenTimeCreditType,
    val seconds: Long
)

data class CommandResult(
    val state: ScreenTimeState,
    val appliedSeconds: Long
)

enum class EnforcementReason {
    NONE,
    FORCE_LOCK,
    TIME_EXHAUSTED
}

data class EnforcementDecision(
    val managed: Boolean,
    val shouldCharge: Boolean,
    val shouldBlock: Boolean,
    val reason: EnforcementReason
)

object ScreenTimeEngine {
    // Stops clock or timezone jumps from minting another daily allowance immediately.
    private const val MIN_RESET_INTERVAL_MS = 20L * 60 * 60 * 1000

    fun initialState(
        policy: ScreenTimePolicy,
        dayKey: String,
        dayStartedAtEpochMillis: Long,
        nowEpochMillis: Long
    ): ScreenTimeState {
        val allowance = policy.dailyFreeSeconds.coerceAtLeast(0)
        return ScreenTimeState(
            dayKey = dayKey,
            dailyFreeAllowanceSeconds = allowance,
            freeRemainingSeconds = allowance,
            lastResetAtEpochMillis = dayStartedAtEpochMillis,
            maxObservedEpochMillis = nowEpochMillis
        )
    }

    fun rollDayIfNeeded(
        state: ScreenTimeState,
        policy: ScreenTimePolicy,
        currentDayKey: String,
        currentDayStartedAtEpochMillis: Long,
        nowEpochMillis: Long
    ): ScreenTimeState {
        val observedMax = maxOf(state.maxObservedEpochMillis, nowEpochMillis)
        val clockDidNotMoveBack = nowEpochMillis >= state.maxObservedEpochMillis
        val enoughRealTimePassed = nowEpochMillis - state.lastResetAtEpochMillis >= MIN_RESET_INTERVAL_MS
        val movedToLaterDay = currentDayKey > state.dayKey
        if (!clockDidNotMoveBack || !enoughRealTimePassed || !movedToLaterDay) {
            return state.copy(maxObservedEpochMillis = observedMax)
        }

        val allowance = policy.dailyFreeSeconds.coerceAtLeast(0)
        return state.copy(
            dayKey = currentDayKey,
            dailyFreeAllowanceSeconds = allowance,
            freeRemainingSeconds = allowance,
            todayBonusRemainingSeconds = 0,
            earnedTodaySeconds = 0,
            managedUsageTodaySeconds = 0,
            lastResetAtEpochMillis = currentDayStartedAtEpochMillis,
            maxObservedEpochMillis = observedMax
        )
    }

    fun applyPolicy(state: ScreenTimeState, policy: ScreenTimePolicy): ScreenTimeState {
        val newAllowance = policy.dailyFreeSeconds.coerceAtLeast(0)
        val allowanceChange = newAllowance - state.dailyFreeAllowanceSeconds
        val adjustedFreeRemaining = state.freeRemainingSeconds + allowanceChange
        val bankShortfall = (-adjustedFreeRemaining).coerceAtLeast(0)
        return state.copy(
            dailyFreeAllowanceSeconds = newAllowance,
            freeRemainingSeconds = adjustedFreeRemaining.coerceAtLeast(0),
            earnedBalanceSeconds = (state.earnedBalanceSeconds - bankShortfall).coerceAtLeast(0)
        )
    }

    fun applyUsage(state: ScreenTimeState, seconds: Long): ScreenTimeState {
        var remainingCharge = seconds.coerceAtLeast(0)
        if (remainingCharge == 0L || state.totalRemainingSeconds == 0L) return state

        val freeSpent = min(state.freeRemainingSeconds, remainingCharge)
        remainingCharge -= freeSpent
        val todayBonusSpent = min(state.todayBonusRemainingSeconds, remainingCharge)
        remainingCharge -= todayBonusSpent
        val earnedSpent = min(state.earnedBalanceSeconds, remainingCharge)
        val totalSpent = freeSpent + todayBonusSpent + earnedSpent

        return state.copy(
            freeRemainingSeconds = state.freeRemainingSeconds - freeSpent,
            todayBonusRemainingSeconds = state.todayBonusRemainingSeconds - todayBonusSpent,
            earnedBalanceSeconds = state.earnedBalanceSeconds - earnedSpent,
            managedUsageTodaySeconds = state.managedUsageTodaySeconds + totalSpent
        )
    }

    fun applyCommand(
        state: ScreenTimeState,
        policy: ScreenTimePolicy,
        command: ScreenTimeCommand
    ): CommandResult {
        if (command.id.isBlank() || command.id in state.processedCommandIds) {
            return CommandResult(state, 0)
        }
        if (command.type == ScreenTimeCreditType.QUIZ_REWARD) {
            return applyQuizReward(state, policy, command.id, command.seconds)
        }

        val processed = state.processedCommandIds + command.id
        val next = when (command.type) {
            ScreenTimeCreditType.GRANT_TODAY -> state.copy(
                todayBonusRemainingSeconds = state.todayBonusRemainingSeconds + command.seconds.coerceAtLeast(0),
                processedCommandIds = processed
            )
            ScreenTimeCreditType.ADJUST_TODAY -> {
                if (command.seconds >= 0) {
                    state.copy(
                        todayBonusRemainingSeconds = state.todayBonusRemainingSeconds + command.seconds,
                        processedCommandIds = processed
                    )
                } else {
                    val requestedRemoval = -command.seconds
                    val bonusRemoved = min(state.todayBonusRemainingSeconds, requestedRemoval)
                    val freeRemoved = min(state.freeRemainingSeconds, requestedRemoval - bonusRemoved)
                    state.copy(
                        freeRemainingSeconds = state.freeRemainingSeconds - freeRemoved,
                        todayBonusRemainingSeconds = state.todayBonusRemainingSeconds - bonusRemoved,
                        processedCommandIds = processed
                    )
                }
            }
            ScreenTimeCreditType.GRANT_BANK -> state.copy(
                earnedBalanceSeconds = state.earnedBalanceSeconds + command.seconds.coerceAtLeast(0),
                processedCommandIds = processed
            )
            ScreenTimeCreditType.SET_BANK -> state.copy(
                earnedBalanceSeconds = command.seconds.coerceAtLeast(0),
                processedCommandIds = processed
            )
            ScreenTimeCreditType.ADJUST_BANK -> state.copy(
                earnedBalanceSeconds = (state.earnedBalanceSeconds + command.seconds).coerceAtLeast(0),
                processedCommandIds = processed
            )
            ScreenTimeCreditType.QUIZ_REWARD -> error("Handled above")
        }
        val applied = when (command.type) {
            ScreenTimeCreditType.GRANT_TODAY ->
                next.todayBonusRemainingSeconds - state.todayBonusRemainingSeconds
            ScreenTimeCreditType.ADJUST_TODAY ->
                (next.freeRemainingSeconds + next.todayBonusRemainingSeconds) -
                    (state.freeRemainingSeconds + state.todayBonusRemainingSeconds)
            ScreenTimeCreditType.GRANT_BANK,
            ScreenTimeCreditType.SET_BANK,
            ScreenTimeCreditType.ADJUST_BANK ->
                next.earnedBalanceSeconds - state.earnedBalanceSeconds
            ScreenTimeCreditType.QUIZ_REWARD -> error("Handled above")
        }
        return CommandResult(next, applied)
    }

    fun applyQuizReward(
        state: ScreenTimeState,
        policy: ScreenTimePolicy,
        rewardId: String,
        requestedSeconds: Long
    ): CommandResult {
        if (rewardId.isBlank() || rewardId in state.processedCommandIds) {
            return CommandResult(state, 0)
        }
        val remainingCap = (policy.dailyRewardCapSeconds.coerceAtLeast(0) - state.earnedTodaySeconds)
            .coerceAtLeast(0)
        val applied = min(requestedSeconds.coerceAtLeast(0), remainingCap)
        return CommandResult(
            state.copy(
                earnedBalanceSeconds = state.earnedBalanceSeconds + applied,
                earnedTodaySeconds = state.earnedTodaySeconds + applied,
                processedCommandIds = state.processedCommandIds + rewardId
            ),
            applied
        )
    }

    fun getEnforcementDecision(
        state: ScreenTimeState,
        policy: ScreenTimePolicy,
        foregroundPackage: String?,
        blockEverythingCandidate: Boolean,
        exemptFromAutomaticRules: Boolean,
        forceLocked: Boolean
    ): EnforcementDecision {
        if (exemptFromAutomaticRules || (foregroundPackage != null && foregroundPackage in policy.exemptPackages)) {
            return EnforcementDecision(false, false, false, EnforcementReason.NONE)
        }
        // With no selected-app rules, parent "Lock now" covers the device. Once rules
        // exist, a parent lock keeps respecting their selected-app scope.
        val forceLockWithoutSelectedApps = forceLocked &&
            !policy.blockEverything && policy.blockedPackages.isEmpty()
        if (forceLockWithoutSelectedApps) {
            return EnforcementDecision(true, false, true, EnforcementReason.FORCE_LOCK)
        }
        if (foregroundPackage == null) {
            return EnforcementDecision(false, false, false, EnforcementReason.NONE)
        }

        val managed = if (policy.blockEverything) {
            blockEverythingCandidate
        } else {
            foregroundPackage in policy.blockedPackages
        }
        if (!managed) {
            return EnforcementDecision(false, false, false, EnforcementReason.NONE)
        }
        if (forceLocked) {
            return EnforcementDecision(true, false, true, EnforcementReason.FORCE_LOCK)
        }
        if (!policy.restrictionsEnabled) {
            return EnforcementDecision(false, false, false, EnforcementReason.NONE)
        }
        val exhausted = state.totalRemainingSeconds <= 0
        return EnforcementDecision(
            managed = true,
            shouldCharge = !exhausted,
            shouldBlock = exhausted,
            reason = if (exhausted) EnforcementReason.TIME_EXHAUSTED else EnforcementReason.NONE
        )
    }
}
