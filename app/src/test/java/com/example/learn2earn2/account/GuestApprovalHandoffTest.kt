package com.example.learn2earn2.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestApprovalHandoffTest {
    @Test
    fun selectPrefersRemoteHash() {
        assertEquals("remote", GuestApprovalHandoff.select("remote", "local"))
    }

    @Test
    fun selectFallsBackToLocalHash() {
        assertEquals("local", GuestApprovalHandoff.select("", "local"))
        assertEquals("local", GuestApprovalHandoff.select(null, "local"))
    }

    @Test
    fun selectOmitsBlankHashes() {
        assertNull(GuestApprovalHandoff.select(null, null))
        assertNull(GuestApprovalHandoff.select("", ""))
    }

    @Test
    fun firebaseFieldsIncludesOnlyPresentHash() {
        assertTrue(GuestApprovalHandoff.firebaseFields(null).isEmpty())
        assertTrue(GuestApprovalHandoff.firebaseFields("").isEmpty())
        assertEquals(
            mapOf(GuestApproval.KEY_HASH to "hash"),
            GuestApprovalHandoff.firebaseFields("hash")
        )
    }
}
