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
    val settings: com.heartline.shared.hr.MonitorSettings? = null,
    /** What this watch app can do beyond the protocol version ([Features]); older watches send none. */
    val features: List<String> = emptyList()
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

/**
 * Phone → watch: file [name] of [type] ("log" segment or "raw" session) arrived whole, [size]
 * bytes, and is kept on the phone; the watch may delete its copy.
 */
@Serializable
data class ArchiveAck(val type: String, val name: String, val size: Long)

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

/** Optional abilities announced in [Hello.features], so newer phones can tell what an older watch lacks. */
object Features {
    /** Measurements started from the phone: the `remote/<METRIC>` watch route and the MEASURE_ messages. */
    const val REMOTE_MEASURE = "remote_measure_v1"
}

/** Where a measurement started from the phone is. */
@Serializable
enum class MeasureStage {
    /** The watch opened the measurement and is about to start. */
    ACCEPTED,

    /** Getting ready: connecting to the sensor, settling. */
    PREPARING,
    MEASURING,

    /** Measured; computing and storing the result. */
    SAVING,

    /** The watch could not start it; see [MeasureState.reason]. */
    REJECTED
}

/** Something the user can fix while measuring (the progress waits until it is fixed). */
@Serializable
enum class MeasureHint { NONE, HOLD_STILL, WRIST_CONTACT, LOW_SIGNAL, MOVING }

/** Why the watch could not start, or why a measurement failed. */
@Serializable
enum class MeasureProblem {
    /** Another measurement is running on the watch. */
    BUSY,

    /** The watch app is not set up (phone link, terms, profile). */
    NEEDS_SETUP,
    PERMISSION,

    /** Health Platform's developer mode is off. */
    SDK_POLICY,
    NEEDS_CALIBRATION,
    NEEDS_PROFILE,
    UNSUPPORTED,

    /** The sensor service is missing, outdated or failed. */
    SENSOR,
    LOW_SIGNAL,
    WRIST_CONTACT,
    MOVING,
    TIMEOUT,

    /** The user left the measurement on the watch. */
    WATCH_LEFT,
    OTHER
}

/**
 * Watch → phone, about once a second and at every change: the measurement [sessionId] the phone
 * started. Sent without retries; [seq] grows, so the phone ignores one that arrives late.
 */
@Serializable
data class MeasureState(
    val sessionId: String,
    val metric: Metric,
    val stage: MeasureStage,
    val seq: Int,
    val progress: Float = 0f,
    val secondsLeft: Int? = null,
    val hint: MeasureHint = MeasureHint.NONE,
    /** A live value while measuring (the heart rate), if any. */
    val live: Int? = null,
    val reason: MeasureProblem? = null,
    /** Blood-pressure calibration: the round being taken. */
    val round: Int? = null
)

@Serializable
enum class MeasureOutcome { OK, FAILED, CANCELLED }

/**
 * Watch → phone, once, through the outbox: how measurement [sessionId] ended. With [MeasureOutcome.OK],
 * [recordId] is the stored record (it follows as usual) and [summary] lets the phone show the value
 * at once. A calibration round has no record: its data goes as a [CaptureResult].
 */
@Serializable
data class MeasureResult(
    val id: String,
    val sessionId: String,
    val metric: Metric,
    val outcome: MeasureOutcome,
    val problem: MeasureProblem? = null,
    val recordId: String? = null,
    val summary: com.heartline.shared.model.RecordSummary? = null,
    val startedAtMs: Long = 0,
    val round: Int? = null
)

/** Phone → watch: stop measurement [sessionId]. */
@Serializable
data class MeasureCancel(val sessionId: String)
