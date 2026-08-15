package com.example.learn2earn2.account

import android.content.Context
import com.google.firebase.auth.FirebaseAuth

object ParentAccount {
    private const val PREFS = "learn2earn_prefs"
    private const val KEY_GUEST = "parent_is_guest"

    fun isReady(context: Context): Boolean {
        val user = FirebaseAuth.getInstance().currentUser
        if (isGuest(context)) {
            if (ParentSessionReentryPolicy.canUseGuestParentSession(
                    isGuest = true,
                    authUid = user?.uid,
                    authIsAnonymous = user?.isAnonymous == true
                )
            ) return true
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_GUEST).apply()
            return false
        }
        return ParentSessionReentryPolicy.shouldOpenParentDashboard(
            isGuest = false,
            authUid = user?.uid,
            authIsAnonymous = user?.isAnonymous == true
        )
    }

    fun ownerId(context: Context): String? = FirebaseAuth.getInstance().currentUser?.uid

    fun useGuest(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GUEST, true).apply()
    }

    fun useAccount(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_GUEST).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_GUEST).apply()
        FirebaseAuth.getInstance().signOut()
    }

    fun resetGuest(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_GUEST).apply()
        FirebaseAuth.getInstance().currentUser?.takeIf { it.isAnonymous }?.let { FirebaseAuth.getInstance().signOut() }
    }

    fun isGuest(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_GUEST, false)
}
