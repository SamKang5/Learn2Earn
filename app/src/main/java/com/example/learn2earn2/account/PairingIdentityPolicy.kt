package com.example.learn2earn2.account

object PairingIdentityPolicy {
    fun childOwnsPath(authUid: String?, childId: String?, recordedAuthUid: String?): Boolean =
        !authUid.isNullOrBlank() && (authUid == childId || authUid == recordedAuthUid)
}
