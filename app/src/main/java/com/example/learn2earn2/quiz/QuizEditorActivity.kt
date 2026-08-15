package com.example.learn2earn2.quiz

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.learn2earn2.R
import com.example.learn2earn2.account.ParentAccount
import com.example.learn2earn2.image.CropImageActivity
import com.example.learn2earn2.ui.KeyboardDismissActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import org.json.JSONObject
import org.json.JSONArray

class QuizEditorActivity : KeyboardDismissActivity() {

    private val db = FirebaseDatabase.getInstance("https://learn2earn-bc2bc-default-rtdb.asia-southeast1.firebasedatabase.app/")
    private lateinit var questionsContainer: LinearLayout
    private lateinit var quizName: EditText
    private lateinit var subjectSpinner: Spinner
    private lateinit var gradeSpinner: Spinner
    private lateinit var passingScoreInput: EditText
    private lateinit var rewardMinutesInput: EditText
    private lateinit var scoreImproveCooldownInput: EditText
    private lateinit var rewardTiersContainer: LinearLayout
    private lateinit var coverContainer: View
    private lateinit var thumbnail: ImageView
    private lateinit var defaultCover: ImageView
    private lateinit var emptyCover: TextView
    private lateinit var coverScrim: View
    private lateinit var coverActions: View
    private val questions = mutableListOf<QuestionInput>()
    private var thumbnailUri: String? = null
    private var quizId: String? = null
    private var pendingImageTarget: ImageTarget? = null
    private val extraRewardTierInputs = mutableListOf<Pair<EditText, EditText>>()
    private var aiDrafts: JSONArray? = null
    private var aiDraftIndex = 0

