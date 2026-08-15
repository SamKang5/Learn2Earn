package com.example.learn2earn2.ui

import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity

abstract class KeyboardDismissActivity : AppCompatActivity() {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        KeyboardDismissal.dismissIfOutsideFocusedInput(window, currentFocus, event)
        return super.dispatchTouchEvent(event)
    }
}
