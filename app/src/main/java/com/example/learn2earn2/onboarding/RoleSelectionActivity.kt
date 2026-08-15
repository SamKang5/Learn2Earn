package com.example.learn2earn2.onboarding

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.learn2earn2.R

class RoleSelectionActivity : AppCompatActivity() {

    private companion object {
        const val PREFS_NAME = "learn2earn_prefs"
        const val KEY_ROLE = "user_role"
        const val ROLE_PARENT = "parent"
        const val ROLE_CHILD = "child"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_role_selection)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(target.paddingLeft, bars.top, target.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)

        findViewById<View>(R.id.card_parent).setOnClickListener {
            saveRole(ROLE_PARENT)
        }

        findViewById<View>(R.id.card_child).setOnClickListener {
            saveRole(ROLE_CHILD)
        }
    }

    private fun saveRole(role: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ROLE, role).apply()
        Toast.makeText(this, "Selected $role mode", Toast.LENGTH_SHORT).show()
    }
}
