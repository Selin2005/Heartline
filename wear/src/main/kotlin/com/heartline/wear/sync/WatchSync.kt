// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.heartline.datalayer.DataLayerTransport
import com.heartline.shared.diag.LogOffload
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.WatchSyncEngine
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.di.APP_SCOPE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** Pushes the outbox to the phone; retried with backoff until everything is acked. */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val engine: WatchSyncEngine by inject()
    private val store: WatchRecordStore by inject()
    private val logs: LogOffload by inject()

    override suspend fun doWork(): Result {
        engine.flush()
        // Log segments and raw sensor sessions that haven't reached the phone yet.
        logs.run()
        return if (store.pending().isEmpty() && !logs.stalled) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "heartline-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Receives acks and delete requests from the phone. */
class WatchSyncService : WearableListenerService() {
    private val engine: WatchSyncEngine by inject()
    private val transport: DataLayerTransport by inject()
    private val scope: CoroutineScope by inject(APP_SCOPE)

    override fun onMessageReceived(event: MessageEvent) {
        val envelope = Envelope(event.path, event.data)
        scope.launch {
            transport.deliver(envelope)
            engine.handle(envelope)
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        scope.launch { engine.handle(Envelope(channel.path, transport.readChannel(channel))) }
    }
}
