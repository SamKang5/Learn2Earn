package com.example.learn2earn2.child

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

object ChildFirebaseSession {
    private const val CHILD_APP_NAME = "learn2earnChild"
    private const val FIREBASE_URL =
        "https://learn2earn-bc2bc-default-rtdb.asia-southeast1.firebasedatabase.app/"

    fun auth(context: Context): FirebaseAuth = FirebaseAuth.getInstance(app(context))

    fun database(context: Context): FirebaseDatabase =
        FirebaseDatabase.getInstance(app(context), FIREBASE_URL)

    private fun app(context: Context): FirebaseApp =
        FirebaseApp.getApps(context.applicationContext).firstOrNull { it.name == CHILD_APP_NAME }
            ?: FirebaseApp.initializeApp(
                context.applicationContext,
                FirebaseApp.getInstance().options,
                CHILD_APP_NAME
            )
            ?: FirebaseApp.getInstance(CHILD_APP_NAME)
}
