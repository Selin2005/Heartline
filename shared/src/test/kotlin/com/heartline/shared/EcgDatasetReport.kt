package com.heartline.shared

import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.ecg.EcgSession
import com.heartline.shared.model.EcgResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs [EcgAnalyzer] over public databases converted by tools/ecg-eval/convert.py and prints what
 * the plan (docs/ECG_ACCURACY_PLAN.md §3) measures: QRS detection F1, the result for every
 * reference label, how often abnormal rhythms end up "poor", AF sensitivity/specificity.
 * Skipped unless ECG_DATASET points to the converted directory:
 *
 *     ECG_DATASET=/path/ecg-eval ECG_SCALE=0.4 ./gradlew :shared:test --tests '*EcgDatasetReport*' -i
 *
 * ECG_SCALE multiplies the signal (0.4 ≈ wrist lead-I amplitude); ECG_GROUP limits to one group.
 */
class EcgDatasetReport {
    private data class Entry(val id: String, val group: String, val label: String, val file: File, val beats: File?)

    private data class Outcome(
        val entry: Entry,
        val result: EcgResult,
        val reason: String,
        val tp: Int,
        val fp: Int,
        val fn: Int,
        val details: String = "",
        val featureRow: String = ""
    )

    @Test
    fun report() {
        val dir = System.getenv("ECG_DATASET")?.let(::File)
        assumeTrue("ECG_DATASET not set", dir != null)
        val scale = System.getenv("ECG_SCALE")?.toFloat() ?: 1f
        val group = System.getenv("ECG_GROUP")
        val entries = File(dir, "index.csv").readLines().drop(1).map { it.split(",") }
            .map { Entry(it[0], it[1], it[2], File(dir, it[3]), it[4].takeIf { f -> f.isNotBlank() }?.let { f -> File(dir, f) }) }
            .filter { group == null || it.group == group }
        // The watch always records 30 s: shorter database records (CinC has 9–60 s) would only measure "too short".
        val outcomes = entries.parallelStream().map { e -> run(e, scale) }.toList().filterNotNull()
        val text = "skipped ${entries.size - outcomes.size} records shorter than 20 s\n" + render(outcomes, scale)
        println(text)
        System.getenv("ECG_REPORT_OUT")?.let { File(it).writeText(text) }
        System.getenv("ECG_FEATURES_OUT")?.let { path ->
            File(path).writeText(
                "id,group,label,reason,rule," + com.heartline.shared.ecg.RhythmFeatures.NAMES.joinToString(",") + "\n" +
                    outcomes.joinToString("\n") { it.featureRow }
            )
        }
        System.getenv("ECG_DETAILS_OUT")?.let { path ->
            File(path).writeText(
                "id,label,result,reason,note,usable,beats,bpm,quality,nrmssd,entropy,tpr,cov,pratio,ectopic,vlike,patterned,noise,runs\n" +
                    outcomes.joinToString("\n") { it.details }
            )
        }
    }

    private fun floats(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    private fun ints(f: File): IntArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return IntArray(b.remaining()).also { b.get(it) }
    }

    private fun run(e: Entry, scale: Float): Outcome? {
        val x = floats(e.file).let { s -> if (scale == 1f) s else FloatArray(s.size) { s[it] * scale } }
        if (x.size < 20 * FS) return null
        val a = EcgAnalyzer.analyze(x, FS, EcgSession())
        var tp = 0
        var fp = 0
        var fn = 0
        e.beats?.let { f ->
            // Beats within the first/last second are ignored (filter edges, partial beats).
            val ref = ints(f).filter { it in FS until x.size - FS }
            val det = a.peaks.filter { it in FS until x.size - FS }
            val used = BooleanArray(det.size)
            for (r in ref) {
                val i = det.indices.filter { !used[it] && abs(det[it] - r) <= TOLERANCE }.minByOrNull { abs(det[it] - r) }
                if (i != null) {
                    used[i] = true
                    tp++
                } else {
                    fn++
                }
            }
            fp = used.count { !it }
        }
        val m = a.metrics
        val f = a.evidence.features
        val details = listOf(
            e.id, e.label, a.result, m.poorReason, m.note, m.usableSec, m.beats, m.averageBpm, m.qualityScore,
            f?.nRmssd, f?.shannonEntropy, f?.turningPointRatio, f?.cov, a.evidence.pWaveRatio,
            m.ectopicBeats, m.ventricularLikeBeats, a.evidence.patterned,
            "%.3f".format(
                a.noiseProbability
            ),
            a.runs.joinToString("/") { run ->
                run.joinToString("|") {
                    "%.0f".format(it.ms) + if (a.beatCluster[it.beat] !=
                        0
                    ) {
                        "v"
                    } else {
                        ""
                    }
                }
            }
        ).joinToString(",") + ";" + a.seconds.joinToString(";") { (k, q) -> "${k.name}:${q?.b}:${q?.k}:${q?.p}:${q?.bas}" }
        val featureRow = listOf(e.id, e.group, e.label, a.signalReason, a.result).joinToString(",") + "," + a.features.joinToString(",")
        return Outcome(e, a.result, a.metrics.poorReason.name, tp, fp, fn, details, featureRow)
    }

