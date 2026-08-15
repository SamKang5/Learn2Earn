package com.example.learn2earn2.child

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

/** Verifies a parent credential without replacing the child's Firebase session. */
object ParentPasswordVerifier {
    fun verify(context: Context, email: String, password: String, complete: (Boolean) -> Unit) {
        val app = runCatching {
            FirebaseApp.getApps(context).firstOrNull { it.name == SECONDARY_APP_NAME }
                ?: FirebaseApp.initializeApp(
                    context,
                    FirebaseApp.getInstance().options,
                    SECONDARY_APP_NAME
                )
        }.getOrNull()
        val auth = app?.let { FirebaseAuth.getInstance(it) }
        if (auth == null) {
            complete(false)
            return
        }
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener { result ->
            auth.signOut()
            complete(result.isSuccessful)
        }
    }

    private const val SECONDARY_APP_NAME = "parentCredentialVerifier"
}
