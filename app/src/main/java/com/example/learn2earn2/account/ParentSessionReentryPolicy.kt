package com.example.learn2earn2.account

object ParentSessionReentryPolicy {
    fun canUseGuestParentSession(isGuest: Boolean, authUid: String?, authIsAnonymous: Boolean): Boolean =
        isGuest && !authUid.isNullOrBlank() && authIsAnonymous

    fun shouldOpenParentDashboard(
        isGuest: Boolean,
        authUid: String?,
        authIsAnonymous: Boolean
    ): Boolean =
        if (isGuest) {
            canUseGuestParentSession(isGuest, authUid, authIsAnonymous)
        } else {
            !authUid.isNullOrBlank() && !authIsAnonymous
        }
}
