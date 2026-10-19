// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.tools

import com.heartline.shared.ecg.EcgAnalysis
import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.ecg.EcgSession
import com.heartline.shared.ecg.RhythmClassifier
import com.heartline.shared.ecg.RhythmFeatures
import com.heartline.shared.ecg.RhythmModel
import com.heartline.shared.model.EcgNote
import com.heartline.shared.model.EcgPoorReason
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.Protocol
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Real watch ECGs from an unpacked log export (Settings → Help & diagnostics → Export logs),
 * replayed through algorithm 3 and algorithm 4 without any reference rhythm: what the wrist
 * signal is like, what each algorithm says, whether the watch's stored result replays the same,
 * whether two recordings minutes apart agree, whether the watch and the phone's second opinion
 * agree, how often each finding comes up against what it does on clinical recordings, and which
 * recordings are worth a closer look. `./gradlew :shared:ecgEval -Pdir=<folder>`.
 */
object EcgExportEvaluation {
    /** Two recordings this close count as a repeat of each other. */
    const val REPEAT_MS = 15 * 60_000L

    data class Record(val id: String, val startMs: Long, val fs: Int, val stored: RecordSummary.Ecg, val wave: FloatArray)

    data class Row(val record: Record, val now: EcgAnalysis, val algorithm3: EcgResult) {
        val shown: String get() = shown(now.result, now.metrics.note)
        val usable: Boolean get() = now.metrics.poorReason == EcgPoorReason.NONE && now.result != EcgResult.POOR_RECORDING
    }

    /** "SINUS", "SINUS+V" (extra beats from the lower chambers), "FREQ+S", "INC/sinus", "AF", … */
    fun shown(result: EcgResult, note: EcgNote): String {
        val base = when (result) {
            EcgResult.SINUS_RHYTHM -> "SINUS"
            EcgResult.FREQUENT_EXTRA_BEATS -> "FREQ"
            EcgResult.AFIB_SIGNS -> "AF"
            EcgResult.HIGH_HEART_RATE -> "HIGH"
            EcgResult.LOW_HEART_RATE -> "LOW"
            EcgResult.INCONCLUSIVE -> "INC"
            EcgResult.POOR_RECORDING -> "POOR"
        }
        return base + when (note) {
            EcgNote.EXTRA_BEATS_UPPER -> "+S"
            EcgNote.EXTRA_BEATS_LOWER -> "+V"
            EcgNote.EXTRA_BEATS_BOTH -> "+VS"
            EcgNote.SINUS_FAST, EcgNote.SINUS_SLOW -> "/sinus"
            else -> ""
        }
    }

    // ---- Loading ----

