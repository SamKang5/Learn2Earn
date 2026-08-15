package com.example.learn2earn2.child

import android.content.Context
import android.content.Intent
import android.app.AppOpsManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.example.learn2earn2.R
import com.example.learn2earn2.account.GuestApproval
import com.example.learn2earn2.account.GuestApprovalHandoff
import com.example.learn2earn2.account.PairingIdentityPolicy
import com.example.learn2earn2.child.time.ScreenTimeStore
import com.example.learn2earn2.quiz.LearningApi
import com.example.learn2earn2.quiz.LearningApiResult
import com.example.learn2earn2.ui.KeyboardDismissActivity
import com.example.learn2earn2.ui.AppCredits
import com.example.learn2earn2.onboarding.RoleSelectionActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import java.util.Locale

class ChildMainActivity : KeyboardDismissActivity() {

    private val auth by lazy { ChildFirebaseSession.auth(this) }
    private val db by lazy { ChildFirebaseSession.database(this) }
    private lateinit var llUnpaired: View
    private lateinit var llPaired: View
    private lateinit var tvStatus: TextView
    private lateinit var tvScreentimeDebug: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvFreeTime: TextView
    private lateinit var tvTimeBank: TextView
    private lateinit var tvProtectionStatus: TextView
    private lateinit var tvAppSyncStatus: TextView
    private lateinit var btnLink: Button
    private lateinit var bottomNav: BottomNavigationView
    private var listener: ValueEventListener? = null
    private var childStatusRef: DatabaseReference? = null
    private var confirmedRemoteLink = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isLinking = false
    private var linkAttemptGeneration = 0L
    private var childDeviceId = "unknown_child"
    private var parentAccessActive = false
    private var isCheckingRoleSwitch = false

