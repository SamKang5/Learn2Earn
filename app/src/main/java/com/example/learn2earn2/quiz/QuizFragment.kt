package com.example.learn2earn2.quiz

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.learn2earn2.R
import com.example.learn2earn2.account.ParentAccount
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class QuizFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance("https://learn2earn-bc2bc-default-rtdb.asia-southeast1.firebasedatabase.app/")
    private var quizzesRef: DatabaseReference? = null
    private var quizzesListener: ValueEventListener? = null
    private var quizzes = emptyList<QuizVaultItem>()
    private lateinit var vaultAdapter: QuizVaultAdapter
    private lateinit var filterSpinner: Spinner
    private lateinit var sortSpinner: Spinner
    private lateinit var emptyState: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_quiz, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        filterSpinner = view.findViewById(R.id.sp_quiz_filter)
        sortSpinner = view.findViewById(R.id.sp_quiz_sort)
        emptyState = view.findViewById(R.id.tv_quiz_empty)
        vaultAdapter = QuizVaultAdapter(::onQuizAction)
        view.findViewById<RecyclerView>(R.id.rv_quizzes).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = vaultAdapter
        }
        view.findViewById<Button>(R.id.btn_create_quiz).setOnClickListener {
            startActivity(Intent(requireContext(), QuizEditorActivity::class.java))
        }
        view.findViewById<Button>(R.id.btn_create_quiz_ai).setOnClickListener {
            startActivity(Intent(requireContext(), AiQuizSetupActivity::class.java))
        }

        setSpinnerItems(filterSpinner, listOf("All subjects"))
        setSpinnerItems(sortSpinner, listOf("Newest first", "Title A-Z"))
        val refreshListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, selected: View?, position: Int, id: Long) = renderVault()
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        filterSpinner.onItemSelectedListener = refreshListener
        sortSpinner.onItemSelectedListener = refreshListener

        val parentId = ParentAccount.ownerId(requireContext()) ?: return
        quizzesRef = db.getReference("users/$parentId/quizzes")
        quizzesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.children.forEach(::backfillPortableMedia)
                quizzes = snapshot.children.mapNotNull { quiz ->
                    val id = quiz.key ?: return@mapNotNull null
                    QuizVaultItem(
                        id = id,
                        title = quiz.child("title").getValue(String::class.java) ?: "Untitled quiz",
                        subject = quiz.child("subject").getValue(String::class.java) ?: "",
                        grade = quiz.child("grade").getValue(String::class.java) ?: "",
                        thumbnailUri = quiz.child("thumbnailUri").getValue(String::class.java),
                        questionCount = quiz.child("questions").childrenCount.toInt(),
                        updatedAt = quiz.child("updatedAt").getValue(Long::class.java) ?: 0L,
                        policy = QuizRewardPolicy(
                            passingScorePercent = (quiz.child("passingScorePercent").value as? Number)?.toInt() ?: 80,
                            rewardMinutes = (quiz.child("rewardMinutes").value as? Number)?.toInt() ?: 15,
                            maxAttempts = (quiz.child("maxAttempts").value as? Number)?.toInt() ?: 3,
                            scoreImproveCooldownMinutes = (quiz.child("scoreImproveCooldownMinutes").value as? Number)?.toInt() ?: 60,
                            rewardTiers = quiz.child("rewardTiers").children.mapNotNull { tier ->
                                val score = (tier.child("minimumScorePercent").value as? Number)?.toInt()
                                val minutes = (tier.child("rewardMinutes").value as? Number)?.toInt()
                                if (score != null && minutes != null) RewardTier(score, minutes) else null
                            }.ifEmpty { listOf(RewardTier((quiz.child("passingScorePercent").value as? Number)?.toInt() ?: 80, (quiz.child("rewardMinutes").value as? Number)?.toInt() ?: 15)) }
                        ).normalized()
                    )
                }
                updateSubjectFilter()
                renderVault()
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        quizzesRef?.addValueEventListener(quizzesListener!!)
    }

    private fun backfillPortableMedia(quiz: DataSnapshot) {
        val updates = linkedMapOf<String, Any>()
        fun addImageData(path: String, node: DataSnapshot) {
            if (!node.child("imageData").getValue(String::class.java).isNullOrBlank()) return
            val uri = node.child("imageUri").getValue(String::class.java) ?: return
            QuizImageData.encode(requireContext(), uri)?.let { updates["$path/imageData"] = it }
        }

        if (quiz.child("coverImageData").getValue(String::class.java).isNullOrBlank()) {
            quiz.child("thumbnailUri").getValue(String::class.java)?.let { uri ->
                QuizImageData.encode(requireContext(), uri)?.let { updates["coverImageData"] = it }
            }
        }
        quiz.child("questions").children.forEach { question ->
            val questionKey = question.key ?: return@forEach
            addImageData("questions/$questionKey", question)
            question.child("choices").children.forEach { choice ->
                val choiceKey = choice.key ?: return@forEach
                addImageData("questions/$questionKey/choices/$choiceKey", choice)
            }
        }
        if (updates.isNotEmpty()) {
            quiz.ref.updateChildren(updates).addOnSuccessListener {
                (updates["coverImageData"] as? String)?.let { cover ->
                    LearningApi.syncQuizCover(
                        requireContext(),
                        quiz.key.orEmpty(),
                        quiz.child("title").getValue(String::class.java).orEmpty(),
                        cover
                    )
                }
            }
        }
    }

    private fun updateSubjectFilter() {
        val selected = filterSpinner.selectedItem?.toString() ?: "All subjects"
        val subjects = listOf("All subjects") + QuizOptions.subjects
        setSpinnerItems(filterSpinner, subjects)
        filterSpinner.setSelection(subjects.indexOf(selected).takeIf { it >= 0 } ?: 0)
    }

    private fun renderVault() {
        if (!::vaultAdapter.isInitialized) return
        val subject = filterSpinner.selectedItem?.toString() ?: "All subjects"
        val filtered = quizzes.filter { subject == "All subjects" || it.subject == subject }
        val sorted = if (sortSpinner.selectedItem?.toString() == "Title A-Z") {
            filtered.sortedBy { it.title.lowercase() }
        } else {
            filtered.sortedByDescending { it.updatedAt }
        }
        vaultAdapter.submitQuizzes(sorted)
        emptyState.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setSpinnerItems(spinner: Spinner, values: List<String>) {
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, values)
    }

    private fun onQuizAction(quiz: QuizVaultItem, action: QuizVaultAction) {
        when (action) {
            QuizVaultAction.VIEW -> startActivity(Intent(requireContext(), QuizPreviewActivity::class.java)
                .putExtra(QuizPreviewActivity.EXTRA_QUIZ_ID, quiz.id))
            QuizVaultAction.EDIT -> startActivity(Intent(requireContext(), QuizEditorActivity::class.java)
                .putExtra(QuizEditorActivity.EXTRA_QUIZ_ID, quiz.id))
            QuizVaultAction.DELETE -> AlertDialog.Builder(requireContext())
                .setTitle("Delete quiz?")
                .setMessage("This removes ${quiz.title} and its questions.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> quizzesRef?.child(quiz.id)?.removeValue() }
                .show()
        }
    }

    override fun onDestroyView() {
        quizzesListener?.let { quizzesRef?.removeEventListener(it) }
        quizzesListener = null
        quizzesRef = null
        super.onDestroyView()
    }
}
