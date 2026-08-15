package com.example.learn2earn2.quiz

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.learn2earn2.R
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class AiQuizRequest(
    val subject: String,
    val grade: String,
    val topic: String,
    val note: String,
    val difficulty: String,
    val questionCount: Int,
    val quizCount: Int
)

sealed class AiQuizResult {
    data class Success(val draftsJson: String) : AiQuizResult()
    data class Failure(val code: String, val message: String) : AiQuizResult()
}

object AiQuizApi {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun generate(context: Context, request: AiQuizRequest, callback: (AiQuizResult) -> Unit) {
        val endpoint = context.getString(R.string.ai_quiz_api_url)
        if (endpoint.contains("replace-with-pages-url")) {
            callback(AiQuizResult.Failure("CONFIGURATION_REQUIRED", "AI quiz service is not configured yet."))
            return
        }
        val user = FirebaseAuth.getInstance().currentUser
            ?: run {
                callback(AiQuizResult.Failure("UNAUTHENTICATED", "Sign in or continue as guest first."))
                return
            }
        user.getIdToken(true).addOnCompleteListener { tokenTask ->
            val token = tokenTask.result?.token
            if (!tokenTask.isSuccessful || token == null) {
                callback(AiQuizResult.Failure("UNAUTHENTICATED", "Could not verify your account. Try again."))
                return@addOnCompleteListener
            }
            executor.execute {
                val result = runCatching { call(endpoint, token, request) }
                    .getOrElse { AiQuizResult.Failure("NETWORK_ERROR", "Could not reach AI quiz service. Check your connection and try again.") }
                mainHandler.post { callback(result) }
            }
        }
    }

    private fun call(endpoint: String, token: String, request: AiQuizRequest): AiQuizResult {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val body = JSONObject().apply {
                put("subject", request.subject)
                put("grade", request.grade)
                put("topic", request.topic)
                put("note", request.note)
                put("difficulty", request.difficulty)
                put("questionCount", request.questionCount)
                put("quizCount", request.quizCount)
            }.toString()
            connection.outputStream.bufferedWriter().use { it.write(body) }
            val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(text)
            if (connection.responseCode in 200..299) {
                AiQuizResult.Success(json.getJSONArray("drafts").toString())
            } else {
                val error = json.optJSONObject("error")
                AiQuizResult.Failure(error?.optString("code").orEmpty(), error?.optString("message").orEmpty().ifBlank { "Could not generate quiz." })
            }
        } finally {
            connection.disconnect()
        }
    }
}
