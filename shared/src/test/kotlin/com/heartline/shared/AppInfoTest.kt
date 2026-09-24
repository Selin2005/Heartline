package com.heartline.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoTest {
    @Test
    fun protocolVersionIsOne() {
        assertEquals(1, AppInfo.PROTOCOL_VERSION)
    }
}
