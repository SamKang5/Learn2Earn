package com.example.learn2earn2.emergency

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.learn2earn2.R
import com.example.learn2earn2.account.ParentAccount
import com.example.learn2earn2.onboarding.RoleSelectionActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.UUID

class EmergencyContactsActivity : AppCompatActivity() {
    private lateinit var repository: EmergencyContactRepository
    private lateinit var adapter: EmergencyContactsAdapter
    private lateinit var emptyText: TextView
    private lateinit var contactList: RecyclerView

    private val requestContactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchContactPicker() else showContactsPermissionDenied()
    }

    private val contactPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, "No contact selected.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val contactUri = result.data?.data
        if (contactUri == null) {
            Toast.makeText(this, "Selected contact was unavailable.", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        handlePickedContact(contactUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = EmergencyContactRepository(this)
        adapter = EmergencyContactsAdapter(
            onEdit = { showManualContactDialog(it) },
            onRemove = { removeContact(it) },
            onMoveUp = { moveContact(it, -1) },
            onMoveDown = { moveContact(it, 1) }
        )
        setContentView(createContent())
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(target.paddingLeft, bars.top, target.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
        renderContacts()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun createContent(): View {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.l2e_canvas))
        }
        shell.addView(createTopIsland(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = 16.dp()
            topMargin = 12.dp()
            marginEnd = 16.dp()
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 18.dp(), 20.dp(), 18.dp())
        }
        root.addView(TextView(this).apply {
            text = "Emergency contacts"
            setTextColor(getColor(R.color.l2e_ink))
            textSize = 30f
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
        })
        root.addView(TextView(this).apply {
            text = "Approved personal contacts are stored only on this device."
            setTextColor(getColor(R.color.l2e_muted))
            textSize = 15f
            setPadding(0, 4.dp(), 0, 14.dp())
        })
        root.addView(actionButton("Choose from contacts", primary = true) {
            ensureContactsPermissionAndPick()
        })
        root.addView(actionButton("Enter manually", primary = false) {
            showManualContactDialog(null)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = 8.dp()
        })
        root.addView(TextView(this).apply {
            text = "OFFICIAL SERVICES"
            setTextColor(getColor(R.color.l2e_muted))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 18.dp(), 0, 6.dp())
        })
        root.addView(TextView(this).apply {
            text = EmergencyServices.officialServices.joinToString("\n") { "${it.label} - ${it.phoneNumber}" }
            setTextColor(getColor(R.color.l2e_ink))
            textSize = 15f
            setBackgroundResource(R.drawable.bg_child_item)
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        })
        root.addView(TextView(this).apply {
            text = "APPROVED PERSONAL CONTACTS"
            setTextColor(getColor(R.color.l2e_muted))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 18.dp(), 0, 6.dp())
        })
        emptyText = TextView(this).apply {
            text = "No personal emergency contacts approved yet."
            setTextColor(getColor(R.color.l2e_muted))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(16.dp(), 20.dp(), 16.dp(), 20.dp())
        }
        contactList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EmergencyContactsActivity)
            adapter = this@EmergencyContactsActivity.adapter
            clipToPadding = false
        }
        root.addView(FrameStack(emptyText, contactList), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = 4.dp() })

        shell.addView(root, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        shell.addView(createBottomNavigation())
        return shell
    }

    private fun createTopIsland(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
        setBackgroundResource(R.drawable.bg_top_island)
        addView(TextView(this@EmergencyContactsActivity).apply {
            text = "Learn2Earn"
            setTextColor(getColor(R.color.white))
            textSize = 24f
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(this@EmergencyContactsActivity).apply {
            text = "Switch user"
            isAllCaps = false
            setTextColor(getColor(R.color.l2e_ink))
            textSize = 12f
            setBackgroundResource(R.drawable.bg_btn_secondary)
            backgroundTintList = null
            setOnClickListener { resetRole() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 40.dp()))
    }

    private fun createBottomNavigation(): BottomNavigationView = BottomNavigationView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            64.dp()
        )
        setBackgroundColor(getColor(R.color.l2e_surface))
        itemIconTintList = ContextCompat.getColorStateList(context, R.color.l2e_forest)
        itemTextColor = ContextCompat.getColorStateList(context, R.color.l2e_forest)
        labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
        inflateMenu(R.menu.parent_nav_menu)
        selectedItemId = R.id.nav_emergency_contacts
        setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_emergency_contacts -> true
                else -> {
                    finish()
                    true
                }
            }
        }
    }

    private fun resetRole() {
        ParentAccount.clear(this)
        getSharedPreferences("learn2earn_prefs", Context.MODE_PRIVATE).edit()
            .remove("user_role")
            .apply()
        startActivity(Intent(this, RoleSelectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun actionButton(label: String, primary: Boolean, click: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(getColor(if (primary) R.color.white else R.color.l2e_ink))
            setBackgroundResource(if (primary) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
            backgroundTintList = null
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                52.dp()
            )
        }

    private fun showManualContactDialog(contact: EmergencyContact?) {
        val nameInput = EditText(this).apply {
            hint = "Contact name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(contact?.displayName.orEmpty())
            setSingleLine(true)
        }
        val phoneInput = EditText(this).apply {
            hint = "Phone number"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(contact?.phoneNumber.orEmpty())
            setSingleLine(true)
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 8.dp(), 20.dp(), 0)
            addView(nameInput)
            addView(phoneInput)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (contact == null) "Add emergency contact" else "Edit emergency contact")
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val error = saveManualContact(
                    nameInput.text.toString(),
                    phoneInput.text.toString(),
                    contact?.id
                )
                if (error == null) dialog.dismiss() else Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    private fun saveManualContact(name: String, phone: String, replacingId: String?): String? {
        val contacts = repository.contacts()
        val input = when (val result = EmergencyContactValidation.validateManualContact(
            displayName = name,
            phoneNumber = phone,
            existingContacts = contacts,
            replacingContactId = replacingId
        )) {
            is EmergencyContactValidationResult.Valid -> result.input
            is EmergencyContactValidationResult.Invalid -> return result.message
        }
        val replacementIndex = contacts.indexOfFirst { it.id == replacingId }
        val id = replacingId ?: UUID.randomUUID().toString()
        val nextContact = EmergencyContact(
            id = id,
            displayName = input.displayName,
            phoneNumber = input.phoneNumber,
            normalizedPhoneNumber = input.normalizedPhoneNumber,
            contactId = null,
            lookupKey = null,
            displayOrder = if (replacementIndex >= 0) replacementIndex else contacts.size
        )
        val updated = if (replacementIndex >= 0) {
            contacts.toMutableList().also { it[replacementIndex] = nextContact }
        } else {
            contacts + nextContact
        }
        repository.saveContacts(updated)
        renderContacts()
        Toast.makeText(this, "Emergency contact saved.", Toast.LENGTH_SHORT).show()
        return null
    }

    private fun ensureContactsPermissionAndPick() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchContactPicker()
            return
        }
        requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun launchContactPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "No Contacts app is available on this device.", Toast.LENGTH_LONG).show()
            return
        }
        contactPicker.launch(intent)
    }

    private fun showContactsPermissionDenied() {
        AlertDialog.Builder(this)
            .setTitle("Contacts permission")
            .setMessage("Contacts permission is only used to choose one approved emergency contact.")
            .setPositiveButton("Open settings") { _, _ ->
                val uri = Uri.fromParts("package", packageName, null)
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handlePickedContact(contactUri: Uri) {
        val contact = readPickedContact(contactUri)
        if (contact == null) {
            Toast.makeText(this, "Selected contact was unavailable.", Toast.LENGTH_LONG).show()
            return
        }
        if (contact.phoneNumbers.isEmpty()) {
            Toast.makeText(this, "Choose a contact with a usable phone number.", Toast.LENGTH_LONG).show()
            return
        }
        if (contact.phoneNumbers.size == 1) {
            savePickedContact(contact, contact.phoneNumbers.first())
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose a number")
            .setItems(contact.phoneNumbers.toTypedArray()) { _, which ->
                savePickedContact(contact, contact.phoneNumbers[which])
            }
            .show()
    }

    private fun readPickedContact(contactUri: Uri): PickedContact? {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )
        contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY))
                ?: "Emergency contact"
            val lookupKey = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY))
            val hasPhone = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0
            return PickedContact(id, lookupKey, name, if (hasPhone) readContactPhoneNumbers(id) else emptyList())
        }
        return null
    }

    private fun readContactPhoneNumbers(contactId: Long): List<String> {
        val numbers = mutableListOf<String>()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val args = arrayOf(contactId.toString())
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    ?.trim()
                    .orEmpty()
                val normalized = EmergencyPhoneNumbers.normalize(number)
                if (EmergencyPhoneNumbers.isUsable(number) &&
                    numbers.none { EmergencyPhoneNumbers.normalize(it) == normalized }
                ) {
                    numbers.add(number)
                }
            }
        }
        return numbers
    }

    private fun savePickedContact(contact: PickedContact, phoneNumber: String) {
        val normalized = EmergencyPhoneNumbers.normalize(phoneNumber)
        if (!EmergencyPhoneNumbers.isUsable(phoneNumber)) {
            Toast.makeText(this, "Choose a contact with a usable phone number.", Toast.LENGTH_LONG).show()
            return
        }
        if (isDuplicateNumber(normalized, replacingId = null)) {
            Toast.makeText(this, "That phone number is already approved.", Toast.LENGTH_LONG).show()
            return
        }
        val contacts = repository.contacts()
        repository.saveContacts(
            contacts + EmergencyContact(
                id = UUID.randomUUID().toString(),
                displayName = contact.displayName.trim().ifBlank { "Emergency contact" },
                phoneNumber = phoneNumber.trim(),
                normalizedPhoneNumber = normalized,
                contactId = contact.contactId,
                lookupKey = contact.lookupKey,
                displayOrder = contacts.size
            )
        )
        renderContacts()
        Toast.makeText(this, "Emergency contact saved.", Toast.LENGTH_SHORT).show()
    }

    private fun removeContact(contact: EmergencyContact) {
        repository.saveContacts(repository.contacts().filterNot { it.id == contact.id })
        renderContacts()
    }

    private fun moveContact(contact: EmergencyContact, direction: Int) {
        val contacts = repository.contacts().toMutableList()
        val from = contacts.indexOfFirst { it.id == contact.id }
        val to = from + direction
        if (from !in contacts.indices || to !in contacts.indices) return
        val moved = contacts.removeAt(from)
        contacts.add(to, moved)
        repository.saveContacts(contacts)
        renderContacts()
    }

    private fun renderContacts() {
        val contacts = repository.contacts()
        adapter.submitContacts(contacts)
        emptyText.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
        contactList.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun isDuplicateNumber(normalized: String, replacingId: String?): Boolean =
        repository.contacts().any { it.id != replacingId && it.normalizedPhoneNumber == normalized }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private class FrameStack(vararg views: View) : android.widget.FrameLayout(views.first().context) {
        init {
            views.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        }
    }

    private data class PickedContact(
        val contactId: Long,
        val lookupKey: String?,
        val displayName: String,
        val phoneNumbers: List<String>
    )
}
