// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.model.RecordMeta
import com.heartline.shared.sync.CalibrationStatus
import com.heartline.shared.sync.Hello
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.LinkStage
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PeerDirectory
import com.heartline.shared.sync.PeerProbe
import com.heartline.shared.sync.PhoneStatus
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.SetupRequest
import com.heartline.shared.sync.SetupTarget
import com.heartline.shared.sync.StampedStatus
import com.heartline.shared.sync.WatchLinkChecker
import com.heartline.shared.sync.WatchSyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTest {
    private object NoOutbox : Outbox {
        override suspend fun pending() = emptyList<OutboxItem>()

        override suspend fun markDelivered(id: String) = Unit
    }

    private object NoSink : RecordSink {
        override suspend fun contains(id: String) = false

        override suspend fun save(meta: RecordMeta, wave: FloatArray?) = Unit

        override suspend fun delete(id: String) = Unit
    }

    private class Directory(var probe: PeerProbe) : PeerDirectory {
        override suspend fun probe() = probe
    }

    private class Rig(scope: TestScope, var phoneStatus: PhoneStatus?, probe: PeerProbe = PeerProbe.REACHABLE) {
        val pair = InMemoryTransport.pair()
        val watchT = pair.first
        val phoneT = pair.second
        val latest = MutableStateFlow<StampedStatus?>(null)
        val directory = Directory(probe)
        val setupRequests = mutableListOf<SetupRequest>()
        val hellos = mutableListOf<Hello>()
        lateinit var phone: PhoneSyncEngine
        val watch = WatchSyncEngine(watchT, NoOutbox, onStatus = { latest.value = StampedStatus(it, scope.testScheduler.currentTime) })
        val checker =
            WatchLinkChecker(directory, watchT, latest, { Hello(appVersion = "1.0") }, clock = { scope.testScheduler.currentTime })

        init {
            phone = PhoneSyncEngine(
                phoneT,
                NoSink,
                onHello = { hello ->
                    hellos += hello
                    phoneStatus?.let { phone.sendStatus(it) }
                },
                onSetupRequest = { setupRequests += it }
            )
            watchT.incoming.onEach { watch.handle(it) }.launchIn(scope.backgroundScope)
            phoneT.incoming.onEach { phone.handle(it) }.launchIn(scope.backgroundScope)
            // Let both collectors subscribe before anything is sent (the inboxes don't replay).
            scope.testScheduler.runCurrent()
        }
    }

    private val complete =
        PhoneStatus(appVersion = "1.0", onboarded = true, termsAccepted = true, profileComplete = true, displayName = "Sam")

    @Test
    fun connectsAndCarriesSetupState() = runTest {
        val rig = Rig(this, complete)
        val state = rig.checker.check()
        assertEquals(LinkStage.CONNECTED, state.stage)
        assertTrue(state.status!!.setupComplete)
        assertEquals("Sam", state.status!!.displayName)
        assertEquals(1, rig.hellos.size)
    }

    @Test
    fun incompleteProfileIsReported() = runTest {
        val rig = Rig(this, complete.copy(profileComplete = false))
        val state = rig.checker.check()
        assertEquals(LinkStage.CONNECTED, state.stage)
        assertFalse(state.status!!.setupComplete)
    }

    @Test
    fun termsNotAcceptedIsIncomplete() = runTest {
        val status = Rig(this, complete.copy(termsAccepted = false)).checker.check().status!!
        assertFalse(status.setupComplete)
    }

    @Test
    fun olderPhoneWithoutTermsFieldCountsAsNotAccepted() {
        val json = """{"protocol":1,"appVersion":"0.9","onboarded":true,"profileComplete":true}"""
        assertFalse(Protocol.json.decodeFromString<PhoneStatus>(json).setupComplete)
    }

    @Test
    fun noPhoneAndMissingAppAreDistinct() = runTest {
        assertEquals(LinkStage.NO_DEVICE, Rig(this, complete, PeerProbe.NO_DEVICE).checker.check().stage)
        assertEquals(LinkStage.APP_MISSING, Rig(this, complete, PeerProbe.APP_MISSING).checker.check().stage)
    }

    @Test
    fun silentPhoneTimesOut() = runTest {
        val rig = Rig(this, phoneStatus = null)
        assertEquals(LinkStage.NO_RESPONSE, rig.checker.check().stage)
    }

    @Test
    fun staleStatusDoesNotCount() = runTest {
        val rig = Rig(this, phoneStatus = null)
        rig.latest.value = StampedStatus(complete, -1)
        assertEquals(LinkStage.NO_RESPONSE, rig.checker.check().stage)
    }

    @Test
    fun unreachableTransportIsNoResponse() = runTest {
        val rig = Rig(this, complete)
        rig.watchT.connected = false
        assertEquals(LinkStage.NO_RESPONSE, rig.checker.check().stage)
    }

    @Test
    fun protocolMismatchIsIncompatible() = runTest {
        val rig = Rig(this, complete.copy(protocol = Protocol.VERSION + 1))
        assertEquals(LinkStage.INCOMPATIBLE, rig.checker.check().stage)
    }

    @Test
    fun setupRequestReachesPhone() = runTest {
        val rig = Rig(this, complete)
        rig.watchT.send(Protocol.SETUP_REQUEST, Protocol.json.encodeToString(SetupRequest(SetupTarget.BP_CALIBRATION)).encodeToByteArray())
        // advanceUntilIdle ignores backgroundScope work; runCurrent lets the phone collector run.
        testScheduler.runCurrent()
        assertEquals(listOf(SetupRequest(SetupTarget.BP_CALIBRATION)), rig.setupRequests)
    }

    @Test
    fun statusRoundTripsCalibration() {
        val s = complete.copy(calibration = CalibrationStatus.EXPIRED, calibrationDaysLeft = 0)
        assertEquals(s, Protocol.json.decodeFromString<PhoneStatus>(Protocol.json.encodeToString(s)))
    }
}
