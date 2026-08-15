package com.example.learn2earn2.child

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Real-time foreground app signal for the paired-child timer. */
class ChildUsageAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        event.packageName?.toString()?.takeIf { it.isNotBlank() }?.let(ForegroundAppTracker::update)
    }

    override fun onInterrupt() = Unit
}

/** Shared, in-process last-known foreground app supplied by the accessibility service. */
object ForegroundAppTracker {
    @Volatile private var packageName: String? = null

    fun update(value: String) {
        packageName = value
    }

    /**
     * Accessibility only sends an event when the active window changes. The current
     * foreground app is therefore valid until Android reports a different window.
     */
    fun lastKnownPackage(): String? = packageName
}