    private val sourcePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            pendingImageTarget = null
            return@registerForActivityResult
        }
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val target = pendingImageTarget ?: return@registerForActivityResult
        cropLauncher.launch(CropImageActivity.intent(this, uri.toString(), target.aspectRatio))
    }

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val croppedUri = result.data?.getStringExtra(CropImageActivity.EXTRA_CROPPED_URI) ?: return@registerForActivityResult
        when (val target = pendingImageTarget) {
            ImageTarget.Cover -> {
                thumbnailUri = croppedUri
                showCover()
            }
            is ImageTarget.Question -> {
                target.question.imageUri = croppedUri
                setImage(target.question.image, croppedUri)
            }
            is ImageTarget.Choice -> {
                target.choice.imageUri = croppedUri
                setImage(target.choice.image, croppedUri)
            }
            null -> Unit
        }
        pendingImageTarget = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz_editor)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
        questionsContainer = findViewById(R.id.ll_questions)
        quizName = findViewById(R.id.et_quiz_name)
        subjectSpinner = findViewById(R.id.sp_quiz_subject)
        gradeSpinner = findViewById(R.id.sp_quiz_grade)
        passingScoreInput = findViewById(R.id.et_passing_score)
        rewardMinutesInput = findViewById(R.id.et_reward_minutes)
        scoreImproveCooldownInput = findViewById(R.id.et_score_improve_cooldown)
        rewardTiersContainer = findViewById(R.id.ll_reward_tiers)
        coverContainer = findViewById(R.id.fl_quiz_cover)
        thumbnail = findViewById(R.id.iv_quiz_thumbnail)
        defaultCover = findViewById(R.id.iv_default_quiz_cover)
        emptyCover = findViewById(R.id.tv_cover_empty)
        coverScrim = findViewById(R.id.view_cover_scrim)
        coverActions = findViewById(R.id.ll_cover_actions)
        quizId = intent.getStringExtra(EXTRA_QUIZ_ID)

        setSpinnerItems(subjectSpinner, listOf(SELECT_SUBJECT) + QuizOptions.subjects)
        setSpinnerItems(gradeSpinner, listOf(SELECT_GRADE) + QuizOptions.grades)
        passingScoreInput.setText(DEFAULT_POLICY.passingScorePercent.toString())
        rewardMinutesInput.setText(DEFAULT_POLICY.rewardMinutes.toString())
        scoreImproveCooldownInput.setText(DEFAULT_POLICY.scoreImproveCooldownMinutes.toString())
        findViewById<Button>(R.id.btn_editor_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_save_quiz).setOnClickListener { saveQuiz() }
        findViewById<Button>(R.id.btn_add_question).setOnClickListener { addQuestion() }
        findViewById<Button>(R.id.btn_add_reward_tier).setOnClickListener { addRewardTier() }
        coverContainer.setOnClickListener { onCoverClicked() }
        findViewById<ImageButton>(R.id.btn_edit_cover).setOnClickListener { selectImage(ImageTarget.Cover) }
        findViewById<ImageButton>(R.id.btn_delete_cover).setOnClickListener {
            thumbnailUri = null
            coverActions.visibility = View.GONE
            coverScrim.visibility = View.GONE
            showCover()
        }

        if (quizId == null) {
            showCover()
            aiDrafts = intent.getStringExtra(EXTRA_AI_DRAFTS_JSON)?.let(::JSONArray)
            aiDraftIndex = intent.getIntExtra(EXTRA_AI_DRAFT_INDEX, 0)
            val draft = aiDrafts?.optJSONObject(aiDraftIndex)?.toString()
                ?: intent.getStringExtra(EXTRA_AI_DRAFT_JSON)
            if (draft != null && runCatching { populateAiDraft(JSONObject(draft)) }.isFailure) {
                Toast.makeText(this, "Could not load AI quiz draft", Toast.LENGTH_LONG).show()
                addQuestion()
            } else if (draft == null) {
                addQuestion()
            }
        } else {
            loadQuiz(quizId!!)
        }
    }

    private fun loadQuiz(id: String) {
        val parentId = ParentAccount.ownerId(this) ?: return
        db.getReference("users/$parentId/quizzes/$id").get()
            .addOnSuccessListener { snapshot -> populateQuiz(snapshot) }
            .addOnFailureListener { Toast.makeText(this, "Could not open quiz", Toast.LENGTH_LONG).show() }
    }

    private fun populateQuiz(snapshot: DataSnapshot) {
        if (!snapshot.exists()) {
            Toast.makeText(this, "Quiz no longer exists", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        quizName.setText(snapshot.child("title").getValue(String::class.java).orEmpty())
        setSpinnerValue(subjectSpinner, snapshot.child("subject").getValue(String::class.java).orEmpty(), SELECT_SUBJECT)
        setSpinnerValue(gradeSpinner, snapshot.child("grade").getValue(String::class.java).orEmpty(), SELECT_GRADE)
        passingScoreInput.setText(
            ((snapshot.child("passingScorePercent").value as? Number)?.toInt()
                ?: DEFAULT_POLICY.passingScorePercent).toString()
        )
        rewardMinutesInput.setText(
            ((snapshot.child("rewardMinutes").value as? Number)?.toInt()
                ?: DEFAULT_POLICY.rewardMinutes).toString()
        )
        scoreImproveCooldownInput.setText(
            ((snapshot.child("scoreImproveCooldownMinutes").value as? Number)?.toInt()
                ?: DEFAULT_POLICY.scoreImproveCooldownMinutes).toString()
        )
        snapshot.child("rewardTiers").children.mapNotNull { tier ->
            val score = (tier.child("minimumScorePercent").value as? Number)?.toInt()
            val minutes = (tier.child("rewardMinutes").value as? Number)?.toInt()
            if (score != null && minutes != null) RewardTier(score, minutes) else null
        }.sortedByDescending { it.minimumScorePercent }.drop(1).forEach(::addRewardTier)
        thumbnailUri = snapshot.child("thumbnailUri").getValue(String::class.java)
        showCover()
        questionsContainer.removeAllViews()
        questions.clear()
        snapshot.child("questions").children.forEach { questionSnapshot ->
            val question = addQuestion(0)
            question.prompt.setText(questionSnapshot.child("prompt").getValue(String::class.java).orEmpty())
            question.multipleAnswers.isChecked = questionSnapshot.child("allowMultipleAnswers").getValue(Boolean::class.java) ?: false
            question.imageUri = questionSnapshot.child("imageUri").getValue(String::class.java)
            question.imageUri?.let { setImage(question.image, it) }
            questionSnapshot.child("choices").children.forEach { choiceSnapshot ->
                val choice = addChoice(question)
                choice.text.setText(choiceSnapshot.child("text").getValue(String::class.java).orEmpty())
                choice.correct.isChecked = choiceSnapshot.child("isCorrect").getValue(Boolean::class.java) ?: false
                choice.imageUri = choiceSnapshot.child("imageUri").getValue(String::class.java)
                choice.imageUri?.let { setImage(choice.image, it) }
            }
        }
        if (questions.isEmpty()) addQuestion()
    }

    private fun populateAiDraft(draft: JSONObject) {
        passingScoreInput.setText("90")
        rewardMinutesInput.setText("15")
        addRewardTier(RewardTier(80, 10))
        addRewardTier(RewardTier(70, 5))
        quizName.setText(draft.getString("title"))
        setSpinnerValue(subjectSpinner, draft.getString("subject"), SELECT_SUBJECT)
        setSpinnerValue(gradeSpinner, draft.getString("grade"), SELECT_GRADE)
        questionsContainer.removeAllViews()
        questions.clear()
        val draftQuestions = draft.getJSONArray("questions")
        for (index in 0 until draftQuestions.length()) {
            val questionJson = draftQuestions.getJSONObject(index)
            val question = addQuestion(0)
            question.prompt.setText(questionJson.getString("prompt"))
            question.multipleAnswers.isChecked = false
            val choices = questionJson.getJSONArray("choices")
            for (choiceIndex in 0 until choices.length()) {
                val choiceJson = choices.getJSONObject(choiceIndex)
                val choice = addChoice(question)
                choice.text.setText(choiceJson.getString("text"))
                choice.correct.isChecked = choiceJson.getBoolean("isCorrect")
            }
        }
        if (questions.isEmpty()) addQuestion()
    }

    private fun onCoverClicked() {
        if (thumbnailUri == null && quizId == null) {
            selectImage(ImageTarget.Cover)
        } else {
            val visible = coverActions.visibility != View.VISIBLE
            coverActions.visibility = if (visible) View.VISIBLE else View.GONE
            coverScrim.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun showCover() {
        val hasCustomCover = thumbnailUri != null
        val showDefault = !hasCustomCover && quizId != null
        emptyCover.visibility = if (hasCustomCover || showDefault) View.GONE else View.VISIBLE
        defaultCover.visibility = if (showDefault) View.VISIBLE else View.GONE
        thumbnail.visibility = if (hasCustomCover) View.VISIBLE else View.GONE
        if (hasCustomCover) setImage(thumbnail, thumbnailUri!!)
        coverActions.visibility = View.GONE
        coverScrim.visibility = View.GONE
        findViewById<ImageButton>(R.id.btn_delete_cover).visibility = if (hasCustomCover) View.VISIBLE else View.GONE
    }

    private fun selectImage(target: ImageTarget) {
        pendingImageTarget = target
        sourcePicker.launch(arrayOf("image/*"))
    }

    private fun addQuestion(initialChoiceCount: Int = 2): QuestionInput {
        val view = LayoutInflater.from(this).inflate(R.layout.item_quiz_question, questionsContainer, false)
        val question = QuestionInput(
            root = view,
            prompt = view.findViewById(R.id.et_question_prompt),
            image = view.findViewById(R.id.iv_question_image),
            multipleAnswers = view.findViewById(R.id.switch_multiple_answers),
            choicesContainer = view.findViewById(R.id.ll_choices)
        )
        view.findViewById<Button>(R.id.btn_add_choice).setOnClickListener { addChoice(question) }
        view.findViewById<ImageButton>(R.id.btn_question_image).setOnClickListener { selectImage(ImageTarget.Question(question)) }
        view.findViewById<ImageButton>(R.id.btn_remove_question).setOnClickListener {
            questions.remove(question)
            questionsContainer.removeView(question.root)
            renumberQuestions()
        }
        question.multipleAnswers.setOnCheckedChangeListener { _, allowMultiple ->
            if (!allowMultiple) question.choices.firstOrNull { it.correct.isChecked }?.let { selected ->
                question.choices.filterNot { it === selected }.forEach { it.correct.isChecked = false }
            }
        }
        repeat(initialChoiceCount) { addChoice(question) }
        questions += question
        questionsContainer.addView(view)
        renumberQuestions()
        return question
    }

    private fun addChoice(question: QuestionInput): ChoiceInput {
        val view = LayoutInflater.from(this).inflate(R.layout.item_quiz_choice, question.choicesContainer, false)
        val choice = ChoiceInput(
            root = view,
            text = view.findViewById(R.id.et_choice_text),
            correct = view.findViewById(R.id.cb_choice_correct),
            image = view.findViewById(R.id.iv_choice_image)
        )
        choice.correct.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !question.multipleAnswers.isChecked) {
                question.choices.filterNot { it === choice }.forEach { it.correct.isChecked = false }
            }
        }
        view.findViewById<ImageButton>(R.id.btn_choice_image).setOnClickListener { selectImage(ImageTarget.Choice(choice)) }
        view.findViewById<ImageButton>(R.id.btn_remove_choice).setOnClickListener {
            if (question.choices.size <= 2) {
                Toast.makeText(this, "A question needs at least 2 choices", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            question.choices.remove(choice)
            question.choicesContainer.removeView(choice.root)
        }
        question.choices += choice
        question.choicesContainer.addView(view)
        return choice
    }

    private fun renumberQuestions() {
        questions.forEachIndexed { index, question ->
            question.root.findViewById<TextView>(R.id.tv_question_number).text = "Question ${index + 1}"
        }
    }

    private fun saveQuiz() {
        val title = quizName.text.toString().trim()
        if (title.isEmpty()) {
            quizName.error = "Add a quiz name"
            quizName.requestFocus()
            return
        }
        val policy = readRewardPolicy() ?: return
        val questionData = mutableListOf<Map<String, Any>>()
        for ((index, question) in questions.withIndex()) {
            val prompt = question.prompt.text.toString().trim()
            val hasQuestionContent = prompt.isNotEmpty() ||
                question.imageUri != null ||
                question.choices.any {
                    it.text.text.toString().isNotBlank() || it.imageUri != null
                }
            if (!hasQuestionContent) continue
            if (prompt.isEmpty()) {
                Toast.makeText(
                    this,
                    "Question ${index + 1} needs text. Images can be added as context.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            val imageOnlyChoice = question.choices.indexOfFirst {
                it.imageUri != null && it.text.text.toString().isBlank()
            }
            if (imageOnlyChoice >= 0) {
                Toast.makeText(
                    this,
                    "Question ${index + 1}, choice ${imageOnlyChoice + 1} needs text.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            val completedChoices = question.choices.filter {
                it.text.text.toString().isNotBlank()
            }
            val choices = completedChoices.map { choice ->
                val text = choice.text.text.toString().trim()
                val imageData = choice.imageUri?.let {
                    portableImageData(it, "Answer image") ?: return
                }
                buildMap<String, Any> {
                    put("text", text)
                    put("isCorrect", choice.correct.isChecked)
                    choice.imageUri?.let { put("imageUri", it) }
                    imageData?.let { put("imageData", it) }
                }
            }
            if (choices.size < 2) {
                Toast.makeText(this, "Question ${index + 1} needs at least 2 choices.", Toast.LENGTH_SHORT).show()
                return
            }
            val correctCount = completedChoices.count { it.correct.isChecked }
            if (correctCount == 0) {
                Toast.makeText(this, "Question ${index + 1} needs a correct choice.", Toast.LENGTH_SHORT).show()
                return
            }
            if (!question.multipleAnswers.isChecked && correctCount != 1) {
                Toast.makeText(
                    this,
                    "Question ${index + 1} is single-answer, so choose exactly one correct choice.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            val imageData = question.imageUri?.let {
                portableImageData(it, "Question image") ?: return
            }
            questionData += buildMap {
                put("prompt", prompt)
                put("allowMultipleAnswers", question.multipleAnswers.isChecked)
                put("choices", choices)
                question.imageUri?.let { put("imageUri", it) }
                imageData?.let { put("imageData", it) }
            }
        }
        if (questionData.isEmpty()) {
            Toast.makeText(this, "Add at least one complete question.", Toast.LENGTH_SHORT).show()
            return
        }
        val parentId = ParentAccount.ownerId(this) ?: return
        val coverImageData = thumbnailUri?.let {
            portableImageData(it, "Cover image") ?: return
        }
        val quizRef = quizId?.let { db.getReference("users/$parentId/quizzes/$it") }
            ?: db.getReference("users/$parentId/quizzes").push()
        val subject = subjectSpinner.selectedItem.toString().takeUnless { it == SELECT_SUBJECT }.orEmpty()
        val grade = gradeSpinner.selectedItem.toString().takeUnless { it == SELECT_GRADE }.orEmpty()
        if (subject.isBlank() || grade.isBlank()) {
            Toast.makeText(this, "Choose a subject and grade before saving.", Toast.LENGTH_SHORT).show()
            return
        }
        val quizData = buildMap<String, Any> {
            put("title", title)
            put("subject", subject)
            put("grade", grade)
            put("questions", questionData)
            put("passingScorePercent", policy.passingScorePercent)
            put("rewardMinutes", policy.rewardMinutes)
            put("rewardTiers", policy.normalizedTiers().map { mapOf("minimumScorePercent" to it.minimumScorePercent, "rewardMinutes" to it.rewardMinutes) })
            put("scoreImproveCooldownMinutes", policy.scoreImproveCooldownMinutes)
            put("maxAttempts", 1)
            put("updatedAt", ServerValue.TIMESTAMP)
            thumbnailUri?.let { put("thumbnailUri", it) }
            coverImageData?.let { put("coverImageData", it) }
        }
        quizRef.setValue(quizData)
            .addOnSuccessListener {
                val nextIndex = aiDraftIndex + 1
                if (aiDrafts != null && nextIndex < aiDrafts!!.length()) {
                    startActivity(Intent(this, QuizEditorActivity::class.java)
                        .putExtra(EXTRA_AI_DRAFTS_JSON, aiDrafts.toString())
                        .putExtra(EXTRA_AI_DRAFT_INDEX, nextIndex))
                } else {
                    Toast.makeText(this, "Quiz saved", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .addOnFailureListener { Toast.makeText(this, "Could not save quiz", Toast.LENGTH_LONG).show() }
    }

    private fun portableImageData(uri: String, label: String): String? =
        QuizImageData.encode(this, uri).also { imageData ->
            if (imageData == null) {
                Toast.makeText(this, "$label could not be prepared for child devices.", Toast.LENGTH_LONG).show()
            }
        }

    private fun readRewardPolicy(): QuizRewardPolicy? {
        val passingScore = validatedNumber(passingScoreInput, "Pass score", 1..100) ?: return null
        val rewardMinutes = validatedNumber(rewardMinutesInput, "Reward", 1..240) ?: return null
        val cooldown = validatedNumber(scoreImproveCooldownInput, "Cooldown", 0..10_080) ?: return null
        val tiers = mutableListOf(RewardTier(passingScore, rewardMinutes))
        extraRewardTierInputs.forEach { (scoreInput, minutesInput) ->
            val score = validatedNumber(scoreInput, "Score threshold", 1..100) ?: return null
            val minutes = validatedNumber(minutesInput, "Reward", 1..240) ?: return null
            tiers += RewardTier(score, minutes)
        }
        if (tiers.distinctBy { it.minimumScorePercent }.size != tiers.size) {
            Toast.makeText(this, "Each score threshold must be different.", Toast.LENGTH_SHORT).show()
            return null
        }
        val ascending = tiers.sortedBy { it.minimumScorePercent }
        if (ascending.zipWithNext().any { (lower, higher) -> higher.rewardMinutes <= lower.rewardMinutes }) {
            Toast.makeText(this, "Higher score thresholds must earn more time.", Toast.LENGTH_SHORT).show()
            return null
        }
        return QuizRewardPolicy(
            passingScorePercent = tiers.minOf { it.minimumScorePercent },
            rewardMinutes = tiers.maxOf { it.rewardMinutes },
            maxAttempts = 1,
            scoreImproveCooldownMinutes = cooldown,
            rewardTiers = tiers
        )
    }

    private fun addRewardTier(tier: RewardTier = RewardTier(70, 5)) {
        val view = layoutInflater.inflate(R.layout.item_reward_tier, rewardTiersContainer, false)
        val score = view.findViewById<EditText>(R.id.et_tier_score).apply { setText(tier.minimumScorePercent.toString()) }
        val minutes = view.findViewById<EditText>(R.id.et_tier_minutes).apply { setText(tier.rewardMinutes.toString()) }
        val pair = score to minutes
        view.findViewById<ImageButton>(R.id.btn_remove_reward_tier).setOnClickListener {
            extraRewardTierInputs.remove(pair)
            rewardTiersContainer.removeView(view)
        }
        extraRewardTierInputs += pair
        rewardTiersContainer.addView(view)
    }

    private fun validatedNumber(input: EditText, label: String, range: IntRange): Int? {
        val value = input.text.toString().trim().toIntOrNull()
        if (value == null || value !in range) {
            input.error = "$label must be ${range.first}-${range.last}"
            input.requestFocus()
            return null
        }
        return value
    }

    private fun setSpinnerItems(spinner: Spinner, values: List<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
    }

    private fun setSpinnerValue(spinner: Spinner, value: String, fallback: String) {
        val wanted = value.ifBlank { fallback }
        val index = (0 until spinner.count).firstOrNull { spinner.getItemAtPosition(it) == wanted } ?: 0
        spinner.setSelection(index)
    }

    private fun setImage(image: ImageView, uri: String) {
        image.setImageURI(Uri.parse(uri))
        image.visibility = View.VISIBLE
    }

    private sealed class ImageTarget(val aspectRatio: Float) {
        object Cover : ImageTarget(16f / 9f)
        class Question(val question: QuestionInput) : ImageTarget(4f / 3f)
        class Choice(val choice: ChoiceInput) : ImageTarget(4f / 3f)
    }

    private class QuestionInput(
        val root: View,
        val prompt: EditText,
        val image: ImageView,
        val multipleAnswers: Switch,
        val choicesContainer: LinearLayout,
        val choices: MutableList<ChoiceInput> = mutableListOf(),
        var imageUri: String? = null
    )

    private class ChoiceInput(
        val root: View,
        val text: EditText,
        val correct: CheckBox,
        val image: ImageView,
        var imageUri: String? = null
    )

    companion object {
        const val EXTRA_QUIZ_ID = "quiz_id"
        const val EXTRA_AI_DRAFT_JSON = "ai_draft_json"
        const val EXTRA_AI_DRAFTS_JSON = "ai_drafts_json"
        const val EXTRA_AI_DRAFT_INDEX = "ai_draft_index"
        private const val SELECT_SUBJECT = "Select subject"
        private const val SELECT_GRADE = "Select grade"
        private val DEFAULT_POLICY = QuizRewardPolicy()
    }
}
