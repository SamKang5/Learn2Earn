package com.example.learn2earn2.child

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.learn2earn2.R
import com.example.learn2earn2.quiz.LearningApi
import com.example.learn2earn2.quiz.LearningApiResult
import com.example.learn2earn2.quiz.QuizAnswer
import com.example.learn2earn2.quiz.RewardTier
import com.example.learn2earn2.quiz.QuizRewardPolicy
import com.example.learn2earn2.quiz.QuizRewardStore
import com.example.learn2earn2.quiz.QuizScore
import com.example.learn2earn2.quiz.QuizScorer
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ChildQuizActivity : AppCompatActivity() {

    private val db by lazy { ChildFirebaseSession.database(this) }
    private val auth by lazy { ChildFirebaseSession.auth(this) }
    private lateinit var parentId: String
    private lateinit var childId: String
    private lateinit var childRef: DatabaseReference

    private lateinit var loading: View
    private lateinit var emptyState: View
    private lateinit var content: View
    private lateinit var result: View
    private lateinit var choicesContainer: LinearLayout
    private lateinit var continueButton: Button

    private var quiz: ChildQuiz? = null
    private var attemptCount = 0
    private var questionIndex = 0
    private var selectedAnswers = mutableListOf<MutableSet<Int>>()
    private var remoteSubmissionId: String? = null
    private var restoredQuizId: String? = null
    private var restoredQuestionIndex = 0
    private var restoredAnswers: List<Set<Int>>? = null
    private var selectedQuizId: String? = null
    private var selectedSecureAssignment = false
    private var reviewMode = false
    private var reviewHasAnswerKey = false
    private var reviewScorePercent = 0
    private var reviewHistory: JSONArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_quiz)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }

        loading = findViewById(R.id.quiz_loading)
        emptyState = findViewById(R.id.quiz_empty_state)
        content = findViewById(R.id.quiz_content)
        result = findViewById(R.id.quiz_result)
        choicesContainer = findViewById(R.id.ll_child_quiz_choices)
        continueButton = findViewById(R.id.btn_quiz_continue)
        findViewById<View>(R.id.btn_child_quiz_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_quiz_empty_done).setOnClickListener { finish() }
        continueButton.setOnClickListener { onContinue() }

        restoredQuizId = savedInstanceState?.getString(STATE_QUIZ_ID)
        restoredQuestionIndex = savedInstanceState?.getInt(STATE_QUESTION_INDEX, 0) ?: 0
        restoredAnswers = savedInstanceState?.getString(STATE_SELECTED_ANSWERS)
            ?.let(::parseSavedAnswers)
        selectedQuizId = intent.getStringExtra(EXTRA_QUIZ_ID)
        selectedSecureAssignment = intent.getBooleanExtra(EXTRA_SECURE_ASSIGNMENT, false)
        reviewMode = intent.getBooleanExtra(EXTRA_REVIEW, false)

        val prefs = getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
        parentId = prefs.getString(ChildLockService.PARENT_ID, null).orEmpty()
        childId = prefs.getString(ChildLockService.CHILD_ID, null).orEmpty()
        if (parentId.isBlank() || childId.isBlank()) {
            showEmpty("Pair this device before taking a quiz.")
            return
        }
        childRef = db.getReference("users/$parentId/children/$childId")
        loadNextQuiz()
    }

    private fun loadNextQuiz() {
        showOnly(loading)
        if (reviewMode && selectedSecureAssignment && !selectedQuizId.isNullOrBlank()) {
            loadSecureReview(selectedQuizId!!)
            return
        }
        val secureLearning = getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
            .getBoolean("secure_learning_enabled", false)
        if (secureLearning) {
            LearningApi.balance(this, auth) { balanceResult ->
                if (balanceResult is LearningApiResult.Success) {
                    val recoveredMinutes = QuizRewardStore.reconcileServerTotal(
                        this,
                        balanceResult.body.optInt("earnedMinutes", 0)
                    )
                    if (recoveredMinutes > 0) ChildLockService.start(this)
                    mirrorStudyEnergy(balanceResult.body)
                }
                loadSecureQuiz(selectedQuizId.takeIf { selectedSecureAssignment })
            }
            return
        }
        loadLegacyQuiz(selectedQuizId)
    }

    private fun loadSecureReview(assignmentId: String) {
        LearningApi.quizReview(this, assignmentId, auth) { result ->
            if (result is LearningApiResult.Failure) {
                showEmpty(result.message)
                return@quizReview
            }
            val review = (result as LearningApiResult.Success).body.optJSONObject("review")
            val payload = review?.optJSONObject("quiz")
            if (review == null || payload == null) {
                showEmpty("This review is not available.")
                return@quizReview
            }
            val assignment = JSONObject()
                .put("id", assignmentId)
                .put("quiz", payload)
                .put("minimumScorePercent", 0)
                .put("rewardMinutes", review.optInt("rewardMinutes"))
                .put("maxAttempts", review.optInt("attemptNumber", 1))
            val candidate = parseRemoteQuiz(assignment)
            if (candidate == null || review.optJSONArray("answers") == null) {
                showEmpty("This review could not be loaded.")
                return@quizReview
            }
            reviewHasAnswerKey = review.optBoolean("answerKeyAvailable", false)
            reviewHistory = review.optJSONArray("history") ?: JSONArray().put(review)
            showReviewRun(candidate, 0)
            beginQuiz(candidate, keepAnswers = true)
        }
    }

    private fun showReviewRun(activeQuiz: ChildQuiz, index: Int) {
        val run = reviewHistory?.optJSONObject(index) ?: return
        attemptCount = run.optInt("attemptNumber", 1) - 1
        reviewScorePercent = run.optInt("scorePercent")
        selectedAnswers = MutableList(activeQuiz.questions.size) { mutableSetOf() }
        val answers = run.optJSONArray("answers") ?: JSONArray()
        for (question in 0 until minOf(answers.length(), selectedAnswers.size)) {
            val selected = answers.optJSONArray(question) ?: continue
            for (choice in 0 until selected.length()) {
                selectedAnswers[question].add(selected.optInt(choice))
            }
        }
    }

    private fun chooseReviewRun() {
        val activeQuiz = quiz ?: return
        val history = reviewHistory ?: return
        if (history.length() < 2) return
        val labels = Array(history.length()) { index ->
            val run = history.optJSONObject(index)
            "Run ${run?.optInt("attemptNumber", index + 1)}  •  ${run?.optInt("scorePercent", 0)}%"
        }
        AlertDialog.Builder(this)
            .setTitle("Score history")
            .setItems(labels) { _, index ->
                showReviewRun(activeQuiz, index)
                questionIndex = 0
                renderQuestion()
            }
            .show()
    }

    private fun loadSecureQuiz(assignmentId: String? = null) {
        LearningApi.nextQuiz(this, assignmentId, auth) { apiResult ->
            when (apiResult) {
                is LearningApiResult.Success -> {
                    val assignment = apiResult.body.optJSONObject("assignment")
                    val remoteQuiz = assignment?.let(::parseRemoteQuiz)
                    if (remoteQuiz == null) {
                        showEmpty("No assigned quiz is ready. Ask your parent to assign one from the quiz vault.")
                    } else {
                        attemptCount = assignment.optInt("attemptCount", 0)
                        beginQuiz(remoteQuiz)
                    }
                }
                is LearningApiResult.Failure -> showEmpty(apiResult.message)
            }
        }
    }

    private fun loadLegacyQuiz(quizId: String? = null) {
        showOnly(loading)
        db.getReference("users/$parentId/quizzes").get()
            .addOnSuccessListener { quizzes ->
                childRef.child("quizProgress").get()
                    .addOnSuccessListener { progress -> selectQuiz(quizzes, progress, quizId) }
                    .addOnFailureListener {
                        showEmpty("Could not load quiz progress. Check your connection and try again.")
                    }
            }
            .addOnFailureListener {
                showEmpty("Could not load quizzes. Check your connection and try again.")
            }
    }

    private fun selectQuiz(quizzes: DataSnapshot, progressRoot: DataSnapshot, quizId: String? = null) {
        val candidate = quizzes.children
            .mapNotNull(::parseQuiz)
            .sortedByDescending { it.updatedAt }
            .firstOrNull { item ->
                val progress = progressRoot.child(item.id)
                val attempts = (progress.child("attemptCount").value as? Number)?.toInt() ?: 0
                val complete = progress.child("completed").getValue(Boolean::class.java) ?: false
                (quizId == null || item.id == quizId) && !complete && attempts < item.policy.maxAttempts
            }

        if (candidate == null) {
            showEmpty("No quiz is ready yet. Ask your parent to create or update one.")
            return
        }

        val progress = progressRoot.child(candidate.id)
        attemptCount = (progress.child("attemptCount").value as? Number)?.toInt() ?: 0
        beginQuiz(candidate)
    }

    private fun beginQuiz(candidate: ChildQuiz, keepAnswers: Boolean = false) {
        quiz = candidate
        remoteSubmissionId = candidate.remoteAssignmentId?.let { assignmentId ->
            getSharedPreferences(SUBMISSION_PREFS, Context.MODE_PRIVATE)
                .getString(submissionKey(assignmentId), null)
        }
        if (!keepAnswers) selectedAnswers = MutableList(candidate.questions.size) { mutableSetOf() }
        questionIndex = 0
        if (restoredQuizId == candidate.id) {
            restoredAnswers
                ?.takeIf { it.size == candidate.questions.size }
                ?.let { answers ->
                    selectedAnswers = answers.mapIndexed { index, selected ->
                        selected.filterTo(mutableSetOf()) {
                            it in candidate.questions[index].choices.indices
                        }
                    }.toMutableList()
                }
            questionIndex = restoredQuestionIndex.coerceIn(candidate.questions.indices)
        }
        restoredQuizId = null
        restoredAnswers = null
        restoredQuestionIndex = 0
        findViewById<TextView>(R.id.tv_child_quiz_title).text = candidate.title
        findViewById<TextView>(R.id.tv_child_quiz_meta).text = listOf(
            candidate.subject,
            candidate.grade,
            "up to ${candidate.policy.normalizedTiers().maxOf { it.rewardMinutes }} min"
        ).filter { it.isNotBlank() }.joinToString("  |  ")
        renderQuestion()
    }

    private fun parseRemoteQuiz(assignment: JSONObject): ChildQuiz? {
        val assignmentId = assignment.optString("id").takeIf { it.isNotBlank() } ?: return null
        val payload = assignment.optJSONObject("quiz") ?: return null
        val questionsJson = payload.optJSONArray("questions") ?: return null
        val questions = buildList {
            for (questionIndex in 0 until questionsJson.length()) {
                val question = questionsJson.optJSONObject(questionIndex) ?: continue
                val prompt = question.optString("prompt").trim()
                val choicesJson = question.optJSONArray("choices") ?: continue
                val choices = buildList {
                    for (choiceIndex in 0 until choicesJson.length()) {
                        val choiceJson = choicesJson.optJSONObject(choiceIndex)
                        val text = choiceJson?.optString("text").orEmpty().trim()
                        if (text.isNotBlank()) add(ChildChoice(
                            text,
                            choiceJson?.optBoolean("isCorrect", false) ?: false,
                            choiceJson?.optString("imageData")?.takeIf { it.isNotBlank() }
                        ))
                    }
                }
                if (prompt.isNotBlank() && choices.size >= 2) {
                    add(
                        ChildQuestion(
                            prompt = prompt,
                            allowMultipleAnswers = question.optBoolean("allowMultipleAnswers", false),
                            choices = choices,
                            imageData = question.optString("imageData").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
        if (questions.isEmpty()) return null
        return ChildQuiz(
            id = assignmentId,
            title = payload.optString("title").ifBlank { "Untitled quiz" },
            subject = payload.optString("subject"),
            grade = payload.optString("grade"),
            updatedAt = 0,
            policy = QuizRewardPolicy(
                passingScorePercent = assignment.optInt("minimumScorePercent", 80),
                rewardMinutes = assignment.optInt("rewardMinutes", 15),
                maxAttempts = 1,
                scoreImproveCooldownMinutes = assignment.optInt("scoreImproveCooldownMinutes", 60),
                rewardTiers = assignment.optJSONArray("rewardTiers")?.let { tiers ->
                    buildList {
                        for (index in 0 until tiers.length()) {
                            val tier = tiers.optJSONObject(index) ?: continue
                            add(RewardTier(tier.optInt("minimumScorePercent"), tier.optInt("rewardMinutes")))
                        }
                    }
                }.orEmpty().ifEmpty {
                    listOf(RewardTier(assignment.optInt("minimumScorePercent", 80), assignment.optInt("rewardMinutes", 15)))
                }
            ).normalized(),
            questions = questions,
            remoteAssignmentId = assignmentId,
            coverImageData = payload.optString("coverImageData").takeIf { it.isNotBlank() }
        )
    }

    private fun parseQuiz(snapshot: DataSnapshot): ChildQuiz? {
        val id = snapshot.key ?: return null
        val questions = snapshot.child("questions").children.mapNotNull { questionSnapshot ->
            val prompt = questionSnapshot.child("prompt").getValue(String::class.java).orEmpty().trim()
            if (prompt.isBlank()) return@mapNotNull null
            val choices = questionSnapshot.child("choices").children.mapNotNull { choiceSnapshot ->
                val text = choiceSnapshot.child("text").getValue(String::class.java).orEmpty().trim()
                if (text.isBlank()) return@mapNotNull null
                ChildChoice(
                    text = text,
                    correct = choiceSnapshot.child("isCorrect").getValue(Boolean::class.java) ?: false,
                    imageData = choiceSnapshot.child("imageData").getValue(String::class.java)
                )
            }
            if (choices.size < 2 || choices.none { it.correct }) return@mapNotNull null
            ChildQuestion(
                prompt = prompt,
                allowMultipleAnswers = questionSnapshot.child("allowMultipleAnswers")
                    .getValue(Boolean::class.java) ?: false,
                choices = choices,
                imageData = questionSnapshot.child("imageData").getValue(String::class.java)
            )
        }
        if (questions.isEmpty()) return null
        val policy = QuizRewardPolicy(
            passingScorePercent = (snapshot.child("passingScorePercent").value as? Number)?.toInt() ?: 80,
            rewardMinutes = (snapshot.child("rewardMinutes").value as? Number)?.toInt() ?: 15,
            maxAttempts = (snapshot.child("maxAttempts").value as? Number)?.toInt() ?: 3,
            rewardTiers = snapshot.child("rewardTiers").children.mapNotNull { tier ->
                val score = (tier.child("minimumScorePercent").value as? Number)?.toInt()
                val minutes = (tier.child("rewardMinutes").value as? Number)?.toInt()
                if (score != null && minutes != null) RewardTier(score, minutes) else null
            }.ifEmpty { listOf(RewardTier((snapshot.child("passingScorePercent").value as? Number)?.toInt() ?: 80, (snapshot.child("rewardMinutes").value as? Number)?.toInt() ?: 15)) }
        ).normalized()
        return ChildQuiz(
            id = id,
            title = snapshot.child("title").getValue(String::class.java).orEmpty()
                .ifBlank { "Untitled quiz" },
            subject = snapshot.child("subject").getValue(String::class.java).orEmpty(),
            grade = snapshot.child("grade").getValue(String::class.java).orEmpty(),
            updatedAt = (snapshot.child("updatedAt").value as? Number)?.toLong() ?: 0L,
            policy = policy,
            questions = questions,
            coverImageData = snapshot.child("coverImageData").getValue(String::class.java)
        )
    }

    private fun renderQuestion() {
        val activeQuiz = quiz ?: return
        val question = activeQuiz.questions[questionIndex]
        showOnly(content)
        findViewById<TextView>(R.id.tv_quiz_progress).text =
            "Question ${questionIndex + 1} of ${activeQuiz.questions.size}"
        findViewById<TextView>(R.id.tv_quiz_attempt).apply {
            text = if (reviewMode) "History • ${reviewHistory?.length() ?: 1} runs" else "Score run"
            setOnClickListener(if (reviewMode) View.OnClickListener { chooseReviewRun() } else null)
        }
        findViewById<TextView>(R.id.tv_child_quiz_prompt).text = question.prompt
        findViewById<ImageView>(R.id.iv_child_quiz_image).apply {
            val imageData = question.imageData ?: activeQuiz.coverImageData
            val bitmap = imageData?.let(::decodeImage)
            visibility = if (bitmap == null) View.GONE else View.VISIBLE
            setImageBitmap(bitmap)
        }
        findViewById<TextView>(R.id.tv_quiz_selection_hint).text =
            if (reviewMode && reviewHasAnswerKey) {
                "Your score was $reviewScorePercent%. Your answers and the answer key are shown below."
            } else if (reviewMode) {
                "Your score was $reviewScorePercent%. Correct answers unlock at 100%."
            } else if (question.allowMultipleAnswers) {
                "Select every correct answer"
            } else {
                "Select one answer"
            }

        choicesContainer.removeAllViews()
        val checkBoxes = mutableListOf<CheckBox>()
        var changingSelection = false
        question.choices.forEachIndexed { choiceIndex, choice ->
            val checkBox = LayoutInflater.from(this)
                .inflate(R.layout.item_child_quiz_choice, choicesContainer, false) as CheckBox
            checkBox.text = choice.text
            checkBox.isChecked = selectedAnswers[questionIndex].contains(choiceIndex)
            if (reviewMode) {
                val selected = checkBox.isChecked
                checkBox.text = if (reviewHasAnswerKey) {
                    when {
                        selected && choice.correct -> "${choice.text}\nYour answer - correct"
                        selected -> "${choice.text}\nYour answer"
                        choice.correct -> "${choice.text}\nCorrect answer"
                        else -> choice.text
                    }
                } else {
                    if (selected) "${choice.text}\nYour answer" else choice.text
                }
                checkBox.isEnabled = false
                checkBox.alpha = if (selected || (reviewHasAnswerKey && choice.correct)) 1f else 0.58f
                checkBox.setTextColor(getColor(
                    if (reviewHasAnswerKey && choice.correct) R.color.child_accent
                    else if (selected) R.color.child_ink
                    else R.color.child_muted
                ))
            }
            checkBox.buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(getColor(R.color.l2e_forest), getColor(R.color.l2e_muted))
            )
            checkBox.setOnCheckedChangeListener { _, checked ->
                if (changingSelection) return@setOnCheckedChangeListener
                if (checked && !question.allowMultipleAnswers) {
                    changingSelection = true
                    checkBoxes.filterNot { it === checkBox }.forEach { it.isChecked = false }
                    selectedAnswers[questionIndex].clear()
                    changingSelection = false
                }
                if (checked) selectedAnswers[questionIndex].add(choiceIndex)
                else selectedAnswers[questionIndex].remove(choiceIndex)
                updateContinueButton()
            }
            checkBoxes += checkBox
            choicesContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(checkBox)
                choice.imageData?.let(::decodeImage)?.let { bitmap ->
                    addView(ImageView(this@ChildQuizActivity).apply {
                        contentDescription = "Answer image for ${choice.text}"
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bitmap)
                        setPadding(dp(14), 0, dp(14), dp(10))
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)))
                }
            })
        }
        continueButton.text =
            if (reviewMode) {
                if (questionIndex == activeQuiz.questions.lastIndex) "Done reviewing" else "Next answer"
            } else if (questionIndex == activeQuiz.questions.lastIndex) {
                "Submit answers"
            } else {
                "Next question"
            }
        updateContinueButton()
    }

    private fun updateContinueButton() {
        val enabled = reviewMode || selectedAnswers.getOrNull(questionIndex)?.isNotEmpty() == true
        continueButton.isEnabled = enabled
        continueButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun onContinue() {
        val activeQuiz = quiz ?: return
        if (reviewMode) {
            if (questionIndex < activeQuiz.questions.lastIndex) {
                questionIndex++
                renderQuestion()
            } else {
                finish()
            }
            return
        }
        if (selectedAnswers[questionIndex].isEmpty()) return
        if (questionIndex < activeQuiz.questions.lastIndex) {
            questionIndex++
            renderQuestion()
        } else {
            submit(activeQuiz)
        }
    }

    private fun submit(activeQuiz: ChildQuiz) {
        if (activeQuiz.remoteAssignmentId != null) {
            submitRemote(activeQuiz)
            return
        }
        continueButton.isEnabled = false
        continueButton.text = "Saving result..."
        val score = QuizScorer.score(activeQuiz.questions.mapIndexed { index, question ->
            QuizAnswer(
                correctChoiceIndexes = question.choices.mapIndexedNotNull { choiceIndex, choice ->
                    choiceIndex.takeIf { choice.correct }
                }.toSet(),
                selectedChoiceIndexes = selectedAnswers[index].toSet()
            )
        })
        val nextAttemptCount = attemptCount + 1
        val rewardMinutes = activeQuiz.policy.rewardFor(score.percent)
        val state = "COMPLETED"
        val attemptId = "${activeQuiz.id}-final"
        if (rewardMinutes > 0) {
            QuizRewardStore.credit(this, attemptId, rewardMinutes)
            ChildLockService.start(this)
        }
        recordAttempt(
            activeQuiz = activeQuiz,
            score = score,
            attemptNumber = nextAttemptCount,
            state = state
        ) { success ->
            if (!success) {
                continueButton.isEnabled = true
                continueButton.text = "Submit answers"
                Toast.makeText(this, "Could not save result. Please try again.", Toast.LENGTH_LONG).show()
                return@recordAttempt
            }
            attemptCount = nextAttemptCount
            showResult(score, rewardMinutes, activeQuiz)
        }
    }

    private fun submitRemote(activeQuiz: ChildQuiz) {
        val assignmentId = activeQuiz.remoteAssignmentId ?: return
        continueButton.isEnabled = false
        continueButton.text = "Checking answers..."
        val submissionId = remoteSubmissionId ?: UUID.randomUUID().toString().also {
            remoteSubmissionId = it
            getSharedPreferences(SUBMISSION_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(submissionKey(assignmentId), it)
                .apply()
        }
        LearningApi.submitQuiz(
            context = this,
            assignmentId = assignmentId,
            answers = selectedAnswers.map { it.toList().sorted() },
            submissionId = submissionId,
            auth = auth
        ) { apiResult ->
            if (apiResult is LearningApiResult.Failure) {
                continueButton.isEnabled = true
                continueButton.text = "Submit answers"
                Toast.makeText(this, apiResult.message, Toast.LENGTH_LONG).show()
            } else {
                getSharedPreferences(SUBMISSION_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(submissionKey(assignmentId))
                    .apply()
                val body = (apiResult as LearningApiResult.Success).body
                mirrorStudyEnergy(body)
                val percent = body.optInt("scorePercent", 0).coerceIn(0, 100)
                val rewardMinutes = body.optInt("rewardMinutes", 0).coerceAtLeast(0)
                attemptCount = body.optInt("attemptCount", attemptCount + 1)
                val serverTotalMinutes = body.optInt("earnedMinutes", -1)
                val reconciledMinutes = if (serverTotalMinutes >= 0) {
                    QuizRewardStore.reconcileServerTotal(this, serverTotalMinutes)
                } else if (rewardMinutes > 0 &&
                    QuizRewardStore.credit(this, "server:$assignmentId", rewardMinutes)
                ) {
                    rewardMinutes
                } else {
                    0
                }
                if (reconciledMinutes > 0) {
                    ChildLockService.start(this)
                }
                val estimatedCorrect = (percent * activeQuiz.questions.size + 50) / 100
                showResult(
                    QuizScore(estimatedCorrect, activeQuiz.questions.size, percent),
                    rewardMinutes,
                    activeQuiz,
                    body.optJSONObject("studyEnergy")?.optInt("remainingMinutes"),
                    body.optInt("rewardEarnedMinutes"),
                    body.optInt("prizePoolMinutes"),
                    body.optInt("pendingRewardMinutes"),
                    body.optBoolean("retryAllowed"),
                    body.optLong("nextRewardAt")
                )
            }
        }
    }

    private fun recordAttempt(
        activeQuiz: ChildQuiz,
        score: QuizScore,
        attemptNumber: Int,
        state: String,
        done: (Boolean) -> Unit
    ) {
        childRef.child("quizProgress").child(activeQuiz.id).setValue(
            mapOf(
                "attemptCount" to attemptNumber,
                "completed" to (state == "COMPLETED"),
                "state" to state,
                "lastScorePercent" to score.percent,
                "updatedAt" to ServerValue.TIMESTAMP
            )
        ).addOnCompleteListener { done(it.isSuccessful) }
    }

    private fun mirrorStudyEnergy(body: JSONObject) {
        val energy = body.optJSONObject("studyEnergy") ?: return
        if (!energy.has("remainingMinutes") || !energy.has("capMinutes")) return
        childRef.child("runtime").updateChildren(
            mapOf(
                "studyEnergyRemainingMinutes" to energy.optInt("remainingMinutes").coerceAtLeast(0)
            )
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        quiz?.let { activeQuiz ->
            outState.putString(STATE_QUIZ_ID, activeQuiz.id)
            outState.putInt(STATE_QUESTION_INDEX, questionIndex)
            outState.putString(
                STATE_SELECTED_ANSWERS,
                JSONArray().apply {
                    selectedAnswers.forEach { selected ->
                        put(JSONArray().apply {
                            selected.sorted().forEach { choiceIndex -> put(choiceIndex) }
                        })
                    }
                }.toString()
            )
        }
        super.onSaveInstanceState(outState)
    }

    private fun parseSavedAnswers(value: String): List<Set<Int>>? = runCatching {
        val root = JSONArray(value)
        buildList {
            for (questionIndex in 0 until root.length()) {
                val selected = root.optJSONArray(questionIndex) ?: return@runCatching null
                add(buildSet {
                    for (choiceIndex in 0 until selected.length()) {
                        val valueAtIndex = selected.optInt(choiceIndex, -1)
                        if (valueAtIndex >= 0) add(valueAtIndex)
                    }
                })
            }
        }
    }.getOrNull()

    private fun showResult(
        score: QuizScore,
        rewardMinutes: Int,
        activeQuiz: ChildQuiz,
        energyRemaining: Int? = null,
        rewardEarned: Int = rewardMinutes,
        prizePool: Int = activeQuiz.policy.normalizedTiers().maxOf { it.rewardMinutes },
        pendingReward: Int = 0,
        canImprove: Boolean = false,
        nextImproveAt: Long = 0L
    ) {
        showOnly(result)
        findViewById<TextView>(R.id.tv_quiz_result_score).text = "${score.percent}%"
        val title = findViewById<TextView>(R.id.tv_quiz_result_title)
        val message = findViewById<TextView>(R.id.tv_quiz_result_message)
        val action = findViewById<Button>(R.id.btn_quiz_result_action)
        title.text = if (rewardMinutes > 0) "Prize progress" else "Score saved"
        title.setTextColor(getColor(R.color.child_on_dark))
        message.text = buildString {
            append("You scored ${score.percent}%.")
            if (rewardMinutes > 0) append(" +$rewardMinutes minutes.")
            else append(" No time earned.")
            append(" $rewardEarned / $prizePool minutes claimed.")
            if (pendingReward > 0) append(" $pendingReward minutes pay after Study Energy resets.")
            energyRemaining?.let { append(" $it energy left today.") }
        }
        if (canImprove) {
            action.text = "Improve score"
            action.setOnClickListener {
                remoteSubmissionId = null
                selectedAnswers.forEach { it.clear() }
                questionIndex = 0
                renderQuestion()
            }
        } else {
            action.text = if (nextImproveAt > System.currentTimeMillis()) "Done" else "Back to Earn"
            action.setOnClickListener { finish() }
        }
    }

    private fun showEmpty(message: String) {
        showOnly(emptyState)
        findViewById<TextView>(R.id.tv_quiz_empty_message).text = message
    }

    private fun showOnly(visible: View) {
        listOf(loading, emptyState, content, result).forEach {
            it.visibility = if (it === visible) View.VISIBLE else View.GONE
        }
    }

    private data class ChildQuiz(
        val id: String,
        val title: String,
        val subject: String,
        val grade: String,
        val updatedAt: Long,
        val policy: QuizRewardPolicy,
        val questions: List<ChildQuestion>,
        val remoteAssignmentId: String? = null,
        val coverImageData: String? = null
    )

    private data class ChildQuestion(
        val prompt: String,
        val allowMultipleAnswers: Boolean,
        val choices: List<ChildChoice>,
        val imageData: String? = null
    )

    private data class ChildChoice(
        val text: String,
        val correct: Boolean,
        val imageData: String? = null
    )

    private fun decodeImage(value: String) = runCatching {
        val bytes = Base64.decode(value, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_QUIZ_ID = "quiz_id"
        const val EXTRA_SECURE_ASSIGNMENT = "secure_assignment"
        const val EXTRA_REVIEW = "review"
        private const val SUBMISSION_PREFS = "learn2earn_quiz_submissions"
        private const val STATE_QUIZ_ID = "quiz_id"
        private const val STATE_QUESTION_INDEX = "question_index"
        private const val STATE_SELECTED_ANSWERS = "selected_answers"
        private fun submissionKey(assignmentId: String) = "submission:$assignmentId"
    }
}
