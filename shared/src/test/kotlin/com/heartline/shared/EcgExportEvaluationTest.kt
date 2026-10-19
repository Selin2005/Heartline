// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.ecg.EcgSession
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sample.SyntheticEcg
import com.heartline.shared.sync.Protocol
import com.heartline.shared.tools.EcgExportEvaluation
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ecgEval reads an export as the phone writes it (PhoneLogExporter.writeRecords) and replays it. */
class EcgExportEvaluationTest {
    private fun export(waves: List<Pair<Long, FloatArray>>): File {
        val dir = Files.createTempDirectory("ecg-export").toFile()
        val records = File(dir, "records").apply { mkdirs() }
        val fs = SyntheticEcg.SAMPLE_RATE_HZ
        val rows = waves.mapIndexed { i, (start, wave) ->
            val id = "rec$i-0000-0000"
            val a = EcgAnalyzer.analyze(wave, fs, EcgSession())
            val summary = Protocol.json.encodeToString<RecordSummary>(RecordSummary.Ecg(a.averageBpm, a.result, 0f, metrics = a.metrics))
            File(records, "20261019-000$i-ecg-${id.take(8)}-wave.csv").writeText(
                "index,timeMs,value\n" + wave.withIndex().joinToString("\n") { (k, v) -> "$k,${k * 1000L / fs},$v" }
            )
            listOf(id, "ECG", "t", start, 30000, fs, wave.size, "", "\"" + summary.replace("\"", "\"\"") + "\"").joinToString(",")
        }
        File(records, "records.csv").writeText(
            "id,kind,start,startMs,durationMs,sampleRateHz,sampleCount,note,summary\n" + rows.joinToString("\n") + "\n"
        )
        return dir
    }

    @Test
    fun readsReplaysAndReportsAnExport() {
        val t0 = 1_790_000_000_000L
        val dir = export(
            listOf(
                t0 to SyntheticEcg.generate(30.0, 70.0, 0.02, seed = 1),
                t0 + 5 * 60_000 to SyntheticEcg.generate(30.0, 72.0, 0.02, seed = 2),
                t0 + 3 * 3_600_000 to SyntheticEcg.generate(30.0, 95.0, 0.3, seed = 3)
            )
        )
        val records = EcgExportEvaluation.load(dir)
        assertEquals(3, records.size)
        val rows = EcgExportEvaluation.evaluate(records)
        // The stored result (made by this algorithm on the same wave) replays the same.
        rows.forEach { assertEquals(it.record.stored.result, it.now.result) }
        val report = EcgExportEvaluation.report(rows, ZoneOffset.UTC).also(::println)
        assertTrue(report, "3 recordings" in report)
        assertTrue(report, "100% of 3 identical" in report)
        assertTrue(report, "of 1 pairs" in report)
        assertTrue(report, "algorithm 3:" in report && "algorithm 4:" in report)
        val csv = EcgExportEvaluation.csv(rows).lines().filter { it.isNotBlank() }
        assertEquals(4, csv.size)
    }

    @Test
    fun csvLinesWithQuotedJson() {
        assertEquals(listOf("a", "{\"x\":1,\"y\":2}", ""), EcgExportEvaluation.parseCsvLine("a,\"{\"\"x\"\":1,\"\"y\"\":2}\","))
    }
}
