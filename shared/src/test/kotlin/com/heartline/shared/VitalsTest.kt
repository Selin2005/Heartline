// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.hr.AlertSensitivity
import com.heartline.shared.hr.DayStats
import com.heartline.shared.hr.HeartHistory
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.hr.VitalAlert
import com.heartline.shared.sync.Protocol
import com.heartline.shared.vitals.Spo2Sample
import com.heartline.shared.vitals.TempSample
import com.heartline.shared.vitals.VitalsBaseline
import com.heartline.shared.vitals.VitalsHistory
import com.heartline.shared.vitals.VitalsMonitor
import com.heartline.shared.vitals.VitalsQuality
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalsTest {
    private val zone = ZoneOffset.UTC
    private var ids = 0
    private val monitor = VitalsMonitor(zone) { "v${ids++}" }
    private val on = MonitorSettings()
    private val day0 = 20_000L
    private fun at(day: Long, hour: Int, minute: Int = 0) = day * 86_400_000L + hour * 3_600_000L + minute * 60_000L

    @Test
    fun qualityRejectsMovementWrongPulseAndOffWristTemperature() {
        assertTrue(VitalsQuality.spo2(97, 62, 60, moved = false))
        assertFalse(VitalsQuality.spo2(97, 62, 60, moved = true))
        assertFalse(VitalsQuality.spo2(97, 90, 60, moved = false)) // its pulse doesn't match the wrist's
        assertFalse(VitalsQuality.spo2(65, null, null, moved = false))
        assertTrue(VitalsQuality.temperature(34.2f, 24f, 24.5f))
        // Real Galaxy Watch8 Classic readings: the case's "ambient" is warmer than skin when worn.
        assertTrue(VitalsQuality.temperature(34.725f, 35.99f, 35.96f))
        assertTrue(VitalsQuality.temperature(34.259f, 34.818f, null))
        assertFalse(VitalsQuality.temperature(26f, 25.5f, null)) // cooled to the room: on a table
        assertFalse(VitalsQuality.temperature(34f, 30f, 22f)) // ambient changed by 8 °C: a shower, outside
    }

    @Test
    fun oxygenLimitsAreClinicalPerSensitivity() {
        assertEquals(88, VitalsBaseline.spo2Low(AlertSensitivity.LOW))
        assertEquals(90, VitalsBaseline.spo2Low(AlertSensitivity.STANDARD)) // readings below it count
        assertEquals(92, VitalsBaseline.spo2Low(AlertSensitivity.HIGH))
    }

    @Test
    fun oneOrTwoLowOxygenReadingsNeverNotifyThreeInARowDo() {
        fun s(minute: Int, percent: Int, recheck: Boolean = true) = Spo2Sample(at(day0, 14, minute), percent, HrContext.REST, recheck)
        val low = s(0, 88, recheck = false)
        // Below the limit (hypoxaemia is SpO2 < 90 %): 90 itself is not low.
        assertTrue(monitor.needsRecheck(89, on))
        assertFalse(monitor.needsRecheck(90, on))
        // A re-check back to normal: a sensor glitch, no notice.
        assertNull(monitor.onSpo2(VitalsHistory(), low, listOf(s(2, 96)), on).second)
        // Two low in a row is still not enough (artefacts in a row happen).
        assertNull(monitor.onSpo2(VitalsHistory(), low, listOf(s(2, 87)), on).second)
        val (h, alert) = monitor.onSpo2(VitalsHistory(), low, listOf(s(2, 87), s(4, 89)), on)
        assertEquals(VitalAlert.SPO2_LOW, alert!!.vital)
        assertEquals(89, alert.bpm)
        // Low readings are not learnt into the normal.
        assertTrue(h.days.single().spo2Day.isEmpty())
        // At most one notice in 6 hours.
        assertNull(monitor.onSpo2(h, low.copy(tsMs = at(day0, 16)), listOf(s(122, 87), s(124, 86)), on).second)
        // The blood oxygen part switched off: readings still kept, no notice.
        assertNull(monitor.onSpo2(VitalsHistory(), low, listOf(s(2, 87), s(4, 89)), on.copy(spo2Monitoring = false)).second)
    }

    @Test
    fun oxygenNormalStartsFromThePopulationAndLearnsQuickly() {
        val start = VitalsBaseline.limits(VitalsHistory(), day0, AlertSensitivity.STANDARD, 40)
        assertEquals(96, start.spo2DayNormal)
        assertEquals(95, start.spo2NightNormal)
        var h = VitalsHistory()
        for (i in 0 until 20) {
            h =
                monitor.onSpo2(
                    h,
                    Spo2Sample(
                        at(day0, 0) + i * 3_600_000L,
                        98,
                        if (i <
                            7
                        ) {
                            HrContext.SLEEP
                        } else {
                            HrContext.REST
                        }
                    ),
                    emptyList(),
                    on
                ).first
        }
        val learnt = VitalsBaseline.limits(h, day0, AlertSensitivity.STANDARD, 40)
        assertTrue(learnt.spo2DayNormal in 97..98)
        // One night (7 readings) is already more than half the wearer's own.
        assertTrue(learnt.spo2NightNormal in 97..98)
    }

    private fun nightsOfOxygen(h0: VitalsHistory, from: Long, to: Long, percent: (Long) -> Int): VitalsHistory {
        var h = h0
        for (d in from..to) {
            for (hour in 0 until 7) {
                h =
                    monitor.onSpo2(h, Spo2Sample(at(d, hour), percent(d), HrContext.SLEEP), emptyList(), on).first
            }
        }
        return h
    }

    @Test
    fun lowerOxygenInSleepOverNightsGivesANotice() {
        var h = nightsOfOxygen(VitalsHistory(), day0 - 20, day0 - 3) { 96 }
        // Two of the last three nights 4 points lower (e.g. a chest infection, or high altitude).
        h = nightsOfOxygen(h, day0 - 2, day0) { d -> if (d == day0 - 1) 96 else 92 }
        val (after, alerts) = monitor.afterNight(h, null, on, at(day0, 10))
        assertEquals(listOf(VitalAlert.SPO2_NIGHTS), alerts.map { it.vital })
        // Checked once a day, after 10:00 only.
        assertTrue(monitor.afterNight(after, null, on, at(day0, 12)).second.isEmpty())
        assertTrue(monitor.afterNight(h, null, on, at(day0, 8)).second.isEmpty())
        // One lower night is not enough.
        val once = nightsOfOxygen(nightsOfOxygen(VitalsHistory(), day0 - 20, day0 - 1) { 96 }, day0, day0) { 92 }
        assertTrue(monitor.afterNight(once, null, on, at(day0, 10)).second.isEmpty())
    }

    private fun nightsOfTemp(h0: VitalsHistory, from: Long, to: Long, celsius: (Long) -> Float): VitalsHistory {
        var h = h0
        for (d in from..to) {
            for (k in 0 until 10) {
                // Night of day d: 23:00 the evening before to 06:00; the first hour is not counted.
                val ts = at(d - 1, 23) + k * 40 * 60_000L
                h = monitor.onTemp(h, TempSample(ts, celsius(d) + (k % 3 - 1) * 0.05f, 22f, HrContext.SLEEP, counted = k >= 2))
            }
        }
        return h
    }

    @Test
    fun nightTemperatureIsTheMedianAfterTheFirstHour() {
        val h = nightsOfTemp(VitalsHistory(), day0, day0) { 34.0f }
        assertEquals(8, h.days.single { it.day == day0 }.temps.size)
        assertEquals(34.0, VitalsBaseline.nightTemp(h, day0)!!, 0.06)
    }

    @Test
    fun aWarmerNightTwiceInARowNotifiesButTheCycleDoesNot() {
        // A month with a menstrual-cycle swing of +0.4 °C in the second half.
        var h = nightsOfTemp(VitalsHistory(), day0 - 28, day0 - 2) { d -> 34.0f + if ((d - day0 + 28) % 28 >= 14) 0.4f else 0f }
        h = nightsOfTemp(h, day0 - 1, day0) { 34.0f + 0.4f }
        assertTrue(monitor.afterNight(h, null, on, at(day0, 10)).second.isEmpty())
        // Two nights 1.3 °C warmer than usual.
        var warm = nightsOfTemp(VitalsHistory(), day0 - 28, day0 - 2) { 34.0f }
        warm = nightsOfTemp(warm, day0 - 1, day0) { 35.3f }
        val (after, alerts) = monitor.afterNight(warm, null, on, at(day0, 10))
        assertEquals(listOf(VitalAlert.TEMPERATURE), alerts.map { it.vital })
        assertEquals(1.3f, alerts.single().value!!, 0.1f)
        // Those nights are not learnt from.
        assertTrue(after.days.filter { it.day >= day0 - 1 }.all { it.unusual })
        // Only one warm night: no notice yet.
        val one = nightsOfTemp(nightsOfTemp(VitalsHistory(), day0 - 28, day0 - 1) { 34.0f }, day0, day0) { 35.3f }
        assertTrue(monitor.afterNight(one, null, on, at(day0, 10)).second.isEmpty())
        // The temperature part off: no notice.
        assertTrue(monitor.afterNight(warm, null, on.copy(skinTempMonitoring = false), at(day0, 10)).second.isEmpty())
    }

    @Test
    fun aWarmerNightWithARaisedHeartRateGivesOneCombinedNoticeAtOnce() {
        val temps = nightsOfTemp(nightsOfTemp(VitalsHistory(), day0 - 28, day0 - 1) { 34.0f }, day0, day0) { 34.7f }
        fun night(bpm: Int) = (0 until 40).associate { bpm + it % 3 - 1 to 1 }.let { m -> (bpm - 1..bpm + 1).associateWith { 13 } }
        val heart = HeartHistory((1..20).map { DayStats(day0 - it, sleep = night(55)) } + DayStats(day0, sleep = night(66)))
        val alerts = monitor.afterNight(temps, heart, on, at(day0, 10)).second
        assertEquals(listOf(VitalAlert.COMBINED), alerts.map { it.vital })
        // Without the heart part, +0.7 alone is not enough.
        assertTrue(monitor.afterNight(temps, heart, on.withHeartAlerts(false), at(day0, 10)).second.isEmpty())
    }

    @Test
    fun temperatureNoticesNeedFiveNights() {
        val few = nightsOfTemp(nightsOfTemp(VitalsHistory(), day0 - 3, day0 - 2) { 34f }, day0 - 1, day0) { 36f }
        assertTrue(monitor.afterNight(few, null, on, at(day0, 10)).second.isEmpty())
        val limits = VitalsBaseline.limits(few, day0, AlertSensitivity.STANDARD, 40)
        assertNotNull(limits.lastNightDeviation)
    }

    @Test
    fun settingsPartsAndOldJson() {
        val old = Protocol.json.decodeFromString(MonitorSettings.serializer(), """{"heartRateAlertsEnabled":true}""")
        assertTrue(old.spo2Active && old.skinTempActive && old.heartActive)
        val onlyHeart = MonitorSettings().copy(spo2Monitoring = false, skinTempMonitoring = false)
        assertTrue(onlyHeart.heartActive)
        assertFalse(onlyHeart.spo2Active || onlyHeart.skinTempActive)
        // The master off stops every part, which keep their own state for when it comes back.
        val off = onlyHeart.withMonitoring(false)
        assertFalse(off.heartActive || off.spo2Active || off.skinTempActive)
        assertTrue(off.withMonitoring(true).heartActive && !off.withMonitoring(true).spo2Active)
        // The heart part off: the old switches older watches read follow it.
        val noHeart = MonitorSettings().withHeartAlerts(false)
        assertFalse(noHeart.heartRateAlertsEnabled || noHeart.irregularRhythmEnabled)
        assertTrue(noHeart.spo2Active && noHeart.passiveHeartRate)
    }
}
