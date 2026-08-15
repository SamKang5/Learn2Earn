package com.example.learn2earn2.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentSessionReentryPolicyTest {
    @Test
    fun parentGuestSessionExistsBeforeChildSwitch() {
        assertTrue(
            ParentSessionReentryPolicy.canUseGuestParentSession(
                isGuest = true,
                authUid = "parent-uid",
                authIsAnonymous = true
            )
        )
    }

    @Test
    fun parentAccountCompleteStateRoutesToDashboard() {
        assertTrue(
            ParentSessionReentryPolicy.shouldOpenParentDashboard(
                isGuest = true,
                authUid = "parent-uid",
                authIsAnonymous = true
            )
        )
        assertTrue(
            ParentSessionReentryPolicy.shouldOpenParentDashboard(
                isGuest = false,
                authUid = "parent-uid",
                authIsAnonymous = false
            )
        )
    }

    @Test
    fun missingParentSessionRoutesToSetup() {
        assertFalse(
            ParentSessionReentryPolicy.shouldOpenParentDashboard(
                isGuest = true,
                authUid = null,
                authIsAnonymous = false
            )
        )
        assertFalse(
            ParentSessionReentryPolicy.shouldOpenParentDashboard(
                isGuest = false,
                authUid = "child-uid",
                authIsAnonymous = true
            )
        )
    }

    @Test
    fun childFirebaseIdentityCannotMasqueradeAsParent() {
        assertFalse(
            ParentSessionReentryPolicy.shouldOpenParentDashboard(
                isGuest = true,
                authUid = "child-uid",
                authIsAnonymous = false
            )
        )
    }

    @Test
    fun restartRestorationRequiresPersistedParentAuthAndGuestMarker() {
        assertTrue(
            ParentSessionReentryPolicy.shouldOpenParentDashboard(
                isGuest = true,
                authUid = "parent-uid",
                authIsAnonymous = true
            )
        )
        assertFalse(
            ParentSessionReentryPolicy.shouldOpenParentDashboard(
                isGuest = false,
                authUid = "parent-uid",
                authIsAnonymous = true
            )
        )
    }
}
