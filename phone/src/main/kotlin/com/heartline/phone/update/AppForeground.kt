// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Whether Heartline is on screen. Android 10+ doesn't let an app in the background open a screen
 * (the installer's confirmation), so then the update is handed over with a notification instead.
 */
object AppForeground {
    @VisibleForTesting
    var override: Boolean? = null

    fun now(): Boolean = override ?: runCatching {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }.getOrDefault(false)
}
