package com.example.learn2earn2.parent

import android.os.Bundle
import android.app.Dialog
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.learn2earn2.R
import com.example.learn2earn2.account.GuestApprovalHandoff
import com.example.learn2earn2.account.ParentAccount
import com.example.learn2earn2.account.GuestApproval
import com.example.learn2earn2.quiz.LearningApi
import com.example.learn2earn2.quiz.LearningApiResult
import com.example.learn2earn2.ui.KeyboardDismissal
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID

class DevicesFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance("https://learn2earn-bc2bc-default-rtdb.asia-southeast1.firebasedatabase.app/")
    private var activeCodeRef: DatabaseReference? = null
    private var childCountAtCodeShow = -1
    private var childIdsAtCodeShow = emptySet<String>()
    private var codeRequestGeneration = 0L
    private var preserveActiveCodeForRoleSwitch = false

    private var childrenRef: DatabaseReference? = null
    private var childrenListener: ValueEventListener? = null
    private var hasPromptedForGuestPin = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_devices, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnConnectChild = view.findViewById<Button>(R.id.btn_connect_child)
        val btnCancelCode = view.findViewById<Button>(R.id.btn_cancel_code)
        val llCodeCard = view.findViewById<View>(R.id.ll_code_card)
        val tvPairingCode = view.findViewById<TextView>(R.id.tv_pairing_code)
        val tvEmptyState = view.findViewById<View>(R.id.tv_empty_state)
        val tvLoading = view.findViewById<View>(R.id.tv_children_loading)
        val tvError = view.findViewById<View>(R.id.tv_children_error)
        val rvChildren = view.findViewById<RecyclerView>(R.id.rv_children)
        rvChildren.layoutManager = LinearLayoutManager(requireContext())

        rvChildren.layoutAnimation = LayoutAnimationController(
            AnimationUtils.loadAnimation(requireContext(), R.anim.item_fall_in), 0.12f
        )

        val parentDeviceId = ParentAccount.ownerId(requireContext()) ?: run {
            tvLoading.visibility = View.GONE
            tvError.visibility = View.GONE
            tvEmptyState.visibility = View.VISIBLE
            rvChildren.visibility = View.GONE
            return
        }
        retryPendingSecureUnpairs(parentDeviceId)
        if (ParentAccount.isGuest(requireContext())) {
            ensureGuestApprovalPin(parentDeviceId)
        }

        // --- Connect flow ---
        btnConnectChild.setOnClickListener {
            publishNewCode(parentDeviceId, tvPairingCode)
            showCodeCard(llCodeCard, btnCancelCode)
        }

        btnCancelCode.setOnClickListener {
            hideCodeCard(llCodeCard, btnCancelCode, tvPairingCode)
        }

        // --- Children listener ---
        val ref = db.getReference("users/$parentDeviceId/children")
        childrenRef = ref
        val adapter = ChildrenAdapter(
            children = emptyList(),
            onToggleLock = { child ->
                val parentUnlocked = child.manualUnlockUntil?.let { it > System.currentTimeMillis() } == true
                val manualLockActive = child.isLocked && (child.manualLockUntil == null || child.manualLockUntil > System.currentTimeMillis())
                if (parentUnlocked) {
                    ref.child(child.id).updateChildren(
                        mapOf(
                            "isLocked" to true,
                            "manualLockUntil" to nextMidnight(),
                            "manualUnlockUntil" to null
                        )
                    )
                } else if (manualLockActive) {
                    ref.child(child.id).updateChildren(
                        mapOf("isLocked" to false, "manualLockUntil" to null, "manualUnlockUntil" to nextMidnight())
                    )
                } else {
                    ref.child(child.id).updateChildren(
                        mapOf("isLocked" to true, "manualLockUntil" to nextMidnight(), "manualUnlockUntil" to null)
                    )
                }
            },
            onUnpair = { child ->
                fun removeLocalLink() {
                    ref.child(child.id).removeValue()
                }
                val authUid = child.authUid?.takeIf { it.isNotBlank() }
                if (authUid == null || !child.secureLearningEnabled || ParentAccount.isGuest(requireContext())) {
                    removeLocalLink()
                } else {
                    LearningApi.unpairChild(requireContext(), authUid) { result ->
                        if (result is LearningApiResult.Failure &&
                            result.code !in setOf(
                                "CHILD_NOT_FOUND",
                                "PAIRING_REQUIRED",
                                "PARENT_ACCOUNT_REQUIRED"
                            ) &&
                            isAdded
                        ) {
                            // Never leave a parent unable to remove a child because the optional
                            // service is temporarily unavailable (or that child switched users).
                            db.getReference("users/$parentDeviceId/pendingSecureUnpairs/$authUid")
                                .setValue(true)
                            Toast.makeText(requireContext(), "Device unlinked. Secure cleanup will retry.", Toast.LENGTH_LONG).show()
                        }
                        removeLocalLink()
                    }
                }
            },
            onRename = { child ->
                val dialog = Dialog(requireContext())
                val content = layoutInflater.inflate(R.layout.dialog_rename_device, null)
                val input = content.findViewById<EditText>(R.id.et_device_name).apply { setText(child.name); selectAll() }
                content.findViewById<View>(R.id.btn_cancel_rename).setOnClickListener { dialog.dismiss() }
                content.findViewById<View>(R.id.btn_save_rename).setOnClickListener {
                    input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { ref.child(child.id).child("name").setValue(it) }
                    dialog.dismiss()
                }
                dialog.setContentView(content)
                dialog.show()
                dialog.window?.let { window ->
                    window.setBackgroundDrawableResource(android.R.color.transparent)
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    window.decorView.setOnTouchListener { _, event ->
                        KeyboardDismissal.dismissIfOutsideFocusedInput(window, input, event)
                        false
                    }
                }
            },
            onSetScreenTimeLimit = { child, minutes ->
                ref.child(child.id).child("screenTimeLimitMinutes").setValue(minutes)
            },
            onConfigureApps = { child ->
                showAppRulesDialog(child, ref)
            },
            onRestrictionsEnabledChanged = { child, enabled ->
                ref.child(child.id).child("restrictionsEnabled").setValue(enabled)
            },
            onConfigureLearningPlan = { child ->
                child.authUid?.takeIf { it.isNotBlank() }?.let { childUid ->
                    startActivity(Intent(requireContext(), LearningPlanActivity::class.java)
                        .putExtra(LearningPlanActivity.EXTRA_CHILD_UID, childUid)
                        .putExtra(LearningPlanActivity.EXTRA_CHILD_NAME, child.name))
                }
            },
            onAssignQuizzes = { child ->
                child.authUid?.takeIf { it.isNotBlank() }?.let { childUid ->
                    startActivity(Intent(requireContext(), ChildQuizAssignmentActivity::class.java)
                        .putExtra(ChildQuizAssignmentActivity.EXTRA_CHILD_UID, childUid)
                        .putExtra(ChildQuizAssignmentActivity.EXTRA_CHILD_NAME, child.name))
                }
            },
            onResumeTimer = { child ->
                ref.child(child.id).updateChildren(
                    mapOf(
                        "isLocked" to false,
                        "manualLockUntil" to null,
                        "manualUnlockUntil" to null
                    )
                )
            },
            onAdjustDailyFree = { child, adjustment ->
                val current = child.dailyFreeSeconds ?: 0L
                val next = when (adjustment.action) {
                    TimeAdjustmentAction.SET -> adjustment.seconds
                    TimeAdjustmentAction.ADD -> current + adjustment.seconds
                    TimeAdjustmentAction.REMOVE -> current - adjustment.seconds
                }.coerceIn(0L, 24L * 60 * 60 + 59L * 60)
                ref.child(child.id).updateChildren(
                    mapOf(
                        "dailyFreeSeconds" to next,
                        "dailyFreeMinutes" to next / 60,
                        "screenTimeLimitMinutes" to next / 60
                    )
                )
            },
            onAdjustBank = { child, adjustment ->
                val commandId = UUID.randomUUID().toString()
                val seconds = when (adjustment.action) {
                    TimeAdjustmentAction.SET -> adjustment.seconds
                    TimeAdjustmentAction.ADD -> adjustment.seconds
                    TimeAdjustmentAction.REMOVE -> -adjustment.seconds
                }
                val currentBank = child.earnedBalanceSeconds ?: 0L
                val pendingBank = when (adjustment.action) {
                    TimeAdjustmentAction.SET -> adjustment.seconds
                    TimeAdjustmentAction.ADD -> currentBank + adjustment.seconds
                    TimeAdjustmentAction.REMOVE -> currentBank - adjustment.seconds
                }.coerceAtLeast(0L)
                val command = mapOf(
                    "type" to if (adjustment.action == TimeAdjustmentAction.SET) "SET_BANK" else "ADJUST_BANK",
                    "seconds" to seconds,
                    "createdAt" to com.google.firebase.database.ServerValue.TIMESTAMP
                )
                // Show the parent's requested value immediately. The child clears this
                // preview only after it has applied this exact queued command.
                ref.child(child.id).updateChildren(
                    mapOf(
                        "timeCommands/$commandId" to command,
                        "runtime/pendingBankCommandId" to commandId,
                        "runtime/pendingBankSeconds" to pendingBank
                    )
                )
            },
            onAdjustToday = { child, adjustment ->
                val commandId = UUID.randomUUID().toString()
                val seconds = when (adjustment.action) {
                    TimeAdjustmentAction.ADD -> adjustment.seconds
                    TimeAdjustmentAction.REMOVE -> -adjustment.seconds
                    TimeAdjustmentAction.SET -> adjustment.seconds
                }
                ref.child(child.id).child("timeCommands").child(commandId).setValue(
                    mapOf(
                        "type" to "ADJUST_TODAY",
                        "seconds" to seconds,
                        "expiresAt" to nextMidnight(),
                        "createdAt" to com.google.firebase.database.ServerValue.TIMESTAMP
                    )
                )
            },
            onSetStudyEnergyCap = { child, rewardCapMinutes ->
                val updates = mapOf(
                    "dailyRewardCapMinutes" to rewardCapMinutes
                )
                fun saveLocalPolicy() {
                    ref.child(child.id).updateChildren(updates)
                }
                if (ParentAccount.isGuest(requireContext()) || !child.secureLearningEnabled) {
                    saveLocalPolicy()
                } else {
                    child.authUid?.takeIf { it.isNotBlank() }?.let { authUid ->
                        LearningApi.setRewardPolicy(requireContext(), authUid, rewardCapMinutes) { result ->
                            if (result is LearningApiResult.Success) {
                                // The server is the authority for secure quiz rewards. Mirror
                                // its accepted cap to Firebase so the child UI updates live.
                                saveLocalPolicy()
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    "Secure quiz cap could not update. The previous cap is still active.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    } ?: Toast.makeText(
                        requireContext(),
                        "Secure quiz cap could not update: child identity is missing.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
        )
        rvChildren.adapter = adapter
        var hasAnimatedChildren = false

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return

                if (ParentAccount.isGuest(requireContext())) {
                    copyGuestApprovalHashToChildren(parentDeviceId, snapshot)
                }

                val childrenList = snapshot.children.mapNotNull { child ->
                    val id = child.key ?: return@mapNotNull null
                if (child.child("runtime").child("unpairRequestedAt").exists()) {
                        // A child changed roles. Delete its entire record so a later
                        // child-app launch cannot leave an "Unknown" orphan behind.
                        child.ref.removeValue()
                        return@mapNotNull null
                    }
                    val name = child.child("name").getValue(String::class.java) ?: "Unknown Kid"
                    val isLocked = child.child("isLocked").getValue(Boolean::class.java) ?: false
                    val dailyFreeSeconds = (child.child("dailyFreeSeconds").value as? Number)
                        ?.toLong()?.coerceIn(0, 24L * 60 * 60 + 59L * 60)
                        ?: ((child.child("dailyFreeMinutes").value as? Number
                            ?: child.child("screenTimeLimitMinutes").value as? Number)
                            ?.toLong()?.coerceIn(0, 24 * 60 + 59)?.times(60))
                    val installedApps = child.child("installedApps").children.mapNotNull { app ->
                        val packageName = app.child("packageName").getValue(String::class.java)
                            ?: return@mapNotNull null
                        val label = app.child("label").getValue(String::class.java) ?: packageName
                        ChildApp(packageName, label)
                    }.sortedBy { it.label.lowercase() }
                    val blockedPackages = child.child("blockedPackages").takeIf { it.exists() }
                        ?.children
                        ?.mapNotNull { it.getValue(String::class.java) }
                        ?.toSet()
                    val exemptPackages = child.child("exemptPackages").takeIf { it.exists() }
                        ?.children
                        ?.mapNotNull { it.getValue(String::class.java) }
                        ?.toSet()
                    val restrictionsEnabled = child.child("restrictionsEnabled").getValue(Boolean::class.java) ?: false
                    val blockEverything = child.child("blockEverything").getValue(Boolean::class.java) ?: false
                    val manualUnlockUntil = (child.child("manualUnlockUntil").value as? Number)?.toLong()
                    val manualLockUntil = (child.child("manualLockUntil").value as? Number)?.toLong()
                    val dailyRewardCapMinutes = (child.child("dailyRewardCapMinutes").value as? Number)
                        ?.toInt()?.coerceIn(0, 24 * 60)
                    val runtime = child.child("runtime")
                    val studyEnergyRemainingMinutes = (runtime.child("studyEnergyRemainingMinutes").value as? Number)
                        ?.toInt()?.coerceAtLeast(0)
                    val remainingSeconds = (runtime.child("totalRemainingSeconds").value as? Number)?.toLong()
                    val freeRemainingSeconds = (runtime.child("freeRemainingSeconds").value as? Number)?.toLong()
                    val todayBonusRemainingSeconds = (runtime.child("todayBonusRemainingSeconds").value as? Number)?.toLong()
                    val pendingBankSeconds = (runtime.child("pendingBankSeconds").value as? Number)?.toLong()
                    val earnedBalanceSeconds = pendingBankSeconds
                        ?: (runtime.child("earnedBalanceSeconds").value as? Number)?.toLong()
                    val appLockState = runtime.child("appLockState").getValue(String::class.java)
                    val isUsingPhone = runtime.child("isUsingPhone").getValue(Boolean::class.java)
                    val authUid = child.child("authUid").getValue(String::class.java)
                    val secureLearningEnabled = child.child("secureLearningEnabled")
                        .getValue(Boolean::class.java) ?: false
                    if (!child.hasChild("parentEmail")) {
                        FirebaseAuth.getInstance().currentUser?.email?.let { email ->
                            child.ref.child("parentEmail").setValue(email)
                        }
                    }
                    ChildDevice(
                        id = id,
                        name = name,
                        isLocked = isLocked,
                        dailyFreeSeconds = dailyFreeSeconds,
                        installedApps = installedApps,
                        blockedPackages = blockedPackages,
                        exemptPackages = exemptPackages,
                        restrictionsEnabled = restrictionsEnabled,
                        blockEverything = blockEverything,
                        manualUnlockUntil = manualUnlockUntil,
                        manualLockUntil = manualLockUntil,
                        dailyRewardCapMinutes = dailyRewardCapMinutes,
                        studyEnergyRemainingMinutes = studyEnergyRemainingMinutes,
                        remainingSeconds = remainingSeconds,
                        freeRemainingSeconds = freeRemainingSeconds,
                        todayBonusRemainingSeconds = todayBonusRemainingSeconds,
                        earnedBalanceSeconds = earnedBalanceSeconds,
                        bankSyncPending = pendingBankSeconds != null,
                        appLockState = appLockState,
                        isUsingPhone = isUsingPhone,
                        authUid = authUid,
                        secureLearningEnabled = secureLearningEnabled
                    )
                }

                tvLoading.visibility = View.GONE
                tvError.visibility = View.GONE
                tvEmptyState.visibility = if (childrenList.isEmpty()) View.VISIBLE else View.GONE
                rvChildren.visibility = if (childrenList.isEmpty()) View.GONE else View.VISIBLE

                adapter.submitChildren(childrenList)
                if (childrenList.isNotEmpty() && !hasAnimatedChildren) {
                    rvChildren.scheduleLayoutAnimation()
                    hasAnimatedChildren = true
                }

                if (childCountAtCodeShow >= 0 && childrenList.size > childCountAtCodeShow) {
                    val newlyPairedChild = childrenList.firstOrNull { it.id !in childIdsAtCodeShow }
                    hideCodeCard(llCodeCard, btnCancelCode, tvPairingCode)
                    newlyPairedChild?.let { promptRenameNewChild(it, ref) }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Learn2Earn", "Children listener error: ${error.message}")
                if (!isAdded) return
                tvLoading.visibility = View.GONE
                tvEmptyState.visibility = View.GONE
                rvChildren.visibility = View.GONE
                tvError.visibility = View.VISIBLE
            }
        }
        childrenListener = listener
        ref.addValueEventListener(listener)
    }

    private fun showCodeCard(card: View, cancelBtn: Button) {
        if (!isAdded) return
        card.clearAnimation()
        cancelBtn.clearAnimation()
        card.visibility = View.VISIBLE
        cancelBtn.visibility = View.VISIBLE
        val cardAnim = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_down_fade_in)
        val btnAnim = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_down_fade_in)
        card.startAnimation(cardAnim)
        cancelBtn.startAnimation(btnAnim)
    }

    /** Complete server cleanup after a previous unlink succeeded locally while offline. */
    private fun retryPendingSecureUnpairs(parentId: String) {
        if (ParentAccount.isGuest(requireContext())) return
        val pending = db.getReference("users/$parentId/pendingSecureUnpairs")
        pending.get().addOnSuccessListener { snapshot ->
            snapshot.children.mapNotNull { it.key }.forEach { childUid ->
                LearningApi.unpairChild(requireContext(), childUid) { result ->
                    if (result is LearningApiResult.Success ||
                        (result is LearningApiResult.Failure && result.code == "CHILD_NOT_FOUND")
                    ) {
                        pending.child(childUid).removeValue()
                    }
                }
            }
        }
    }

    private fun publishNewCode(parentDeviceId: String, tvCode: TextView) {
        cancelActiveCode()
        val generation = codeRequestGeneration
        tvCode.text = "------"
        if (ParentAccount.isGuest(requireContext())) {
            db.getReference("users/$parentDeviceId/${GuestApproval.KEY_HASH}")
                .get()
                .addOnCompleteListener { task ->
                    if (!isAdded || generation != codeRequestGeneration) return@addOnCompleteListener
                    val guestApprovalHash = task.result
                        ?.getValue(String::class.java)
                        ?.takeIf { it.isNotBlank() }
                    if (guestApprovalHash == null) {
                        Toast.makeText(
                            requireContext(),
                            "Set the guest approval PIN before pairing a child.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@addOnCompleteListener
                    }
                    GuestApprovalHandoff.saveLocal(requireContext(), guestApprovalHash)
                    publishNewCode(parentDeviceId, tvCode, generation, guestApprovalHash)
                }
            return
        }
        publishNewCode(parentDeviceId, tvCode, generation, null)
    }

    private fun publishNewCode(
        parentDeviceId: String,
        tvCode: TextView,
        generation: Long,
        guestApprovalHash: String?
    ) {
        LearningApi.createPairingCode(requireContext()) { result ->
            if (!isAdded || generation != codeRequestGeneration) return@createPairingCode
            when (result) {
                is LearningApiResult.Success -> {
                    val code = result.body.optString("code")
                    if (code.isNotBlank()) {
                        val expiresAt = result.body.optLong(
                            "expiresAt",
                            System.currentTimeMillis() + PAIRING_CODE_TTL_MS
                        )
                        val ref = db.getReference("pairing_codes/$code")
                        activeCodeRef = ref
                        ref.setValue(
                            mapOf(
                                "parentUid" to parentDeviceId,
                                "createdAt" to com.google.firebase.database.ServerValue.TIMESTAMP,
                                "expiresAt" to expiresAt,
                                "secureLearning" to true
                            ) + GuestApprovalHandoff.firebaseFields(guestApprovalHash)
                        ).addOnSuccessListener {
                            if (!isAdded || generation != codeRequestGeneration) {
                                ref.removeValue()
                                return@addOnSuccessListener
                            }
                            tvCode.text = code
                            db.getReference("users/$parentDeviceId/children").get()
                                .addOnSuccessListener { snap ->
                                    childCountAtCodeShow = snap.childrenCount.toInt()
                                    childIdsAtCodeShow = snap.children.mapNotNull { it.key }.toSet()
                                }
                                .addOnFailureListener {
                                    childCountAtCodeShow = 0
                                    childIdsAtCodeShow = emptySet()
                                }
                        }.addOnFailureListener {
                            if (generation != codeRequestGeneration) return@addOnFailureListener
                            if (activeCodeRef == ref) activeCodeRef = null
                            if (isAdded) {
                                Toast.makeText(
                                    requireContext(),
                                    "Could not publish the secure pairing code to Firebase.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    } else {
                        publishLegacyCode(parentDeviceId, tvCode, guestApprovalHash)
                    }
                }
                is LearningApiResult.Failure -> {
                    // Firebase pairing is the normal transparent fallback. The parent only
                    // needs feedback when creating a code fails altogether.
                    publishLegacyCode(parentDeviceId, tvCode, guestApprovalHash)
                }
            }
        }
    }

    private fun publishLegacyCode(
        parentDeviceId: String,
        tvCode: TextView,
        guestApprovalHash: String? = null
    ) {
        cancelActiveCode()

        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = java.security.SecureRandom()
        val code = (1..6).map { chars[random.nextInt(chars.length)] }.joinToString("")

        tvCode.text = code

        val ref = db.getReference("pairing_codes/$code")
        activeCodeRef = ref

        ref.setValue(
            mapOf(
                "parentUid" to parentDeviceId,
                "createdAt" to com.google.firebase.database.ServerValue.TIMESTAMP,
                "expiresAt" to System.currentTimeMillis() + PAIRING_CODE_TTL_MS,
                "secureLearning" to false
            ) + GuestApprovalHandoff.firebaseFields(guestApprovalHash)
        )
            .addOnFailureListener { e ->
                Log.e("Learn2Earn", "Failed to write code: ${e.message}")
                if (isAdded) {
                    Toast.makeText(requireContext(), "Could not publish pairing code. Check connection and Firebase rules.", Toast.LENGTH_LONG).show()
                }
            }

        db.getReference("users/$parentDeviceId/children").get()
            .addOnSuccessListener { snap ->
                childCountAtCodeShow = snap.childrenCount.toInt()
                childIdsAtCodeShow = snap.children.mapNotNull { it.key }.toSet()
            }
            .addOnFailureListener {
                childCountAtCodeShow = 0
                childIdsAtCodeShow = emptySet()
            }
    }

    private fun hideCodeCard(card: View, cancelBtn: Button, codeText: TextView) {
        if (!isAdded) return
        cancelActiveCode()
        val cardAnim = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up_fade_out)
        val btnAnim = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up_fade_out)
        cardAnim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(a: Animation?) {}
            override fun onAnimationRepeat(a: Animation?) {}
            override fun onAnimationEnd(a: Animation?) {
                card.visibility = View.GONE
                cancelBtn.visibility = View.GONE
                codeText.text = "--------"
            }
        })
        card.startAnimation(cardAnim)
        cancelBtn.startAnimation(btnAnim)
        childCountAtCodeShow = -1
        childIdsAtCodeShow = emptySet()
    }

    private fun promptRenameNewChild(child: ChildDevice, ref: DatabaseReference) {
        if (!isAdded) return
        val input = EditText(requireContext()).apply {
            setText(child.name)
            selectAll()
            hint = "Child's name"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Name this child")
            .setMessage("Use a name that makes this device easy to recognize.")
            .setView(input)
            .setNegativeButton("Keep default", null)
            .setPositiveButton("Save", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = input.text.toString().trim()
                        if (name.isBlank()) {
                            input.error = "Enter a name"
                            return@setOnClickListener
                        }
                        ref.child(child.id).child("name").setValue(name)
                        dialog.dismiss()
                    }
                }
                dialog.setOnDismissListener {
                    promptInitialDailyFreeTime(child, ref)
                }
                dialog.show()
            }
    }

    private fun promptInitialDailyFreeTime(child: ChildDevice, ref: DatabaseReference) {
        if (!isAdded) return
        val minutes = EditText(requireContext()).apply {
            hint = "Minutes per day"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(((child.dailyFreeSeconds ?: 60L * 60L) / 60L).toString())
            selectAll()
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Set daily free time")
            .setMessage("This resets each day. Quiz rewards and parent-given time stay separate.")
            .setView(minutes)
            .setNegativeButton("Later", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = minutes.text.toString().toLongOrNull()
                if (value == null || value !in 0..(24L * 60L + 59L)) {
                    minutes.error = "Enter 0 to 1439 minutes"
                    return@setOnClickListener
                }
                val seconds = value * 60L
                ref.child(child.id).updateChildren(
                    mapOf(
                        "dailyFreeSeconds" to seconds,
                        "dailyFreeMinutes" to value,
                        "screenTimeLimitMinutes" to value
                    )
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun cancelActiveCode() {
        codeRequestGeneration += 1
        activeCodeRef?.removeValue()
        activeCodeRef?.onDisconnect()?.cancel()
        activeCodeRef = null
    }

    fun preserveActiveCodeForRoleSwitch() {
        preserveActiveCodeForRoleSwitch = true
        activeCodeRef?.onDisconnect()?.cancel()
    }

    private fun showAppRulesDialog(child: ChildDevice, ref: DatabaseReference) {
        if (child.installedApps.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("No apps received yet")
                .setMessage("On the child phone, open Learn2Earn and tap Refresh apps for parent. Then return here and open this screen again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val context = requireContext()
        val apps = child.installedApps
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
        }
        content.addView(TextView(context).apply {
            text = "Choose apps controlled by the timer. They use time and are blocked when time ends or you manually lock this child. Saving turns time limits on."
            setTextColor(context.getColor(R.color.l2e_muted))
            textSize = 14f
        })
        val everything = SwitchCompat(context).apply {
            text = "Control and block all apps"
            textSize = 17f
            setTextColor(context.getColor(R.color.l2e_ink))
            isChecked = child.blockEverything
            setPadding(0, dp(18), 0, dp(12))
        }
        content.addView(everything)
        val appsLabel = TextView(context).apply {
            setTextColor(context.getColor(R.color.l2e_forest))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        content.addView(appsLabel)
        val appsColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        val managedSelection = child.blockedPackages.orEmpty().toMutableSet()
        val exemptSelection = child.exemptPackages.orEmpty().toMutableSet()
        var editingAllApps = child.blockEverything
        val checkBoxes = apps.map { app ->
            CheckBox(context).apply {
                text = "${app.label}\n${app.packageName}"
                textSize = 15f
                setTextColor(context.getColor(R.color.l2e_ink))
                minHeight = dp(56)
                setPadding(0, dp(4), 0, dp(4))
                appsColumn.addView(this)
            }
        }
        val appsScroll = ScrollView(context).apply {
            addView(appsColumn)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(250)
            )
        }
        content.addView(appsScroll)
        fun captureAppSelection() {
            val target = if (editingAllApps) exemptSelection else managedSelection
            target.clear()
            apps.indices.filter { checkBoxes[it].isChecked }
                .mapTo(target) { apps[it].packageName }
        }
        fun renderAppSelection() {
            val selected = if (editingAllApps) exemptSelection else managedSelection
            appsLabel.text = if (editingAllApps) {
                "Apps never controlled or blocked"
            } else {
                "Apps controlled and blocked when time ends"
            }
            checkBoxes.forEachIndexed { index, checkbox ->
                checkbox.isChecked = apps[index].packageName in selected
            }
        }
        everything.setOnCheckedChangeListener { _, checked ->
            captureAppSelection()
            editingAllApps = checked
            renderAppSelection()
        }
        renderAppSelection()
        AlertDialog.Builder(context)
            .setTitle("Apps to control for ${child.name}")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                captureAppSelection()
                val updates = mapOf<String, Any?>(
                    "restrictionsEnabled" to true,
                    "blockEverything" to everything.isChecked,
                    "blockedPackages" to managedSelection.toList(),
                    "exemptPackages" to exemptSelection.toList()
                )
                ref.child(child.id).updateChildren(updates)
            }
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun nextMidnight(): Long = java.util.Calendar.getInstance().run {
        add(java.util.Calendar.DAY_OF_YEAR, 1)
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private fun ensureGuestApprovalPin(parentId: String) {
        db.getReference("users/$parentId/${GuestApproval.KEY_HASH}").get().addOnSuccessListener { snapshot ->
            if (!isAdded || snapshot.exists() || hasPromptedForGuestPin) return@addOnSuccessListener
            hasPromptedForGuestPin = true
            val pin = EditText(requireContext()).apply {
                hint = "6-digit guest PIN"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                setSingleLine(true)
            }
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle("Set guest approval PIN")
                .setMessage("Use this PIN to approve a paired child's emergency unlock or role switch. It cannot be recovered.")
                .setView(pin)
                .setCancelable(false)
                .setPositiveButton("Save", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = pin.text.toString()
                    if (value.length != 6) {
                        pin.error = "Enter exactly 6 digits"
                        return@setOnClickListener
                    }
                    val hash = GuestApproval.hash(value)
                    db.getReference("users/$parentId/${GuestApproval.KEY_HASH}")
                        .setValue(hash)
                        .addOnSuccessListener {
                            GuestApprovalHandoff.saveLocal(requireContext(), hash)
                            db.getReference("users/$parentId/children").get().addOnSuccessListener { children ->
                                copyGuestApprovalHashToChildren(parentId, children)
                            }
                            dialog.dismiss()
                        }
                        .addOnFailureListener { pin.error = "Could not save PIN" }
                }
            }
            dialog.show()
        }
    }

    private fun copyGuestApprovalHashToChildren(parentId: String, children: DataSnapshot) {
        db.getReference("users/$parentId/${GuestApproval.KEY_HASH}").get().addOnSuccessListener { hash ->
            val value = hash.getValue(String::class.java) ?: return@addOnSuccessListener
            children.children.filterNot { it.hasChild(GuestApproval.KEY_HASH) }.forEach { child ->
                child.ref.child(GuestApproval.KEY_HASH).setValue(value)
            }
        }
    }

    companion object {
        private const val PAIRING_CODE_TTL_MS = 10 * 60 * 1000L
        private const val DEFAULT_DAILY_REWARD_CAP_MINUTES = 120
    }

    override fun onDestroyView() {
        super.onDestroyView()
        childrenListener?.let { childrenRef?.removeEventListener(it) }
        if (!preserveActiveCodeForRoleSwitch) {
            cancelActiveCode()
        }
    }
}
