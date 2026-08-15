package com.example.learn2earn2.quiz

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.learn2earn2.R
import com.example.learn2earn2.account.ParentAccount
import com.google.firebase.database.FirebaseDatabase

class QuizPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz_preview)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
        findViewById<Button>(R.id.btn_preview_back).setOnClickListener { finish() }
        val quizId = intent.getStringExtra(EXTRA_QUIZ_ID) ?: run { finish(); return }
        val parentId = ParentAccount.ownerId(this) ?: return
        FirebaseDatabase.getInstance("https://learn2earn-bc2bc-default-rtdb.asia-southeast1.firebasedatabase.app/")
            .getReference("users/$parentId/quizzes/$quizId")
            .get()
            .addOnSuccessListener { snapshot -> showQuiz(snapshot) }
    }

    private fun showQuiz(snapshot: com.google.firebase.database.DataSnapshot) {
        val title = snapshot.child("title").getValue(String::class.java) ?: "Untitled quiz"
        findViewById<TextView>(R.id.tv_preview_title).text = title
        findViewById<TextView>(R.id.tv_preview_meta).text = listOf(
            snapshot.child("subject").getValue(String::class.java),
            snapshot.child("grade").getValue(String::class.java)
        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        snapshot.child("thumbnailUri").getValue(String::class.java)?.let { uri ->
            findViewById<ImageView>(R.id.iv_preview_cover).setImageURI(Uri.parse(uri))
        }
        val container = findViewById<LinearLayout>(R.id.ll_preview_questions)
        snapshot.child("questions").children.forEachIndexed { index, question ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_child_item)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            card.addView(textView("Question ${index + 1}", 14f, R.color.l2e_forest, true))
            question.child("prompt").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let {
                card.addView(textView(it, 17f, R.color.l2e_ink, true))
            }
            question.child("imageUri").getValue(String::class.java)?.let { uri ->
                card.addView(ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(160)).apply { topMargin = dp(10) }
                    contentDescription = "Question image"
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageURI(Uri.parse(uri))
                })
            }
            question.child("choices").children.forEach { choice ->
                val text = choice.child("text").getValue(String::class.java).orEmpty()
                val correct = choice.child("isCorrect").getValue(Boolean::class.java) ?: false
                if (text.isNotBlank()) card.addView(textView("${if (correct) "✓" else "○"} $text", 15f, R.color.l2e_ink, false))
                choice.child("imageUri").getValue(String::class.java)?.let { uri ->
                    card.addView(ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(88), dp(66)).apply { topMargin = dp(6) }
                        contentDescription = "Answer image"
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageURI(Uri.parse(uri))
                    })
                }
            }
            container.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
    }

    private fun textView(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(getColor(color))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, 0)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_QUIZ_ID = "quiz_id"
    }
}
