package com.example.learn2earn2.child

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Base64
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.learn2earn2.R
import com.example.learn2earn2.quiz.LearningApi
import com.example.learn2earn2.quiz.LearningApiResult
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener

class ChildEarnFragment : Fragment(R.layout.fragment_child_earn) {
    private val db by lazy { ChildFirebaseSession.database(requireContext()) }
    private lateinit var filter: Spinner
    private lateinit var sort: Spinner
    private lateinit var loading: View
    private lateinit var emptyState: View
    private lateinit var emptyText: TextView
    private lateinit var studyEnergy: TextView
    private lateinit var scroll: View
    private lateinit var list: LinearLayout
    private var items = emptyList<CatalogItem>()
    private var showFinished = false
    private var learningPlanPending = false
    private var energyRef: DatabaseReference? = null
    private var energyListener: ValueEventListener? = null
    private var catalogRef: DatabaseReference? = null
    private var catalogListener: ValueEventListener? = null
    private var secureLearning = false
    private var displayedEnergyUsedMinutes = 0
    private var observedEnergyCapMinutes: Int? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        filter = view.findViewById(R.id.sp_catalog_filter)
        sort = view.findViewById(R.id.sp_catalog_sort)
        loading = view.findViewById(R.id.catalog_loading)
        emptyState = view.findViewById(R.id.catalog_empty)
        emptyText = view.findViewById(R.id.tv_catalog_empty)
        studyEnergy = view.findViewById(R.id.tv_study_energy)
        scroll = view.findViewById(R.id.sv_catalog)
        list = view.findViewById(R.id.ll_catalog_items)

        val available = view.findViewById<Button>(R.id.btn_catalog_available)
        val finished = view.findViewById<Button>(R.id.btn_catalog_finished)
        fun selectFinished(value: Boolean) {
            showFinished = value
            available.setBackgroundResource(if (value) android.R.color.transparent else R.drawable.bg_child_segment_active)
            available.setTextColor(requireContext().getColor(if (value) R.color.child_muted else R.color.child_ink))
            finished.setBackgroundResource(if (value) R.drawable.bg_child_segment_active else android.R.color.transparent)
            finished.setTextColor(requireContext().getColor(if (value) R.color.child_ink else R.color.child_muted))
            render()
        }
        available.setOnClickListener { selectFinished(false) }
        finished.setOnClickListener { selectFinished(true) }

