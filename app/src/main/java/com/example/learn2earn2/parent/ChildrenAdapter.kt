package com.example.learn2earn2.parent

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.example.learn2earn2.R
import java.util.Locale

data class ChildDevice(
    val id: String,
    val name: String,
    val isLocked: Boolean,
    val dailyFreeSeconds: Long?,
    val installedApps: List<ChildApp>,
    val blockedPackages: Set<String>?,
    val exemptPackages: Set<String>?,
    val restrictionsEnabled: Boolean,
    val blockEverything: Boolean,
    val manualUnlockUntil: Long?,
    val manualLockUntil: Long?,
    val dailyRewardCapMinutes: Int? = null,
    /** Latest server-authoritative secure learning balance, mirrored by the child app. */
    val studyEnergyRemainingMinutes: Int? = null,
    val remainingSeconds: Long? = null,
    val freeRemainingSeconds: Long? = null,
    val todayBonusRemainingSeconds: Long? = null,
    val earnedBalanceSeconds: Long? = null,
    /** Parent-side intended bank value, shown until the child applies its queued command. */
    val bankSyncPending: Boolean = false,
    val appLockState: String? = null,
    val isUsingPhone: Boolean? = null,
    val authUid: String? = null,
    /** True only for links backed by the secure learning service. */
    val secureLearningEnabled: Boolean = false
)

enum class TimeAdjustmentAction { SET, ADD, REMOVE }

data class TimeAdjustment(val action: TimeAdjustmentAction, val seconds: Long)

data class ChildApp(val packageName: String, val label: String)

