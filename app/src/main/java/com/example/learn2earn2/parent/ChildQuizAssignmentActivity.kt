package com.example.learn2earn2.parent

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.learn2earn2.R
import com.example.learn2earn2.account.ParentAccount
import com.example.learn2earn2.quiz.LearningApi
import com.example.learn2earn2.quiz.LearningApiResult
import com.example.learn2earn2.quiz.QuizImageData
import com.example.learn2earn2.quiz.QuizRewardPolicy
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import org.json.JSONArray
import org.json.JSONObject

class ChildQuizAssignmentActivity : AppCompatActivity() {
    private val db = FirebaseDatabase.getInstance("https://learn2earn-bc2bc-default-rtdb.asia-southeast1.firebasedatabase.app/")
    private lateinit var childUid: String
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var list: LinearLayout
    private lateinit var assign: Button
    private val selected = linkedMapOf<String, QuizChoice>()
    private var assignedSourceIds = emptySet<String>()
    private var assignedQuizKeys = emptySet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childUid = intent.getStringExtra(EXTRA_CHILD_UID).orEmpty()
        if (childUid.isBlank() || ParentAccount.isGuest(this)) { finish(); return }
        setContentView(R.layout.activity_child_quiz_assignment)
        applySystemBarInsets()
        findViewById<View>(R.id.btn_close_assignment).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_assignment_title).text = intent.getStringExtra(EXTRA_CHILD_NAME).orEmpty().ifBlank { "Assign quizzes" }
        status = findViewById(R.id.tv_assignment_status)
        count = findViewById(R.id.tv_assignment_count)
        list = findViewById(R.id.ll_assignment_quizzes)
        assign = findViewById<Button>(R.id.btn_assign_selected).apply { setOnClickListener { assignSelected() } }
        loadAssignedThenCatalog()
    }

    private fun applySystemBarInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.root_assignment)
        val left = root.paddingLeft; val top = root.paddingTop; val right = root.paddingRight; val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
    }

    private fun loadAssignedThenCatalog() {
        status.text = "Loading assigned quizzes..."
        LearningApi.learningPlan(this, childUid) { result ->
            assignedSourceIds = if (result is LearningApiResult.Success) {
                val values = result.body.optJSONArray("assignedQuizSourceIds")
                buildSet { for (index in 0 until (values?.length() ?: 0)) values?.optString(index)?.takeIf(String::isNotBlank)?.let(::add) }
            } else emptySet()
            assignedQuizKeys = if (result is LearningApiResult.Success) {
                val values = result.body.optJSONArray("assignedQuizKeys")
                buildSet { for (index in 0 until (values?.length() ?: 0)) values?.optString(index)?.takeIf(String::isNotBlank)?.let(::add) }
            } else emptySet()
            loadQuizzes()
        }
    }

    private fun loadQuizzes() {
        val parentId = ParentAccount.ownerId(this) ?: return
        status.text = "Choose from quiz catalog"
        db.getReference("users/$parentId/quizzes").get().addOnSuccessListener { snapshot ->
            list.removeAllViews()
            val choices = snapshot.children.mapNotNull(::quizChoice)
            choices.filter { it.id in assignedSourceIds }.forEach(::refreshExistingAssignment)
            choices.forEach(::addQuizRow)
            if (list.childCount == 0) status.text = "No quizzes yet"
        }.addOnFailureListener { status.text = "Could not load catalog" }
    }

    private fun refreshExistingAssignment(choice: QuizChoice) {
        LearningApi.assignQuiz(this, childUid, choice.id, choice.payload, choice.policy) { result ->
            if (result is LearningApiResult.Success && result.body.optBoolean("refreshed")) {
                signalCatalogRefresh()
            }
        }
    }

    private fun addQuizRow(choice: QuizChoice) {
        val alreadyAssigned = choice.id in assignedSourceIds || choice.key in assignedQuizKeys
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_surface_inner); setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        row.addView(ImageView(this).apply {
            contentDescription = "Cover for ${choice.title}"
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_quiz_cover_frame)
            clipToOutline = true
            setPadding(dp(1), dp(1), dp(1), dp(1))
            choice.coverUri?.let { uri -> runCatching { setImageURI(Uri.parse(uri)) } }
            if (drawable == null) choice.coverImageData?.let(::decodeImage)?.let(::setImageBitmap)
            if (drawable == null) setImageResource(R.drawable.default_quiz_cover)
        }, LinearLayout.LayoutParams(dp(72), dp(56)).apply { marginEnd = dp(12) })
        val copy = TextView(this).apply {
            text = "${choice.title}\n${choice.subject} · ${choice.questions} questions · +${choice.policy.rewardMinutes} min" + if (alreadyAssigned) "\nAlready assigned" else ""
            textSize = 15f; setTextColor(getColor(R.color.l2e_ink))
        }
        val check = CheckBox(this).apply {
            contentDescription = if (alreadyAssigned) "${choice.title} is already assigned" else "Select ${choice.title}"
            isChecked = alreadyAssigned; isEnabled = !alreadyAssigned; alpha = if (alreadyAssigned) 0.7f else 1f
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected[choice.id] = choice else selected.remove(choice.id)
                updateSelection()
            }
        }
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(check)
        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun updateSelection() {
        count.text = if (selected.isEmpty()) "Select quizzes to assign" else "${selected.size} selected"
        assign.isEnabled = selected.isNotEmpty()
    }

    private fun assignSelected() {
        val choices = selected.values.toList(); if (choices.isEmpty()) return
        assign.isEnabled = false; status.text = "Assigning..."
        var complete = 0; var assigned = 0; var error = ""
        choices.forEach { choice ->
            LearningApi.assignQuiz(this, childUid, choice.id, choice.payload, choice.policy) { result ->
                complete += 1
                if (result is LearningApiResult.Success) assigned += 1 else if (result is LearningApiResult.Failure) error = result.message
                if (complete == choices.size) {
                    if (assigned > 0) signalCatalogRefresh()
                    status.text = if (assigned == choices.size) "$assigned quizzes assigned" else "$assigned assigned. $error"
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                    selected.clear(); loadAssignedThenCatalog()
                }
            }
        }
    }

    private fun signalCatalogRefresh() {
        val parentId = ParentAccount.ownerId(this) ?: return
        db.getReference("users/$parentId/children/$childUid/learningCatalogVersion").setValue(ServerValue.TIMESTAMP)
    }

    private fun quizChoice(snapshot: DataSnapshot): QuizChoice? {
        val title = snapshot.child("title").getValue(String::class.java).orEmpty().trim()
        val subject = snapshot.child("subject").getValue(String::class.java).orEmpty().trim()
        val grade = snapshot.child("grade").getValue(String::class.java).orEmpty().trim()
        if (title.isBlank() || subject.isBlank() || grade.isBlank()) return null
        val questions = JSONArray()
        for (question in snapshot.child("questions").children) {
            val prompt = question.child("prompt").getValue(String::class.java).orEmpty().trim()
            val choices = JSONArray(); var correct = 0
            for (choice in question.child("choices").children) {
                val text = choice.child("text").getValue(String::class.java).orEmpty().trim()
                val right = choice.child("isCorrect").getValue(Boolean::class.java) ?: false
                if (text.isBlank()) return null
                if (right) correct += 1
                val imageData = choice.child("imageData").getValue(String::class.java)
                    ?: choice.child("imageUri").getValue(String::class.java)?.let { QuizImageData.encode(this, it) }
                choices.put(JSONObject().put("text", text).put("isCorrect", right).putOptional("imageData", imageData))
            }
            val multiple = question.child("allowMultipleAnswers").getValue(Boolean::class.java) ?: false
            if (prompt.isBlank() || choices.length() < 2 || correct == 0 || (!multiple && correct != 1)) return null
            val imageData = question.child("imageData").getValue(String::class.java)
                ?: question.child("imageUri").getValue(String::class.java)?.let { QuizImageData.encode(this, it) }
            questions.put(JSONObject().put("prompt", prompt).put("allowMultipleAnswers", multiple).put("choices", choices).putOptional("imageData", imageData))
        }
        if (questions.length() == 0) return null
        val policy = QuizRewardPolicy((snapshot.child("passingScorePercent").value as? Number)?.toInt() ?: 80, (snapshot.child("rewardMinutes").value as? Number)?.toInt() ?: 15)
        val coverUri = snapshot.child("thumbnailUri").getValue(String::class.java)
        val coverImageData = snapshot.child("coverImageData").getValue(String::class.java)
            ?: coverUri?.let { QuizImageData.encode(this, it) }
        return QuizChoice(snapshot.key.orEmpty(), title, subject, questions.length(), policy, coverUri, coverImageData,
            JSONObject().put("title", title).put("subject", subject).put("grade", grade).put("questions", questions).putOptional("coverImageData", coverImageData))
    }

    private fun JSONObject.putOptional(name: String, value: String?): JSONObject = apply { if (!value.isNullOrBlank()) put(name, value) }
    private fun decodeImage(value: String) = runCatching { val bytes = Base64.decode(value, Base64.DEFAULT); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private data class QuizChoice(val id: String, val title: String, val subject: String, val questions: Int, val policy: QuizRewardPolicy, val coverUri: String?, val coverImageData: String?, val payload: JSONObject) {
        val key get() = listOf(title, subject, payload.optString("grade")).joinToString("\u0000")
    }
    companion object { const val EXTRA_CHILD_UID = "assignment_child_uid"; const val EXTRA_CHILD_NAME = "assignment_child_name" }
}
