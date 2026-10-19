// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.model

/** Wellness-level rhythm categories shown after an ECG recording. */
enum class EcgResult {
    SINUS_RHYTHM,
    AFIB_SIGNS,
    HIGH_HEART_RATE,
    LOW_HEART_RATE,
    INCONCLUSIVE,
    POOR_RECORDING,

    /** Algorithm 4: sinus rhythm with many early beats (≥ 10 % of beats); EcgNote says which kind. */
    FREQUENT_EXTRA_BEATS
    ;

    val severity: Severity
        get() = when (this) {
            SINUS_RHYTHM -> Severity.NORMAL
            AFIB_SIGNS -> Severity.ALERT
            HIGH_HEART_RATE, LOW_HEART_RATE, FREQUENT_EXTRA_BEATS -> Severity.WARN
            INCONCLUSIVE, POOR_RECORDING -> Severity.NEUTRAL
        }
}

enum class Severity { NORMAL, WARN, ALERT, NEUTRAL }
