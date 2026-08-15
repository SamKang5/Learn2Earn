package com.example.learn2earn2.child.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTimeEngineTest {

    @Test
    fun changingRewardCapOnlyChangesFutureEarnings() {
        val dayStart = 1_700_000_000_000L
        val initialPolicy = ScreenTimePolicy(dailyRewardCapSeconds = 160 * 60L)
        var state = ScreenTimeEngine.initialState(initialPolicy, "2026-07-23", dayStart, dayStart)

        state = ScreenTimeEngine.applyQuizReward(state, initialPolicy, "attempt-1", 10 * 60L).state
        val increasedPolicy = initialPolicy.copy(dailyRewardCapSeconds = 170 * 60L)
        val afterIncrease = ScreenTimeEngine.applyQuizReward(state, increasedPolicy, "attempt-2", 20 * 60L)
        assertEquals(20 * 60L, afterIncrease.appliedSeconds)

        val loweredPolicy = initialPolicy.copy(dailyRewardCapSeconds = 15 * 60L)
        val afterDecrease = ScreenTimeEngine.applyQuizReward(afterIncrease.state, loweredPolicy, "attempt-3", 5 * 60L)
        assertEquals(0L, afterDecrease.appliedSeconds)
        assertEquals(30 * 60L, afterDecrease.state.earnedBalanceSeconds)
        assertEquals(30 * 60L, afterDecrease.state.earnedTodaySeconds)
    }
    private val dayStart = 1_700_000_000_000L

    @Test
    fun usageSpendsFreeThenBankedTime() {
        val policy = ScreenTimePolicy(dailyFreeSeconds = 60 * 60)
        var state = ScreenTimeEngine.initialState(policy, "2026-07-23", dayStart, dayStart)
        state = ScreenTimeEngine.applyCommand(
            state,
            policy,
            ScreenTimeCommand("parent-1", ScreenTimeCreditType.GRANT_BANK, 30 * 60)
        ).state

        state = ScreenTimeEngine.applyUsage(state, 65 * 60)

        assertEquals(0L, state.freeRemainingSeconds)
        assertEquals(25L * 60, state.earnedBalanceSeconds)
        assertEquals(65L * 60, state.managedUsageTodaySeconds)
    }

    @Test
    fun quizRewardsAreDeduplicatedAndCapped() {
        val policy = ScreenTimePolicy(dailyRewardCapSeconds = 60 * 60)
        var state = ScreenTimeEngine.initialState(policy, "2026-07-23", dayStart, dayStart)

        val first = ScreenTimeEngine.applyQuizReward(state, policy, "attempt-1", 45 * 60)
        state = first.state
        val duplicate = ScreenTimeEngine.applyQuizReward(state, policy, "attempt-1", 45 * 60)
        val second = ScreenTimeEngine.applyQuizReward(duplicate.state, policy, "attempt-2", 30 * 60)

        assertEquals(45L * 60, first.appliedSeconds)
        assertEquals(0L, duplicate.appliedSeconds)
        assertEquals(15L * 60, second.appliedSeconds)
        assertEquals(60L * 60, second.state.earnedBalanceSeconds)
        assertEquals(60L * 60, second.state.earnedTodaySeconds)
    }

    @Test
    fun parentTimeChangesDoNotUseQuizRewardCap() {
        val policy = ScreenTimePolicy(dailyRewardCapSeconds = 10 * 60)
        val state = ScreenTimeEngine.initialState(policy, "2026-07-23", dayStart, dayStart)

        val parentGrant = ScreenTimeEngine.applyCommand(
            state,
            policy,
            ScreenTimeCommand("parent-bank", ScreenTimeCreditType.GRANT_BANK, 45 * 60)
        )
        val todayAdjustment = ScreenTimeEngine.applyCommand(
            parentGrant.state,
            policy,
            ScreenTimeCommand("parent-today", ScreenTimeCreditType.ADJUST_TODAY, 20 * 60)
        )

        assertEquals(45L * 60, todayAdjustment.state.earnedBalanceSeconds)
        assertEquals(20L * 60, todayAdjustment.state.todayBonusRemainingSeconds)
        assertEquals(0L, todayAdjustment.state.earnedTodaySeconds)
    }

    @Test
    fun removingTodayTimeDeductsDailyFreeAllowanceWhenNoBonusExists() {
        val policy = ScreenTimePolicy(dailyFreeSeconds = 60 * 60)
        val state = ScreenTimeEngine.initialState(policy, "2026-07-23", dayStart, dayStart)

        val removal = ScreenTimeEngine.applyCommand(
            state,
            policy,
            ScreenTimeCommand("remove-today", ScreenTimeCreditType.ADJUST_TODAY, -15 * 60)
        )

        assertEquals(45L * 60, removal.state.freeRemainingSeconds)
        assertEquals(0L, removal.state.todayBonusRemainingSeconds)
        assertEquals(-15L * 60, removal.appliedSeconds)
    }

    @Test
    fun changingDailyFreeTimeAdjustsRemainingThenUsesBankForShortfall() {
        val tenMinutes = ScreenTimePolicy(dailyFreeSeconds = 10 * 60)
        var state = ScreenTimeEngine.initialState(tenMinutes, "2026-07-23", dayStart, dayStart)
        state = ScreenTimeEngine.applyCommand(
            state,
            tenMinutes,
            ScreenTimeCommand("bank", ScreenTimeCreditType.GRANT_BANK, 10 * 60)
        ).state
        state = ScreenTimeEngine.applyUsage(state, 6 * 60)

        val increased = ScreenTimeEngine.applyPolicy(state, ScreenTimePolicy(dailyFreeSeconds = 15 * 60))
        assertEquals(9L * 60, increased.freeRemainingSeconds)
        assertEquals(10L * 60, increased.earnedBalanceSeconds)

        val decreased = ScreenTimeEngine.applyPolicy(state, ScreenTimePolicy(dailyFreeSeconds = 5 * 60))
        assertEquals(0L, decreased.freeRemainingSeconds)
        assertEquals(9L * 60, decreased.earnedBalanceSeconds)
    }

    @Test
    fun expiredTimerStaysExpiredWhenNoValidRewardIsGranted() {
        val policy = ScreenTimePolicy(dailyFreeSeconds = 60, dailyRewardCapSeconds = 60 * 60)
        var state = ScreenTimeEngine.initialState(policy, "2026-07-23", dayStart, dayStart)
        state = ScreenTimeEngine.applyUsage(state, 60)

        val blankReward = ScreenTimeEngine.applyQuizReward(state, policy, "", 5 * 60)
        val zeroReward = ScreenTimeEngine.applyQuizReward(blankReward.state, policy, "attempt-1", 0)

        assertEquals(0L, state.totalRemainingSeconds)
        assertEquals(0L, blankReward.appliedSeconds)
        assertEquals(0L, zeroReward.appliedSeconds)
        assertEquals(0L, zeroReward.state.totalRemainingSeconds)
    }

    @Test
    fun nextDayExpiresTodayBonusButKeepsBankedTime() {
        val policy = ScreenTimePolicy(dailyFreeSeconds = 60 * 60)
        var state = ScreenTimeEngine.initialState(policy, "2026-07-23", dayStart, dayStart)
        state = ScreenTimeEngine.applyCommand(
            state,
            policy,
            ScreenTimeCommand("today", ScreenTimeCreditType.GRANT_TODAY, 15 * 60)
        ).state
        state = ScreenTimeEngine.applyCommand(
            state,
            policy,
            ScreenTimeCommand("bank", ScreenTimeCreditType.GRANT_BANK, 30 * 60)
        ).state

        state = ScreenTimeEngine.rollDayIfNeeded(
            state,
            policy,
            "2026-07-24",
            dayStart + 24 * 60 * 60 * 1000,
            dayStart + 24 * 60 * 60 * 1000
        )

        assertEquals(60L * 60, state.freeRemainingSeconds)
        assertEquals(0L, state.todayBonusRemainingSeconds)
        assertEquals(30L * 60, state.earnedBalanceSeconds)
    }

    @Test
    fun clockAndTimezoneChangesCannotResetSameAllowanceTwice() {
        val policy = ScreenTimePolicy(dailyFreeSeconds = 60 * 60)
        var state = ScreenTimeEngine.initialState(policy, "2026-07-23", dayStart, dayStart + 12 * 60 * 60 * 1000)
        state = ScreenTimeEngine.applyUsage(state, 30 * 60)

        state = ScreenTimeEngine.rollDayIfNeeded(
            state,
            policy,
            "2026-07-24",
            dayStart + 10 * 60 * 60 * 1000,
            dayStart + 13 * 60 * 60 * 1000
        )
        assertEquals(30L * 60, state.freeRemainingSeconds)

        state = ScreenTimeEngine.rollDayIfNeeded(
            state,
            policy,
            "2026-07-22",
            dayStart - 24 * 60 * 60 * 1000,
            dayStart + 11 * 60 * 60 * 1000
        )
        assertEquals("2026-07-23", state.dayKey)
        assertEquals(30L * 60, state.freeRemainingSeconds)
    }

    @Test
    fun selectedAppsOnlyBlockWhenTimeIsExhausted() {
        val policy = ScreenTimePolicy(
            restrictionsEnabled = true,
            blockedPackages = setOf("example.video")
        )
        val state = ScreenTimeEngine.initialState(policy, "2026-07-23", dayStart, dayStart)

        val blocked = ScreenTimeEngine.getEnforcementDecision(
            state, policy, "example.video", true, false, false
        )
        val allowed = ScreenTimeEngine.getEnforcementDecision(
            state, policy, "example.school", true, false, false
        )

        assertTrue(blocked.shouldBlock)
        assertFalse(blocked.shouldCharge)
        assertFalse(allowed.managed)
        assertFalse(allowed.shouldBlock)
    }

    @Test
    fun manualLockBlocksWithoutSelectedAppsOrForegroundSample() {
        val policy = ScreenTimePolicy(restrictionsEnabled = false)
        val state = ScreenTimeEngine.initialState(policy, "2026-07-23", dayStart, dayStart)

        val unselected = ScreenTimeEngine.getEnforcementDecision(
            state, policy, "example.school", true, false, true
        )
        val missingForeground = ScreenTimeEngine.getEnforcementDecision(
            state, policy, null, false, false, true
        )

        assertTrue(unselected.shouldBlock)
        assertFalse(unselected.shouldCharge)
        assertTrue(missingForeground.shouldBlock)
    }

    @Test
    fun manualLockRespectsSelectedAppRules() {
        val policy = ScreenTimePolicy(
            restrictionsEnabled = false,
            blockedPackages = setOf("example.video")
        )
        val state = ScreenTimeEngine.initialState(policy, "2026-07-23", dayStart, dayStart)

        val selected = ScreenTimeEngine.getEnforcementDecision(
            state, policy, "example.video", true, false, true
        )
        val unselected = ScreenTimeEngine.getEnforcementDecision(
            state, policy, "example.school", true, false, true
        )

        assertTrue(selected.shouldBlock)
        assertFalse(selected.shouldCharge)
        assertFalse(unselected.shouldBlock)
    }

    @Test
    fun allAppsModeBlocksTheLauncherAfterTimeRunsOut() {
        val policy = ScreenTimePolicy(
            restrictionsEnabled = true,
            blockEverything = true
        )
        val state = ScreenTimeEngine.initialState(policy, "2026-07-23", dayStart, dayStart)

        val launcher = ScreenTimeEngine.getEnforcementDecision(
            state = state,
            policy = policy,
            foregroundPackage = "com.example.launcher",
            blockEverythingCandidate = true,
            exemptFromAutomaticRules = false,
            forceLocked = false
        )

        assertTrue(launcher.shouldBlock)
        assertFalse(launcher.shouldCharge)
    }

    @Test
    fun allAppsModeNeverChargesParentExemptApps() {
        val policy = ScreenTimePolicy(
            dailyFreeSeconds = 60 * 60,
            restrictionsEnabled = true,
            blockEverything = true,
            exemptPackages = setOf("example.school")
        )
        val state = ScreenTimeEngine.initialState(policy, "2026-07-23", dayStart, dayStart)

        val entertainment = ScreenTimeEngine.getEnforcementDecision(
            state, policy, "example.video", true, false, false
        )
        val school = ScreenTimeEngine.getEnforcementDecision(
            state, policy, "example.school", true, false, false
        )

        assertTrue(entertainment.shouldCharge)
        assertFalse(school.managed)
        assertFalse(school.shouldCharge)
    }
}
