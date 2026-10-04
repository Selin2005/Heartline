// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.tools

import com.heartline.shared.bp.BpExportEvaluation
import com.heartline.shared.bp.BpTuning
import java.io.File

/**
 * `./gradlew :shared:bpEval -Pdir=<unpacked export>`: the cuff-checked blood-pressure sessions of
 * an export, replayed through the current algorithm and its main variants (see BpExportEvaluation).
 */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: error("usage: bpEval <unpacked export folder>"))
    val variants = linkedMapOf(
        "default (6.4)" to BpTuning.DEFAULT,
        "algorithm 6.3" to BpTuning.ALGORITHM_6_3,
        "6.4 with ρgh" to BpTuning.DEFAULT.copy(hydrostaticFactor = 1.0),
        "6.4 with diastolic shape model" to BpTuning.DEFAULT.copy(diastolic = BpTuning.Diastolic.SHAPE),
        "6.4, transit corrected for ρgh" to BpTuning.DEFAULT.copy(transitHydrostaticFactor = 1.0)
    )
    // Sessions without a cuff reading (cuff shown as 0/0), under the default.
    println(
        BpExportEvaluation.format(
            "all sessions, default (no cuff = 0/0)",
            BpExportEvaluation.evaluate(BpExportEvaluation.load(dir, withoutCuff = true))
        )
    )
    for (mode in BpExportEvaluation.Mode.entries) {
        val cases = BpExportEvaluation.load(dir, mode)
        println("##### $mode: ${cases.size} cuff-checked sessions")
        for ((name, tuning) in variants) println(BpExportEvaluation.format(name, BpExportEvaluation.evaluate(cases, tuning)))
    }
}
