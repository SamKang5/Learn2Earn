package com.example.learn2earn2.emergency

object EmergencyPhoneNumbers {
    fun normalize(rawNumber: String): String {
        val trimmed = rawNumber.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isBlank()) return ""
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    fun isUsable(rawNumber: String): Boolean {
        val normalized = normalize(rawNumber)
        val digits = normalized.count { it.isDigit() }
        return digits >= MIN_USABLE_DIGITS
    }

    fun mask(rawNumber: String): String {
        val normalized = normalize(rawNumber)
        if (normalized.isBlank()) return ""
        val prefix = if (normalized.startsWith("+")) "+" else ""
        val digits = normalized.filter { it.isDigit() }
        if (digits.length <= 4) return prefix + digits
        return prefix + "*".repeat((digits.length - 4).coerceAtMost(8)) + digits.takeLast(4)
    }

    private const val MIN_USABLE_DIGITS = 3
}
