package com.example.learn2earn2.account

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.learn2earn2.R
import com.example.learn2earn2.onboarding.RoleSelectionActivity
import com.example.learn2earn2.parent.ParentMainActivity
import com.example.learn2earn2.ui.AppCredits
import com.example.learn2earn2.ui.KeyboardDismissActivity
import com.google.firebase.auth.FirebaseAuth

class ParentAccountActivity : KeyboardDismissActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var confirmPassword: EditText
    private lateinit var continueButton: Button
    private lateinit var guestButton: View
    private lateinit var confirmPasswordContainer: View
    private var registering = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var authTimeout: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!intent.getBooleanExtra(EXTRA_FORCE_REGISTER, false) && ParentAccount.isReady(this)) {
            openParent()
            return
        }
        setContentView(R.layout.activity_parent_account)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }

        findViewById<TextView>(R.id.tv_role_badge)?.setOnClickListener { AppCredits.show(this) }
        findViewById<View>(R.id.btn_reset_role)?.setOnClickListener { returnToRoleSelection() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                returnToRoleSelection()
            }
        })

        email = findViewById(R.id.et_account_email)
        password = findViewById(R.id.et_account_password)
        confirmPassword = findViewById(R.id.et_account_confirm_password)
        confirmPasswordContainer = findViewById(R.id.til_account_confirm_password)
        continueButton = findViewById(R.id.btn_account_continue)
        findViewById<TextView>(R.id.tv_account_toggle).setOnClickListener { toggleMode() }
        continueButton.setOnClickListener { authenticate() }
        guestButton = findViewById(R.id.btn_continue_guest)
        guestButton.setOnClickListener { continueAsGuest() }
        if (intent.getBooleanExtra(EXTRA_FORCE_REGISTER, false)) toggleMode()
    }

    private fun returnToRoleSelection() {
        getSharedPreferences("learn2earn_prefs", Context.MODE_PRIVATE)
            .edit().remove("user_role").apply()
        startActivity(
            Intent(this, RoleSelectionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun toggleMode() {
        registering = !registering
        confirmPasswordContainer.visibility = if (registering) View.VISIBLE else View.GONE
        if (!registering) confirmPassword.text.clear()
        findViewById<TextView>(R.id.tv_account_title).text = if (registering) "Create account" else "Welcome back"
        findViewById<TextView>(R.id.tv_account_subtitle).text = if (registering) "Set up your parent dashboard" else "Sign in to manage devices and quizzes"
        continueButton.text = if (registering) "Create account" else "Sign in"
        findViewById<TextView>(R.id.tv_account_toggle).text = if (registering) "Already have an account? Sign in" else "New here? Create an account"
    }

    private fun authenticate() {
        val emailText = email.text.toString().trim()
        val passwordText = password.text.toString()
        if (emailText.isBlank() || passwordText.length < 6) {
            Toast.makeText(this, "Enter an email and a password of at least 6 characters", Toast.LENGTH_SHORT).show()
            return
        }
        if (registering && passwordText != confirmPassword.text.toString()) {
            confirmPassword.error = "Passwords do not match"
            confirmPassword.requestFocus()
            return
        }
        confirmPassword.error = null
        beginAuthRequest()
        val task = if (registering) auth.createUserWithEmailAndPassword(emailText, passwordText)
        else auth.signInWithEmailAndPassword(emailText, passwordText)
        task.addOnCompleteListener {
            endAuthRequest()
            if (it.isSuccessful) {
                ParentAccount.useAccount(this)
                if (registering) {
                    auth.currentUser?.sendEmailVerification()
                    Toast.makeText(this, "Verification email sent. Verify it before using AI quizzes.", Toast.LENGTH_LONG).show()
                }
                openParent()
            } else {
                Toast.makeText(this, "Could not sign in. Check credentials, network, and Firebase Auth setup.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun continueAsGuest() {
        beginAuthRequest()
        auth.signInAnonymously().addOnCompleteListener { task ->
            endAuthRequest()
            if (task.isSuccessful) {
                ParentAccount.useGuest(this)
                openParent()
            } else {
                Toast.makeText(this, "Could not start guest mode. Check network and Anonymous Auth setup.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openParent() {
        getSharedPreferences("learn2earn_prefs", Context.MODE_PRIVATE).edit().putString("user_role", "parent").apply()
        startActivity(Intent(this, ParentMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun beginAuthRequest() {
        continueButton.isEnabled = false
        guestButton.isEnabled = false
        authTimeout?.let(mainHandler::removeCallbacks)
        authTimeout = Runnable {
            continueButton.isEnabled = true
            guestButton.isEnabled = true
            Toast.makeText(this, "Connection timed out. Check your internet, then try again.", Toast.LENGTH_LONG).show()
        }.also { mainHandler.postDelayed(it, AUTH_TIMEOUT_MS) }
    }

    private fun endAuthRequest() {
        authTimeout?.let(mainHandler::removeCallbacks)
        authTimeout = null
        continueButton.isEnabled = true
        guestButton.isEnabled = true
    }

    override fun onDestroy() {
        authTimeout?.let(mainHandler::removeCallbacks)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FORCE_REGISTER = "force_register"
        private const val AUTH_TIMEOUT_MS = 15_000L
    }
}
