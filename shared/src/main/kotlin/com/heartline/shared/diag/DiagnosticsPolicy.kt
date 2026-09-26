// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

/**
 * Whether the apps keep a diagnostic log on the device. Beta testers help with development, so
 * it is on for them unless they turn it off; stable users are asked once and it stays off until
 * they agree.
 */
object DiagnosticsPolicy {
    const val DETAILED_DURATION_MS = 24L * 60 * 60 * 1000

    /** [choice]: the user's answer (null = never asked or changed); [betaUser]: runs or receives betas. */
    fun enabled(choice: Boolean?, betaUser: Boolean): Boolean = choice ?: betaUser

    /** Stable users who haven't answered yet get the one-time question. */
    fun shouldAsk(choice: Boolean?, betaUser: Boolean): Boolean = choice == null && !betaUser

    /** Raw sensor logging is on while [untilMs] is in the future, and only with diagnostic logs on. */
    fun detailed(enabled: Boolean, untilMs: Long, nowMs: Long): Boolean = enabled && untilMs > nowMs
}
