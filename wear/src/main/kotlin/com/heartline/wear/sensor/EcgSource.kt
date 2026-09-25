package com.heartline.wear.sensor

import kotlinx.coroutines.flow.Flow

/**
 * A batch of ECG samples (mV at 500 Hz) and whether the electrodes lost contact.
 * [timestampMs]: sensor time of the last sample, used to measure the real sample rate.
 * [ppg]: the green PPG value the SDK reports with each ECG sample (same length), if present.
 * [saturated]: a sample hit the sensor's fixed limits (SDK MIN/MAX_THRESHOLD_MV): not usable.
 */
class EcgChunk(
    val samples: FloatArray,
    val leadOff: Boolean,
    val timestampMs: Long? = null,
    val ppg: FloatArray? = null,
    val saturated: Boolean = false,
)

class SensorException(val problem: SensorProblem) : Exception(problem.name)

/** On-demand ECG (ECG_ON_DEMAND). Collecting starts the sensor; cancelling stops it. */
interface EcgSource {
    val sampleRateHz: Int get() = 500

    /** @throws SensorException through the flow on SDK errors. */
    fun stream(): Flow<EcgChunk>
}

/** Schedules delivery of new records to the phone. */
fun interface SyncScheduler {
    fun schedule()
}
