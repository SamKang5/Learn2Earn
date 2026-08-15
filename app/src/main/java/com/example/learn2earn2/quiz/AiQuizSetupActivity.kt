package com.example.learn2earn2.quiz

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.learn2earn2.R
import com.example.learn2earn2.account.ParentAccount
import com.example.learn2earn2.account.ParentAccountActivity
import com.example.learn2earn2.ui.KeyboardDismissActivity
import com.google.firebase.auth.FirebaseAuth

class AiQuizSetupActivity : KeyboardDismissActivity() {
    private lateinit var subject: Spinner
    private lateinit var grade: Spinner
    private lateinit var difficulty: Spinner
    private lateinit var questionCount: EditText
    private lateinit var quizCount: Spinner
    private lateinit var topic: EditText
    private lateinit var note: EditText
    private lateinit var generateButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var formScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_quiz_setup)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        formScroll = findViewById(R.id.ai_quiz_scroll)
        ViewCompat.setOnApplyWindowInsetsListener(formScroll) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(view.paddingLeft, safeArea.top, view.paddingRight, safeArea.bottom)
            insets
        }
        subject = findViewById(R.id.sp_ai_subject)
        grade = findViewById(R.id.sp_ai_grade)
        difficulty = findViewById(R.id.sp_ai_difficulty)
        questionCount = findViewById(R.id.et_ai_question_count)
        quizCount = findViewById(R.id.sp_ai_quiz_count)
        topic = findViewById(R.id.et_ai_topic)
        note = findViewById(R.id.et_ai_note)
        generateButton = findViewById(R.id.btn_generate_ai_quiz)
        progress = findViewById(R.id.pb_ai_generating)
        status = findViewById(R.id.tv_ai_status)
        setSpinnerItems(subject, listOf(SELECT_SUBJECT) + QuizOptions.subjects)
        setSpinnerItems(grade, listOf(SELECT_GRADE) + QuizOptions.grades)
        setSpinnerItems(difficulty, listOf("Easy", "Balanced", "Challenging"))
        questionCount.setText("5")
        setSpinnerItems(quizCount, listOf("1 quiz", "2 quizzes", "3 quizzes", "4 quizzes", "5 quizzes"))
        listOf(questionCount, topic, note).forEach { field ->
            field.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) formScroll.post { scrollFieldIntoView(view) }
            }
        }
        findViewById<Button>(R.id.btn_ai_back).setOnClickListener { finish() }
        generateButton.setOnClickListener { generate() }
    }

    private fun scrollFieldIntoView(field: View) {
        val bounds = Rect()
        field.getDrawingRect(bounds)
        formScroll.offsetDescendantRectToMyCoords(field, bounds)
        val visibleBottom = formScroll.height - formScroll.paddingBottom
        when {
            bounds.bottom > visibleBottom -> formScroll.smoothScrollBy(0, bounds.bottom - visibleBottom)
            bounds.top < formScroll.paddingTop -> formScroll.smoothScrollBy(0, bounds.top - formScroll.paddingTop)
        }
    }

    private fun generate() {
        val subjectValue = subject.selectedItem.toString()
        val gradeValue = grade.selectedItem.toString()
        val topicValue = topic.text.toString().trim()
        if (subjectValue == SELECT_SUBJECT) {
            status.text = "Choose a subject."
            return
        }
        if (gradeValue == SELECT_GRADE) {
            status.text = "Choose a grade."
            return
        }
        if (topicValue.length > 200) {
            topic.error = "Keep topic under 200 characters"
            topic.requestFocus()
            return
        }
        val noteValue = note.text.toString().trim()
        if (noteValue.length > 500) {
            note.error = "Keep note under 500 characters"
            note.requestFocus()
            return
        }
        val questionCountValue = questionCount.text.toString().toIntOrNull()
        if (questionCountValue == null || questionCountValue !in 1..20) {
            questionCount.error = "Use 1-20 questions"
            questionCount.requestFocus()
            return
        }
        setGenerating(true)
        AiQuizApi.generate(
            this,
            AiQuizRequest(
                subject = subjectValue,
                grade = gradeValue,
                topic = topicValue,
                note = noteValue,
                difficulty = difficulty.selectedItem.toString(),
                questionCount = questionCountValue,
                quizCount = quizCount.selectedItem.toString().substringBefore(' ').toInt()
            )
        ) { result ->
            setGenerating(false)
            when (result) {
                is AiQuizResult.Success -> {
                    startActivity(Intent(this, QuizEditorActivity::class.java)
                        .putExtra(QuizEditorActivity.EXTRA_AI_DRAFTS_JSON, result.draftsJson))
                    finish()
                }
                is AiQuizResult.Failure -> showFailure(result)
            }
        }
    }

    private fun showFailure(result: AiQuizResult.Failure) {
        status.text = result.message
        when (result.code) {
            "QUOTA_EXHAUSTED" -> showQuotaDialog()
            "EMAIL_VERIFICATION_REQUIRED" -> showVerificationDialog()
        }
    }

    private fun showQuotaDialog() {
        if (!ParentAccount.isGuest(this)) return
        AlertDialog.Builder(this)
            .setTitle("Guest trial complete")
            .setMessage("Your 3 guest AI quizzes are used. Create an account for 10 AI quizzes each month. Guest data will reset.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Create account") { _, _ ->
                ParentAccount.resetGuest(this)
                startActivity(Intent(this, ParentAccountActivity::class.java)
                    .putExtra(ParentAccountActivity.EXTRA_FORCE_REGISTER, true))
                finish()
            }
            .show()
    }

    private fun showVerificationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Verify your email")
            .setMessage("Verify your parent account email to use AI quizzes.")
            .setNegativeButton("Later", null)
            .setPositiveButton("Resend email") { _, _ ->
                FirebaseAuth.getInstance().currentUser?.sendEmailVerification()
                status.text = "Verification email sent."
            }
            .show()
    }

    private fun setGenerating(generating: Boolean) {
        generateButton.isEnabled = !generating
        progress.visibility = if (generating) View.VISIBLE else View.GONE
        status.text = if (generating) "Creating editable quiz draft(s)..." else ""
    }

    private fun setSpinnerItems(spinner: Spinner, values: List<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
    }

    companion object {
        private const val SELECT_SUBJECT = "Select subject"
        private const val SELECT_GRADE = "Select grade"
    }
}
