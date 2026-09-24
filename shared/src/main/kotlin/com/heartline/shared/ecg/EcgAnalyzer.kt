package com.heartline.shared.ecg

import com.heartline.shared.model.EcgResult

data class EcgAnalysis(val peaks: IntArray, val averageBpm: Int?, val result: EcgResult, val evidence: RhythmClassifier.Evidence)

/** Turns a finished recording into a wellness result. */
object EcgAnalyzer {
    fun analyze(raw: FloatArray, fs: Int, leadOffRatio: Float): EcgAnalysis {
        val clean = EcgFilter.clean(raw, fs)
        val peaks = RPeakDetector.detect(clean, fs)
        val bpm = RPeakDetector.heartRateBpm(peaks, fs)
        val (result, evidence) = RhythmClassifier.classify(clean, peaks, fs, bpm, leadOffRatio)
        return EcgAnalysis(peaks, bpm, result, evidence)
    }
}
