// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.design.Palette
import com.heartline.shared.model.Metric
import com.heartline.wear.tile.CardKind
import com.heartline.wear.tile.CardModels
import com.heartline.wear.tile.TileData
import com.heartline.wear.tile.TileRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class CardModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val full = CardSamples.full
    private val empty = TileData(null, null, null, null)

    @Test
    fun cardsShowTheirReadingAndOpenTheirScreen() {
        val heart = CardModels.of(context, CardKind.HEART, full)
        assertEquals("72", heart.value)
        assertEquals("Today 52–118", heart.status)
        assertEquals(TileRoutes.HEART_RATE, heart.route)
        assertEquals(12, heart.bars.size)

        val bp = CardModels.of(context, CardKind.BLOOD_PRESSURE, full)
        assertEquals("118/76", bp.value)
        assertEquals(Palette.Dark.STATUS_NORMAL, bp.statusColor)
        assertEquals(TileRoutes.BLOOD_PRESSURE, bp.route)

        assertEquals("21.4", CardModels.of(context, CardKind.BODY, full).value)
        assertEquals(TileRoutes.measure(Metric.STRESS), CardModels.of(context, CardKind.STRESS, full).route)
    }

    @Test
    fun todayOpensTheNextCheckIn() {
        val today = CardModels.of(context, CardKind.TODAY, full)
        assertEquals("Good morning, Sara".substringAfter(", "), today.title.substringAfter(", "))
        assertEquals("1/3", today.value)
        assertEquals(TileRoutes.measure(Metric.BLOOD_PRESSURE), today.route)
        assertEquals(listOf(true, false, false), today.items.map { it.done })
        assertEquals("6-day streak", today.detail)
    }

    @Test
    fun emptyCardsSayNothingIsMeasured() {
        val heart = CardModels.of(context, CardKind.HEART, empty)
        assertNull(heart.value)
        assertEquals("Not measured yet", heart.detail)
        // Shortcut cards always have their icons.
        assertEquals(6, CardModels.of(context, CardKind.MEASURE, empty).items.size)
    }

    /** Every card service is bound as a widget (the tile stack) and as a tile (older watches), with its card info. */
    @Test
    fun manifestDeclaresEveryCardForBothSurfaces() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(root, "wear/src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val services = doc.getElementsByTagName("service")
        val cards = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }.filter { it.getAttributeNS(android, "name").endsWith("TileService") }
        assertEquals(9, cards.size)
        cards.forEach { s ->
            val name = s.getAttributeNS(android, "name")
            val actions = s.getElementsByTagName("action").let { a -> (0 until a.length).map { (a.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") } }
            assertTrue(name, "androidx.glance.wear.action.BIND_WIDGET_PROVIDER" in actions)
            assertTrue(name, "androidx.wear.tiles.action.BIND_TILE_PROVIDER" in actions)
            val meta = s.getElementsByTagName("meta-data").let { m -> (0 until m.length).associate { (m.item(it) as org.w3c.dom.Element).let { e -> e.getAttributeNS(android, "name") to e.getAttributeNS(android, "resource") } } }
            val info = meta.getValue("androidx.glance.wear.widget.provider").removePrefix("@xml/")
            val xml = File(root, "wear/src/main/res/xml/$info.xml").readText()
            assertTrue(info, "type=\"SMALL\"" in xml && "type=\"LARGE\"" in xml && "group=" in xml)
        }
    }
}
