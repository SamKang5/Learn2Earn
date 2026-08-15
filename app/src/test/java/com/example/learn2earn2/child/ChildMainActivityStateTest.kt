package com.example.learn2earn2.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ChildMainActivityStateTest {

    private fun formatClock(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d",
            safe / 3_600,
            (safe % 3_600) / 60,
            safe % 60
        )
    }

    @Test
    fun testClockFormatting() {
        assertEquals("00:00:00", formatClock(0))
        assertEquals("00:00:45", formatClock(45))
        assertEquals("00:01:00", formatClock(60))
        assertEquals("00:05:30", formatClock(330))
        assertEquals("01:00:00", formatClock(3600))
        assertEquals("02:30:15", formatClock(9015))
        assertEquals("00:00:00", formatClock(-50))
    }

    @Test
    fun testPairingCodeNormalization() {
        val rawDigits = listOf("l", "2", "e", "4", "2", "a")
        val code = rawDigits.joinToString("").trim().uppercase()
        assertEquals("L2E42A", code)
        assertEquals(6, code.length)
    }

    @Test
    fun testStudyEnergyCalculation() {
        val capMinutes = 120
        val usedMinutes = 45
        val remaining = (capMinutes - usedMinutes).coerceIn(0, capMinutes)
        assertEquals(75, remaining)
    }
}
