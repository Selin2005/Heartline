// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.datalayer.DeepLinks
import com.heartline.phone.widget.appIntent
import com.heartline.shared.nav.EntryLink
import com.heartline.shared.nav.EntryLinks
import com.heartline.shared.nav.EntrySource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntryLinkTest {
    @Test
    fun widgetTapsOpenAStandaloneScreen() {
        val intent = appIntent(ApplicationProvider.getApplicationContext(), "blood_pressure")
        val route = DeepLinks.route(intent.data, DeepLinks.PHONE_HOST)!!
        // MainActivity hands this to HeartlineApp, which opens it as the only screen: Back leaves the app.
        assertEquals(EntryLink("blood_pressure", EntrySource.WIDGET), EntryLinks.parse(route))
    }
}
