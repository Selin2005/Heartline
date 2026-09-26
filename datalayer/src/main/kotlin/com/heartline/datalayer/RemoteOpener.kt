// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.datalayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.wear.remote.interactions.RemoteActivityHelper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Opens a Heartline screen on the other device right away (no notification to tap), via a
 * `heartline://` deep link the other app's MainActivity handles.
 */
class RemoteOpener(context: Context, private val transport: DataLayerTransport) {
    private val helper = RemoteActivityHelper(context.applicationContext, Executors.newSingleThreadExecutor())

    /** @return true when the other device accepted the launch. */
    suspend fun open(uri: String): Boolean {
        val node = transport.peerNodeId() ?: return false.also { Log.w(TAG, "open $uri: no peer") }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        return suspendCancellableCoroutine { cont ->
            val future = runCatching { helper.startRemoteActivity(intent, node) }.getOrElse {
                Log.w(TAG, "open $uri failed", it)
                cont.resume(false)
                return@suspendCancellableCoroutine
            }
            future.addListener({
                val ok = runCatching { future.get() }.onFailure { Log.w(TAG, "open $uri failed", it) }.isSuccess
                if (ok) Log.i(TAG, "opened $uri on $node")
                if (cont.isActive) cont.resume(ok)
            }, Runnable::run)
        }
    }

    private companion object {
        const val TAG = "Heartline/Open"
    }
}

/** Deep links understood by each app's MainActivity. */
object DeepLinks {
    const val SCHEME = "heartline"
    const val WATCH_HOST = "watch"
    const val PHONE_HOST = "phone"

    fun watch(route: String) = "$SCHEME://$WATCH_HOST/$route"

    fun phone(route: String) = "$SCHEME://$PHONE_HOST/$route"

    /** The route part of a heartline:// link for [host], or null for anything else. */
    fun route(uri: Uri?, host: String): String? =
        uri?.takeIf { it.scheme == SCHEME && it.host == host }?.let { u ->
            val path = u.path.orEmpty().trimStart('/')
            if (u.query.isNullOrEmpty()) path else "$path?${u.query}"
        }?.takeIf { it.isNotEmpty() }
}
