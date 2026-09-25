package com.heartline.shared

import com.heartline.shared.nav.EntryLink
import com.heartline.shared.nav.EntryLinks
import com.heartline.shared.nav.EntrySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryLinksTest {
    @Test
    fun taggedRoutesRoundTrip() {
        val link = EntryLinks.tag("blood_pressure", EntrySource.WIDGET)
        assertEquals("blood_pressure?src=widget", link)
        assertEquals(EntryLink("blood_pressure", EntrySource.WIDGET), EntryLinks.parse(link))
        assertTrue(EntryLinks.parse(link).external)
    }

    @Test
    fun otherQueryParametersAreKept() {
        val link = EntryLinks.tag("quick/SPO2?start=1", EntrySource.TILE)
        assertEquals(EntryLink("quick/SPO2?start=1", EntrySource.TILE), EntryLinks.parse(link))
    }

    @Test
    fun plainRoutesAreInternal() {
        assertFalse(EntryLinks.parse("ecg").external)
        assertEquals(EntryLink("metric/SPO2", null), EntryLinks.parse("metric/SPO2?src=unknown"))
    }
}