    private fun render(o: List<Outcome>, scale: Float): String = buildString {
        appendLine("ECG dataset report (scale $scale, ${o.size} recordings)")
        for ((group, rows) in o.groupBy { it.entry.group }.toSortedMap()) {
            appendLine("== $group")
            for ((label, byLabel) in rows.groupBy { it.entry.label }.toSortedMap()) {
                val n = byLabel.size
                val results = EcgResult.entries.joinToString(" ") { r -> "${short(r)}=${pct(byLabel.count { it.result == r }, n)}" }
                val reasons = byLabel.filter { it.result == EcgResult.POOR_RECORDING }.groupingBy { it.reason }.eachCount()
                    .entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" }
                val tp = byLabel.sumOf { it.tp }
                val fp = byLabel.sumOf { it.fp }
                val fn = byLabel.sumOf { it.fn }
                val f1 = if (tp + fp + fn ==
                    0
                ) {
                    ""
                } else {
                    " qrsF1=%.3f Se=%.3f PPV=%.3f".format(
                        2.0 * tp / (2 * tp + fp + fn),
                        tp.toDouble() / (tp + fn),
                        tp.toDouble() / (tp + fp).coerceAtLeast(1)
                    )
                }
                appendLine("  %-4s n=%-5d %s%s  poor[%s]".format(label, n, results, f1, reasons))
            }
        }
        o.filter { it.entry.group == "cinc2017" }.takeIf { it.isNotEmpty() }?.let { c ->
            val af = c.filter { it.entry.label == "A" }
            val normal = c.filter { it.entry.label == "N" }
            val other = c.filter { it.entry.label == "O" }
            val noisy = c.filter { it.entry.label == "~" }
            appendLine(
                (
                    "CinC summary: AF sens=%s (of AF)  AF spec=%s (N not called AF)  N→sinus=%s  O→poor=%s  O→AF=%s  " +
                        "noisy→poor=%s  unclassified(N+A+O)=%s"
                    ).format(
                    pct(af.count { it.result == EcgResult.AFIB_SIGNS }, af.size),
                    pct(normal.count { it.result != EcgResult.AFIB_SIGNS }, normal.size),
                    pct(normal.count { it.result == EcgResult.SINUS_RHYTHM }, normal.size),
                    pct(other.count { it.result == EcgResult.POOR_RECORDING }, other.size),
                    pct(other.count { it.result == EcgResult.AFIB_SIGNS }, other.size),
                    pct(noisy.count { it.result == EcgResult.POOR_RECORDING }, noisy.size),
                    (af + normal + other).let { l ->
                        pct(
                            l.count {
                                it.result == EcgResult.POOR_RECORDING ||
                                    it.result == EcgResult.INCONCLUSIVE
                            },
                            l.size
                        )
                    }
                )
            )
        }
        o.filter { it.entry.group == "mitdb" && it.entry.label == "E" }.takeIf { it.isNotEmpty() }?.let { e ->
            appendLine(
                "MIT-BIH ectopy: false AF=%s  poor=%s".format(
                    pct(
                        e.count {
                            it.result == EcgResult.AFIB_SIGNS
                        },
                        e.size
                    ),
                    pct(
                        e.count {
                            it.result ==
                                EcgResult.POOR_RECORDING
                        },
                        e.size
                    )
                )
            )
        }
    }

    private fun short(r: EcgResult) = when (r) {
        EcgResult.SINUS_RHYTHM -> "sinus"
        EcgResult.AFIB_SIGNS -> "afib"
        EcgResult.HIGH_HEART_RATE -> "high"
        EcgResult.LOW_HEART_RATE -> "low"
        EcgResult.INCONCLUSIVE -> "inconc"
        EcgResult.POOR_RECORDING -> "poor"
    }

    private fun pct(k: Int, n: Int) = if (n == 0) "–" else "%.0f%%".format(100.0 * k / n)

    private companion object {
        const val FS = 500
        const val TOLERANCE = 37 // 75 ms
    }
}
