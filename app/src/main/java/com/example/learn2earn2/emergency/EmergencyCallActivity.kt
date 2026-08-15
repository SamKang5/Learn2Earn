package com.example.learn2earn2.emergency

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.learn2earn2.R
import com.example.learn2earn2.child.ChildLockService
import com.example.learn2earn2.child.ParentApprovalActivity

class EmergencyCallActivity : AppCompatActivity() {
    private lateinit var repository: EmergencyContactRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = EmergencyContactRepository(this)
        setContentView(createContent())
    }

    override fun onResume() {
        super.onResume()
        setContentView(createContent())
    }

    private fun createContent(): View {
        val targets = EmergencyServices.officialServices + repository.approvedCallTargets()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.child_navy))
            setPadding(24.dp(), 26.dp(), 24.dp(), 20.dp())
        }
        root.addView(TextView(this).apply {
            text = "Emergency"
            setTextColor(getColor(R.color.child_on_dark))
            textSize = 30f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Choose an approved emergency number. The device remains locked after the call screen closes."
            setTextColor(getColor(R.color.child_on_dark_muted))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 8.dp(), 0, 18.dp())
        })
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        targets.forEach { target ->
            list.addView(callButton(target))
        }
        if (repository.contacts().isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No personal emergency contacts are approved on this device."
                setTextColor(getColor(R.color.child_on_dark_muted))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(8.dp(), 14.dp(), 8.dp(), 4.dp())
            })
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        root.addView(secondaryButton("Manage approved contacts") {
            startActivity(Intent(this, ParentApprovalActivity::class.java).apply {
                action = ParentApprovalActivity.ACTION_MANAGE_EMERGENCY_CONTACTS
            })
        })
        root.addView(secondaryButton("Back") { finish() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = 8.dp()
        })
        return root
    }

    private fun callButton(target: EmergencyCallTarget): Button =
        Button(this).apply {
            val detail = if (target.official) target.phoneNumber else EmergencyPhoneNumbers.mask(target.phoneNumber)
            text = "${target.label} - $detail"
            isAllCaps = false
            setTextColor(getColor(R.color.child_ink))
            setBackgroundResource(R.drawable.bg_child_secondary)
            backgroundTintList = null
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_call, 0, 0, 0)
            compoundDrawablePadding = 12.dp()
            setOnClickListener { confirmDial(target) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                56.dp()
            ).apply { bottomMargin = 10.dp() }
        }

    private fun secondaryButton(label: String, click: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(getColor(R.color.child_on_dark))
            setBackgroundResource(R.drawable.bg_tactile_button_ghost)
            backgroundTintList = null
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                50.dp()
            )
        }

    private fun confirmDial(target: EmergencyCallTarget) {
        val detail = if (target.official) target.phoneNumber else EmergencyPhoneNumbers.mask(target.phoneNumber)
        AlertDialog.Builder(this)
            .setTitle("Dial ${target.label}?")
            .setMessage("Open the phone dialer for $detail?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open dialer") { _, _ -> openDialer(target.phoneNumber) }
            .show()
    }

    private fun openDialer(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phoneNumber, null))
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val dialerPackage = resolved?.activityInfo?.packageName
        if (dialerPackage == null) {
            Toast.makeText(this, "No phone dialer is available on this device.", Toast.LENGTH_LONG).show()
            return
        }
        intent.setPackage(dialerPackage)
        ChildLockService.allowEmergencyDialer(this, dialerPackage)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No phone dialer is available on this device.", Toast.LENGTH_LONG).show()
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
