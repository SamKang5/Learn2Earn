package com.example.learn2earn2.child

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.learn2earn2.R
import com.example.learn2earn2.account.GuestApproval
import com.example.learn2earn2.onboarding.RoleSelectionActivity

class ParentApprovalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { setBackgroundColor(getColor(R.color.l2e_canvas)) })
        showApproval()
    }

    private fun showApproval() {
        val prefs = getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
        val lockedUntil = prefs.getLong(KEY_APPROVAL_LOCKED_UNTIL, 0L)
        if (lockedUntil > System.currentTimeMillis()) {
            val waitMinutes = ((lockedUntil - System.currentTimeMillis() + 59_999) / 60_000)
                .coerceAtLeast(1)
            Toast.makeText(
                this,
                "Too many failed approvals. Try again in $waitMinutes minute${if (waitMinutes == 1L) "" else "s"}.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        val email = prefs.getString(PARENT_EMAIL, null)
        val guestHash = prefs.getString(GuestApproval.KEY_HASH, null)
        if (email.isNullOrBlank() && guestHash.isNullOrBlank()) {
            Toast.makeText(this, "Parent approval is not configured for this device.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val password = EditText(this).apply {
            hint = if (email.isNullOrBlank()) "Guest approval PIN" else "Parent account password"
            inputType = if (email.isNullOrBlank()) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        val emergencyContactManagement = intent.action == ACTION_MANAGE_EMERGENCY_CONTACTS
        val credentialName = if (email.isNullOrBlank()) "guest approval PIN" else "parent password"
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (emergencyContactManagement) "Emergency contacts" else "Switch role")
            .setMessage(
                if (emergencyContactManagement) {
                    "The $credentialName opens emergency contact settings on this device. This does not unlock the child session."
                } else {
                    "The $credentialName is required before changing this paired device's role."
                }
            )
            .setView(password)
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setPositiveButton("Confirm", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val passwordText = password.text.toString()
                if (passwordText.isBlank()) {
                    password.error = "Enter parent password"
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                val verify: ((Boolean) -> Unit) -> Unit = if (email.isNullOrBlank()) {
                    { complete -> complete(GuestApproval.verify(passwordText, guestHash.orEmpty())) }
                } else {
                    { complete -> ParentPasswordVerifier.verify(this, email, passwordText, complete) }
                }
                verify { valid ->
                    runOnUiThread {
                        if (!valid) {
                            val failures = prefs.getInt(KEY_APPROVAL_FAILURES, 0) + 1
                            if (failures >= MAX_APPROVAL_FAILURES) {
                                prefs.edit()
                                    .remove(KEY_APPROVAL_FAILURES)
                                    .putLong(
                                        KEY_APPROVAL_LOCKED_UNTIL,
                                        System.currentTimeMillis() + APPROVAL_LOCKOUT_MS
                                    )
                                    .apply()
                                Toast.makeText(
                                    this,
                                    "Too many failed approvals. Try again in 5 minutes.",
                                    Toast.LENGTH_LONG
                                ).show()
                                dialog.dismiss()
                            } else {
                                prefs.edit().putInt(KEY_APPROVAL_FAILURES, failures).apply()
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                val remaining = MAX_APPROVAL_FAILURES - failures
                                password.error =
                                    "Not accepted. $remaining attempt${if (remaining == 1) "" else "s"} left"
                            }
                            return@runOnUiThread
                        }
                        prefs.edit()
                            .remove(KEY_APPROVAL_FAILURES)
                            .remove(KEY_APPROVAL_LOCKED_UNTIL)
                            .apply()
                        if (emergencyContactManagement) openEmergencyContactSettings() else switchRole()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.setOnDismissListener { if (!isFinishing) finish() }
        dialog.show()
    }

    private fun switchRole() {
        getSharedPreferences("learn2earn_prefs", Context.MODE_PRIVATE).edit().remove("user_role").apply()
        val childPrefs = getSharedPreferences("learn2earn_child", Context.MODE_PRIVATE)
        val parentId = childPrefs.getString(ChildLockService.PARENT_ID, null)
        val childId = childPrefs.getString(ChildLockService.CHILD_ID, null)
        // Child credentials can write only their runtime branch. The parent listener
        // consumes this request and deletes the complete child record.
        if (!parentId.isNullOrBlank() && !childId.isNullOrBlank()) {
            ChildFirebaseSession.database(this)
                .getReference("users/$parentId/children/$childId/runtime/unpairRequestedAt")
                .setValue(com.google.firebase.database.ServerValue.TIMESTAMP)
        }
        childPrefs.edit().clear().apply()
        ChildLockService.stop(this)
        startActivity(Intent(this, RoleSelectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun openEmergencyContactSettings() {
        Toast.makeText(this, "Emergency contacts settings", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val ACTION_MANAGE_EMERGENCY_CONTACTS = "com.example.learn2earn2.MANAGE_EMERGENCY_CONTACTS"
        const val ACTION_SWITCH_ROLE = "com.example.learn2earn2.SWITCH_ROLE"
        const val PARENT_EMAIL = "parent_email"
        private const val KEY_APPROVAL_FAILURES = "parent_approval_failures"
        private const val KEY_APPROVAL_LOCKED_UNTIL = "parent_approval_locked_until"
        private const val MAX_APPROVAL_FAILURES = 5
        private const val APPROVAL_LOCKOUT_MS = 5 * 60 * 1000L
    }
}
