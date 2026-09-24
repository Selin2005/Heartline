package com.heartline.wear.sensor

import kotlinx.coroutines.flow.Flow

/** A batch of ECG samples (mV at 500 Hz) and whether the electrodes lost contact. */
class EcgChunk(val samples: FloatArray, val leadOff: Boolean)

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
