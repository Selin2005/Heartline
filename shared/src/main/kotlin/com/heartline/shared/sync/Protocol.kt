package com.heartline.shared.sync

import kotlinx.serialization.json.Json

/** Wearable Data Layer contract v1 (MASTER_PLAN §4.6). */
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
    const val ALERT = "$ROOT/alert"
    const val OPEN = "$ROOT/open"

    /** Capability name both apps advertise so each can find the other node. */
    const val CAPABILITY_PHONE = "heartline_phone"
    const val CAPABILITY_WATCH = "heartline_watch"

    fun wavePath(id: String) = "$RECORD_WAVE_PREFIX$id"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
}
