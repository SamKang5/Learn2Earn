package com.example.learn2earn2.parent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.example.learn2earn2.R
import kotlin.math.max

/** Small, dependency-free chart for the latest quiz scores. */
class LearningStatsChartView(context: Context) : View(context) {
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.l2e_forest)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.l2e_progress_line)
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.l2e_muted)
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }

    var scores: List<Int> = emptyList()
        set(value) {
            field = value.takeLast(MAX_BARS).map { it.coerceIn(0, 100) }
            contentDescription = if (field.isEmpty()) {
                "No completed quizzes yet"
            } else {
                "Recent quiz scores: ${field.joinToString(", ") { "$it percent" }}"
            }
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val chartTop = paddingTop.toFloat()
        val chartBottom = height - paddingBottom - dp(22f)
        val chartHeight = max(0f, chartBottom - chartTop)
        if (scores.isEmpty()) {
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("No completed quizzes yet", paddingLeft.toFloat(), chartBottom, labelPaint)
            labelPaint.textAlign = Paint.Align.CENTER
            return
        }

        canvas.drawLine(paddingLeft.toFloat(), chartTop, (width - paddingRight).toFloat(), chartTop, guidePaint)
        canvas.drawLine(paddingLeft.toFloat(), chartBottom, (width - paddingRight).toFloat(), chartBottom, guidePaint)
        val availableWidth = width - paddingLeft - paddingRight
        val slotWidth = availableWidth.toFloat() / scores.size
        val barWidth = minOf(dp(28f), slotWidth * 0.58f)
        scores.forEachIndexed { index, score ->
            val centerX = paddingLeft + slotWidth * index + slotWidth / 2f
            val top = chartBottom - chartHeight * score / 100f
            canvas.drawRoundRect(
                RectF(centerX - barWidth / 2f, top, centerX + barWidth / 2f, chartBottom),
                dp(7f), dp(7f), scorePaint
            )
            canvas.drawText("$score%", centerX, height - paddingBottom.toFloat(), labelPaint)
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        const val MAX_BARS = 7
    }
}
