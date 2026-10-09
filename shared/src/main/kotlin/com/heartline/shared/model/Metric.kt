// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.model

/** Every measurement the product offers; used for capability gating and UI. */
enum class Metric {
    ECG,
    BLOOD_PRESSURE,
    HEART_RATE,
    SPO2,
    SKIN_TEMPERATURE,
    BODY_COMPOSITION,
    STRESS;

    /**
     * Measured from the phone: the phone runs the whole measurement while the watch shows it too.
     * ECG and body composition need fingers on the watch's keys, so they start on the watch.
     */
    val measuresOnPhone: Boolean get() = this != ECG && this != BODY_COMPOSITION
}
