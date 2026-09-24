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
        val done = withTimeout(10_000) { vm.state.first { it is QuickState.Done } } as QuickState.Done
        assertEquals(80f * 0.42f, (done.summary as RecordSummary.BodyComposition).skeletalMuscleKg!!, 0.01f)
    }

    @Test
    fun stressFromOneMinuteOfHrv() = runBlocking {
        val stress = StressSource(FakeHrSource(bpm = 70.0, irregularity = 0.06, periodMs = 0), seconds = 60)
        val vm = QuickMeasureViewModel(stress, profiles, store, { scheduled++ })
        vm.start()
        val done = withTimeout(10_000) { vm.state.first { it is QuickState.Done } } as QuickState.Done
        val summary = done.summary as RecordSummary.Stress
        assertTrue("$summary", summary.score in 0..100 && summary.rmssdMs!! > 0)
    }
}
