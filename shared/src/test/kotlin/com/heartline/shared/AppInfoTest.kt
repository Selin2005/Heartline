// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoTest {
    @Test
    fun protocolVersionIsOne() {
        assertEquals(1, AppInfo.PROTOCOL_VERSION)
    }
}