        filter.adapter = adapter(listOf("All subjects"))
        sort.adapter = adapter(listOf("Newest", "A-Z", "Reward"))
        val refresh = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, selected: View?, position: Int, id: Long) = render()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        filter.onItemSelectedListener = refresh
        sort.onItemSelectedListener = refresh
        observeStudyEnergy()
        observeCatalogChanges()
    }

    override fun onResume() {
        super.onResume()
        if (::loading.isInitialized) loadCatalog()
    }

    private fun loadCatalog() {
        loading.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        scroll.visibility = View.GONE
        val context = requireContext()
        val prefs = context.getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
        val parentId = prefs.getString(ChildLockService.PARENT_ID, null).orEmpty()
        val childId = prefs.getString(ChildLockService.CHILD_ID, null).orEmpty()
        if (parentId.isBlank() || childId.isBlank()) return showEmpty("Connect this device first")
        if (prefs.getBoolean("secure_learning_enabled", false)) {
            LearningApi.quizCatalog(context, ChildFirebaseSession.auth(context)) { result ->
                if (!isAdded) return@quizCatalog
                if (result is LearningApiResult.Failure) return@quizCatalog showEmpty(result.message)
                val energy = (result as LearningApiResult.Success).body.optJSONObject("studyEnergy")
                learningPlanPending = result.body.optBoolean("learningPlanPending")
                val remaining = energy?.optInt("remainingMinutes") ?: 0
                val cap = energy?.optInt("capMinutes") ?: 120
                renderStudyEnergy(remaining, cap)
                val rows = result.body.optJSONArray("assignments")
                items = buildList {
                    for (index in 0 until (rows?.length() ?: 0)) {
                        val row = rows?.optJSONObject(index) ?: continue
                        add(CatalogItem(
                            id = row.optString("id"),
                            title = row.optString("title", "Untitled quiz"),
                            subject = row.optString("subject"),
                            grade = row.optString("grade"),
                            rewardMinutes = row.optInt("rewardMinutes"),
                            prizePoolMinutes = row.optInt("prizePoolMinutes", row.optInt("rewardMinutes")),
                            rewardEarnedMinutes = row.optInt("rewardEarnedMinutes"),
                            pendingRewardMinutes = row.optInt("pendingRewardMinutes"),
                            questionCount = row.optInt("questionCount"),
                            updatedAt = row.optLong("createdAt"),
                            secure = true,
                            finished = row.optBoolean("finished"),
                            canReview = row.optBoolean("canReview"),
                            canStart = row.optBoolean("canStart", true),
                            rewardEligible = row.optBoolean("rewardEligible", true),
                            fullReviewAvailable = row.optBoolean("fullReviewAvailable", false),
                            nextRewardAt = row.optLong("nextRewardAt"),
                            bestScorePercent = row.optInt("bestScorePercent"),
                            coverImageData = row.optString("coverImageData").takeIf { it.isNotBlank() }
                        ))
                    }
                }.filter { it.id.isNotBlank() }
                setupFilters()
                render()
            }
            return
        }
        db.getReference("users/$parentId/quizzes").get().addOnSuccessListener { quizzes ->
            db.getReference("users/$parentId/children/$childId/quizProgress").get()
                .addOnSuccessListener { progress ->
                    items = quizzes.children.mapNotNull { legacyItem(it, progress.child(it.key.orEmpty())) }
                    setupFilters()
                    render()
                }
                .addOnFailureListener { showEmpty("Could not load progress") }
        }.addOnFailureListener { showEmpty("Could not load quizzes") }
    }

    private fun observeStudyEnergy() {
        val context = requireContext()
        val prefs = context.getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
        val parentId = prefs.getString(ChildLockService.PARENT_ID, null).orEmpty()
        val childId = prefs.getString(ChildLockService.CHILD_ID, null).orEmpty()
        if (parentId.isBlank() || childId.isBlank()) return

        secureLearning = prefs.getBoolean("secure_learning_enabled", false)
        val ref = db.getReference("users/$parentId/children/$childId")
        energyRef = ref
        energyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                val cap = (snapshot.child("dailyRewardCapMinutes").value as? Number)
                    ?.toInt()?.coerceIn(0, 24 * 60) ?: DEFAULT_STUDY_ENERGY_MINUTES
                val capChanged = observedEnergyCapMinutes != cap
                observedEnergyCapMinutes = cap
                if (secureLearning) {
                    renderStudyEnergy((cap - displayedEnergyUsedMinutes).coerceAtLeast(0), cap)
                    if (capChanged) refreshSecureStudyEnergy()
                } else {
                    val usedMinutes = ((snapshot.child("runtime").child("earnedTodaySeconds").value as? Number)
                        ?.toLong()?.coerceAtLeast(0) ?: 0L) / 60L
                    renderStudyEnergy((cap - usedMinutes).coerceAtLeast(0).toInt(), cap)
                }
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        ref.addValueEventListener(energyListener!!)
    }

    private fun observeCatalogChanges() {
        val context = requireContext()
        val prefs = context.getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("secure_learning_enabled", false)) return
        val parentId = prefs.getString(ChildLockService.PARENT_ID, null).orEmpty()
        val childId = prefs.getString(ChildLockService.CHILD_ID, null).orEmpty()
        if (parentId.isBlank() || childId.isBlank()) return
        val ref = db.getReference("users/$parentId/children/$childId/learningCatalogVersion")
        catalogRef = ref
        catalogListener = object : ValueEventListener {
            private var first = true
            override fun onDataChange(snapshot: DataSnapshot) {
                if (first) { first = false; return }
                if (isAdded) loadCatalog()
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }
        ref.addValueEventListener(catalogListener!!)
    }

    private fun refreshSecureStudyEnergy() {
        LearningApi.balance(requireContext(), ChildFirebaseSession.auth(requireContext())) { result ->
            if (!isAdded || result !is LearningApiResult.Success) return@balance
            val energy = result.body.optJSONObject("studyEnergy") ?: return@balance
            renderStudyEnergy(
                energy.optInt("remainingMinutes").coerceAtLeast(0),
                energy.optInt("capMinutes", DEFAULT_STUDY_ENERGY_MINUTES).coerceIn(0, 24 * 60)
            )
        }
    }

    private fun renderStudyEnergy(remainingMinutes: Int, capMinutes: Int) {
        val cap = capMinutes.coerceIn(0, 24 * 60)
        val remaining = remainingMinutes.coerceIn(0, cap)
        displayedEnergyUsedMinutes = cap - remaining
        studyEnergy.text = "$remaining / $cap"
    }

    override fun onDestroyView() {
        energyListener?.let { listener -> energyRef?.removeEventListener(listener) }
        catalogListener?.let { listener -> catalogRef?.removeEventListener(listener) }
        energyListener = null
        energyRef = null
        catalogRef = null
        catalogListener = null
        observedEnergyCapMinutes = null
        super.onDestroyView()
    }

    private fun legacyItem(snapshot: DataSnapshot, progress: DataSnapshot): CatalogItem? {
        val id = snapshot.key ?: return null
        val questionCount = snapshot.child("questions").children.count()
        if (questionCount == 0) return null
        val attempts = (progress.child("attemptCount").value as? Number)?.toInt() ?: 0
        val completed = progress.child("completed").getValue(Boolean::class.java) ?: false
        return CatalogItem(
            id = id,
            title = snapshot.child("title").getValue(String::class.java).orEmpty().ifBlank { "Untitled quiz" },
            subject = snapshot.child("subject").getValue(String::class.java).orEmpty(),
            grade = snapshot.child("grade").getValue(String::class.java).orEmpty(),
            rewardMinutes = (snapshot.child("rewardMinutes").value as? Number)?.toInt() ?: 15,
            prizePoolMinutes = (snapshot.child("rewardMinutes").value as? Number)?.toInt() ?: 15,
            rewardEarnedMinutes = 0,
            pendingRewardMinutes = 0,
            questionCount = questionCount,
            updatedAt = (snapshot.child("updatedAt").value as? Number)?.toLong() ?: 0L,
            secure = false,
            finished = completed || attempts > 0,
            canReview = false,
            canStart = !completed,
            rewardEligible = !completed,
            fullReviewAvailable = false,
            nextRewardAt = 0,
            bestScorePercent = (progress.child("lastScorePercent").value as? Number)?.toInt() ?: 0,
            coverImageData = snapshot.child("coverImageData").getValue(String::class.java)
        )
    }

    private fun setupFilters() {
        val selected = filter.selectedItem?.toString()
        val values = listOf("All subjects") + items.map { it.subject }.filter(String::isNotBlank).distinct().sorted()
        filter.adapter = adapter(values)
        filter.setSelection(values.indexOf(selected).takeIf { it >= 0 } ?: 0)
    }

    private fun render() {
        if (!::filter.isInitialized || filter.adapter == null) return
        val subject = filter.selectedItem?.toString() ?: "All subjects"
        val filtered = items.filter {
            (if (showFinished) it.finished else !it.finished) &&
                (subject == "All subjects" || it.subject == subject)
        }
        val sorted = when (sort.selectedItem?.toString()) {
            "A-Z" -> filtered.sortedBy { it.title.lowercase() }
            "Reward" -> filtered.sortedByDescending { it.rewardMinutes }
            else -> filtered.sortedByDescending { it.updatedAt }
        }
        if (sorted.isEmpty() && !showFinished && learningPlanPending) {
            return showEmpty("Your parent is preparing more quizzes.")
        }
        list.removeAllViews()
        sorted.forEach { list.addView(catalogRow(it)) }
        loading.visibility = View.GONE
        emptyState.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        scroll.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
        emptyText.text = if (showFinished) "Finished quizzes appear here" else "No quizzes ready"
    }

    private fun catalogRow(item: CatalogItem) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_child_card)
        elevation = dp(2).toFloat()
        setPadding(dp(16), dp(16), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }

        addView(LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(ImageView(context).apply {
                val bitmap = item.coverImageData?.let(::decodeImage)
                contentDescription = "Cover for ${item.title}"
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_quiz_cover_frame)
                clipToOutline = true
                setPadding(dp(1), dp(1), dp(1), dp(1))
                if (bitmap == null) {
                    setImageResource(R.drawable.default_quiz_cover)
                } else {
                    setImageBitmap(bitmap)
                }
            }, LinearLayout.LayoutParams(dp(64), dp(48)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(TextView(context).apply {
                    text = item.title
                    textSize = 17f
                    setTextColor(context.getColor(R.color.child_ink))
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                })
                addView(TextView(context).apply {
                    text = listOf(item.subject, "${item.questionCount} questions")
                        .filter(String::isNotBlank).joinToString("  •  ")
                    textSize = 12f
                    setTextColor(context.getColor(R.color.child_muted))
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply {
                text = "${item.rewardEarnedMinutes}/${item.prizePoolMinutes}m"
                textSize = 13f
                setTextColor(context.getColor(R.color.child_accent))
                setTypeface(typeface, Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_child_icon_tile)
            }, LinearLayout.LayoutParams(dp(58), dp(38)))
        })

        addView(TextView(context).apply {
            text = when {
                item.pendingRewardMinutes > 0 -> "${item.pendingRewardMinutes}m pays after energy resets"
                item.finished && item.bestScorePercent >= 100 -> "Mastered • 100%"
                item.finished -> "Prize claimed • Best ${item.bestScorePercent}%"
                !item.canStart && item.nextRewardAt > System.currentTimeMillis() ->
                    "Improve ${DateUtils.getRelativeTimeSpanString(item.nextRewardAt)}"
                !item.rewardEligible && item.nextRewardAt > System.currentTimeMillis() ->
                    "Reward ${DateUtils.getRelativeTimeSpanString(item.nextRewardAt)}"
                else -> "Reward ready"
            }
            textSize = 12f
            setTextColor(context.getColor(R.color.child_muted))
            setPadding(0, dp(12), 0, 0)
        })

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            if (item.canStart) addView(actionButton(
                if (item.rewardEligible) "Start" else "Practice",
                primary = true
            ) { openQuiz(item, false) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (item.canReview) addView(actionButton(
                if (item.fullReviewAvailable) "Review" else "Summary",
                primary = false
            ) {
                openQuiz(item, true)
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (item.canStart) marginStart = dp(8)
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit) =
        Button(requireContext()).apply {
            text = label
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColor(if (primary) R.color.white else R.color.child_accent))
            setBackgroundResource(if (primary) R.drawable.bg_child_primary else R.drawable.bg_child_secondary)
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.press_scale)
            setOnClickListener { action() }
        }

    private fun openQuiz(item: CatalogItem, review: Boolean) {
        startActivity(
            android.content.Intent(requireContext(), ChildQuizActivity::class.java).apply {
                putExtra(ChildQuizActivity.EXTRA_QUIZ_ID, item.id)
                putExtra(ChildQuizActivity.EXTRA_SECURE_ASSIGNMENT, item.secure)
                putExtra(ChildQuizActivity.EXTRA_REVIEW, review)
            }
        )
    }

    private fun showEmpty(message: String) {
        loading.visibility = View.GONE
        emptyText.text = message
        emptyState.visibility = View.VISIBLE
        scroll.visibility = View.GONE
    }

    private fun adapter(values: List<String>) =
        ArrayAdapter(requireContext(), R.layout.item_child_spinner, values).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun decodeImage(value: String) = runCatching {
        val bytes = Base64.decode(value, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private companion object {
        const val DEFAULT_STUDY_ENERGY_MINUTES = 120
    }

    private data class CatalogItem(
        val id: String,
        val title: String,
        val subject: String,
        val grade: String,
        val rewardMinutes: Int,
        val prizePoolMinutes: Int,
        val rewardEarnedMinutes: Int,
        val pendingRewardMinutes: Int,
        val questionCount: Int,
        val updatedAt: Long,
        val secure: Boolean,
        val finished: Boolean,
        val canReview: Boolean,
        val canStart: Boolean,
        val rewardEligible: Boolean,
        val fullReviewAvailable: Boolean,
        val nextRewardAt: Long,
        val bestScorePercent: Int,
        val coverImageData: String? = null
    )
}
