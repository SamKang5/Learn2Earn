package com.example.learn2earn2.quiz

import android.net.Uri
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.learn2earn2.R
import java.text.DateFormat
import java.util.Date

data class QuizVaultItem(
    val id: String,
    val title: String,
    val subject: String,
    val grade: String,
    val thumbnailUri: String?,
    val questionCount: Int,
    val updatedAt: Long,
    val policy: QuizRewardPolicy = QuizRewardPolicy()
)

enum class QuizVaultAction { VIEW, EDIT, DELETE }

class QuizVaultAdapter(
    private val onAction: (QuizVaultItem, QuizVaultAction) -> Unit
) : RecyclerView.Adapter<QuizVaultAdapter.ViewHolder>() {

    private var quizzes = emptyList<QuizVaultItem>()

    fun submitQuizzes(newQuizzes: List<QuizVaultItem>) {
        quizzes = newQuizzes
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.iv_quiz_cover)
        val coverPlaceholder: TextView = view.findViewById(R.id.tv_quiz_cover_placeholder)
        val title: TextView = view.findViewById(R.id.tv_quiz_title)
        val meta: TextView = view.findViewById(R.id.tv_quiz_meta)
        val details: TextView = view.findViewById(R.id.tv_quiz_details)
        val actions: ImageButton = view.findViewById(R.id.btn_quiz_actions)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quiz_vault, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val quiz = quizzes[position]
        holder.title.text = quiz.title
        holder.meta.text = listOf(quiz.subject, quiz.grade).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { "No subject or grade" }
        holder.details.text = "${quiz.questionCount} questions  |  Pass ${quiz.policy.passingScorePercent}%  |  +${quiz.policy.rewardMinutes} min"
        holder.coverPlaceholder.visibility = View.GONE
        holder.cover.setImageResource(R.drawable.default_quiz_cover)
        quiz.thumbnailUri?.takeIf { it.isNotBlank() }?.let { uri ->
            runCatching { holder.cover.setImageURI(Uri.parse(uri)) }
                .onFailure { holder.cover.setImageResource(R.drawable.default_quiz_cover) }
        }
        holder.actions.setOnClickListener { showActions(holder.actions, quiz) }
    }

    override fun getItemCount() = quizzes.size

    private fun showActions(anchor: View, quiz: QuizVaultItem) {
        PopupMenu(anchor.context, anchor).apply {
            menu.add(0, R.id.action_view_quiz, 0, "View")
            menu.add(0, R.id.action_edit_quiz, 1, "Edit")
            menu.add(0, R.id.action_delete_quiz, 2, "Delete")
            setOnMenuItemClickListener { item: MenuItem ->
                val action = when (item.itemId) {
                    R.id.action_view_quiz -> QuizVaultAction.VIEW
                    R.id.action_edit_quiz -> QuizVaultAction.EDIT
                    R.id.action_delete_quiz -> QuizVaultAction.DELETE
                    else -> return@setOnMenuItemClickListener false
                }
                onAction(quiz, action)
                true
            }
            show()
        }
    }

    private fun formatUpdatedAt(updatedAt: Long): String {
        if (updatedAt <= 0) return "recently"
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(updatedAt))
    }
}
