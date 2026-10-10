// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.link

import com.heartline.datalayer.diag.HLog
import android.content.Context
import com.heartline.datalayer.DeepLinks
import com.heartline.datalayer.RemoteOpener
import com.heartline.shared.sync.PhoneStatus
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.SetupRequest
import com.heartline.shared.sync.SetupTarget
import com.heartline.shared.sync.StampedStatus
import com.heartline.shared.sync.SyncTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/**
 * The last [PhoneStatus] the phone sent, kept across restarts so the watch can still open (offline)
 * once it has been set up with the phone at least once.
 */
class WatchLinkStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)
    private val cached: PhoneStatus? = prefs.getString(KEY_STATUS, null)?.let { runCatching { Protocol.json.decodeFromString<PhoneStatus>(it) }.getOrNull() }

    // The cached copy is stamped 0 so it never counts as a fresh reply to a new hello.
    private val mutable = MutableStateFlow(cached?.let { StampedStatus(it, 0L) })
    val latest: StateFlow<StampedStatus?> = mutable.asStateFlow()

    fun update(status: PhoneStatus) {
        HLog.i(TAG, "phone status: $status")
        prefs.edit().putString(KEY_STATUS, Protocol.json.encodeToString(status)).apply()
        mutable.value = StampedStatus(status, now())
    }

    /** True once the phone has confirmed a complete setup at least once. */
    val wasSetUp: Boolean get() = latest.value?.status?.setupComplete == true

    private companion object {
        const val TAG = "Heartline/Link"
        const val KEY_STATUS = "status"
    }
}

/** Whether a Heartline activity is on screen, so phone requests can navigate instead of notifying. */
object AppForeground {
    @Volatile
    var resumed: Boolean = false
}

/**
 * Screens the phone (or a notification) asks the app to open. A route waits here until the UI
 * takes it ([take]): one that arrives before the UI is listening (the activity was just
 * recreated) is kept, not dropped, and each is opened once.
 */
class WatchCommandBus {
    private val routes = MutableStateFlow<String?>(null)

    /** The route waiting to be opened, if any. */
    val pending: StateFlow<String?> = routes.asStateFlow()

    fun post(route: String) {
        HLog.i(TAG, "route queued: $route")
        routes.value = route
    }

    /** Takes [route] if it is still the one waiting (a newer one stays). */
    fun take(route: String) {
        routes.compareAndSet(route, null)
    }

    private companion object {
        const val TAG = "Heartline/Open"
    }
}

/** Sends the user to a screen on the phone: a direct launch, or a notification there as fallback. */
class PhoneOpener(private val opener: RemoteOpener, private val transport: SyncTransport) {
    suspend fun open(target: SetupTarget): Boolean {
        if (opener.open(DeepLinks.phone(target.phoneRoute))) return true
        return transport.send(Protocol.SETUP_REQUEST, Protocol.json.encodeToString(SetupRequest(target)).encodeToByteArray())
    }
}
