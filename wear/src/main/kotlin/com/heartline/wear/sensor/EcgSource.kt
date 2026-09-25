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

/**
 * Electrode contact for one SDK batch from its LEAD_OFF values. The SDK sets LEAD_OFF on the
 * first point of a batch only (the others read null; Samsung's ECG sample reads `list[0]`).
 * Contact only when every value present is 0 (documented: 0 = contact, any other = none);
 * a batch without any value keeps the previous state, and none before the first value is no contact.
 */
fun batchContact(flags: List<Int?>, previous: Boolean): Boolean {
    val present = flags.filterNotNull()
    if (present.isEmpty()) return previous
    return present.all { it == LEAD_OFF_CONTACT }
}

/** LEAD_OFF value when the finger is on the electrode key. */
const val LEAD_OFF_CONTACT = 0

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
