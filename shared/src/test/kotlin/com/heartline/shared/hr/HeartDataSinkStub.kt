// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.hr

import com.heartline.shared.sync.HeartDataSink

class HeartDataSinkStub : HeartDataSink {
    val batches = mutableListOf<HrBatch>()
    val alerts = mutableListOf<HealthAlert>()

    override suspend fun saveBatch(batch: HrBatch) {
        batches += batch
    }

    override suspend fun saveAlert(alert: HealthAlert) {
        alerts += alert
    }
}
