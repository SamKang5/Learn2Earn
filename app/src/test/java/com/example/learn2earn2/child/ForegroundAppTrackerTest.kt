package com.example.learn2earn2.child

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundAppTrackerTest {

    @Test
    fun keepsLastForegroundAppUntilAndroidReportsAnotherWindow() {
        ForegroundAppTracker.update("example.video")
        assertEquals("example.video", ForegroundAppTracker.lastKnownPackage())

        ForegroundAppTracker.update("example.chat")
        assertEquals("example.chat", ForegroundAppTracker.lastKnownPackage())
    }
}
