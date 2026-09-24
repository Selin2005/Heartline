package com.heartline.wear.sensor

import com.heartline.shared.sample.SyntheticPpg
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Green PPG samples at 100 Hz; [contact] is false while the sensor reports poor skin contact. */
class PpgChunk(val samples: FloatArray, val contact: Boolean)

/** PPG_ON_DEMAND (green), used for blood pressure. */
interface PpgSource {
    val sampleRateHz: Int get() = 100

    fun stream(): Flow<PpgChunk>
}

class FakePpgSource(private val heartRateBpm: Double = 68.0, private val stiffness: Double = 0.5, private val chunkDelayMs: Long = 50) : PpgSource {
    override fun stream(): Flow<PpgChunk> = flow {
        val signal = SyntheticPpg.generate(40.0, heartRateBpm, stiffness, seed = System.nanoTime().toInt())
        var offset = 0
        while (offset + CHUNK <= signal.size) {
            emit(PpgChunk(signal.copyOfRange(offset, offset + CHUNK), contact = true))
            offset += CHUNK
            delay(chunkDelayMs)
        }
    }

    private companion object {
        const val CHUNK = 5
    }
}
