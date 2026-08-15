package com.example.learn2earn2.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyContactRepositoryTest {

    @Test
    fun testContactModelProperties() {
        val contactWithDevice = EmergencyContact(
            id = "1",
            displayName = "Mom",
            phoneNumber = "0901234567",
            normalizedPhoneNumber = "0901234567",
            contactId = 101L,
            lookupKey = "mom_key",
            displayOrder = 0
        )
        assertTrue(contactWithDevice.isFromDeviceContact)

        val manualContact = EmergencyContact(
            id = "2",
            displayName = "Dad",
            phoneNumber = "0907654321",
            normalizedPhoneNumber = "0907654321",
            contactId = null,
            lookupKey = null,
            displayOrder = 1
        )
        assertFalse(manualContact.isFromDeviceContact)
    }

    @Test
    fun testDisplayOrderSorting() {
        val unsorted = listOf(
            EmergencyContact("b", "B", "111", "111", null, null, 2),
            EmergencyContact("a", "A", "222", "222", null, null, 0),
            EmergencyContact("c", "C", "333", "333", null, null, 1)
        )

        val sorted = unsorted.sortedBy { it.displayOrder }
        assertEquals("a", sorted[0].id)
        assertEquals("c", sorted[1].id)
        assertEquals("b", sorted[2].id)
    }

    @Test
    fun testEmergencyServicesDefaults() {
        val services = EmergencyServices.officialServices
        assertEquals(3, services.size)
        assertEquals("Police", services[0].label)
        assertEquals("113", services[0].phoneNumber)
        assertTrue(services[0].official)
        assertEquals("Fire and Rescue", services[1].label)
        assertEquals("114", services[1].phoneNumber)
        assertEquals("Medical Emergency", services[2].label)
        assertEquals("115", services[2].phoneNumber)
    }
}