    private val timeRefresh = object : Runnable {
        override fun run() {
            refreshTimeStatus()
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(target.paddingLeft, bars.top, target.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)

        findViewById<TextView>(R.id.tv_child_app_title).setOnClickListener { AppCredits.show(this) }

        llUnpaired = findViewById(R.id.ll_unpaired_view)
        llPaired = findViewById<View>(R.id.ll_paired_view).also {
            it.isVerticalScrollBarEnabled = false
            it.isFocusableInTouchMode = true
        }
        tvStatus = findViewById(R.id.tv_child_status)
        tvScreentimeDebug = findViewById(R.id.tv_screentime_debug)
        tvTotalTime = findViewById(R.id.tv_total_time)
        tvFreeTime = findViewById(R.id.tv_free_time)
        tvTimeBank = findViewById(R.id.tv_time_bank)
        tvProtectionStatus = findViewById(R.id.tv_protection_status)
        tvAppSyncStatus = findViewById(R.id.tv_app_sync_status)
        val pairingDigits = listOf(
            R.id.pairing_digit_1, R.id.pairing_digit_2, R.id.pairing_digit_3,
            R.id.pairing_digit_4, R.id.pairing_digit_5, R.id.pairing_digit_6
        ).map { findViewById<EditText>(it) }
        val pairingCodeFields = findViewById<View>(R.id.ll_pairing_code_fields)
        val pairingHints = pairingDigits.map { it.hint }
        pairingDigits.forEachIndexed { index, field ->
            field.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    pairingDigits.forEach { it.hint = "" }
                } else {
                    field.post {
                        if (pairingDigits.none { it.hasFocus() } && pairingDigits.all { it.text.isEmpty() }) {
                            pairingDigits.forEachIndexed { hintIndex, digit ->
                                if (digit.text.isEmpty()) digit.hint = pairingHints[hintIndex]
                            }
                        }
                    }
                }
            }
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!s.isNullOrEmpty() && index < pairingDigits.lastIndex) pairingDigits[index + 1].requestFocus()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            field.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL && event.action == android.view.KeyEvent.ACTION_DOWN && field.text.isEmpty() && index > 0) {
                    pairingDigits[index - 1].requestFocus()
                    pairingDigits[index - 1].setSelection(pairingDigits[index - 1].text.length)
                    true
                } else false
            }
        }
        btnLink = findViewById(R.id.btn_link)
        bottomNav = findViewById(R.id.child_bottom_nav)
        bottomNav.selectedItemId = if (intent.getBooleanExtra(EXTRA_OPEN_EARN, false)) {
            R.id.nav_child_earn
        } else {
            R.id.nav_child_wallet
        }
        bottomNav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_child_wallet -> {
                    showChildTab(false)
                    true
                }
                R.id.nav_child_earn -> {
                    showChildTab(true)
                    true
                }
                else -> false
            }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.child_earn_container, ChildEarnFragment())
                .commit()
        }
        ensureChildIdentity()
        getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
            .getString("pending_pairing_code", null)
            ?.takeIf { it.length == 6 }
            ?.forEachIndexed { index, character ->
                pairingDigits[index].setText(character.toString())
            }
        val btnResetRole = findViewById<View>(R.id.btn_reset_role)
        findViewById<Button>(R.id.btn_enable_protection).setOnClickListener {
            openNextRequiredProtectionSetting()
        }
        findViewById<Button>(R.id.btn_refresh_parent_apps).setOnClickListener {
            childStatusRef?.let(::syncInstalledApps) ?: run {
                Toast.makeText(this, "Pair this device before sending apps.", Toast.LENGTH_SHORT).show()
            }
        }
        val androidDeviceId = Settings.Secure.getString(
            contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "unknown_child"
        val prefs = getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
        val savedParentId = prefs.getString("parent_device_id", null)
        childDeviceId = prefs.getString(ChildLockService.CHILD_ID, null)
            ?: auth.currentUser?.uid
            ?: androidDeviceId

        if (savedParentId != null) {
            prefs.edit()
                .putString(ChildLockService.CHILD_ID, childDeviceId)
                .putString("auth_uid", auth.currentUser?.uid)
                .apply()
            setupPairedState(savedParentId, childDeviceId)
        } else {
            showUnpaired()
        }

        btnLink.setOnClickListener {
            if (auth.currentUser == null) {
                Toast.makeText(this, "Preparing secure child identity. Try again in a moment.", Toast.LENGTH_SHORT).show()
                ensureChildIdentity()
                return@setOnClickListener
            }
            val code = pairingDigits.joinToString("") { it.text.toString() }.trim().uppercase()
            if (code.length != 6) {
                Toast.makeText(this, "Enter the 6-character code", Toast.LENGTH_SHORT).show()
                pairingCodeFields.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake))
                return@setOnClickListener
            }

            val attempt = ++linkAttemptGeneration
            isLinking = true
            btnLink.isEnabled = false
            btnLink.text = getString(R.string.linking)

            val timeoutRunnable = Runnable {
                if (isCurrentLinkAttempt(attempt)) {
                    failPairing(attempt, "Connection timeout. Check Firebase / Internet.")
                }
            }
            mainHandler.postDelayed(timeoutRunnable, 55_000)

            val codeRef = db.getReference("pairing_codes/$code")
            childDeviceId = auth.currentUser?.uid ?: childDeviceId

            codeRef.get().addOnCompleteListener { task ->
                if (!isCurrentLinkAttempt(attempt)) return@addOnCompleteListener
                if (!task.isSuccessful) {
                    failPairing(
                        attempt,
                        "Could not check the pairing code. Check Internet and Firebase setup."
                    )
                    return@addOnCompleteListener
                }
                val snapshot = task.result
                val expiresAt = (snapshot.child("expiresAt").value as? Number)?.toLong()
                val parentDeviceId = (snapshot.value as? String)
                    ?: snapshot.child("parentUid").getValue(String::class.java)
                if (parentDeviceId.isNullOrBlank() ||
                    (expiresAt != null && expiresAt < System.currentTimeMillis())
                ) {
                    failPairing(attempt, "Invalid or expired code.")
                    pairingCodeFields.startAnimation(
                        AnimationUtils.loadAnimation(this, R.anim.shake)
                    )
                    return@addOnCompleteListener
                }
                val secureLearning = snapshot.child("secureLearning")
                    .getValue(Boolean::class.java) ?: false
                if (!secureLearning) {
                    val guestApprovalHash = GuestApprovalHandoff.select(
                        snapshot.child(GuestApproval.KEY_HASH).getValue(String::class.java),
                        GuestApprovalHandoff.loadLocal(this)
                    )
                    claimFirebaseCode(
                        parentDeviceId, code, codeRef, prefs, timeoutRunnable, false, attempt,
                        guestApprovalHash
                    )
                    return@addOnCompleteListener
                }
                val guestApprovalHash = GuestApprovalHandoff.select(
                    snapshot.child(GuestApproval.KEY_HASH).getValue(String::class.java),
                    GuestApprovalHandoff.loadLocal(this)
                )
                LearningApi.claimPairingCode(this, code, auth) { result ->
                    if (!isCurrentLinkAttempt(attempt)) return@claimPairingCode
                    when (result) {
                        is LearningApiResult.Success -> {
                            if (result.body.optString("parentUid") != parentDeviceId) {
                                failPairing(attempt, "Secure pairing returned an invalid parent.")
                            } else {
                                prefs.edit().putString("pending_parent_id", parentDeviceId)
                                    .putString("pending_pairing_code", code).apply()
                                claimFirebaseCode(
                                    parentDeviceId, code, codeRef, prefs, timeoutRunnable, true, attempt,
                                    guestApprovalHash
                                )
                            }
                        }
                        is LearningApiResult.Failure -> failPairing(attempt, result.message)
                    }
                }
            }
        }

        btnResetRole.setOnClickListener { requestRoleSwitch() }
    }

    private fun linkChildToParent(
        parentId: String,
        code: String,
        codeRef: DatabaseReference,
        prefs: android.content.SharedPreferences,
        timeoutRunnable: Runnable,
        secureLearning: Boolean,
        attempt: Long,
        guestApprovalHash: String?
    ) {
        val childRef = db.getReference("users/$parentId/children/$childDeviceId")
        childRef.get().addOnCompleteListener { readTask ->
            if (!isCurrentLinkAttempt(attempt)) return@addOnCompleteListener
            if (!readTask.isSuccessful) {
                clearPendingPairing(prefs)
                failPairing(
                    attempt,
                    "Parent record could not be checked. Check connection and Firebase rules."
                )
                return@addOnCompleteListener
            }
            val existing = readTask.result
            if (existing.exists()) {
                val existingAuthUid = existing.child("authUid").getValue(String::class.java)
                val existingSecureLearning = existing.child("secureLearningEnabled")
                    .getValue(Boolean::class.java) ?: false
                if (PairingIdentityPolicy.childOwnsPath(auth.currentUser?.uid, childDeviceId, existingAuthUid) &&
                    existingSecureLearning == secureLearning
                ) {
                    finishPairing(
                        parentId,
                        codeRef,
                        prefs,
                        timeoutRunnable,
                        secureLearning,
                        GuestApprovalHandoff.select(
                            existing.child(GuestApproval.KEY_HASH).getValue(String::class.java),
                            guestApprovalHash
                        )
                    )
                } else if (PairingIdentityPolicy.childOwnsPath(auth.currentUser?.uid, childDeviceId, existingAuthUid)) {
                    clearPendingPairing(prefs)
                    failPairing(
                        attempt,
                        "Ask the parent to unpair this older link, then create a new secure code."
                    )
                } else {
                    clearPendingPairing(prefs)
                    failPairing(attempt, "That child record belongs to another device.")
                }
                return@addOnCompleteListener
            }
            childRef.updateChildren(
                mapOf(
                    "name" to "Kid Phone",
                    "isLocked" to false,
                    "authUid" to auth.currentUser?.uid,
                    "pairedAt" to com.google.firebase.database.ServerValue.TIMESTAMP,
                    "dailyRewardCapMinutes" to 120,
                    "pairingCode" to code,
                    "secureLearningEnabled" to secureLearning
                ) + GuestApprovalHandoff.firebaseFields(guestApprovalHash)
            ).addOnCompleteListener { writeTask ->
                if (!isCurrentLinkAttempt(attempt)) return@addOnCompleteListener
                if (writeTask.isSuccessful) {
                    finishPairing(
                        parentId, codeRef, prefs, timeoutRunnable, secureLearning, guestApprovalHash
                    )
                } else {
                    clearPendingPairing(prefs)
                    failPairing(
                        attempt,
                        "Parent record could not be created. Check Firebase rules."
                    )
                }
            }
        }
    }

    private fun finishPairing(
        parentId: String,
        codeRef: DatabaseReference,
        prefs: android.content.SharedPreferences,
        timeoutRunnable: Runnable,
        secureLearning: Boolean,
        guestApprovalHash: String?
    ) {
        isLinking = false
        mainHandler.removeCallbacks(timeoutRunnable)
        val saved = prefs.edit()
            .putString(ChildLockService.PARENT_ID, parentId)
            .putString(ChildLockService.CHILD_ID, childDeviceId)
            .putString("auth_uid", auth.currentUser?.uid)
            .putBoolean("secure_learning_enabled", secureLearning)
            .remove("pending_parent_id")
            .remove("pending_pairing_code")
            .apply {
                if (!guestApprovalHash.isNullOrBlank()) {
                    putString(GuestApproval.KEY_HASH, guestApprovalHash)
                }
            }
            .commit()
        if (!saved) {
            btnLink.isEnabled = true
            btnLink.text = "Connect"
            Toast.makeText(this, "Could not save pairing on this device. Try again.", Toast.LENGTH_LONG).show()
            return
        }
        codeRef.removeValue()
        setupPairedState(parentId, childDeviceId)
        Toast.makeText(this, "Linked!", Toast.LENGTH_SHORT).show()
    }

    private fun claimFirebaseCode(
        parentId: String,
        code: String,
        codeRef: DatabaseReference,
        prefs: android.content.SharedPreferences,
        timeoutRunnable: Runnable,
        secureLearning: Boolean,
        attempt: Long,
        guestApprovalHash: String?
    ) {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            clearPendingPairing(prefs)
            failPairing(attempt, "Child identity is not ready. Try again.")
            return
        }
        codeRef.get().addOnCompleteListener { readTask ->
            if (!isCurrentLinkAttempt(attempt)) return@addOnCompleteListener
            if (!readTask.isSuccessful) {
                clearPendingPairing(prefs)
                failPairing(attempt, "Could not verify the pairing code in Firebase.")
                return@addOnCompleteListener
            }
            val snapshot = readTask.result
            val storedParent = (snapshot.value as? String)
                ?: snapshot.child("parentUid").getValue(String::class.java)
            val expiresAt = (snapshot.child("expiresAt").value as? Number)?.toLong()
            if (storedParent != parentId ||
                (expiresAt != null && expiresAt < System.currentTimeMillis())
            ) {
                clearPendingPairing(prefs)
                failPairing(attempt, "This pairing code is invalid or expired.")
                return@addOnCompleteListener
            }
            codeRef.child("claimedBy").setValue(uid).addOnCompleteListener { claimTask ->
                if (!isCurrentLinkAttempt(attempt)) return@addOnCompleteListener
                if (!claimTask.isSuccessful) {
                    clearPendingPairing(prefs)
                    failPairing(attempt, "This pairing code was already claimed or expired.")
                    return@addOnCompleteListener
                }
                linkChildToParent(
                    parentId, code, codeRef, prefs, timeoutRunnable, secureLearning, attempt,
                    GuestApprovalHandoff.select(
                        snapshot.child(GuestApproval.KEY_HASH).getValue(String::class.java),
                        guestApprovalHash
                    )
                )
            }
        }
    }

    private fun clearPendingPairing(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .remove("pending_parent_id")
            .remove("pending_pairing_code")
            .apply()
    }

    private fun isCurrentLinkAttempt(attempt: Long): Boolean =
        isLinking && linkAttemptGeneration == attempt

    private fun failPairing(attempt: Long, message: String) {
        if (linkAttemptGeneration != attempt) return
        isLinking = false
        btnLink.isEnabled = true
        btnLink.text = "Connect"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setupPairedState(parentId: String, childId: String) {
        llUnpaired.visibility = View.GONE
        llPaired.visibility = View.VISIBLE
        bottomNav.visibility = View.VISIBLE
        showChildTab(bottomNav.selectedItemId == R.id.nav_child_earn)

        listener?.let { childStatusRef?.removeEventListener(it) }
        val childRef = db.getReference("users/$parentId/children/$childId")
        childStatusRef = childRef
        confirmedRemoteLink = false
        ChildLockService.start(this)

        listener = childRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    ChildLockService.stop(this@ChildMainActivity)
                    val currentUid = auth.currentUser?.uid
                    getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .apply {
                            if (!currentUid.isNullOrBlank()) putString("auth_uid", currentUid)
                        }
                        .apply()
                    getSharedPreferences("learn2earn_quiz_submissions", Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .apply()
                    childRef.removeEventListener(this)
                    showUnpaired()
                    Toast.makeText(this@ChildMainActivity, "Unpaired by parent", Toast.LENGTH_LONG).show()
                    return
                }
                if (!confirmedRemoteLink) {
                    confirmedRemoteLink = true
                    syncInstalledApps(childRef)
                }

                val isLocked = snapshot.child("isLocked").getValue(Boolean::class.java) ?: false
                val manualUnlockUntil = (snapshot.child("manualUnlockUntil").value as? Number)?.toLong() ?: 0L
                val parentUnlocked = manualUnlockUntil > System.currentTimeMillis()
                parentAccessActive = parentUnlocked
                snapshot.child("parentEmail").getValue(String::class.java)?.let { email ->
                    getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
                        .edit().putString(ParentApprovalActivity.PARENT_EMAIL, email).apply()
                }
                snapshot.child(GuestApproval.KEY_HASH).getValue(String::class.java)?.let { hash ->
                    getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
                        .edit().putString(GuestApproval.KEY_HASH, hash).apply()
                }
                val dailyFreeSeconds = (snapshot.child("dailyFreeSeconds").value as? Number)
                    ?.toLong()?.coerceIn(0L, 24L * 60 * 60 + 59L * 60)
                val screenTimeLimitMinutes = dailyFreeSeconds?.div(60)?.toInt()
                    ?: (snapshot.child("screenTimeLimitMinutes").value as? Number)
                        ?.toInt()?.coerceIn(0, 24 * 60 + 59)
                getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE).edit().apply {
                    if (screenTimeLimitMinutes == null) remove("screen_time_limit_minutes")
                    else putInt("screen_time_limit_minutes", screenTimeLimitMinutes)
                }.apply()
                refreshTimeStatus(dailyFreeSeconds ?: screenTimeLimitMinutes?.times(60L))
                if (isLocked && !parentUnlocked) {
                    renderAccessStatus(getString(R.string.locked), locked = true)
                } else if (parentUnlocked) {
                    renderAccessStatus("Parent access")
                } else if (isLocked) {
                    renderAccessStatus(getString(R.string.locked), locked = true)
                } else {
                    renderAccessStatus("Ready")
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun showUnpaired() {
        llPaired.visibility = View.GONE
        llUnpaired.visibility = View.VISIBLE
        bottomNav.visibility = View.GONE
        llUnpaired.startAnimation(AnimationUtils.loadAnimation(this, R.anim.scale_fade_in))
        btnLink.isEnabled = auth.currentUser != null
        btnLink.text = "Connect"
    }

    private fun ensureChildIdentity() {
        val current = auth.currentUser
        if (current?.isAnonymous == true) {
            getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
                .edit().putString("auth_uid", current.uid).apply()
            if (childDeviceId == "unknown_child") childDeviceId = current.uid
            return
        }
        if (current != null) auth.signOut()
        btnLink.isEnabled = false
        auth.signInAnonymously().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val uid = auth.currentUser?.uid
                if (uid != null) {
                    getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
                        .edit().putString("auth_uid", uid).apply()
                    if (getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
                            .getString(ChildLockService.PARENT_ID, null).isNullOrBlank()
                    ) {
                        childDeviceId = uid
                    }
                }
                btnLink.isEnabled = true
            } else {
                btnLink.isEnabled = false
                Toast.makeText(this, "Secure child sign-in failed. Check Firebase Anonymous Auth.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::tvProtectionStatus.isInitialized) updateProtectionStatus()
        if (::tvScreentimeDebug.isInitialized) {
            mainHandler.removeCallbacks(timeRefresh)
            mainHandler.post(timeRefresh)
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(timeRefresh)
        super.onPause()
    }

    private fun showChildTab(showEarn: Boolean) {
        findViewById<View>(R.id.child_wallet_view).visibility =
            if (showEarn) View.GONE else View.VISIBLE
        findViewById<View>(R.id.child_earn_container).visibility =
            if (showEarn) View.VISIBLE else View.GONE
    }

    private fun refreshTimeStatus(configuredSeconds: Long? = null) {
        val state = ScreenTimeStore(this).loadState()
        val legacyDailySeconds = configuredSeconds
            ?: getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
                .getInt("screen_time_limit_minutes", 0)
                .coerceAtLeast(0) * 60L
        val todayRemaining = state?.let {
            it.freeRemainingSeconds + it.todayBonusRemainingSeconds
        } ?: legacyDailySeconds
        val bankRemaining = state?.earnedBalanceSeconds ?: 0L
        tvTotalTime.text = formatClock(todayRemaining + bankRemaining)
        tvFreeTime.text = formatClock(todayRemaining)
        tvTimeBank.text = formatClock(bankRemaining)
        tvScreentimeDebug.text = when {
            parentAccessActive -> "Parent access. Timer paused."
            state == null -> "Waiting for daily time."
            state.totalRemainingSeconds <= 0 -> "No time left. Complete a quiz."
            else -> "Timer paused in Learn2Earn."
        }
    }

    private fun formatClock(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d",
            safe / 3_600,
            (safe % 3_600) / 60,
            safe % 60
        )
    }

    private fun renderAccessStatus(label: String, locked: Boolean = false) {
        tvStatus.text = label
        tvStatus.setTextColor(getColor(if (locked) R.color.child_danger else R.color.child_accent))
    }

    private fun updateProtectionStatus() {
        val ready = hasUsageAccess() && Settings.canDrawOverlays(this) &&
            isUsageAccessibilityEnabled() && isBatteryUnrestricted()
        val setupButton = findViewById<Button>(R.id.btn_enable_protection)
        tvProtectionStatus.text = if (ready) {
            "Active"
        } else {
            "Needs usage, overlay, app tracking, and unrestricted battery"
        }
        setupButton.text = if (ready) "Protection active" else "Set up protection"
        setupButton.isEnabled = !ready
        setupButton.alpha = if (ready) 0.72f else 1f
        if (ready && getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
                .getString(ChildLockService.PARENT_ID, null) != null) {
            ChildLockService.start(this)
        }
    }

    private fun syncInstalledApps(ref: DatabaseReference) {
        val apps = InstalledApps.userApps(this)
        tvAppSyncStatus.text = "Sending ${apps.size} non-system apps to parent..."
        ref.child("installedApps").setValue(apps.map { it.asFirebaseValue() })
            .addOnSuccessListener {
                tvAppSyncStatus.text = "${apps.size} apps available in parent controls."
            }
            .addOnFailureListener {
                tvAppSyncStatus.text = "Could not send apps. Check connection and try again."
                Toast.makeText(this, "App sync failed. Check connection and Firebase rules.", Toast.LENGTH_LONG).show()
            }
    }

    private fun openNextRequiredProtectionSetting() {
        when {
            !Settings.canDrawOverlays(this) -> {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this, "Allow display over other apps, then return here.", Toast.LENGTH_LONG).show()
            }
            !hasUsageAccess() -> {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                Toast.makeText(this, "Allow Learn2Earn usage access, then return here.", Toast.LENGTH_LONG).show()
            }
            !isUsageAccessibilityEnabled() -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Turn on Learn2Earn app tracking, then return here.", Toast.LENGTH_LONG).show()
            }
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 44)
            }
            !isBatteryUnrestricted() -> {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                Toast.makeText(this, "Allow unrestricted battery so protection keeps running.", Toast.LENGTH_LONG).show()
            }
            else -> Toast.makeText(this, "Protection is ready.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isUsageAccessibilityEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                info.resolveInfo.serviceInfo.packageName == packageName &&
                    info.resolveInfo.serviceInfo.name == ChildUsageAccessibilityService::class.java.name
            }
    }

    private fun isBatteryUnrestricted(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun requestRoleSwitch() {
        val prefs = getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
        val parentId = prefs.getString(ChildLockService.PARENT_ID, null)
        val childId = prefs.getString(ChildLockService.CHILD_ID, null)
        if (parentId.isNullOrBlank() || childId.isNullOrBlank()) {
            resetRole()
            return
        }
        if (isCheckingRoleSwitch) return
        isCheckingRoleSwitch = true

        db.getReference("users/$parentId/children/$childId")
            .get()
            .addOnCompleteListener { task ->
                isCheckingRoleSwitch = false
                if (task.isSuccessful && !task.result.exists()) {
                    clearStalePairing()
                    Toast.makeText(this, "This device was unpaired by its parent.", Toast.LENGTH_LONG).show()
                    resetRole()
                    return@addOnCompleteListener
                }

                if (task.isSuccessful) {
                    task.result.child("parentEmail").getValue(String::class.java)?.let { email ->
                        prefs.edit().putString(ParentApprovalActivity.PARENT_EMAIL, email).apply()
                    }
                    task.result.child(GuestApproval.KEY_HASH).getValue(String::class.java)?.let { hash ->
                        prefs.edit().putString(GuestApproval.KEY_HASH, hash).apply()
                    }
                }
                startActivity(Intent(this, ParentApprovalActivity::class.java).apply {
                    action = ParentApprovalActivity.ACTION_SWITCH_ROLE
                })
            }
    }

    private fun clearStalePairing() {
        ChildLockService.stop(this)
        listener?.let { childStatusRef?.removeEventListener(it) }
        listener = null
        childStatusRef = null
        getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("learn2earn_quiz_submissions", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun resetRole() {
        getSharedPreferences("learn2earn_prefs", Context.MODE_PRIVATE)
            .edit().remove("user_role").apply()
        getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
            .edit().remove(ChildLockService.PARENT_ID).remove(ChildLockService.CHILD_ID).apply()
        ChildLockService.stop(this)
        startActivity(
            Intent(this, RoleSelectionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        listener?.let { childStatusRef?.removeEventListener(it) }
        listener = null
        childStatusRef = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_EARN = "com.example.learn2earn2.OPEN_EARN"
    }
}
