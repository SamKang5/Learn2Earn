package com.example.learn2earn2.emergency

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class EmergencyContactRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun contacts(): List<EmergencyContact> {
        val raw = prefs.getString(KEY_CONTACTS_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString(KEY_ID).takeIf { it.isNotBlank() } ?: continue
                    val name = item.optString(KEY_NAME).takeIf { it.isNotBlank() } ?: continue
                    val phone = item.optString(KEY_PHONE).takeIf { it.isNotBlank() } ?: continue
                    val normalized = item.optString(KEY_NORMALIZED).takeIf { it.isNotBlank() }
                        ?: EmergencyPhoneNumbers.normalize(phone)
                    if (normalized.isBlank()) continue
                    add(
                        EmergencyContact(
                            id = id,
                            displayName = name,
                            phoneNumber = phone,
                            normalizedPhoneNumber = normalized,
                            contactId = if (item.isNull(KEY_CONTACT_ID)) null else item.optLong(KEY_CONTACT_ID),
                            lookupKey = item.optString(KEY_LOOKUP_KEY).takeIf { it.isNotBlank() },
                            displayOrder = item.optInt(KEY_ORDER, index)
                        )
                    )
                }
            }.sortedBy { it.displayOrder }
        } catch (_: JSONException) {
            clearCorruptContacts()
        } catch (_: RuntimeException) {
            clearCorruptContacts()
        }
    }

    fun saveContacts(contacts: List<EmergencyContact>) {
        val array = JSONArray()
        contacts.forEachIndexed { index, contact ->
            array.put(
                JSONObject()
                    .put(KEY_ID, contact.id)
                    .put(KEY_NAME, contact.displayName)
                    .put(KEY_PHONE, contact.phoneNumber)
                    .put(KEY_NORMALIZED, contact.normalizedPhoneNumber)
                    .put(KEY_CONTACT_ID, contact.contactId ?: JSONObject.NULL)
                    .put(KEY_LOOKUP_KEY, contact.lookupKey ?: JSONObject.NULL)
                    .put(KEY_ORDER, index)
            )
        }
        prefs.edit().putString(KEY_CONTACTS_JSON, array.toString()).apply()
    }

    fun approvedCallTargets(): List<EmergencyCallTarget> =
        contacts().map { EmergencyCallTarget(it.displayName, it.phoneNumber, official = false) }

    private fun clearCorruptContacts(): List<EmergencyContact> {
        prefs.edit().remove(KEY_CONTACTS_JSON).apply()
        return emptyList()
    }

    companion object {
        private const val PREFS_NAME = "learn2earn_emergency_contacts"
        private const val KEY_CONTACTS_JSON = "approved_contacts_json"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "displayName"
        private const val KEY_PHONE = "phoneNumber"
        private const val KEY_NORMALIZED = "normalizedPhoneNumber"
        private const val KEY_CONTACT_ID = "contactId"
        private const val KEY_LOOKUP_KEY = "lookupKey"
        private const val KEY_ORDER = "displayOrder"
    }
}
