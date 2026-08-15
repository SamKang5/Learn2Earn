package com.example.learn2earn2.parent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.learn2earn2.R
import com.example.learn2earn2.account.ParentAccount
import com.example.learn2earn2.quiz.LearningApi
import com.example.learn2earn2.quiz.LearningApiResult
import com.google.firebase.database.FirebaseDatabase
import org.json.JSONObject
import kotlin.math.roundToInt

class ProgressFragment : Fragment(R.layout.fragment_parent_progress) {
    private val db = FirebaseDatabase.getInstance(
        "https://learn2earn-bc2bc-default-rtdb.asia-southeast1.firebasedatabase.app/"
    )
    private lateinit var cards: LinearLayout
    private lateinit var loading: View
    private lateinit var empty: TextView
    private lateinit var childSelector: Spinner
    private var children = emptyList<ProgressChild>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cards = view.findViewById(R.id.ll_progress_cards)
        loading = view.findViewById(R.id.tv_progress_loading)
        empty = view.findViewById(R.id.tv_progress_empty)
        childSelector = view.findViewById(R.id.sp_progress_child)
        childSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, selected: View?, position: Int, id: Long) {
                children.getOrNull(position)?.let(::showChild)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        view.findViewById<Button>(R.id.btn_refresh_progress).setOnClickListener { loadProgress() }
    }

    override fun onResume() {
        super.onResume()
        if (::cards.isInitialized) loadProgress()
    }

    private fun loadProgress() {
        val parentId = ParentAccount.ownerId(requireContext()) ?: return showEmpty("Sign in to view learning progress.")
        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        cards.removeAllViews()
        db.getReference("users/$parentId/children").get()
            .addOnSuccessListener { snapshot ->
                if (!isAdded) return@addOnSuccessListener
                loading.visibility = View.GONE
                children = snapshot.children.mapNotNull { child ->
                    if (child.key == null) return@mapNotNull null
                    ProgressChild(
                        name = child.child("name").getValue(String::class.java).orEmpty().ifBlank { "Child" },
                        authUid = child.child("authUid").getValue(String::class.java),
                        secure = child.child("secureLearningEnabled").getValue(Boolean::class.java) == true
                    )
                }
                if (children.isEmpty()) return@addOnSuccessListener showEmpty("Connect a child to start tracking their learning.")
                childSelector.visibility = View.VISIBLE
                childSelector.adapter = ArrayAdapter(
                    requireContext(),
                    R.layout.item_parent_progress_child,
                    children.map { it.name }
                ).apply {
                    setDropDownViewResource(R.layout.item_parent_progress_child_dropdown)
                }
                childSelector.setSelection(0)
                showChild(children.first())
            }
            .addOnFailureListener {
                if (isAdded) showEmpty("Could not load children. Check your connection and try again.")
            }
    }

    private fun showChild(child: ProgressChild) {
        cards.removeAllViews()
        empty.visibility = View.GONE
        val card = addCard(child)
        if (child.secure && !child.authUid.isNullOrBlank()) loadSecureSummary(child, card) else renderSecureRequired(card)
    }

    private fun addCard(child: ProgressChild): ProgressCard {
        val context = requireContext()
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_progress_card_outer)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_progress_card_inner)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        outer.addView(inner)
        cards.addView(outer, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        inner.addView(TextView(context).apply {
            text = child.name
            textSize = 22f
            setTextColor(context.getColor(R.color.l2e_ink))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        val detail = TextView(context).apply {
            text = "Loading quiz history..."
            textSize = 13f
            setTextColor(context.getColor(R.color.l2e_muted))
            setPadding(0, dp(4), 0, 0)
        }
        inner.addView(detail)

        val metricRow = LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(18))
        }
        val activeMetric = addMetric(metricRow, "-", "active now")
        val earnedMetric = addMetric(metricRow, "-", "earned")
        val averageMetric = addMetric(metricRow, "-", "quiz average")
        inner.addView(metricRow, LinearLayout.LayoutParams(-1, -2))

        inner.addView(TextView(context).apply {
            text = "Recent quiz scores"
            textSize = 13f
            setTextColor(context.getColor(R.color.l2e_muted))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        val chart = LearningStatsChartView(context).apply {
            setPadding(0, dp(10), 0, dp(2))
        }
        inner.addView(chart, LinearLayout.LayoutParams(-1, dp(132)))
        val plan = Button(context).apply {
            text = "View learning plan"
            isAllCaps = false
            setTextColor(context.getColor(R.color.white))
            setBackgroundResource(R.drawable.bg_progress_action)
            setOnClickListener {
                startActivity(Intent(context, LearningPlanActivity::class.java)
                    .putExtra(LearningPlanActivity.EXTRA_CHILD_UID, child.authUid)
                    .putExtra(LearningPlanActivity.EXTRA_CHILD_NAME, child.name))
            }
        }
        inner.addView(plan, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(18) })
        return ProgressCard(detail, metricRow, activeMetric, earnedMetric, averageMetric, chart, plan)
    }

    private fun addMetric(parent: LinearLayout, value: String, label: String): TextView {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        val valueView = TextView(requireContext()).apply {
            text = value
            textSize = 20f
            setTextColor(requireContext().getColor(R.color.l2e_forest))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }
        container.addView(valueView)
        container.addView(TextView(requireContext()).apply {
            text = label
            textSize = 12f
            setTextColor(requireContext().getColor(R.color.l2e_muted))
            setPadding(0, dp(2), 0, 0)
        })
        parent.addView(container, LinearLayout.LayoutParams(0, -2, 1f))
        return valueView
    }

    private fun loadSecureSummary(child: ProgressChild, card: ProgressCard) {
        LearningApi.childSummary(requireContext(), child.authUid.orEmpty()) { result ->
            if (!isAdded) return@childSummary
            when (result) {
                is LearningApiResult.Failure -> {
                    card.detail.text = result.message
                    card.metricRow.visibility = View.GONE
                    card.chart.scores = emptyList()
                }
                is LearningApiResult.Success -> renderSummary(card, result.body)
            }
        }
    }

    private fun renderSummary(card: ProgressCard, summary: JSONObject) {
        val attempts = summary.optJSONArray("attempts")
        val scores = buildList {
            for (index in 0 until (attempts?.length() ?: 0)) {
                attempts?.optJSONObject(index)?.optInt("scorePercent")?.let(::add)
            }
        }.reversed()
        val average = if (scores.isEmpty()) null else scores.average().roundToInt()
        card.detail.text = if (scores.isEmpty()) "No quizzes completed yet" else "Recent quiz scores"
        card.metricRow.visibility = View.VISIBLE
        card.activeMetric.text = summary.optInt("activeCount").toString()
        card.earnedMetric.text = "${summary.optInt("earnedMinutes")}m"
        card.averageMetric.text = average?.let { "$it%" } ?: "-"
        card.chart.scores = scores
    }

    private fun renderSecureRequired(card: ProgressCard) {
        card.detail.text = "Secure learning is needed for quiz stats and plans."
        card.metricRow.visibility = View.GONE
        card.chart.scores = emptyList()
        card.plan.visibility = View.GONE
    }

    private fun showEmpty(message: String) {
        if (!::loading.isInitialized) return
        loading.visibility = View.GONE
        if (::childSelector.isInitialized) childSelector.visibility = View.GONE
        cards.removeAllViews()
        empty.text = message
        empty.visibility = View.VISIBLE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private data class ProgressChild(
        val name: String,
        val authUid: String?,
        val secure: Boolean
    )

    private data class ProgressCard(
        val detail: TextView,
        val metricRow: View,
        val activeMetric: TextView,
        val earnedMetric: TextView,
        val averageMetric: TextView,
        val chart: LearningStatsChartView,
        val plan: Button
    )
}
