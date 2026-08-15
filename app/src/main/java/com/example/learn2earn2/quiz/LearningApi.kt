package com.example.learn2earn2.quiz

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.learn2earn2.R
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.TimeZone
import java.util.concurrent.Executors

sealed class LearningApiResult {
    data class Success(val body: JSONObject) : LearningApiResult()
    data class Failure(val code: String, val message: String) : LearningApiResult()
}

/**
 * Small authenticated client for the Cloudflare Pages + D1 learning API.
 * It uses Firebase ID tokens, so no service-account key or paid Firebase
 * product is shipped in the Android app.
 */
object LearningApi {
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun createPairingCode(context: Context, callback: (LearningApiResult) -> Unit) {
        post(
            context,
            JSONObject()
                .put("action", "createPairingCode")
                .put("timezoneOffsetMinutes", timezoneOffsetMinutes()),
            callback = callback
        )
    }

    fun claimPairingCode(
        context: Context,
        code: String,
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        callback: (LearningApiResult) -> Unit
    ) {
        post(
            context,
            JSONObject()
                .put("action", "claimPairingCode")
                .put("code", code),
            auth = auth,
            callback = callback
        )
    }

    fun assignQuiz(
        context: Context,
        childUid: String,
        sourceQuizId: String,
        quiz: JSONObject,
        policy: QuizRewardPolicy,
        callback: (LearningApiResult) -> Unit
    ) {
        post(
            context,
            JSONObject()
                .put("action", "assignQuiz")
                .put("childUid", childUid)
                .put("sourceQuizId", sourceQuizId)
                .put("quiz", quiz)
                .put("minimumScorePercent", policy.passingScorePercent)
                .put("rewardMinutes", policy.rewardMinutes)
                .put("rewardTiers", JSONArray().apply { policy.normalizedTiers().forEach { put(JSONObject().put("minimumScorePercent", it.minimumScorePercent).put("rewardMinutes", it.rewardMinutes)) } })
                .put("scoreImproveCooldownMinutes", policy.scoreImproveCooldownMinutes)
                .put("maxAttempts", policy.maxAttempts.coerceAtMost(5))
                .put("repeatIntervalMinutes", policy.repeatIntervalMinutes)
                .put("retryWhenFailed", policy.retryWhenFailed)
                .put("allowPracticeDuringCooldown", policy.allowPracticeDuringCooldown),
            callback = callback
        )
    }

    fun syncQuizCover(
        context: Context,
        sourceQuizId: String,
        title: String,
        coverImageData: String,
        callback: (LearningApiResult) -> Unit = {}
    ) {
        post(
            context,
            JSONObject()
                .put("action", "syncQuizCover")
                .put("sourceQuizId", sourceQuizId)
                .put("title", title)
                .put("coverImageData", coverImageData),
            callback = callback
        )
    }

    fun nextQuiz(
        context: Context,
        assignmentId: String? = null,
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        callback: (LearningApiResult) -> Unit
    ) {
        get(
            context,
            "nextQuiz",
            assignmentId?.let { mapOf("assignmentId" to it) }.orEmpty(),
            auth = auth,
            callback = callback
        )
    }

    fun quizCatalog(
        context: Context,
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        callback: (LearningApiResult) -> Unit
    ) {
        get(context, "quizCatalog", emptyMap(), auth = auth, callback = callback)
    }

    fun quizReview(
        context: Context,
        assignmentId: String,
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        callback: (LearningApiResult) -> Unit
    ) {
        get(context, "quizReview", mapOf("assignmentId" to assignmentId), auth = auth, callback = callback)
    }

    fun balance(
        context: Context,
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        callback: (LearningApiResult) -> Unit
    ) {
        get(context, "balance", emptyMap(), auth = auth, callback = callback)
    }

    fun childSummary(
        context: Context,
        childUid: String,
        callback: (LearningApiResult) -> Unit
    ) {
        get(context, "childSummary", mapOf("childUid" to childUid), callback = callback)
    }

    fun submitQuiz(
        context: Context,
        assignmentId: String,
        answers: List<List<Int>>,
        submissionId: String,
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        callback: (LearningApiResult) -> Unit
    ) {
        val encodedAnswers = JSONArray().apply {
            answers.forEach { selected ->
                put(JSONArray().apply { selected.forEach { index -> put(index) } })
            }
        }
        post(
            context,
            JSONObject()
                .put("action", "submitQuiz")
                .put("assignmentId", assignmentId)
                .put("submissionId", submissionId)
                .put("answers", encodedAnswers),
            auth = auth,
            callback = callback
        )
    }

