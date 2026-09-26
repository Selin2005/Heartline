package com.heartline.shared.model

/** Wellness-level rhythm categories shown after an ECG recording. */
enum class EcgResult {
    SINUS_RHYTHM,
    AFIB_SIGNS,
    HIGH_HEART_RATE,
    LOW_HEART_RATE,
    INCONCLUSIVE,
    POOR_RECORDING
    ;

    val severity: Severity
        get() = when (this) {
            SINUS_RHYTHM -> Severity.NORMAL
            AFIB_SIGNS -> Severity.ALERT
            HIGH_HEART_RATE, LOW_HEART_RATE -> Severity.WARN
            INCONCLUSIVE, POOR_RECORDING -> Severity.NEUTRAL
        }
}

enum class Severity { NORMAL, WARN, ALERT, NEUTRAL }
