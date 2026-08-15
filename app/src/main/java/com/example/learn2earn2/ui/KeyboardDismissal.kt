package com.example.learn2earn2.ui

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object KeyboardDismissal {
    fun dismissIfOutsideFocusedInput(window: android.view.Window, focusedView: View?, event: MotionEvent) {
        if (event.action != MotionEvent.ACTION_DOWN || focusedView !is EditText) return

        val bounds = Rect()
        focusedView.getGlobalVisibleRect(bounds)
        if (!bounds.contains(event.rawX.toInt(), event.rawY.toInt()) && !isTouchInsideInput(window.decorView, event)) {
            focusedView.clearFocus()
            WindowInsetsControllerCompat(window, focusedView).hide(WindowInsetsCompat.Type.ime())
        }
    }

    private fun isTouchInsideInput(view: View, event: MotionEvent): Boolean = when (view) {
        is EditText -> {
            val bounds = Rect()
            view.getGlobalVisibleRect(bounds)
            bounds.contains(event.rawX.toInt(), event.rawY.toInt())
        }
        is android.view.ViewGroup -> (0 until view.childCount).any {
            isTouchInsideInput(view.getChildAt(it), event)
        }
        else -> false
    }
}
