package com.heartline.phone.ecg

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.heartline.phone.data.RecordRepository
import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.ecg.EcgFounderInput
import com.heartline.shared.ecg.EcgSession
import com.heartline.shared.ecg.RhythmModel
import com.heartline.shared.model.EcgPoorReason
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ECGFounder's 150 label probabilities for a recording (mean over its windows), or null. */
fun interface EcgFounder {
    fun probabilities(windows: List<FloatArray>): DoubleArray?
}

/**
 * ECGFounder single-lead (Li et al., NEJM AI 2025; MIT licence) with ONNX Runtime, bundled as fp16
 * (62 MB): 8-bit quantization distorted its outputs (tools/ecg-ml/README.md). The asset is copied
 * once to app storage so the session maps the file instead of holding a 62 MB array on the heap.
 */
class OnnxEcgFounder private constructor(private val session: OrtSession) : EcgFounder {
    private val env = OrtEnvironment.getEnvironment()

    override fun probabilities(windows: List<FloatArray>): DoubleArray? {
        if (windows.isEmpty()) return null
        val input = FloatBuffer.allocate(windows.size * EcgFounderInput.WINDOW)
        windows.forEach { input.put(it) }
        input.rewind()
        return runCatching {
            OnnxTensor.createTensor(env, input, longArrayOf(windows.size.toLong(), 1, EcgFounderInput.WINDOW.toLong())).use { tensor ->
                session.run(mapOf("ecg" to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = result[0].value as Array<FloatArray>
                    DoubleArray(out[0].size) { j -> out.sumOf { it[j].toDouble() } / out.size }
                }
            }
        }.onFailure { Log.w(TAG, "ECGFounder failed", it) }.getOrNull()
    }

    companion object {
        const val ASSET = "ecg/ecgfounder_1lead_fp16.onnx"
        private const val TAG = "Heartline/ECG"

        fun fromAssets(context: Context): OnnxEcgFounder? = runCatching {
            val file = File(context.noBackupFilesDir, ASSET.substringAfterLast('/'))
            val size = context.assets.openFd(ASSET).use { it.length }
            if (!file.exists() || file.length() != size) {
                val tmp = File(file.path + ".tmp")
                context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                check(tmp.renameTo(file)) { "can't install $file" }
            }
            OnnxEcgFounder(OrtEnvironment.getEnvironment().createSession(file.path, OrtSession.SessionOptions()))
        }.onFailure { Log.w(TAG, "ECGFounder unavailable: ${it.message}") }.getOrNull()
    }
}

/**
 * The phone's second opinion on a watch ECG: the app's own analysis of the stored recording plus
 * ECGFounder's label probabilities, through a model trained on both (tools/ecg-ml,
 * train_second_opinion.py; cross-validated on CinC 2017, MIT-BIH, AFDB and CPSC 2021 it finds
 * more AFib with fewer false alarms than the watch model on each of them, docs/ECG_ALGORITHM.md).
 * Shown next to the watch's result, never instead.
 */
class EcgSecondOpinion(
    private val founder: () -> EcgFounder?,
    private val model: () -> RhythmModel?,
) {
    /** @return (result, AFib probability), or null when the model isn't available or the recording can't be read. */
    fun assess(wave: FloatArray, fs: Int): Pair<EcgResult, Double>? {
        val f = founder() ?: return null
        val m = model() ?: return null
        val analysis = EcgAnalyzer.analyze(wave, fs, EcgSession())
        if (analysis.signalReason != EcgPoorReason.NONE || analysis.averageBpm == null) return EcgResult.POOR_RECORDING to 0.0
        val probs = f.probabilities(EcgFounderInput.windows(wave, fs)) ?: return null
        val features = analysis.features + probs
        if (features.size != m.features.size) return null
        val p = m.predict(features)
        val rules = com.heartline.shared.ecg.RhythmClassifier.Decision(analysis.result, analysis.metrics.note)
        val decision = EcgAnalyzer.decide(m, p, analysis.averageBpm!!, rules)
        return decision.result to p[m.classes.indexOf("af")]
    }

    /** Adds the second opinion to a newly synced ECG record. */
    suspend fun onRecordSaved(records: RecordRepository, meta: RecordMeta, wave: FloatArray?) {
        if (meta.kind != RecordKind.ECG || wave == null) return
        val summary = meta.summary as? RecordSummary.Ecg ?: return
        val metrics = summary.metrics ?: return
        val (result, af) = withContext(Dispatchers.Default) { assess(wave, meta.sampleRateHz) } ?: return
        records.updateSummary(meta.id, summary.copy(metrics = metrics.copy(secondOpinion = result, secondOpinionAfProbability = af)))
    }

    companion object {
        const val MODEL_ASSET = "ecg/second_opinion_model.json"

        fun fromAssets(context: Context): EcgSecondOpinion {
            val founder by lazy { OnnxEcgFounder.fromAssets(context) }
            val model by lazy { runCatching { RhythmModel.parse(context.assets.open(MODEL_ASSET).use { it.readBytes().decodeToString() }) }.getOrNull() }
            return EcgSecondOpinion({ founder }, { model })
        }
    }
}