    fun load(dir: File): List<Record> {
        val csv = File(dir, "records/records.csv").takeIf { it.exists() } ?: return emptyList()
        val waves = File(dir, "records").listFiles { f -> f.name.endsWith("-wave.csv") && "-ecg-" in f.name }.orEmpty()
        val lines = csv.readLines()
        val header = parseCsvLine(lines.firstOrNull() ?: return emptyList())
        val at = { name: String -> header.indexOf(name) }
        return lines.drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val c = parseCsvLine(line)
            if (c.getOrNull(at("kind")) != "ECG") return@mapNotNull null
            val id = c[at("id")]
            val summary = runCatching { Protocol.json.decodeFromString<RecordSummary>(c[at("summary")]) }.getOrNull() as? RecordSummary.Ecg
                ?: return@mapNotNull null
            val file = waves.firstOrNull { it.name.endsWith("-ecg-${id.take(8)}-wave.csv") } ?: return@mapNotNull null
            val wave = file.readLines().drop(1).mapNotNull { l ->
                l.substringAfterLast(',').let { if (it.isEmpty()) Float.NaN else it.toFloatOrNull() }
            }
            Record(id, c[at("startMs")].toLong(), c[at("sampleRateHz")].toInt(), summary, wave.toFloatArray())
        }.sortedBy { it.startMs }
    }

    /** One CSV line with quoted fields ("" inside quotes is a quote). */
    fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && line.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    out += cell.toString()
                    cell.clear()
                }
                else -> cell.append(ch)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    // ---- Replay ----

    /** Algorithm 3's model (24 features, a prefix of algorithm 4's), kept only for this comparison. */
    private val algorithm3Model: RhythmModel by lazy {
        RhythmModel.parse(
            EcgExportEvaluation::class.java.getResourceAsStream("/ecg/rhythm_model_algorithm3.json")!!.readBytes().decodeToString()
        )
            .also { check(it.features == RhythmFeatures.NAMES.take(it.features.size)) { "algorithm 3's features are no prefix" } }
    }

    fun evaluate(records: List<Record>): List<Row> = records.parallelStream().map { r ->
        val a = EcgAnalyzer.analyze(r.wave, r.fs, EcgSession(leadOffRatio = r.stored.leadOffRatio))
        Row(r, a, algorithm3(a))
    }.toList()

    /** Algorithm 3's decision (EcgAnalyzer.decide as it was) on the same analysis. */
    fun algorithm3(a: EcgAnalysis): EcgResult {
        val rules = a.rules ?: return a.result
        val bpm = a.averageBpm
        if (a.signalReason != EcgPoorReason.NONE || bpm == null) return rules.result
        val m = algorithm3Model
        val p = m.predict(a.features.copyOf(m.features.size))
        val t = m.thresholds
        val cls = { name: String -> p[m.classes.indexOf(name)] }
        return when {
            cls("noisy") >= (t["noisy"] ?: 0.5) -> EcgResult.POOR_RECORDING
            cls("af") >= (t["af"] ?: 0.5) && bpm <= RhythmClassifier.AF_MAX_HR -> EcgResult.AFIB_SIGNS
            bpm > RhythmClassifier.HIGH_HR -> EcgResult.HIGH_HEART_RATE
            bpm < RhythmClassifier.LOW_HR -> EcgResult.LOW_HEART_RATE
            cls("normal") >= (t["normal"] ?: 0.5) && bpm <= RhythmClassifier.SINUS_MAX_HR -> EcgResult.SINUS_RHYTHM
            else -> EcgResult.INCONCLUSIVE
        }
    }

    // ---- Report ----

    fun report(rows: List<Row>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        if (rows.isEmpty()) {
            appendLine("No ECG recordings with a stored wave in this export.")
            return@buildString
        }
        val n = rows.size
        fun pct(k: Int, of: Int = n) = if (of == 0) "–" else "%.0f%%".format(100.0 * k / of)
        fun median(v: List<Double>) = v.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }

        appendLine(
            "ECG evaluation without a reference rhythm: $n recordings, ${time.format(
                Instant.ofEpochMilli(rows.first().record.startMs)
            )} – ${time.format(Instant.ofEpochMilli(rows.last().record.startMs))}"
        )
        appendLine(
            "Recorded with: " +
                rows.groupingBy {
                    "algorithm ${it.record.stored.metrics?.algorithm ?: "?"}"
                }.eachCount().entries.joinToString { "${it.key} ×${it.value}" }
        )
        appendLine()

        val usable = rows.filter { it.usable }
        appendLine("== The signal on the wrist")
        appendLine(
            "  usable recordings: ${usable.size} (${pct(usable.size)}); poor: " +
                rows.filter {
                    !it.usable
                }.groupingBy {
                    it.now.metrics.poorReason.takeIf { r -> r != EcgPoorReason.NONE }?.name ?: "MODEL_NOISY"
                }.eachCount().entries.joinToString().ifEmpty { "none" }
        )
        val amplitude = usable.map { it.now.features.getOrElse(RhythmFeatures.NAMES.indexOf("amplitude_mv")) { 0.0 } }
        appendLine(
            "  median QRS amplitude: %.2f mV (clinical lead I is about 1 mV; the models were checked at × 0.3)".format(median(amplitude))
        )
        appendLine(
            "  median signal quality: %.0f/100, usable seconds %.0f%%".format(
                median(
                    usable.map {
                        it.now.metrics.qualityScore.toDouble()
                    }
                ),
                median(usable.map { it.now.metrics.usablePercent.toDouble() })
            )
        )
        val sinusLike = usable.filter { it.now.result == EcgResult.SINUS_RHYTHM || it.now.result == EcgResult.FREQUENT_EXTRA_BEATS }
        appendLine(
            "  P wave found on sinus recordings: ${pct(sinusLike.count { it.now.metrics.prMs != null }, sinusLike.size)} " +
                "(PTB-XL lead I: 99 %; much less means the wrist's P wave is too small for the extra-beat kinds and the PR note)"
        )
        appendLine()

        appendLine("== What each algorithm says (same recordings, replayed)")
        val shownNow = rows.groupingBy { it.shown }.eachCount().entries.sortedByDescending { it.value }
        val shownOld = rows.groupingBy { shown(it.algorithm3, EcgNote.NONE) }.eachCount().entries.sortedByDescending { it.value }
        appendLine("  algorithm 3: " + shownOld.joinToString { "${it.key} ${pct(it.value)}" })
        appendLine("  algorithm 4: " + shownNow.joinToString { "${it.key} ${pct(it.value)}" })
        val changed = rows.filter { it.algorithm3 != it.now.result }
        appendLine(
            "  changed by algorithm 4: ${changed.size} (${pct(changed.size)}): " +
                changed.groupingBy {
                    "${shown(it.algorithm3, EcgNote.NONE)} → ${it.shown}"
                }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ×${it.value}" }.ifEmpty { "–" }
        )
        appendLine()

        appendLine("== How often each finding comes up (usable recordings) vs clinical normal recordings")
        fun finding(name: String, k: Int, clinical: String) =
            appendLine("  %-42s %5s   (clinical normal: %s)".format(name, pct(k, usable.size), clinical))
        finding("signs of AFib", usable.count { it.now.result == EcgResult.AFIB_SIGNS }, "0–1 %")
        finding(
            "extra beats reported (any)",
            usable.count {
                it.now.metrics.supraventricularBeats + it.now.metrics.ventricularBeats > 0
            },
            "1–3 %"
        )
        finding("  from the upper chambers", usable.count { it.now.metrics.supraventricularBeats > 0 }, "1–2 %")
        finding("  from the lower chambers", usable.count { it.now.metrics.ventricularBeats > 0 }, "1–2 %")
        finding("frequent extra beats", usable.count { it.now.result == EcgResult.FREQUENT_EXTRA_BEATS }, "< 1 %")
        finding("long PR note", usable.count { it.now.metrics.longPr }, "about 5 % (adults with a PR > 200 ms: 1–5 %, more with age)")
        finding("inconclusive", usable.count { it.now.result == EcgResult.INCONCLUSIVE }, "5–10 %")
        appendLine("  Far more than on clinical recordings, for one person who feels well, points at the wrist")
        appendLine("  signal (false alarms), not at the heart.")
        appendLine()

        appendLine("== Does the watch's stored result replay the same? (recordings made with algorithm ${EcgAnalyzer.ALGORITHM})")
        val same = rows.filter { it.record.stored.metrics?.algorithm == EcgAnalyzer.ALGORITHM }
        if (same.isEmpty()) {
            appendLine("  none yet")
        } else {
            val agree = same.count { it.record.stored.result == it.now.result && it.record.stored.metrics?.note == it.now.metrics.note }
            appendLine(
                "  ${pct(
                    agree,
                    same.size
                )} of ${same.size} identical (differences come only from lead-off splices, which the export doesn't keep)"
            )
        }
        appendLine()

        appendLine("== Repeats: two recordings ≤ ${REPEAT_MS / 60_000} min apart, both usable")
        val pairs = usable.zipWithNext().filter { (a, b) -> b.record.startMs - a.record.startMs <= REPEAT_MS }
        if (pairs.isEmpty()) {
            appendLine("  none (record twice in a row now and then: it is the one check of consistency without a reference)")
        } else {
            fun rhythm(r: Row) = r.now.result.name
            fun extra(r: Row) = r.now.metrics.supraventricularBeats + r.now.metrics.ventricularBeats > 0
            appendLine("  same result: ${pct(pairs.count { (a, b) -> rhythm(a) == rhythm(b) }, pairs.size)} of ${pairs.size} pairs")
            appendLine("  same extra-beat finding (seen / not seen): ${pct(pairs.count { (a, b) -> extra(a) == extra(b) }, pairs.size)}")
            appendLine("  same long-PR note: ${pct(pairs.count { (a, b) -> a.now.metrics.longPr == b.now.metrics.longPr }, pairs.size)}")
            appendLine(
                "  PR difference: median %.0f ms".format(
                    median(
                        pairs.mapNotNull { (a, b) ->
                            a.now.metrics.prMs?.let { pa ->
                                b.now.metrics.prMs?.let {
                                    abs(
                                        pa - it
                                    ).toDouble()
                                }
                            }
                        }
                    )
                )
            )
        }
        appendLine()

        appendLine("== Watch vs the phone's second opinion (ECGFounder), where the phone stored one")
        val opinion = usable.filter { it.record.stored.metrics?.secondOpinion != null }
        if (opinion.isEmpty()) {
            appendLine("  none")
        } else {
            val second = { r: Row -> r.record.stored.metrics!!.secondOpinion!! }
            appendLine("  same result: ${pct(opinion.count { second(it) == it.now.result }, opinion.size)} of ${opinion.size}")
            appendLine(
                "  AFib on one side only: ${opinion.count {
                    (second(it) == EcgResult.AFIB_SIGNS) != (it.now.result == EcgResult.AFIB_SIGNS)
                }}"
            )
        }
        appendLine()

        appendLine("== Worth a closer look (see ecg-eval.csv)")
        val look = rows.mapNotNull { r -> reasons(r, rows).takeIf { it.isNotEmpty() }?.let { r to it } }
        if (look.isEmpty()) appendLine("  nothing stands out")
        look.forEach { (r, why) ->
            appendLine(
                "  ${time.format(
                    Instant.ofEpochMilli(r.record.startMs)
                )}  ${r.record.id.take(8)}  ${r.shown.padEnd(9)} ${why.joinToString("; ")}"
            )
        }
    }

    /** Why a recording deserves a look: a finding, or a disagreement between algorithms, repeats or watch and phone. */
    fun reasons(r: Row, all: List<Row>): List<String> = buildList {
        val m = r.now.metrics
        if (r.now.result == EcgResult.AFIB_SIGNS) add("signs of AFib")
        if (r.now.result ==
            EcgResult.FREQUENT_EXTRA_BEATS
        ) {
            add("frequent extra beats (S ${m.supraventricularBeats}, V ${m.ventricularBeats})")
        }
        if (m.longPr) add("long PR (${m.prMs} ms)")
        if (r.usable &&
            r.algorithm3 != r.now.result &&
            !(r.algorithm3 == EcgResult.INCONCLUSIVE && r.now.result == EcgResult.SINUS_RHYTHM)
        ) {
            add("algorithm 3 said ${shown(r.algorithm3, EcgNote.NONE)}")
        }
        val stored = r.record.stored
        if (stored.metrics?.algorithm == EcgAnalyzer.ALGORITHM && stored.result != r.now.result) add("watch stored ${stored.result}")
        stored.metrics?.secondOpinion?.let { if (r.usable && it != r.now.result) add("phone said $it") }
        all.firstOrNull {
            it !== r &&
                it.usable &&
                r.usable &&
                abs(it.record.startMs - r.record.startMs) <= REPEAT_MS &&
                it.now.result != r.now.result
        }
            ?.let { add("a repeat said ${it.shown}") }
    }

    fun csv(rows: List<Row>): String = buildString {
        appendLine(
            "id,start,stored_algorithm,stored_result,algorithm3,result,note,shown,poor,bpm,quality,supraventricular,ventricular,qrs_ms,pr_ms,long_pr,second_opinion,why"
        )
        for (r in rows) {
            val m = r.now.metrics
            appendLine(
                listOf(
                    r.record.id, Instant.ofEpochMilli(r.record.startMs), r.record.stored.metrics?.algorithm ?: "",
                    r.record.stored.result ?: "",
                    r.algorithm3, r.now.result, m.note, r.shown, m.poorReason, m.averageBpm ?: "", m.qualityScore, m.supraventricularBeats,
                    m.ventricularBeats, m.qrsMs ?: "", m.prMs ?: "", m.longPr, r.record.stored.metrics?.secondOpinion ?: "",
                    "\"" + reasons(r, rows).joinToString("; ") + "\""
                ).joinToString(",")
            )
        }
    }
}
