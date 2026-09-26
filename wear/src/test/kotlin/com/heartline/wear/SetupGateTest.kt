// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import androidx.test.core.app.ApplicationProvider
import com.heartline.datalayer.RemoteOpener
import com.heartline.shared.sync.Hello
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.LinkStage
import com.heartline.shared.sync.PeerDirectory
import com.heartline.shared.sync.PeerProbe
import com.heartline.shared.sync.PhoneStatus
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.WatchLinkChecker
import com.heartline.wear.link.WatchLinkStore
import com.heartline.wear.sensor.FakeSensorGateway
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.setup.GateState
import com.heartline.wear.ui.setup.SetupGateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SetupGateTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun tearDown() = Dispatchers.resetMain()

    /** Clears the cached phone status before the store reads it. */
    private fun freshStore(): WatchLinkStore {
        context.getSharedPreferences("link", 0).edit().clear().commit()
        return WatchLinkStore(context)
    }

    private class Directory(val probe: PeerProbe) : PeerDirectory {
        override suspend fun probe() = probe
    }

    /** A phone that answers every hello with [status] (null: never answers). */
    private fun gate(
        probe: PeerProbe,
        status: PhoneStatus?,
        sensors: SensorProblem? = null,
        permissions: Boolean = true,
        store: WatchLinkStore = freshStore(),
    ): SetupGateViewModel {
        val (watchT, phoneT) = InMemoryTransport.pair()
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        phoneT.incoming.onEach { if (it.path == Protocol.HELLO && status != null) phoneT.send(Protocol.STATUS, Protocol.json.encodeToString(status).encodeToByteArray()) }.launchIn(scope)
        watchT.incoming.onEach { if (it.path == Protocol.STATUS) store.update(Protocol.json.decodeFromString(it.data.decodeToString())) }.launchIn(scope)
        val checker = WatchLinkChecker(Directory(probe), watchT, store.latest, { Hello(appVersion = "t") }, timeoutMs = 300)
        val opener = com.heartline.wear.link.PhoneOpener(RemoteOpener(context, com.heartline.datalayer.DataLayerTransport(context, Protocol.CAPABILITY_PHONE)), watchT)
        return SetupGateViewModel(checker, store, FakeSensorGateway(failure = sensors), opener) { permissions }
    }

    private fun SetupGateViewModel.settle(): GateState = runBlocking {
        check()
        withTimeout(5_000) { state.first { it !is GateState.CheckingPhone && it !is GateState.CheckingSensors } }
    }

    private val complete = PhoneStatus(onboarded = true, termsAccepted = true, profileComplete = true)

    @Test fun readyWhenPhoneSetUpAndSensorsOk() = assertEquals(GateState.Ready(offline = false), gate(PeerProbe.REACHABLE, complete).settle())

    @Test fun incompleteProfileBlocks() =
        assertEquals(GateState.SetupIncomplete(complete.copy(profileComplete = false)), gate(PeerProbe.REACHABLE, complete.copy(profileComplete = false)).settle())

    @Test fun termsNotAcceptedBlocks() =
        assertEquals(GateState.SetupIncomplete(complete.copy(termsAccepted = false)), gate(PeerProbe.REACHABLE, complete.copy(termsAccepted = false)).settle())

    @Test fun devModeOffShowsGuide() = assertEquals(GateState.SensorIssue(SensorProblem.SDK_POLICY), gate(PeerProbe.REACHABLE, complete, SensorProblem.SDK_POLICY).settle())

    @Test fun missingPermissionsAsked() = assertEquals(GateState.NeedsPermissions, gate(PeerProbe.REACHABLE, complete, permissions = false).settle())

    @Test fun noPhoneOnFirstRunBlocks() = assertEquals(GateState.PhoneProblem(LinkStage.NO_DEVICE), gate(PeerProbe.NO_DEVICE, complete).settle())

    @Test fun silentPhoneAppBlocks() = assertEquals(GateState.PhoneProblem(LinkStage.NO_RESPONSE), gate(PeerProbe.REACHABLE, null).settle())

    @Test fun phoneAwayAfterSetupRunsOffline() {
        val store = freshStore()
        store.update(complete)
        assertEquals(GateState.Ready(offline = true), gate(PeerProbe.NO_DEVICE, complete, store = store).settle())
    }
}
