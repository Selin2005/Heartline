// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import com.heartline.wear.diag.SdkKeys
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.Value
import com.samsung.android.service.health.tracking.data.ValueKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The raw recorder finds every value key of each tracker in the Samsung SDK itself. */
class RawCaptureKeysTest {
    @Test
    fun everyTrackerHasItsKeys() {
        val ecg = SdkKeys.forTracker("ECG_ON_DEMAND")
        assertTrue(ecg.scalarNames.containsAll(listOf("ECG_MV", "LEAD_OFF", "SEQUENCE", "PPG_GREEN", "MAX_THRESHOLD_MV", "MIN_THRESHOLD_MV")))
        assertTrue(SdkKeys.forTracker("PPG_ON_DEMAND").scalarNames.containsAll(listOf("PPG_GREEN", "PPG_IR", "PPG_RED", "GREEN_STATUS", "IR_STATUS", "RED_STATUS")))
        assertTrue(SdkKeys.forTracker("SPO2_ON_DEMAND").scalarNames.containsAll(listOf("SPO2", "STATUS", "HEART_RATE")))
        assertTrue(SdkKeys.forTracker("BIA_ON_DEMAND").scalarNames.containsAll(listOf("BODY_FAT_RATIO", "BODY_IMPEDANCE_MAGNITUDE", "STATUS", "PROGRESS")))
        assertTrue(SdkKeys.forTracker("SKIN_TEMPERATURE_ON_DEMAND").scalarNames.containsAll(listOf("OBJECT_TEMPERATURE", "AMBIENT_TEMPERATURE")))
        assertTrue(SdkKeys.forTracker("EDA_CONTINUOUS").scalarNames.containsAll(listOf("SKIN_CONDUCTANCE", "STATUS")))
        assertTrue(SdkKeys.forTracker("ACCELEROMETER_CONTINUOUS").scalarNames.containsAll(listOf("ACCELEROMETER_X", "ACCELEROMETER_Y", "ACCELEROMETER_Z")))
        // The continuous PPG spans several sets; names say which.
        assertTrue(SdkKeys.forTracker("PPG_CONTINUOUS").scalarNames.any { it.startsWith("PpgGreenSet.") })
        // Heart rate: IBIs and their statuses are lists, recorded as their own streams.
        val hr = SdkKeys.forTracker("HEART_RATE_CONTINUOUS")
        assertTrue(hr.scalarNames.containsAll(listOf("HEART_RATE", "HEART_RATE_STATUS")))
        assertEquals(setOf("IBI_LIST", "IBI_STATUS_LIST"), hr.listNames.toSet())
        // An unknown tracker gets every key the SDK has.
        assertTrue(SdkKeys.forTracker("SOMETHING_NEW").scalars.size > 40)
    }

    @Test
    fun keysReadTheValuesOfADataPoint() {
        val point = DataPoint(mapOf(ValueKey.EcgSet.ECG_MV to Value(0.42f), ValueKey.EcgSet.LEAD_OFF to Value(0)))
        val keys = SdkKeys.forTracker("ECG_ON_DEMAND")
        val mv = keys.scalars[keys.scalarNames.indexOf("ECG_MV")]
        @Suppress("UNCHECKED_CAST")
        assertEquals(0.42f, point.getValue(mv as ValueKey<Any?>))
    }
}
