// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.Gender
import com.heartline.shared.profile.UserProfile
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.quick.QuickMeasureViewModel
import com.heartline.wear.quick.QuickState
import com.heartline.wear.quick.WatchProfileStore
import com.heartline.wear.sensor.FakeHrSource
import com.heartline.wear.sensor.HrSource
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.sensor.FakeQuickSource
import com.heartline.wear.sensor.StressSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class QuickMeasureTest {
    private lateinit var db: WatchDatabase
    private lateinit var store: WatchRecordStore
    private lateinit var profiles: WatchProfileStore
    private var scheduled = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("profile", 0).edit().clear().commit()
        db = WatchDatabase.inMemory(context)
        store = WatchRecordStore(db.records(), File(context.cacheDir, "quick-test").apply { deleteRecursively() }, db.messages())
        profiles = WatchProfileStore(context)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun source(metric: Metric) = FakeQuickSource.all(tickMs = 1).first { it.metric == metric }

    @Test
    fun spo2IsMeasuredStoredAndSynced() = runBlocking {
        val vm = QuickMeasureViewModel(source(Metric.SPO2), profiles, store, { scheduled++ })
        vm.start()
        val done = withTimeout(10_000) { vm.state.first { it is QuickState.Done } } as QuickState.Done
        val spo2 = done.summary as RecordSummary.Spo2
        assertTrue(spo2.percent in 95..100)
        assertEquals(RecordKind.SPO2, store.pending().single().meta.kind)
        assertEquals(1, scheduled)
    }

    @Test
    fun bodyCompositionNeedsProfile() = runBlocking {
        val vm = QuickMeasureViewModel(source(Metric.BODY_COMPOSITION), profiles, store, { scheduled++ })
        vm.start()
        assertEquals(QuickState.NeedsProfile, vm.state.value)

        profiles.update(UserProfile("Sam", "Lee", birthDate = "1988-05-04", gender = Gender.MAN, heightCm = 180f, weightKg = 80f))
        vm.reset()
        vm.start()
        // No weight date yet: today's weight is asked first, then used for the measurement and kept.
        assertEquals(QuickState.ConfirmWeight(80f), vm.state.value)
        vm.confirmWeight(78.5f)
        val done = withTimeout(10_000) { vm.state.first { it is QuickState.Done } } as QuickState.Done
        val body = done.summary as RecordSummary.BodyComposition
        assertEquals(78.5f * 0.42f, body.skeletalMuscleKg!!, 0.01f)
        assertEquals(78.5f, body.weightKg!!, 1e-6f)
        assertEquals(78.5f, profiles.profile.value!!.weightKg, 1e-6f)

        // Weighed a moment ago: no question the second time.
        vm.cancel()
        vm.start()
        assertTrue(vm.state.value is QuickState.Measuring)
        assertTrue(withTimeout(10_000) { vm.state.first { it is QuickState.Done } } is QuickState.Done)
    }

    @Test
    fun stressFromOneMinuteOfHrv() = runBlocking {
        val stress = StressSource(FakeHrSource(bpm = 70.0, irregularity = 0.06, periodMs = 1), seconds = 60, tickMs = 20)
        val vm = QuickMeasureViewModel(stress, profiles, store, { scheduled++ })
        vm.start()
        val done = withTimeout(10_000) { vm.state.first { it is QuickState.Done } } as QuickState.Done
        val summary = done.summary as RecordSummary.Stress
        assertTrue("$summary", summary.score in 0..100 && summary.rmssdMs!! > 0)
    }

    @Test
    fun stressEndsWhenTheTrackerSendsNothing() = runBlocking {
        val silent = object : HrSource {
            override fun stream() = kotlinx.coroutines.flow.flow<com.heartline.shared.hr.HrSample> { kotlinx.coroutines.awaitCancellation() }
        }
        val vm = QuickMeasureViewModel(StressSource(silent, seconds = 60, tickMs = 5), profiles, store, { scheduled++ })
        vm.start()
        val failed = withTimeout(5_000) { vm.state.first { it is QuickState.Failed } } as QuickState.Failed
        assertEquals(QuickHint.LOW_SIGNAL, failed.hint)
    }

    @Test
    fun stressSurvivesATrackerThatPausesMidway() = runBlocking {
        // Samples stop after 20 s (another listener took the tracker); the minute still ends on time.
        val pausing = object : HrSource {
            override fun stream() = kotlinx.coroutines.flow.flow {
                com.heartline.shared.sample.SyntheticHr.samples(0, 20, 70.0, 0.06, 3).forEach { emit(it) }
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val vm = QuickMeasureViewModel(StressSource(pausing, seconds = 60, tickMs = 5), profiles, store, { scheduled++ })
        vm.start()
        val done = withTimeout(5_000) { vm.state.first { it is QuickState.Done } } as QuickState.Done
        assertTrue((done.summary as RecordSummary.Stress).rmssdMs!! > 0)
    }

    @Test
    fun skinReadingIsTakenDuringTheMinuteSoTheResultIsImmediate() = runBlocking {
        val hr = FakeHrSource(bpm = 70.0, irregularity = 0.06, periodMs = 1)
        // The skin reading takes as long as the last 12 "seconds" and is ready when the minute ends.
        val stress = StressSource(hr, skinConductance = { kotlinx.coroutines.delay(10 * 20L); 4.2f }, seconds = 60, tickMs = 20)
        val vm = QuickMeasureViewModel(stress, profiles, store, { scheduled++ })
        val started = System.currentTimeMillis()
        vm.start()
        val done = withTimeout(10_000) { vm.state.first { it is QuickState.Done } } as QuickState.Done
        assertEquals(4.2f, (done.summary as RecordSummary.Stress).skinConductanceMicroSiemens!!, 0f)
        // 60 ticks of 20 ms plus a little: no extra wait after the minute.
        assertTrue(System.currentTimeMillis() - started < 60 * 20 + 1_000)
    }

    @Test
    fun aSlowSkinReadingNeverHoldsTheResult() = runBlocking {
        val hr = FakeHrSource(bpm = 70.0, irregularity = 0.06, periodMs = 1)
        val stress = StressSource(hr, skinConductance = { kotlinx.coroutines.awaitCancellation() }, seconds = 60, tickMs = 10)
        val vm = QuickMeasureViewModel(stress, profiles, store, { scheduled++ })
        vm.start()
        val done = withTimeout(5_000) { vm.state.first { it is QuickState.Done } } as QuickState.Done
        assertEquals(null, (done.summary as RecordSummary.Stress).skinConductanceMicroSiemens)
    }
}
