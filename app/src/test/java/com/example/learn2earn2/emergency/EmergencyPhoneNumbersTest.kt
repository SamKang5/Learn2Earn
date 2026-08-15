package com.example.learn2earn2.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyPhoneNumbersTest {
    @Test
    fun normalizesSpacesAndPunctuation() {
        assertEquals("+84901234567", EmergencyPhoneNumbers.normalize(" +84 90-123-4567 "))
        assertEquals("0901234567", EmergencyPhoneNumbers.normalize("090.123.4567"))
    }

    @Test
    fun rejectsUnusableNumbers() {
        assertFalse(EmergencyPhoneNumbers.isUsable(""))
        assertFalse(EmergencyPhoneNumbers.isUsable("x1"))
        assertTrue(EmergencyPhoneNumbers.isUsable("115"))
    }

    @Test
    fun masksLongPersonalNumbers() {
        assertEquals("+*******4567", EmergencyPhoneNumbers.mask("+84 90 123 4567"))
        assertEquals("115", EmergencyPhoneNumbers.mask("115"))
    }

    @Test
    fun acceptsValidManualContact() {
        val result = EmergencyContactValidation.validateManualContact(
            displayName = "Auntie",
            phoneNumber = " 090 123 4567 ",
            existingContacts = emptyList()
        )

        assertTrue(result is EmergencyContactValidationResult.Valid)
        val input = (result as EmergencyContactValidationResult.Valid).input
        assertEquals("Auntie", input.displayName)
        assertEquals("090 123 4567", input.phoneNumber)
        assertEquals("0901234567", input.normalizedPhoneNumber)
    }

    @Test
    fun rejectsEmptyManualNumber() {
        val result = EmergencyContactValidation.validateManualContact(
            displayName = "Auntie",
            phoneNumber = " ",
            existingContacts = emptyList()
        )

        assertTrue(result is EmergencyContactValidationResult.Invalid)
    }

    @Test
    fun rejectsInvalidManualNumber() {
        val result = EmergencyContactValidation.validateManualContact(
            displayName = "Auntie",
            phoneNumber = "x1",
            existingContacts = emptyList()
        )

        assertTrue(result is EmergencyContactValidationResult.Invalid)
    }

    @Test
    fun rejectsDuplicateFormattedManualNumber() {
        val existing = listOf(contact("parent", "090.123.4567"))
        val result = EmergencyContactValidation.validateManualContact(
            displayName = "Parent mobile",
            phoneNumber = "090 123 4567",
            existingContacts = existing
        )

        assertTrue(result is EmergencyContactValidationResult.Invalid)
    }

    @Test
    fun allowsEditingExistingNumberButRejectsAnotherContactDuplicate() {
        val existing = listOf(
            contact("parent", "090.123.4567"),
            contact("auntie", "091 222 3333")
        )

        val unchanged = EmergencyContactValidation.validateManualContact(
            displayName = "Parent",
            phoneNumber = "090 123 4567",
            existingContacts = existing,
            replacingContactId = "parent"
        )
        val duplicate = EmergencyContactValidation.validateManualContact(
            displayName = "Parent",
            phoneNumber = "091-222-3333",
            existingContacts = existing,
            replacingContactId = "parent"
        )

        assertTrue(unchanged is EmergencyContactValidationResult.Valid)
        assertTrue(duplicate is EmergencyContactValidationResult.Invalid)
    }

    private fun contact(id: String, phoneNumber: String) = EmergencyContact(
        id = id,
        displayName = id,
        phoneNumber = phoneNumber,
        normalizedPhoneNumber = EmergencyPhoneNumbers.normalize(phoneNumber),
        contactId = null,
        lookupKey = null,
        displayOrder = 0
    )
}
