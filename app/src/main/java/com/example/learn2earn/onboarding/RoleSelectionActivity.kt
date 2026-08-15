package com.example.learn2earn.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.learn2earn.R
import com.example.learn2earn.account.ParentAccount
import com.example.learn2earn.account.ParentAccountActivity
import com.example.learn2earn.child.ChildMainActivity
import com.example.learn2earn.parent.ParentMainActivity

class RoleSelectionActivity : AppCompatActivity() {

    private companion object {
        const val PREFS_NAME = "learn2earn_prefs"
        const val KEY_ROLE = "user_role"
        const val ROLE_PARENT = "parent"
        const val ROLE_CHILD = "child"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if role is already selected
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedRole = prefs.getString(KEY_ROLE, null)

        if (savedRole != null) {
            if (savedRole == ROLE_PARENT && !ParentAccount.isReady(this)) {
                startActivity(Intent(this, ParentAccountActivity::class.java))
                finish()
            } else {
                navigateToMain(savedRole)
            }
            return
        }

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
            saveRoleAndNavigate(ROLE_PARENT)
        }

        findViewById<View>(R.id.card_child).setOnClickListener {
            saveRoleAndNavigate(ROLE_CHILD)
        }
    }

    private fun saveRoleAndNavigate(role: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ROLE, role).apply()
        if (role == ROLE_PARENT) {
            startActivity(Intent(this, ParentAccountActivity::class.java))
            finish()
        } else {
            navigateToMain(role)
        }
    }

    private fun navigateToMain(role: String) {
        val targetClass = if (role == ROLE_PARENT) {
            ParentMainActivity::class.java
        } else {
            ChildMainActivity::class.java
        }

        val intent = Intent(this, targetClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
