// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.tools

import java.io.File

/**
 * `./gradlew :shared:ecgEval -Pdir=<unpacked export>`: every ECG of an export replayed through
 * algorithms 3 and 4, without a reference rhythm (see EcgExportEvaluation). Prints the report and
 * writes one row per recording to `<dir>/ecg-eval.csv`.
 */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull()?.takeIf { it.isNotBlank() } ?: error("usage: ecgEval <unpacked export folder>"))
    val rows = EcgExportEvaluation.evaluate(EcgExportEvaluation.load(dir))
    println(EcgExportEvaluation.report(rows))
    File(dir, "ecg-eval.csv").writeText(EcgExportEvaluation.csv(rows))
    println("wrote ${File(dir, "ecg-eval.csv")}")
}
