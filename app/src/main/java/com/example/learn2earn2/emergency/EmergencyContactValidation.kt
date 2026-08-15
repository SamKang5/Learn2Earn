package com.example.learn2earn2.emergency

data class ValidEmergencyContactInput(
    val displayName: String,
    val phoneNumber: String,
    val normalizedPhoneNumber: String
)

sealed class EmergencyContactValidationResult {
    data class Valid(val input: ValidEmergencyContactInput) : EmergencyContactValidationResult()
    data class Invalid(val message: String) : EmergencyContactValidationResult()
}

object EmergencyContactValidation {
    fun validateManualContact(
        displayName: String,
        phoneNumber: String,
        existingContacts: List<EmergencyContact>,
        replacingContactId: String? = null
    ): EmergencyContactValidationResult {
        val cleanName = displayName.trim()
        val cleanPhone = phoneNumber.trim()
        val normalizedPhone = EmergencyPhoneNumbers.normalize(cleanPhone)
        return when {
            cleanName.isBlank() -> EmergencyContactValidationResult.Invalid("Enter a contact name.")
            cleanPhone.isBlank() -> EmergencyContactValidationResult.Invalid("Enter a phone number.")
            !EmergencyPhoneNumbers.isUsable(cleanPhone) ->
                EmergencyContactValidationResult.Invalid("Enter a usable phone number.")
            existingContacts.any {
                it.id != replacingContactId && it.normalizedPhoneNumber == normalizedPhone
            } -> EmergencyContactValidationResult.Invalid("That phone number is already approved.")
            else -> EmergencyContactValidationResult.Valid(
                ValidEmergencyContactInput(cleanName, cleanPhone, normalizedPhone)
            )
        }
    }
}
