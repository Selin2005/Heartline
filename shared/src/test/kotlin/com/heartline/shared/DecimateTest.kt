package com.heartline.shared

import com.heartline.shared.dsp.decimate
import org.junit.Assert.assertEquals
import org.junit.Test

class DecimateTest {
    @Test
    fun keepsPeaks() {
        val signal = FloatArray(10_000) { if (it == 4321) 5f else 0f }
        val out = decimate(signal, 200)
        assertEquals(200, out.size)
        assertEquals(5f, out.max())
    }

    @Test
    fun shortInputIsUnchanged() {
        val signal = floatArrayOf(1f, 2f, 3f)
        assertEquals(signal.toList(), decimate(signal, 200).toList())
    }
}
