// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

import kotlinx.serialization.json.Json

/** Wearable Data Layer contract v1. */
object Protocol {
    const val VERSION = 1
    private const val ROOT = "/hl/v1"

    const val HELLO = "$ROOT/hello"
    const val RECORD_META = "$ROOT/record/meta"
    const val RECORD_WAVE_PREFIX = "$ROOT/record/wave/"
    const val RECORD_ACK = "$ROOT/record/ack"
    const val DELETE = "$ROOT/delete"
    const val PROFILE = "$ROOT/profile"
    const val SETTINGS = "$ROOT/settings"
    const val BP_CALIBRATION = "$ROOT/bp/calibration"
    const val BP_CALIBRATION_CAPTURE = "$ROOT/bp/calib-capture"
    const val HR_BATCH = "$ROOT/hr/batch"

    /** Raw blood-pressure session logs (BpSessionLog, gzip), watch → phone, acked by id. */
    const val BP_SESSION_PREFIX = "$ROOT/bp/session/"
    const val ALERT = "$ROOT/alert"
    const val OPEN = "$ROOT/open"
    const val STATUS = "$ROOT/status"
    const val SETUP_REQUEST = "$ROOT/setup-request"
    const val LOGS_REQUEST = "$ROOT/logs/request"
    const val LOGS_PREFIX = "$ROOT/logs/data/"

    fun logsPath(requestId: String) = LOGS_PREFIX + requestId

    /** One log segment, streamed watch → phone (read as a stream, not into memory). */
    const val LOGS_SEGMENT_PREFIX = "$ROOT/logs/segment/"

    fun logSegmentPath(requestId: String) = LOGS_SEGMENT_PREFIX + requestId

    /**
     * Watch → phone, in the background: a finished log segment or raw sensor session, streamed on
     * `<prefix><type>/<name>` and acknowledged with [LOGS_ARCHIVE_ACK] ([ArchiveAck]) once stored.
     */
    const val LOGS_ARCHIVE_PREFIX = "$ROOT/logs/archive/"
    const val LOGS_ARCHIVE_ACK = "$ROOT/logs/archive-ack"

    fun logArchivePath(type: String, name: String) = "$LOGS_ARCHIVE_PREFIX$type/$name"

    /** Capability name both apps advertise so each can find the other node. */
    const val CAPABILITY_PHONE = "heartline_phone"
    const val CAPABILITY_WATCH = "heartline_watch"

    fun wavePath(id: String) = "$RECORD_WAVE_PREFIX$id"

    fun sessionPath(id: String) = "$BP_SESSION_PREFIX$id"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
}
