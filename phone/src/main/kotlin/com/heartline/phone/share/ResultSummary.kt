// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.share

import android.content.res.Resources
import com.heartline.phone.R
import com.heartline.phone.report.EcgDetailRows
import com.heartline.phone.ui.components.explanation
import com.heartline.phone.ui.components.label
import com.heartline.phone.ui.model.BpHomeUi
import com.heartline.phone.ui.model.EcgRecordUi
import com.heartline.phone.ui.model.HeartRateUi

/**
 * Plain-text summaries sent to an AI assistant: the user's prompt first, then the measured values
 * in a compact, unambiguous form, and a reminder that this is wellness data, not a diagnosis.
 */
object ResultSummary {
    fun prompt(res: Resources, custom: String?) = custom?.takeIf { it.isNotBlank() } ?: res.getString(R.string.ai_default_prompt)

    fun ecg(res: Resources, record: EcgRecordUi, person: String?, prompt: String): String = buildString {
        appendLine(prompt)
        appendLine()
        appendLine(res.getString(R.string.ai_ecg_header))
        person?.let { appendLine(res.getString(R.string.ai_person, it)) }
        appendLine("${res.getString(R.string.ai_result)}: ${res.getString(record.result.label)}")
        appendLine("${res.getString(R.string.ai_recorded)}: ${record.date} ${record.time}")
        record.averageBpm?.let { appendLine("${res.getString(R.string.ecg_avg_hr)}: $it bpm") }
        record.metrics?.let { m -> EcgDetailRows.rows(res, m).forEach { (k, v) -> appendLine("$k: $v") } }
        if (record.symptoms.isNotEmpty()) appendLine("${res.getString(R.string.ecg_symptoms)}: ${record.symptoms.joinToString { res.getString(it.label) }}")
        appendLine("${res.getString(R.string.ai_about_result)}: ${res.getString(record.result.explanation)}")
        appendLine()
        append(res.getString(R.string.ai_disclaimer))
    }

    fun bp(res: Resources, state: BpHomeUi, person: String?, prompt: String): String = buildString {
        appendLine(prompt)
        appendLine()
        appendLine(res.getString(R.string.ai_bp_header))
        person?.let { appendLine(res.getString(R.string.ai_person, it)) }
        state.readings.take(14).forEach { r ->
            appendLine("${r.date} ${r.time}: ${r.systolic}/${r.diastolic} mmHg${r.uncertainty?.let { " (±$it)" }.orEmpty()}${r.pulse?.let { ", pulse $it bpm" }.orEmpty()}")
        }
        state.average7?.let { appendLine("${res.getString(R.string.bp_avg7)}: ${it.first}/${it.second} mmHg") }
        state.average30?.let { appendLine("${res.getString(R.string.bp_avg30)}: ${it.first}/${it.second} mmHg") }
        state.accuracy?.let { a ->
            appendLine(res.getString(R.string.ai_bp_accuracy, a.count, a.meanDiffSys, a.sdSys, a.meanDiffDia, a.sdDia))
        }
        appendLine(res.getString(R.string.ai_bp_method))
        appendLine()
        append(res.getString(R.string.ai_disclaimer))
    }

    fun heartRate(res: Resources, state: HeartRateUi, person: String?, prompt: String): String = buildString {
        appendLine(prompt)
        appendLine()
        appendLine(res.getString(R.string.ai_hr_header))
        person?.let { appendLine(res.getString(R.string.ai_person, it)) }
        state.latestBpm?.let { appendLine("${res.getString(R.string.ai_latest)}: $it bpm (${state.latestTime.orEmpty()})") }
        state.restingBpm?.let { appendLine("${res.getString(R.string.hr_resting)}: $it bpm") }
        if (state.minBpm != null && state.maxBpm != null) appendLine("${res.getString(R.string.ai_today_range)}: ${state.minBpm}–${state.maxBpm} bpm")
        state.week.forEachIndexed { i, b -> appendLine("${state.weekDates.getOrElse(b.index) { "day ${i + 1}" }}: ${b.min}–${b.max} bpm") }
        state.hrvTodayMs?.let { appendLine("HRV (RMSSD): $it ms") }
        appendLine()
        append(res.getString(R.string.ai_disclaimer))
    }
}
