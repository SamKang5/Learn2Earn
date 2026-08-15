package com.example.learn2earn2.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingIdentityPolicyTest {
    @Test
    fun childPathAllowsOnlyCurrentChildIdentity() {
        assertTrue(PairingIdentityPolicy.childOwnsPath("child-uid", "child-uid", "child-uid"))
        assertTrue(PairingIdentityPolicy.childOwnsPath("child-uid", "legacy-child-key", "child-uid"))
        assertFalse(PairingIdentityPolicy.childOwnsPath("child-a", "child-b", "child-b"))
    }

}
