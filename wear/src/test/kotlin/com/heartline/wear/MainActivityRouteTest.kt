// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.heartline.datalayer.DeepLinks
import com.heartline.wear.link.WatchCommandBus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The phone's "start a measurement" link reaching an activity the system had stopped and now
 * recreates: the route must reach the app, and the old launch intent must not open again.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityRouteTest {
    private val bus = WatchCommandBus()
    private val route = "remote/HEART_RATE?session=s1"

    @Before
    fun setUp() {
        startKoin { modules(module { single { bus } }) }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun link(route: String) = Intent(Intent.ACTION_VIEW, Uri.parse(DeepLinks.watch(route)))

    @Test fun aRecreatedActivityStillOpensThePhonesRequest() {
        Robolectric.buildActivity(MainActivity::class.java, link(route)).create(Bundle())
        assertEquals(route, bus.pending.value)
    }

    @Test fun theRequestThatLaunchedItIsNotOpenedAgainOnRecreation() {
        val saved = Bundle().apply { putString("handled_route", route) }
        Robolectric.buildActivity(MainActivity::class.java, link(route)).create(saved)
        assertNull(bus.pending.value)
    }

    @Test fun aNewRequestReachesARunningActivity() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
        assertNull(bus.pending.value)
        controller.newIntent(link(route))
        assertEquals(route, bus.pending.value)
    }
}
