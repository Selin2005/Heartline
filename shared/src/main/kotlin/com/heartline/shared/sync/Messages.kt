// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

import com.heartline.shared.model.Metric
import kotlinx.serialization.Serializable

@Serializable
enum class Role { PHONE, WATCH }

/** Watch → phone on every app start and every link check; the phone answers with [PhoneStatus]. */
@Serializable
data class Hello(
    val protocol: Int = Protocol.VERSION,
    val appVersion: String,
    val capabilities: List<Metric> = emptyList(),
    val sensorServiceVersion: String? = null,
    val role: Role = Role.WATCH,
    val deviceName: String? = null,
    /** The watch's settings: changed on the watch while the phone was away, they may be newer. */
    val settings: com.heartline.shared.hr.MonitorSettings? = null
) {
    fun isCompatible() = protocol == Protocol.VERSION
}

@Serializable
enum class CalibrationStatus { MISSING, VALID, EXPIRED }

/**
 * Phone → watch: what the phone knows about setup. Sent in reply to [Hello] and whenever the
 * profile, onboarding or BP calibration changes, so the watch can gate its features on it.
 */
@Serializable
data class PhoneStatus(
    val protocol: Int = Protocol.VERSION,
    val appVersion: String = "",
    val onboarded: Boolean = false,
    /** The current Terms of Use and Privacy Policy (AppInfo.TERMS_VERSION) were accepted on the phone. */
    val termsAccepted: Boolean = false,
    val profileComplete: Boolean = false,
    val displayName: String = "",
    val calcSexKnown: Boolean = false,
    val calibration: CalibrationStatus = CalibrationStatus.MISSING,
    val calibrationDaysLeft: Int? = null
) {
    fun isCompatible() = protocol == Protocol.VERSION

    val setupComplete: Boolean get() = termsAccepted && onboarded && profileComplete
}

/** Where the watch sends the user on the phone. */
@Serializable
enum class SetupTarget(val phoneRoute: String) {
    HOME("home"),
    PROFILE("profile"),
    BP_CALIBRATION("bp/calibrate"),
    DEV_MODE_HELP("help/dev-mode")
}

/** Watch → phone: "open this on the phone" (fallback when the remote activity launch fails). */
@Serializable
data class SetupRequest(val target: SetupTarget)

@Serializable
data class Ack(val id: String, val ok: Boolean)

/**
 * Phone → watch, about the diagnostic log (answered on [Protocol.logsPath] or, for a segment,
 * streamed on [Protocol.logSegmentPath]):
 * - [manifest]: the header and the list of segments ([LogManifest]);
 * - [segment]: that one segment file, as it is on the watch (gzip);
 * - [delete]: erase the log;
 * - none of them: the whole log as one text (phones from before segmented export).
 * Watches from before segmented export ignore [manifest] and [segment] and send the whole text.
 */
@Serializable
data class LogRequest(val requestId: String, val delete: Boolean = false, val manifest: Boolean = false, val segment: String? = null)

/** One file of the watch's log: its name and size in bytes (gzip). */
@Serializable
data class LogSegment(val name: String, val size: Long)

/** Watch → phone: the log's header and its segments, oldest first (the process logcat last). */
@Serializable
data class LogManifest(val header: String, val segments: List<LogSegment>)

@Serializable
data class DeleteRecord(val id: String)

/**
 * Phone → watch: take calibration round [round] (1..3, 4 = standing) for wizard [captureId].
 * [precise]: record it in precise mode (ECG + PPG + motion, finger on the key), so the transit
 * time channels get cuff points too (algorithm 6).
 */
@Serializable
data class CaptureRequest(val captureId: String, val round: Int, val precise: Boolean = false)

/** Watch → phone: PPG features recorded for a calibration round. */
@Serializable
data class CaptureResult(
    val id: String,
    val captureId: String,
    val round: Int,
    val features: com.heartline.shared.bp.PpgFeatureVector,
    /** Raw PPG of the round (100 Hz), kept with the calibration so it can be re-analysed later. */
    val ppg: List<Float>? = null,
    /** Mean gravity vector in the watch's frame during the round (arm position), if known. */
    val gravity: List<Double>? = null,
    /** Algorithm 6: the round on every channel (IR, BCG, ECG transit times, skin sensors). */
    val capture: com.heartline.shared.bp.ChannelCapture? = null,
    /** The raw session log of this round (BpSessionLog id), for the export. */
    val sessionId: String? = null
)
