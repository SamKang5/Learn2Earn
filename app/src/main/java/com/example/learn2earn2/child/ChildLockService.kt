package com.example.learn2earn2.child

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.learn2earn2.R
import com.example.learn2earn2.child.time.EnforcementReason
import com.example.learn2earn2.child.time.ScreenTimeCommand
import com.example.learn2earn2.child.time.ScreenTimeCreditType
import com.example.learn2earn2.child.time.ScreenTimeEngine
import com.example.learn2earn2.child.time.ScreenTimePolicy
import com.example.learn2earn2.child.time.ScreenTimeState
import com.example.learn2earn2.child.time.ScreenTimeStore
import com.example.learn2earn2.emergency.EmergencyCallActivity
import com.example.learn2earn2.quiz.QuizRewardStore
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import java.util.Calendar
import java.time.ZonedDateTime

/** Keeps the child-side lock active even after the child app is no longer open. */
class ChildLockService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val db by lazy { ChildFirebaseSession.database(this) }
    private var childRef: DatabaseReference? = null
    private var childListener: ValueEventListener? = null
    private var parentLinkRemoved = false
    private var confirmedRemoteLink = false
    private var isLocked = false
    private var manualUnlockUntil = 0L
    private var manualLockUntil = 0L
    private var emergencyDialActive = false
    private var emergencyDialerPackage: String? = null
    private var dialerLaunchDeadline = 0L
    private var childActivityLaunchDeadline = 0L
    private var lastForegroundPackage: String? = null
    private var lastNonServiceForegroundPackage: String? = null
    private var overlay: View? = null
    private var overlayAdded = false
    private var overlayReason = EnforcementReason.NONE
    private lateinit var timeStore: ScreenTimeStore
    private lateinit var timeState: ScreenTimeState
    private var timePolicy = ScreenTimePolicy()
    private var manageablePackages: Set<String> = emptySet()
    private var launcherPackages: Set<String> = emptySet()
    private var sampledForegroundPackage: String? = null
    private var sampledScreenInteractive = false
    private var lastTickElapsedRealtime = 0L
    private var lastSavedElapsedRealtime = 0L
    private var usageRemainderMillis = 0L
    private var lastBlocking = false
    private var lastUsingPhone = false
    private var lastRuntimeSyncElapsedRealtime = 0L
    private var lastNotificationState: String? = null

    private val foregroundCheck = object : Runnable {
        override fun run() {
            tickEnforcement()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        timeStore = ScreenTimeStore(this)
        timePolicy = timeStore.loadPolicy()
        val today = currentDay()
        timeState = timeStore.loadState() ?: ScreenTimeEngine.initialState(
            timePolicy,
            today.key,
            today.startedAtEpochMillis,
            System.currentTimeMillis()
        )
        rollDayIfNeeded()
        manualUnlockUntil = timeStore.loadManualUnlockUntil()
        manualLockUntil = timeStore.loadManualLockUntil()
        isLocked = timeStore.loadForceLocked()
        if (isLocked && manualLockUntil == 0L) manualLockUntil = nextMidnight()
        launcherPackages = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        ).map { it.activityInfo.packageName }.toSet()
        refreshManageablePackages()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        lastNotificationState = notificationState()
        connectToParentRules()
        handler.post(foregroundCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EMERGENCY_DIAL_STARTED) {
            emergencyDialActive = true
            emergencyDialerPackage = intent.getStringExtra(EXTRA_DIALER_PACKAGE)
            dialerLaunchDeadline = System.currentTimeMillis() + DIALER_LAUNCH_GRACE_MS
            hideOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectToParentRules() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val parentId = prefs.getString(PARENT_ID, null) ?: run {
            stopSelf()
            return
        }
        val childId = prefs.getString(CHILD_ID, null) ?: run {
            stopSelf()
            return
        }
        val ref = db.getReference("users/$parentId/children/$childId")
        childRef = ref
        childListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    parentLinkRemoved = true
                    childRef = null
                    hideOverlay()
                    stopSelf()
                    return
                }
                if (!confirmedRemoteLink) {
                    confirmedRemoteLink = true
                    ref.child("installedApps").setValue(InstalledApps.userApps(this@ChildLockService).map { it.asFirebaseValue() })
                }
                isLocked = snapshot.child("isLocked").getValue(Boolean::class.java) ?: false
                manualUnlockUntil = (snapshot.child("manualUnlockUntil").value as? Number)?.toLong() ?: 0L
                manualLockUntil = (snapshot.child("manualLockUntil").value as? Number)?.toLong() ?: 0L
                if (isLocked && manualLockUntil == 0L) {
                    // Upgrade locks created before daily-expiry support.
                    manualLockUntil = nextMidnight()
                    ref.updateChildren(mapOf("manualLockUntil" to manualLockUntil))
                }
                val freeSeconds = (snapshot.child("dailyFreeSeconds").value as? Number)
                    ?.toLong()?.coerceIn(0, MAX_DAILY_FREE_SECONDS)
                    ?: (
                    snapshot.child("dailyFreeMinutes").value as? Number
                        ?: snapshot.child("screenTimeLimitMinutes").value as? Number
                    )?.toLong()?.coerceIn(0, 24 * 60 + 59)
                    ?.times(60)
                    ?: timePolicy.dailyFreeSeconds
                val rewardCapMinutes = (snapshot.child("dailyRewardCapMinutes").value as? Number)
                    ?.toLong()?.coerceAtLeast(0)
                val blockedPackages = if (snapshot.hasChild("blockedPackages")) {
                    snapshot.child("blockedPackages").children.mapNotNull { entry ->
                        entry.getValue(String::class.java)
                            ?: if (entry.getValue(Boolean::class.java) == true) entry.key else null
                    }.toSet()
                } else {
                    emptySet()
                }
                val exemptPackages = if (snapshot.hasChild("exemptPackages")) {
                    snapshot.child("exemptPackages").children.mapNotNull { entry ->
                        entry.getValue(String::class.java)
                            ?: if (entry.getValue(Boolean::class.java) == true) entry.key else null
                    }.toSet()
                } else {
                    emptySet()
                }
                val nextPolicy = ScreenTimePolicy(
                    dailyFreeSeconds = freeSeconds,
                    dailyRewardCapSeconds = rewardCapMinutes?.times(60)
                        ?: timePolicy.dailyRewardCapSeconds,
                    restrictionsEnabled = snapshot.child("restrictionsEnabled")
                        .getValue(Boolean::class.java) ?: timePolicy.restrictionsEnabled,
                    blockEverything = snapshot.child("blockEverything")
                        .getValue(Boolean::class.java) ?: timePolicy.blockEverything,
                    blockedPackages = blockedPackages,
                    exemptPackages = exemptPackages
                )
                timeState = ScreenTimeEngine.applyPolicy(timeState, nextPolicy)
                timePolicy = nextPolicy
                val appliedParentCommand = applyParentCommands(
                    commands = snapshot.child("timeCommands"),
                    ref = ref,
                    pendingBankCommandId = snapshot.child("runtime")
                        .child("pendingBankCommandId")
                        .getValue(String::class.java)
                )
                timeStore.savePolicy(timePolicy)
                timeStore.saveState(timeState)
                timeStore.saveOverrides(manualUnlockUntil, manualLockUntil, isLocked)
                if (appliedParentCommand) {
                    // A command changes the local balance before the next normal runtime
                    // interval. Publish it now so the parent never falls back to stale
                    // runtime values when the queued-command preview is cleared.
                    persistState(forceRuntimeSync = true)
                }
                refreshManageablePackages()
                handler.post { tickEnforcement() }
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) = Unit
        }
        ref.addValueEventListener(childListener!!)
    }

    private fun tickEnforcement() {
        val nowEpochMillis = System.currentTimeMillis()
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        expireManualLockIfNeeded(nowEpochMillis)
        rollDayIfNeeded(nowEpochMillis)
        applyPendingQuizRewards()

        val elapsedMillis = if (lastTickElapsedRealtime == 0L) {
            0L
        } else {
            (nowElapsedRealtime - lastTickElapsedRealtime).coerceIn(0L, MAX_SAMPLE_INTERVAL_MS)
        }
        val forceLockedForSample = forceLockActive(nowEpochMillis)
        val overrideForSample = overrideActive(nowEpochMillis)
        if (
            elapsedMillis > 0 &&
            sampledScreenInteractive &&
            sampledForegroundPackage != null &&
            !forceLockedForSample &&
            !overrideForSample
        ) {
            val sampledDecision = ScreenTimeEngine.getEnforcementDecision(
                state = timeState,
                policy = timePolicy,
                foregroundPackage = sampledForegroundPackage,
                blockEverythingCandidate = isBlockEverythingCandidate(sampledForegroundPackage),
                exemptFromAutomaticRules = isAutomaticExempt(sampledForegroundPackage),
                forceLocked = false
            )
            if (sampledDecision.shouldCharge) {
                usageRemainderMillis += elapsedMillis
                val seconds = usageRemainderMillis / 1000
                usageRemainderMillis %= 1000
                if (seconds > 0) timeState = ScreenTimeEngine.applyUsage(timeState, seconds)
            }
        }
        lastTickElapsedRealtime = nowElapsedRealtime

        val rawForeground = foregroundPackage()
        if (rawForeground != null && rawForeground != packageName) {
            lastNonServiceForegroundPackage = rawForeground
        }
        // Adding our overlay can generate an accessibility event for Learn2Earn itself.
        // While the overlay is visible, keep enforcing against the app beneath it instead
        // of treating the overlay's own window as an exempt Learn2Earn screen.
        val foreground = if (overlayAdded &&
            (rawForeground == null || rawForeground == packageName)
        ) {
            lastNonServiceForegroundPackage ?: rawForeground
        } else {
            rawForeground
        }
        sampledForegroundPackage = foreground
        sampledScreenInteractive = isScreenInteractive()
        val forceLocked = forceLockActive(nowEpochMillis) && foreground != packageName
        val decision = ScreenTimeEngine.getEnforcementDecision(
            state = timeState,
            policy = timePolicy,
            foregroundPackage = foreground,
            blockEverythingCandidate = isBlockEverythingCandidate(foreground),
            exemptFromAutomaticRules = isAutomaticExempt(foreground),
            forceLocked = forceLocked
        )
        if (!hasOverlayPermission() || nowEpochMillis < childActivityLaunchDeadline ||
            (overrideActive(nowEpochMillis) && !forceLocked)) {
            hideOverlay()
        } else if (emergencyDialActive) {
            val allowedDialerPackage = emergencyDialerPackage
            when {
                allowedDialerPackage != null && foreground == allowedDialerPackage -> hideOverlay()
                nowEpochMillis < dialerLaunchDeadline -> hideOverlay()
                else -> {
                    emergencyDialActive = false
                    emergencyDialerPackage = null
                    if (decision.shouldBlock) showOverlay(decision.reason) else hideOverlay()
                }
            }
        } else if (decision.shouldBlock) {
            showOverlay(decision.reason)
        } else {
            hideOverlay()
        }

        // Usage and enforcement are separate parent-facing signals. A child can be
        // actively using Learn2Earn (or an exempt app) while their timer is not charging.
        val usingPhone = sampledScreenInteractive && foreground != null
        val overrideAllowsUse = overrideActive(nowEpochMillis) && !forceLocked
        val emergencyDialerAllowsUse = emergencyDialActive &&
            (
                (emergencyDialerPackage != null && foreground == emergencyDialerPackage) ||
                    nowEpochMillis < dialerLaunchDeadline
                )
        val appLockActive = decision.shouldBlock && hasOverlayPermission() &&
            !overrideAllowsUse && !emergencyDialerAllowsUse
        val blockingChanged = lastBlocking != appLockActive
        val usageChanged = lastUsingPhone != usingPhone
        lastBlocking = appLockActive
        lastUsingPhone = usingPhone
        updateNotificationIfNeeded()
        if (blockingChanged || usageChanged || nowElapsedRealtime - lastSavedElapsedRealtime >= SAVE_INTERVAL_MS) {
            // Lock/usage indicators are parent-facing state, so don't make the parent wait
            // for the normal periodic balance sync after either one changes.
            persistState(
                nowElapsedRealtime = nowElapsedRealtime,
                forceRuntimeSync = blockingChanged || usageChanged
            )
        }
    }

    private fun expireManualLockIfNeeded(nowEpochMillis: Long) {
        if (isLocked && manualLockUntil > 0L && manualLockUntil <= nowEpochMillis) {
            isLocked = false
            manualLockUntil = 0L
            childRef?.updateChildren(mapOf("isLocked" to false, "manualLockUntil" to null))
            timeStore.saveOverrides(manualUnlockUntil, manualLockUntil, isLocked)
        }
    }

    private fun rollDayIfNeeded(nowEpochMillis: Long = System.currentTimeMillis()) {
        val today = currentDay()
        val previousDay = timeState.dayKey
        timeState = ScreenTimeEngine.rollDayIfNeeded(
            state = timeState,
            policy = timePolicy,
            currentDayKey = today.key,
            currentDayStartedAtEpochMillis = today.startedAtEpochMillis,
            nowEpochMillis = nowEpochMillis
        )
        if (timeState.dayKey != previousDay) timeStore.saveState(timeState)
    }

    private fun persistState(
        nowElapsedRealtime: Long = SystemClock.elapsedRealtime(),
        forceRuntimeSync: Boolean = false
    ) {
        timeStore.saveState(timeState)
        timeStore.savePolicy(timePolicy)
        timeStore.saveOverrides(manualUnlockUntil, manualLockUntil, isLocked)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification())
        if (!parentLinkRemoved && childRef != null &&
            (forceRuntimeSync || nowElapsedRealtime - lastRuntimeSyncElapsedRealtime >= RUNTIME_SYNC_INTERVAL_MS)
        ) {
            childRef?.child("runtime")?.updateChildren(
                mapOf(
                    "dayKey" to timeState.dayKey,
                    "freeRemainingSeconds" to timeState.freeRemainingSeconds,
                    "todayBonusRemainingSeconds" to timeState.todayBonusRemainingSeconds,
                    "earnedBalanceSeconds" to timeState.earnedBalanceSeconds,
                    "earnedTodaySeconds" to timeState.earnedTodaySeconds,
                    "managedUsageTodaySeconds" to timeState.managedUsageTodaySeconds,
                    "totalRemainingSeconds" to timeState.totalRemainingSeconds,
                    "appLockState" to if (lastBlocking) "LOCKED" else "UNLOCKED",
                    "isUsingPhone" to lastUsingPhone,
                    "updatedAt" to com.google.firebase.database.ServerValue.TIMESTAMP
                )
            )
            lastRuntimeSyncElapsedRealtime = nowElapsedRealtime
        }
        lastSavedElapsedRealtime = nowElapsedRealtime
    }

    private fun applyParentCommands(
        commands: DataSnapshot,
        ref: DatabaseReference,
        pendingBankCommandId: String?
    ): Boolean {
        var appliedPendingBankCommand = false
        var appliedCommand = false
        commands.children.forEach { commandSnapshot ->
            val id = commandSnapshot.key ?: return@forEach
            if (id in timeState.processedCommandIds) return@forEach
            val type = when (commandSnapshot.child("type").getValue(String::class.java)) {
                "GRANT_TODAY" -> ScreenTimeCreditType.GRANT_TODAY
                "ADJUST_TODAY" -> ScreenTimeCreditType.ADJUST_TODAY
                "GRANT_BANK" -> ScreenTimeCreditType.GRANT_BANK
                "SET_BANK" -> ScreenTimeCreditType.SET_BANK
                "ADJUST_BANK" -> ScreenTimeCreditType.ADJUST_BANK
                else -> return@forEach
            }
            val seconds = when (type) {
                ScreenTimeCreditType.GRANT_TODAY,
                ScreenTimeCreditType.GRANT_BANK -> (commandSnapshot.child("minutes").value as? Number)
                    ?.toLong()?.coerceIn(0, 24 * 60)?.times(60) ?: return@forEach
                ScreenTimeCreditType.ADJUST_TODAY,
                ScreenTimeCreditType.ADJUST_BANK -> (commandSnapshot.child("seconds").value as? Number)
                    ?.toLong()?.coerceIn(-MAX_TIME_COMMAND_SECONDS, MAX_TIME_COMMAND_SECONDS) ?: return@forEach
                ScreenTimeCreditType.SET_BANK -> (commandSnapshot.child("seconds").value as? Number)
                    ?.toLong()?.coerceIn(0, MAX_TIME_COMMAND_SECONDS) ?: return@forEach
                ScreenTimeCreditType.QUIZ_REWARD -> return@forEach
            }
            val createdAt = (commandSnapshot.child("createdAt").value as? Number)?.toLong()
            val expiresAt = (commandSnapshot.child("expiresAt").value as? Number)?.toLong()
            val todayStartedAt = currentDay().startedAtEpochMillis
            val expiredTodayAdjustment = type in setOf(
                ScreenTimeCreditType.GRANT_TODAY,
                ScreenTimeCreditType.ADJUST_TODAY
            ) && (
                (expiresAt != null && expiresAt <= System.currentTimeMillis()) ||
                    (expiresAt == null && createdAt != null && createdAt < todayStartedAt)
            )
            val result = ScreenTimeEngine.applyCommand(
                state = timeState,
                policy = timePolicy,
                command = ScreenTimeCommand(
                    id,
                    type,
                    if (expiredTodayAdjustment) 0 else seconds
                )
            )
            timeState = result.state
            appliedCommand = true
            if (id == pendingBankCommandId) appliedPendingBankCommand = true
            ref.child("timeCommandAcks").child(id).updateChildren(
                mapOf(
                    "appliedSeconds" to result.appliedSeconds,
                    "appliedAt" to com.google.firebase.database.ServerValue.TIMESTAMP
                )
            )
        }
        if (appliedPendingBankCommand) {
            // The parent may display its intended balance while this command is queued.
            // Only clear that preview after this exact command has been applied locally.
            ref.child("runtime").updateChildren(
                mapOf(
                    "pendingBankCommandId" to null,
                    "pendingBankSeconds" to null
                )
            )
        }
        return appliedCommand
    }

    private fun applyPendingQuizRewards() {
        QuizRewardStore.pendingRewards(this).forEach { reward ->
            val rewardId = "quiz:${reward.attemptId}"
            val requestedSeconds = reward.minutes.toLong() * 60
            // The secure D1 service already enforces the family/day cap. Treat
            // its cumulative delta as banked time so a second local cap cannot
            // discard server-authorized minutes recovered after an outage.
            val serverAuthorized = reward.attemptId.startsWith("server-total:") ||
                reward.attemptId.startsWith("server:")
            val result = if (serverAuthorized) {
                ScreenTimeEngine.applyCommand(
                    state = timeState,
                    policy = timePolicy,
                    command = ScreenTimeCommand(
                        rewardId,
                        ScreenTimeCreditType.GRANT_BANK,
                        requestedSeconds
                    )
                )
            } else {
                ScreenTimeEngine.applyQuizReward(
                    state = timeState,
                    policy = timePolicy,
                    rewardId = rewardId,
                    requestedSeconds = requestedSeconds
                )
            }
            timeState = result.state
            // Save before acknowledging. If the process dies between writes,
            // the processed reward ID prevents a second credit on restart.
            timeStore.saveState(timeState)
            QuizRewardStore.acknowledge(this, reward)
        }
    }

    private fun forceLockActive(nowEpochMillis: Long): Boolean =
        isLocked && (manualLockUntil == 0L || manualLockUntil > nowEpochMillis)

    private fun overrideActive(nowEpochMillis: Long): Boolean =
        manualUnlockUntil > nowEpochMillis

    private fun refreshManageablePackages() {
        manageablePackages = InstalledApps.userApps(this).map { it.packageName }.toSet()
    }

    /**
     * "All apps" is intended to lock the device, not merely the installed third-party
     * apps. The launcher must therefore be coverable too; otherwise opening Learn2Earn
     * and pressing Home is a permanent escape route after time runs out.
     */
    private fun isBlockEverythingCandidate(packageName: String?): Boolean =
        packageName != null && packageName != this.packageName && packageName != "com.android.systemui"

    private fun isAutomaticExempt(packageName: String?): Boolean {
        if (packageName == null || packageName == this.packageName) return true
        if (packageName == "com.android.systemui") return true
        return false
    }

    private fun isScreenInteractive(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    private data class DaySnapshot(val key: String, val startedAtEpochMillis: Long)

    private fun currentDay(): DaySnapshot {
        val now = ZonedDateTime.now()
        val date = now.toLocalDate()
        return DaySnapshot(
            key = date.toString(),
            startedAtEpochMillis = date.atStartOfDay(now.zone).toInstant().toEpochMilli()
        )
    }

    private fun foregroundPackage(): String? {
        // Accessibility reports a new event whenever the active window changes. Unlike
        // UsageStats, it is live and does not need a freshness timeout while an app remains
        // open. Using UsageStats after five seconds was what made TikTok stop charging.
        val accessibilityPackage = ForegroundAppTracker.lastKnownPackage()
        if (accessibilityPackage != null && accessibilityPackage != packageName) {
            return accessibilityPackage
        }
        // Accessibility events for our overlay report Learn2Earn as foreground. Do not
        // let that self-report mask the app beneath it or a later app switch.
        if (!hasUsageAccess()) return accessibilityPackage
        val usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usageStats.queryEvents(end - 5_000, end)
        val event = android.app.usage.UsageEvents.Event()
        var lastPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // minSdk is Android 10, where ACTIVITY_RESUMED covers newly opened apps
            // and apps resumed from Recents.
            if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPackage = event.packageName
            }
        }
        if (lastPackage != null) {
            lastForegroundPackage = lastPackage
            return lastForegroundPackage
        }

        // Do not keep an old cached package forever. Some devices omit the foreground
        // event while switching to an already-running task; their usage-stat timestamp
        // still identifies the app used most recently.
        val mostRecentlyUsed = usageStats.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY,
            end - 60 * 60 * 1000,
            end
        ).maxByOrNull { it.lastTimeUsed }?.packageName
        if (mostRecentlyUsed != null) lastForegroundPackage = mostRecentlyUsed
        return lastForegroundPackage ?: accessibilityPackage
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)

    private fun showOverlay(reason: EnforcementReason) {
        if (overlayAdded && overlayReason == reason) return
        if (overlayAdded) hideOverlay()
        overlayReason = reason
        val exhausted = reason == EnforcementReason.TIME_EXHAUSTED
        val density = resources.displayMetrics.density
        val padding = (32 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.child_navy))
            setPadding(padding, padding, padding, padding)
            addView(ImageView(context).apply {
                setImageResource(if (exhausted) R.drawable.ic_child_earn else R.drawable.ic_child_shield)
                setBackgroundResource(R.drawable.bg_child_icon_tile)
                setPadding(18, 18, 18, 18)
            }, LinearLayout.LayoutParams((72 * density).toInt(), (72 * density).toInt()))
            addView(TextView(context).apply {
                text = if (exhausted) "Time is up" else "Device locked"
                setTextColor(getColor(R.color.child_on_dark))
                textSize = 29f
                gravity = Gravity.CENTER
                setPadding(0, (22 * density).toInt(), 0, 0)
            })
            addView(TextView(context).apply {
                text = if (exhausted) {
                    "Complete a quiz or ask your parent."
                } else {
                    "Ask your parent to unlock."
                }
                setTextColor(getColor(R.color.child_on_dark_muted))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            })
            if (exhausted) {
                addView(lockActionButton("Open Learn2Earn to Earn Time", android.R.drawable.ic_menu_edit) {
                    openChildActivity(Intent(this@ChildLockService, ChildMainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(ChildMainActivity.EXTRA_OPEN_EARN, true)
                    })
                })
            }
            addView(lockActionButton("Emergency", android.R.drawable.ic_menu_call) {
                openChildActivity(Intent(this@ChildLockService, EmergencyCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.OPAQUE
        ).apply { gravity = Gravity.CENTER }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(content, params)
            overlay = content
            overlayAdded = true
        } catch (_: WindowManager.BadTokenException) {
            overlay = null
            overlayAdded = false
        }
    }

    private fun lockActionButton(label: String, icon: Int, click: () -> Unit) = android.widget.Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(getColor(R.color.child_ink))
        setBackgroundResource(R.drawable.bg_child_secondary)
        setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawablePadding = 14
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (54 * resources.displayMetrics.density).toInt()
        ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() }
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: IllegalArgumentException) {
            // The system already removed the window.
        } finally {
            overlay = null
            overlayAdded = false
            overlayReason = EnforcementReason.NONE
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Child protection", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun nextMidnight(): Long = Calendar.getInstance().run {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private fun notification(): Notification {
        val freeToday = timeState.freeRemainingSeconds + timeState.todayBonusRemainingSeconds
        val detail = buildString {
            append("Free time left: ${formatTime(freeToday)}")
            if (timeState.todayBonusRemainingSeconds > 0) {
                append("\nParent time today: ${formatTime(timeState.todayBonusRemainingSeconds)}")
            }
            append("\nTime bank: ${formatTime(timeState.earnedBalanceSeconds)}")
            append("\nTotal time left: ${formatTime(timeState.totalRemainingSeconds)}")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Learn2Earn protection is active")
            .setContentText("Time left: ${formatTime(timeState.totalRemainingSeconds)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .build()
    }

    private fun updateNotificationIfNeeded() {
        val nextState = notificationState()
        if (nextState == lastNotificationState) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification())
        lastNotificationState = nextState
    }

    private fun notificationState(): String = listOf(
        timeState.freeRemainingSeconds,
        timeState.todayBonusRemainingSeconds,
        timeState.earnedBalanceSeconds
    ).joinToString(":")

    private fun formatTime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3_600
        val minutes = (safe % 3_600) / 60
        val remainingSeconds = safe % 60
        return if (hours > 0) {
            "${hours}h ${minutes}m ${remainingSeconds}s"
        } else {
            "${minutes}m ${remainingSeconds}s"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::timeStore.isInitialized && !parentLinkRemoved && hasActiveLocalPairing()) persistState()
        childListener?.let { childRef?.removeEventListener(it) }
        hideOverlay()
        super.onDestroy()
    }

    private fun openChildActivity(intent: Intent) {
        childActivityLaunchDeadline = System.currentTimeMillis() + CHILD_ACTIVITY_LAUNCH_GRACE_MS
        ForegroundAppTracker.update(packageName)
        hideOverlay()
        startActivity(intent)
    }

    private fun hasActiveLocalPairing(): Boolean =
        !getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PARENT_ID, null).isNullOrBlank() &&
            !getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHILD_ID, null).isNullOrBlank()

    companion object {
        private const val PREFS = "learn2earn_child"
        const val PARENT_ID = "parent_device_id"
        const val CHILD_ID = "child_device_id"
        private const val CHANNEL_ID = "child_protection"
        private const val ACTION_EMERGENCY_DIAL_STARTED = "com.example.learn2earn2.EMERGENCY_DIAL_STARTED"
        private const val EXTRA_DIALER_PACKAGE = "dialer_package"
        private const val NOTIFICATION_ID = 102
        private const val CHECK_INTERVAL_MS = 700L
        private const val MAX_SAMPLE_INTERVAL_MS = 2_000L
        private const val SAVE_INTERVAL_MS = 15_000L
        private const val DIALER_LAUNCH_GRACE_MS = 2_000L
        private const val CHILD_ACTIVITY_LAUNCH_GRACE_MS = 2_000L
        // A visible debug counter is useful during setup, while still keeping Firebase writes
        // comfortably within the free tier for a small family deployment.
        private const val RUNTIME_SYNC_INTERVAL_MS = 15_000L
        private const val MAX_DAILY_FREE_SECONDS = 24L * 60 * 60 + 59L * 60
        private const val MAX_TIME_COMMAND_SECONDS = 365L * 24 * 60 * 60

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ChildLockService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChildLockService::class.java))
        }

        fun allowEmergencyDialer(context: Context, dialerPackage: String) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, ChildLockService::class.java).apply {
                    action = ACTION_EMERGENCY_DIAL_STARTED
                    putExtra(EXTRA_DIALER_PACKAGE, dialerPackage)
                }
            )
        }
    }
}
