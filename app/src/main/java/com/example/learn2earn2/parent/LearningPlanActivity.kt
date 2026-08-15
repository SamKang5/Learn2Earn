package com.example.learn2earn2.parent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.example.learn2earn2.R
import com.example.learn2earn2.quiz.LearningApi
import com.example.learn2earn2.quiz.LearningApiResult
import com.example.learn2earn2.quiz.QuizOptions
import org.json.JSONArray
import org.json.JSONObject

class LearningPlanActivity : AppCompatActivity() {
    private lateinit var childUid: String
    private lateinit var status: TextView
    private lateinit var drafts: LinearLayout
    private lateinit var grade: Spinner
    private lateinit var subjectsButton: Button
    private lateinit var otherSubjects: EditText
    private lateinit var curriculumNotes: EditText
    private lateinit var strengths: EditText
    private lateinit var weakAreas: EditText
    private lateinit var difficultiesButton: Button
    private lateinit var minimumAvailable: NumberPicker
    private lateinit var refillCount: NumberPicker
    private lateinit var passingScore: NumberPicker
    private lateinit var rewardMinutes: NumberPicker
    private lateinit var rewardTiers: LinearLayout
    private lateinit var autoAssign: SwitchCompat
    private lateinit var save: Button
    private val selectedSubjects = linkedSetOf<String>()
    private val selectedDifficulties = linkedSetOf("Balanced")
    private val extraRewardInputs = mutableListOf<Pair<EditText, EditText>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childUid = intent.getStringExtra(EXTRA_CHILD_UID).orEmpty()
        if (childUid.isBlank()) { finish(); return }
        setContentView(R.layout.activity_learning_plan)
        findViewById<TextView>(R.id.tv_learning_plan_title).text = "Learning plan for ${intent.getStringExtra(EXTRA_CHILD_NAME).orEmpty().ifBlank { "child" }}"
        findViewById<View>(R.id.btn_close_learning_plan).setOnClickListener { finish() }
        status = findViewById(R.id.tv_learning_plan_status); drafts = findViewById(R.id.ll_learning_plan_drafts)
        grade = findViewById(R.id.sp_plan_grade); subjectsButton = findViewById(R.id.btn_plan_subjects); otherSubjects = findViewById(R.id.et_plan_other_subjects)
        curriculumNotes = findViewById(R.id.et_plan_curriculum); strengths = findViewById(R.id.et_plan_strengths); weakAreas = findViewById(R.id.et_plan_weak_areas)
        difficultiesButton = findViewById(R.id.btn_plan_difficulties); minimumAvailable = findViewById(R.id.np_plan_minimum_available); refillCount = findViewById(R.id.np_plan_refill_count)
        passingScore = findViewById(R.id.np_plan_passing_score); rewardMinutes = findViewById(R.id.np_plan_reward_minutes); rewardTiers = findViewById(R.id.ll_plan_reward_tiers)
        autoAssign = findViewById(R.id.switch_plan_auto_assign); save = findViewById(R.id.btn_save_learning_plan)
        grade.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, QuizOptions.grades)
        picker(minimumAvailable, 10, 3); picker(refillCount, 5, 2); picker(passingScore, 100, 80); picker(rewardMinutes, 240, 15)
        subjectsButton.setOnClickListener { chooseSubjects() }; difficultiesButton.setOnClickListener { chooseDifficulties() }
        findViewById<Button>(R.id.btn_add_plan_reward_tier).setOnClickListener { addRewardTier() }
        save.setOnClickListener { savePlan() }; updateSubjectButton(); updateDifficultyButton(); loadPlan()
    }

    private fun chooseSubjects() {
        val items = QuizOptions.subjects.toTypedArray(); val checks = BooleanArray(items.size) { items[it] in selectedSubjects }
        AlertDialog.Builder(this).setTitle("Choose subjects").setMultiChoiceItems(items, checks) { _, index, checked ->
            if (checked) selectedSubjects += items[index] else selectedSubjects -= items[index]
        }.setNegativeButton("Cancel", null).setPositiveButton("Done") { _, _ -> updateSubjectButton() }.show()
    }
    private fun chooseDifficulties() {
        val items = arrayOf("Easy", "Balanced", "Challenging"); val checks = BooleanArray(items.size) { items[it] in selectedDifficulties }
        AlertDialog.Builder(this).setTitle("Choose difficulties").setMultiChoiceItems(items, checks) { _, index, checked ->
            if (checked) selectedDifficulties += items[index] else selectedDifficulties -= items[index]
        }.setNegativeButton("Cancel", null).setPositiveButton("Done") { _, _ ->
            if (selectedDifficulties.isEmpty()) selectedDifficulties += "Balanced"; updateDifficultyButton()
        }.show()
    }
    private fun updateSubjectButton() {
        subjectsButton.text = if (selectedSubjects.isEmpty()) "Choose subjects" else selectedSubjects.joinToString(", ")
        otherSubjects.visibility = if ("Other" in selectedSubjects) View.VISIBLE else View.GONE
    }
    private fun updateDifficultyButton() { difficultiesButton.text = "Difficulty: ${selectedDifficulties.joinToString(", ")}" }

    private fun loadPlan() {
        status.text = "Loading plan..."
        LearningApi.learningPlan(this, childUid) { result ->
            if (result is LearningApiResult.Failure) { status.text = result.message; return@learningPlan }
            val body = (result as LearningApiResult.Success).body; body.optJSONObject("plan")?.let(::populatePlan)
            status.text = when { body.optBoolean("generationPending") -> "Generating replacement quizzes..."; body.optString("generationError").isNotBlank() -> body.optString("generationError"); else -> "" }
            renderDrafts(body.optJSONArray("drafts"))
        }
    }
    private fun populatePlan(plan: JSONObject) {
        grade.setSelection(QuizOptions.grades.indexOf(plan.optString("grade")).coerceAtLeast(0))
        selectedSubjects.clear(); values(plan.optJSONArray("subjects")).forEach { subject -> if (subject in QuizOptions.subjects) selectedSubjects += subject else { selectedSubjects += "Other"; otherSubjects.setText(listOf(otherSubjects.text.toString(), subject).filter(String::isNotBlank).joinToString(", ")) } }; updateSubjectButton()
        curriculumNotes.setText(plan.optString("curriculumNotes")); strengths.setText(plan.optString("strengths")); weakAreas.setText(plan.optString("weakAreas"))
        selectedDifficulties.clear(); values(plan.optJSONArray("difficulties")).ifEmpty { listOf(plan.optString("difficulty", "Balanced")) }.forEach { selectedDifficulties += it }; updateDifficultyButton()
        minimumAvailable.value = plan.optInt("minimumAvailable", 3).coerceIn(1, 10); refillCount.value = plan.optInt("refillCount", 2).coerceIn(1, 5); passingScore.value = plan.optInt("minimumScorePercent", 80).coerceIn(1, 100); rewardMinutes.value = plan.optInt("rewardMinutes", 15).coerceIn(1, 240); autoAssign.isChecked = plan.optString("assignmentMode") == "auto_assign"
        extraRewardInputs.forEach { rewardTiers.removeView(it.first.parent as View) }; extraRewardInputs.clear()
        val tiers = plan.optJSONArray("rewardTiers"); for (index in 1 until (tiers?.length() ?: 0)) tiers?.optJSONObject(index)?.let { addRewardTier(it.optInt("minimumScorePercent"), it.optInt("rewardMinutes")) }
    }
    private fun savePlan() {
        val subjects = selectedSubjects.filterNot { it == "Other" }.toMutableList() + otherSubjects.text.toString().split(',').map(String::trim).filter(String::isNotBlank)
        if (subjects.isEmpty()) { status.text = "Choose at least one subject."; return }
        val tiers = JSONArray().put(JSONObject().put("minimumScorePercent", passingScore.value).put("rewardMinutes", rewardMinutes.value))
        for ((score, minutes) in extraRewardInputs) {
            val scoreValue = score.text.toString().toIntOrNull(); val minuteValue = minutes.text.toString().toIntOrNull()
            if (scoreValue !in 1..100 || minuteValue !in 1..240) { status.text = "Enter valid values for every reward tier."; return }
            tiers.put(JSONObject().put("minimumScorePercent", scoreValue).put("rewardMinutes", minuteValue))
        }
        val plan = JSONObject().put("grade", grade.selectedItem.toString()).put("subjects", JSONArray(subjects)).put("curriculumNotes", curriculumNotes.text.toString()).put("strengths", strengths.text.toString()).put("weakAreas", weakAreas.text.toString()).put("difficulties", JSONArray(selectedDifficulties.toList())).put("minimumAvailable", minimumAvailable.value).put("refillCount", refillCount.value).put("assignmentMode", if (autoAssign.isChecked) "auto_assign" else "parent_review").put("minimumScorePercent", passingScore.value).put("rewardMinutes", rewardMinutes.value).put("rewardTiers", tiers)
        save.isEnabled = false; status.text = "Saving plan..."
        LearningApi.setLearningPlan(this, childUid, plan) { result ->
            save.isEnabled = true
            status.text = when (result) { is LearningApiResult.Success -> if (result.body.optBoolean("refillQueued")) "Saved. Replacement quizzes are being prepared." else "Saved. Your learning plan is active."; is LearningApiResult.Failure -> result.message }
            if (result is LearningApiResult.Success) { Toast.makeText(this, "Learning plan saved", Toast.LENGTH_SHORT).show(); loadPlan() }
        }
    }
    private fun addRewardTier(score: Int? = null, minutes: Int? = null) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_reward_tier, rewardTiers, false); val scoreInput = view.findViewById<EditText>(R.id.et_tier_score); val minutesInput = view.findViewById<EditText>(R.id.et_tier_minutes)
        score?.let { scoreInput.setText(it.toString()) }; minutes?.let { minutesInput.setText(it.toString()) }; view.findViewById<View>(R.id.btn_remove_reward_tier).setOnClickListener { rewardTiers.removeView(view); extraRewardInputs.removeAll { it.first === scoreInput } }; extraRewardInputs += scoreInput to minutesInput; rewardTiers.addView(view)
    }
    private fun renderDrafts(rows: JSONArray?) { drafts.removeAllViews(); if (rows == null || rows.length() == 0) { drafts.addView(TextView(this).apply { text = "No quizzes waiting for review."; setTextColor(getColor(R.color.l2e_muted)) }); return }; for (index in 0 until rows.length()) { val row = rows.optJSONObject(index) ?: continue; val quiz = row.optJSONObject("quiz") ?: continue; drafts.addView(Button(this).apply { text = "Review: ${quiz.optString("title", "Untitled quiz")}"; setBackgroundResource(R.drawable.bg_btn_secondary); setTextColor(getColor(R.color.l2e_forest)); setOnClickListener { reviewDraft(row, quiz) } }) }
    }
    private fun reviewDraft(row: JSONObject, quiz: JSONObject) { AlertDialog.Builder(this).setTitle(quiz.optString("title", "Quiz draft")).setMessage("${quiz.optString("subject")} · ${quiz.optString("grade")}\n\n${values(quiz.optJSONArray("questions")).size} questions ready for review.").setNeutralButton("Close", null).setNegativeButton("Reject") { _, _ -> decideDraft(row.optString("id"), false) }.setPositiveButton("Approve") { _, _ -> decideDraft(row.optString("id"), true) }.show() }
    private fun decideDraft(id: String, approve: Boolean) { if (id.isBlank()) return; status.text = if (approve) "Approving quiz..." else "Rejecting quiz..."; LearningApi.reviewLearningPlanDraft(this, id, approve) { result -> status.text = if (result is LearningApiResult.Success) if (approve) "Quiz assigned." else "Quiz rejected." else (result as LearningApiResult.Failure).message; if (result is LearningApiResult.Success) loadPlan() } }
    private fun picker(picker: NumberPicker, max: Int, initial: Int) { picker.minValue = 1; picker.maxValue = max; picker.value = initial; picker.wrapSelectorWheel = false; picker.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS }
    private fun values(values: JSONArray?): List<String> = buildList { for (index in 0 until (values?.length() ?: 0)) values?.optString(index)?.takeIf(String::isNotBlank)?.let(::add) }
    companion object { const val EXTRA_CHILD_UID = "learning_plan_child_uid"; const val EXTRA_CHILD_NAME = "learning_plan_child_name" }
}