class ChildrenAdapter(
    children: List<ChildDevice>,
    private val onToggleLock: (ChildDevice) -> Unit,
    private val onUnpair: (ChildDevice) -> Unit,
    private val onRename: (ChildDevice) -> Unit,
    private val onSetScreenTimeLimit: (ChildDevice, Int) -> Unit,
    private val onConfigureApps: (ChildDevice) -> Unit,
    private val onRestrictionsEnabledChanged: (ChildDevice, Boolean) -> Unit,
    private val onResumeTimer: (ChildDevice) -> Unit = {},
    private val onAdjustDailyFree: (ChildDevice, TimeAdjustment) -> Unit = { _, _ -> },
    private val onAdjustToday: (ChildDevice, TimeAdjustment) -> Unit = { _, _ -> },
    private val onAdjustBank: (ChildDevice, TimeAdjustment) -> Unit = { _, _ -> },
    private val onSetStudyEnergyCap: (ChildDevice, Int) -> Unit = { _, _ -> },
    private val onConfigureLearningPlan: (ChildDevice) -> Unit = {},
    private val onAssignQuizzes: (ChildDevice) -> Unit = {}
) : RecyclerView.Adapter<ChildrenAdapter.ViewHolder>() {

    private var children = children
    private var expandedChildId: String? = null

    fun submitChildren(newChildren: List<ChildDevice>) {
        children = newChildren
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_child_name)
        val tvStatusLabel: TextView = view.findViewById(R.id.tv_child_status_label)
        val statusDot: View = view.findViewById(R.id.view_status_dot)
        val btnExpand: ImageButton = view.findViewById(R.id.btn_expand)
        val btnUnpair: ImageButton = view.findViewById(R.id.btn_unpair)
        val details: View = view.findViewById(R.id.child_details)
        val btnRename: ImageButton = view.findViewById(R.id.btn_rename)
        val btnDetailsLock: Button = view.findViewById(R.id.btn_details_lock)
        val btnResumeTimer: Button = view.findViewById(R.id.btn_resume_timer)
        val tvScreentimeLimit: TextView = view.findViewById(R.id.tv_screentime_limit)
        val btnAdjustDaily: Button = view.findViewById(R.id.btn_adjust_daily)
        val btnAppSettings: Button = view.findViewById(R.id.btn_app_settings)
        val switchRestrictions: SwitchCompat = view.findViewById(R.id.switch_restrictions)
        val tvRestrictionSummary: TextView = view.findViewById(R.id.tv_restriction_summary)
        val tvTimeRemaining: TextView = view.findViewById(R.id.tv_time_remaining)
        val tvEarnedTime: TextView = view.findViewById(R.id.tv_earned_time)
        val btnAdjustToday: Button = view.findViewById(R.id.btn_adjust_today)
        val btnAdjustBank: Button = view.findViewById(R.id.btn_adjust_bank)
        val tvStudyEnergy: TextView = view.findViewById(R.id.tv_study_energy)
        val btnStudyEnergy: Button = view.findViewById(R.id.btn_study_energy)
        val btnStudyEnergyInfo: ImageButton = view.findViewById(R.id.btn_study_energy_info)
        val btnLearningPlan: Button = view.findViewById(R.id.btn_learning_plan)
        val btnAssignQuizzes: Button = view.findViewById(R.id.btn_assign_quizzes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_child, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val child = children[position]
        holder.tvName.text = child.name
        holder.details.visibility = if (expandedChildId == child.id) View.VISIBLE else View.GONE
        holder.btnExpand.rotation = if (expandedChildId == child.id) 180f else 0f
        val toggleDetails = {
            android.transition.TransitionManager.beginDelayedTransition(
                holder.itemView as ViewGroup,
                android.transition.AutoTransition().apply { duration = 180 }
            )
            expandedChildId = if (expandedChildId == child.id) null else child.id
            holder.details.visibility = if (expandedChildId == child.id) View.VISIBLE else View.GONE
            holder.btnExpand.animate()
                .rotation(if (expandedChildId == child.id) 180f else 0f)
                .setDuration(180)
                .start()
        }
        holder.itemView.setOnClickListener { toggleDetails() }
        holder.btnExpand.setOnClickListener { toggleDetails() }

        val parentUnlocked = child.manualUnlockUntil?.let { it > System.currentTimeMillis() } == true
        val manualLockActive = child.isLocked && (child.manualLockUntil == null || child.manualLockUntil > System.currentTimeMillis())
        holder.tvStatusLabel.text = when {
            parentUnlocked -> "Parent unlocked until midnight"
            manualLockActive -> holder.itemView.context.getString(R.string.locked)
            else -> holder.itemView.context.getString(R.string.unlocked)
        }
        holder.tvStatusLabel.setTextColor(holder.itemView.context.getColor(if (parentUnlocked || !manualLockActive) R.color.l2e_success else R.color.l2e_danger))
        holder.statusDot.setBackgroundResource(if (parentUnlocked || !manualLockActive) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_red)
        holder.btnDetailsLock.text = when {
            parentUnlocked -> "Lock now"
            manualLockActive -> holder.itemView.context.getString(R.string.unlock)
            else -> holder.itemView.context.getString(R.string.lock)
        }
        holder.btnDetailsLock.setOnClickListener { onToggleLock(child) }
        holder.btnResumeTimer.visibility = if (parentUnlocked || manualLockActive) View.VISIBLE else View.GONE
        holder.btnResumeTimer.setOnClickListener { onResumeTimer(child) }
        holder.btnRename.setOnClickListener { onRename(child) }
        holder.btnUnpair.setOnClickListener { onUnpair(child) }
        holder.tvScreentimeLimit.text = child.dailyFreeSeconds?.let(::formatSeconds) ?: "Set time"
        val adjustDaily = {
            showTimeAdjustmentDialog(
                holder.itemView,
                title = "Adjust daily free time",
                label = "Daily free time",
                initialSeconds = child.dailyFreeSeconds ?: 0,
                includeSet = true,
                onSave = { onAdjustDailyFree(child, it) }
            )
        }
        holder.tvScreentimeLimit.setOnClickListener { adjustDaily() }
        holder.btnAdjustDaily.setOnClickListener { adjustDaily() }
        holder.tvTimeRemaining.text = child.remainingSeconds?.let(::formatSeconds)
            ?: "Waiting for child device"
        holder.tvEarnedTime.text = child.earnedBalanceSeconds?.let {
            formatSeconds(it) + if (child.bankSyncPending) " - syncing" else ""
        } ?: "Waiting for child device"
        holder.btnAdjustToday.setOnClickListener {
            showTimeAdjustmentDialog(
                holder.itemView,
                title = "Adjust today's free time",
                label = "Today only",
                initialSeconds = DEFAULT_ADJUSTMENT_SECONDS,
                includeSet = false,
                onSave = { onAdjustToday(child, it) }
            )
        }
        holder.btnAdjustBank.setOnClickListener {
            showTimeAdjustmentDialog(
                holder.itemView,
                title = "Adjust time bank",
                label = "Time bank",
                initialSeconds = child.earnedBalanceSeconds ?: 0,
                includeSet = true,
                onSave = { onAdjustBank(child, it) }
            )
        }
        val energyCap = child.dailyRewardCapMinutes ?: DEFAULT_STUDY_ENERGY
        holder.tvStudyEnergy.text = child.studyEnergyRemainingMinutes
            ?.coerceIn(0, energyCap)
            ?.let { "$it of $energyCap min left" }
            ?: "$energyCap min / day"
        holder.btnStudyEnergy.setOnClickListener { showStudyEnergyDialog(holder.itemView, energyCap) {
            onSetStudyEnergyCap(child, it)
        } }
        holder.btnStudyEnergyInfo.setOnClickListener {
            AlertDialog.Builder(holder.itemView.context)
                .setTitle("Study Energy")
                .setMessage("One energy equals one minute the child can earn from quizzes. It refills at midnight. Changing the cap updates energy available now, but never takes back time already earned. If the cap is lowered below what was earned today, no more quiz time can be earned until tomorrow.")
                .setPositiveButton("OK", null)
                .show()
        }
        holder.btnLearningPlan.visibility = View.VISIBLE
        holder.btnLearningPlan.isEnabled = child.secureLearningEnabled
        holder.btnLearningPlan.alpha = if (child.secureLearningEnabled) 1f else 0.5f
        holder.btnLearningPlan.text = if (child.secureLearningEnabled) "Learning plan" else "Secure pairing needed"
        holder.btnLearningPlan.setOnClickListener { onConfigureLearningPlan(child) }
        holder.btnAssignQuizzes.visibility = if (child.secureLearningEnabled) View.VISIBLE else View.GONE
        holder.btnAssignQuizzes.setOnClickListener { onAssignQuizzes(child) }
        holder.switchRestrictions.setOnCheckedChangeListener(null)
        holder.switchRestrictions.isChecked = child.restrictionsEnabled
        holder.switchRestrictions.setOnCheckedChangeListener { _, enabled ->
            onRestrictionsEnabledChanged(child, enabled)
        }
        holder.tvRestrictionSummary.text = when {
            !child.restrictionsEnabled -> "Usage timer restrictions are off."
            child.blockEverything && !child.exemptPackages.isNullOrEmpty() ->
                "When time ends, all user apps except ${child.exemptPackages.size} exempt app${if (child.exemptPackages.size == 1) "" else "s"} are blocked."
            child.blockEverything -> "When time ends, all user apps are blocked."
            child.blockedPackages.isNullOrEmpty() -> "Choose apps to block when time ends."
            else -> "When time ends, ${child.blockedPackages.size} selected app${if (child.blockedPackages.size == 1) " is" else "s are"} blocked."
        }
        holder.btnAppSettings.text = if (child.installedApps.isEmpty()) {
            "Choose apps that use time"
        } else {
            "Manage apps that use time"
        }
        holder.btnAppSettings.setOnClickListener { onConfigureApps(child) }
    }

    private fun showTimeAdjustmentDialog(
        view: View,
        title: String,
        label: String,
        initialSeconds: Long,
        includeSet: Boolean,
        onSave: (TimeAdjustment) -> Unit
    ) {
        val context = view.context
        val current = initialSeconds.coerceAtLeast(0)
        val actions = if (includeSet) {
            listOf(TimeAdjustmentAction.SET, TimeAdjustmentAction.ADD, TimeAdjustmentAction.REMOVE)
        } else {
            listOf(TimeAdjustmentAction.ADD, TimeAdjustmentAction.REMOVE)
        }
        val actionPicker = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                actions.map {
                    when (it) {
                        TimeAdjustmentAction.SET -> "Set exact time"
                        TimeAdjustmentAction.ADD -> "Add time"
                        TimeAdjustmentAction.REMOVE -> "Remove time"
                    }
                }
            )
        }
        val hoursPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = if (includeSet) 999 else 24
            value = (current / 3_600).coerceAtMost(maxValue.toLong()).toInt()
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        }
        val minutesPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 59
            value = ((current % 3_600) / 60).toInt()
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        }
        val secondsPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 59
            value = (current % 60).toInt()
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        }
        fun setPickerValue(seconds: Long) {
            val safe = seconds.coerceAtLeast(0)
            hoursPicker.value = (safe / 3_600).coerceAtMost(hoursPicker.maxValue.toLong()).toInt()
            minutesPicker.value = ((safe % 3_600) / 60).toInt()
            secondsPicker.value = (safe % 60).toInt()
        }
        actionPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                setPickerValue(
                    if (actions[position] == TimeAdjustmentAction.SET) current else DEFAULT_ADJUSTMENT_SECONDS
                )
            }
        }
        val pickerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(hoursPicker, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(minutesPicker, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(secondsPicker, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label(context, "Hours"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(context, "Minutes"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(context, "Seconds"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val picker = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(context, label).apply { gravity = Gravity.START })
            addView(actionPicker)
            addView(pickerRow)
            addView(labels)
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(picker)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val seconds = hoursPicker.value * 3_600L + minutesPicker.value * 60L + secondsPicker.value
                onSave(TimeAdjustment(actions[actionPicker.selectedItemPosition], seconds))
            }
            .show()
    }

    private fun showStudyEnergyDialog(view: View, initialMinutes: Int, onSave: (Int) -> Unit) {
        val picker = NumberPicker(view.context).apply {
            minValue = 0
            maxValue = 1_440
            value = initialMinutes.coerceIn(minValue, maxValue)
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        AlertDialog.Builder(view.context)
            .setTitle("Daily Study Energy")
            .setMessage("1 energy = 1 earned minute. Refills at midnight.")
            .setView(picker)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> onSave(picker.value) }
            .show()
    }

    private fun label(context: android.content.Context, text: String) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.l2e_muted))
        textSize = 12f
    }

    private fun formatSeconds(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val remainingSeconds = safe % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%dh %02dm %02ds", hours, minutes, remainingSeconds)
        } else {
            String.format(Locale.getDefault(), "%02dm %02ds", minutes, remainingSeconds)
        }
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    override fun getItemCount() = children.size

    companion object {
        private const val DEFAULT_ADJUSTMENT_SECONDS = 15L * 60L
        private const val DEFAULT_STUDY_ENERGY = 120
        private const val DEFAULT_REWARD_MINUTES = 5
        private const val MAX_DAILY_FREE_MINUTES = 24 * 60 + 59
    }
}
