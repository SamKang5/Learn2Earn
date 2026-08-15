package com.example.learn2earn2.account

import android.content.Context

object GuestApprovalHandoff {
    private const val PREFS = "learn2earn_prefs"

    fun select(remoteHash: String?, localHash: String?): String? =
        remoteHash?.takeIf { it.isNotBlank() }
            ?: localHash?.takeIf { it.isNotBlank() }

    fun firebaseFields(hash: String?): Map<String, Any> {
        val value = hash?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return mapOf(GuestApproval.KEY_HASH to value)
    }

    fun saveLocal(context: Context, hash: String) {
        if (hash.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(GuestApproval.KEY_HASH, hash)
            .apply()
    }

    fun loadLocal(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(GuestApproval.KEY_HASH, null)
            ?.takeIf { it.isNotBlank() }
}