    fun setRewardPolicy(
        context: Context,
        childUid: String,
        dailyCapMinutes: Int,
        callback: (LearningApiResult) -> Unit
    ) {
        post(
            context,
            JSONObject()
                .put("action", "setRewardPolicy")
                .put("childUid", childUid)
                .put("dailyEarnedCapMinutes", dailyCapMinutes)
                .put("timezoneOffsetMinutes", timezoneOffsetMinutes()),
            callback = callback
        )
    }

    fun learningPlan(
        context: Context,
        childUid: String,
        callback: (LearningApiResult) -> Unit
    ) {
        get(context, "learningPlan", mapOf("childUid" to childUid), callback = callback)
    }

    fun setLearningPlan(
        context: Context,
        childUid: String,
        plan: JSONObject,
        callback: (LearningApiResult) -> Unit
    ) {
        post(
            context,
            JSONObject()
                .put("action", "setLearningPlan")
                .put("childUid", childUid)
                .put("plan", plan),
            callback = callback
        )
    }

    fun reviewLearningPlanDraft(
        context: Context,
        draftId: String,
        approve: Boolean,
        callback: (LearningApiResult) -> Unit
    ) {
        post(
            context,
            JSONObject()
                .put("action", "reviewLearningPlanDraft")
                .put("draftId", draftId)
                .put("approve", approve),
            callback = callback
        )
    }

    fun unpairChild(
        context: Context,
        childUid: String,
        callback: (LearningApiResult) -> Unit
    ) {
        post(
            context,
            JSONObject()
                .put("action", "unpairChild")
                .put("childUid", childUid),
            callback = callback
        )
    }

    private fun post(
        context: Context,
        body: JSONObject,
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        callback: (LearningApiResult) -> Unit
    ) {
        withToken(context, auth, callback) { endpoint, token ->
            request(endpoint, "POST", token, body.toString())
        }
    }

    private fun get(
        context: Context,
        view: String,
        parameters: Map<String, String>,
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        callback: (LearningApiResult) -> Unit
    ) {
        withToken(context, auth, callback) { endpoint, token ->
            val query = buildList {
                add("view=${encode(view)}")
                parameters.forEach { (key, value) -> add("${encode(key)}=${encode(value)}") }
            }.joinToString("&")
            request("$endpoint?$query", "GET", token, null)
        }
    }

    private fun withToken(
        context: Context,
        auth: FirebaseAuth,
        callback: (LearningApiResult) -> Unit,
        work: (endpoint: String, token: String) -> LearningApiResult
    ) {
        val endpoint = context.getString(R.string.learning_api_url)
        if (endpoint.contains("replace-with-pages-url")) {
            callback(LearningApiResult.Failure("CONFIGURATION_REQUIRED", "Learning service is not configured."))
            return
        }
        val user = auth.currentUser
        if (user == null) {
            callback(LearningApiResult.Failure("UNAUTHENTICATED", "Sign in before using learning features."))
            return
        }
        user.getIdToken(false).addOnCompleteListener { tokenTask ->
            val token = tokenTask.result?.token
            if (!tokenTask.isSuccessful || token.isNullOrBlank()) {
                callback(LearningApiResult.Failure("UNAUTHENTICATED", "Could not verify this device."))
                return@addOnCompleteListener
            }
            executor.execute {
                val result = runCatching { work(endpoint, token) }
                    .getOrElse {
                        LearningApiResult.Failure(
                            "NETWORK_ERROR",
                            "Could not reach the learning service. Check the connection and try again."
                        )
                    }
                mainHandler.post { callback(result) }
            }
        }
    }

    private fun request(
        endpoint: String,
        method: String,
        token: String,
        body: String?
    ): LearningApiResult {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) connection.outputStream.bufferedWriter().use { it.write(body) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = text.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
            if (status in 200..299) {
                LearningApiResult.Success(json)
            } else {
                val error = json.optJSONObject("error")
                LearningApiResult.Failure(
                    error?.optString("code").orEmpty().ifBlank { "REQUEST_FAILED" },
                    error?.optString("message").orEmpty().ifBlank { "Learning request failed." }
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun timezoneOffsetMinutes(): Int =
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
}
