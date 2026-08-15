package com.example.learn2earn2.emergency

data class EmergencyContact(
    val id: String,
    val displayName: String,
    val phoneNumber: String,
    val normalizedPhoneNumber: String,
    val contactId: Long?,
    val lookupKey: String?,
    val displayOrder: Int
) {
    val isFromDeviceContact: Boolean
        get() = contactId != null || !lookupKey.isNullOrBlank()
}

data class EmergencyCallTarget(
    val label: String,
    val phoneNumber: String,
    val official: Boolean
)
