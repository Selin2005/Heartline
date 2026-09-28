// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.update.UpdateRepository
import com.heartline.shared.update.AppVersion.Channel.BETA
import com.heartline.shared.update.AppVersion.Channel.DEV
import com.heartline.shared.update.AppVersion.Channel.STABLE
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateTrackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun installed(version: String) = UpdateRepository(context, version).also { it.recordInstalled() }

    @Test
    fun trackFollowsTheInstalledChannelAndIsSticky() = runTest {
        assertEquals(STABLE, installed("1.0.0").current().track)
        // A dev build installed by hand puts the phone on the dev track…
        assertEquals(DEV, installed("1.1.0-dev.5").current().track)
        // …and updating to a beta or a main release keeps it there.
        assertEquals(DEV, installed("1.1.0-beta.1").current().track)
        installed("1.1.0").current().let {
            assertEquals(DEV, it.track)
            assertTrue(it.receivesBetas)
        }
        // An explicit choice wins and survives later updates.
        val repo = installed("1.1.0")
        repo.setTrack(BETA)
        assertEquals(BETA, installed("1.2.0").current().track)
        repo.setTrack(STABLE)
        installed("1.2.1").current().let {
            assertEquals(STABLE, it.track)
            assertFalse(it.receivesBetas)
        }
    }
}
