// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.datalayer

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.diag.Redactor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HLogTest {
    @After
    fun tearDown() {
        HLog.setRedactor(Redactor.NONE)
    }

    @Test
    fun `redact masks sensitive profile fields when configured`() {
        HLog.setRedactor(Redactor(names = listOf("Alice", "Example"), birthDate = "1990-01-01"))
        val output = HLog.redact("Patient Alice Example birthDate=1990-01-01")
        assertFalse(output.contains("Alice"))
        assertFalse(output.contains("Example"))
        assertFalse(output.contains("1990-01-01"))
        assertTrue(output.isNotEmpty())
    }
}
