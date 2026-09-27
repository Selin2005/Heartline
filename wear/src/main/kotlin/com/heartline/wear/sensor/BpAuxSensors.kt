// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor

import com.heartline.shared.model.RecordSummary
import com.heartline.wear.sensor.sdk.SdkSensorGateway
import com.heartline.wear.sensor.sdk.SdkSkinTempSource
import com.heartline.wear.sensor.sdk.readSkinConductance
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/** Skin temperature from SKIN_TEMPERATURE_ON_DEMAND, °C. */
data class SkinTempReading(val objectC: Double, val ambientC: Double?)

/**
 * The sensors a blood-pressure session reads before its main recording (algorithm 6): skin
 * temperature (vasoconstriction) and skin conductance (sympathetic arousal, Watch8+). Only one
 * on-demand tracker can run at a time, so they are read one after the other. Each returns null
 * when the watch doesn't have it.
 */
interface BpAuxSensors {
    suspend fun skinTemp(): SkinTempReading?

    suspend fun skinConductance(seconds: Int): Double?

    companion object {
        val NONE = object : BpAuxSensors {
            override suspend fun skinTemp(): SkinTempReading? = null

            override suspend fun skinConductance(seconds: Int): Double? = null
        }
    }
}

class SdkBpAuxSensors(private val gateway: SdkSensorGateway) : BpAuxSensors {
    override suspend fun skinTemp(): SkinTempReading? = runCatching {
        withTimeoutOrNull(12_000) {
            SdkSkinTempSource(gateway).measure(null).filterIsInstance<QuickEvent.Result>().firstOrNull()
        }?.summary?.let { it as? RecordSummary.SkinTemperature }?.let { SkinTempReading(it.skinCelsius.toDouble(), it.ambientCelsius?.toDouble()) }
    }.getOrNull()

    override suspend fun skinConductance(seconds: Int): Double? = gateway.readSkinConductance(seconds)?.toDouble()
}
