package com.heartline.wear

import com.heartline.wear.sensor.batchContact
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LEAD_OFF as the SDK sends it: on the first point of each batch only, the others read null. */
class EcgLeadOffTest {
    private fun batch(first: Int?) = listOf(first) + List(9) { null }

    @Test
    fun firstPointDecidesTheBatch() {
        // The regression: requiring 0 on every point made every batch "no contact".
        assertTrue(batchContact(batch(0), previous = false))
        assertFalse(batchContact(batch(5), previous = true))
        assertFalse(batchContact(batch(3), previous = true)) // undocumented values are no contact
    }

    @Test
    fun batchWithoutValueKeepsThePreviousState() {
        assertTrue(batchContact(batch(null), previous = true))
        assertFalse(batchContact(batch(null), previous = false))
    }

    @Test
    fun anyNonZeroValueInABatchIsNoContact() {
        assertFalse(batchContact(listOf(0, null, 5, null), previous = true))
        assertTrue(batchContact(listOf(0, 0, 0), previous = false))
    }
}
