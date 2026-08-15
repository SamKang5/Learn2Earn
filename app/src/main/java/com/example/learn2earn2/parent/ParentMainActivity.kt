package com.example.learn2earn2.parent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.example.learn2earn2.R
import com.example.learn2earn2.account.ParentAccount
import com.example.learn2earn2.emergency.EmergencyContactsActivity
import com.example.learn2earn2.onboarding.RoleSelectionActivity
import com.example.learn2earn2.quiz.QuizFragment
import com.example.learn2earn2.ui.AppCredits

class ParentMainActivity : AppCompatActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val bottomNav = findViewById<BottomNavigationView>(R.id.parent_bottom_nav)
        bottomNav.selectedItemId = when (intent.getIntExtra(EXTRA_PARENT_TAB, R.id.nav_devices)) {
            R.id.nav_quizzes -> R.id.nav_quizzes
            R.id.nav_progress -> R.id.nav_progress
            else -> R.id.nav_devices
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(target.paddingLeft, bars.top, target.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)

        val btnResetRole = findViewById<Button>(R.id.btn_reset_role)
        val appTitle = findViewById<TextView>(R.id.tv_parent_app_title)
        val bottomNav = findViewById<BottomNavigationView>(R.id.parent_bottom_nav)
        btnResetRole.setOnClickListener { showSwitchUserPrompt() }
        appTitle.setOnClickListener { AppCredits.show(this) }
        bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_devices -> DevicesFragment()
                R.id.nav_quizzes -> QuizFragment()
                R.id.nav_progress -> ProgressFragment()
                R.id.nav_emergency_contacts -> {
                    startActivity(Intent(this, EmergencyContactsActivity::class.java))
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                    return@setOnItemSelectedListener false
                }
                else -> return@setOnItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
            true
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DevicesFragment())
                .commit()
        }

        when (intent.getIntExtra(EXTRA_PARENT_TAB, R.id.nav_devices)) {
            R.id.nav_quizzes -> bottomNav.selectedItemId = R.id.nav_quizzes
            R.id.nav_progress -> bottomNav.selectedItemId = R.id.nav_progress
            else -> bottomNav.selectedItemId = R.id.nav_devices
        }
    }

    private fun showSwitchUserPrompt() {
        AlertDialog.Builder(this)
            .setTitle("Switch user?")
            .setMessage("Switch user keeps this parent signed in. Log out removes this parent session first.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Log out") { _, _ -> logOut() }
            .setPositiveButton("Switch user") { _, _ -> switchUser() }
            .show()
    }

    private fun switchUser() {
        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? DevicesFragment)
            ?.preserveActiveCodeForRoleSwitch()
        getSharedPreferences("learn2earn_prefs", Context.MODE_PRIVATE)
            .edit().remove("user_role").apply()
        openRoleSelection()
    }

    private fun logOut() {
        ParentAccount.clear(this)
        getSharedPreferences("learn2earn_prefs", Context.MODE_PRIVATE)
            .edit().remove("user_role").apply()
        openRoleSelection()
    }

    private fun openRoleSelection() {
        startActivity(
            Intent(this, RoleSelectionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    companion object {
        const val EXTRA_PARENT_TAB = "parent_tab"
    }
}
