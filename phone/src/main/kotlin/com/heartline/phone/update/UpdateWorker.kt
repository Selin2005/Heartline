// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.heartline.phone.notify.PhoneNotifier
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** Once a day (with a network), looks for a new release and announces each new version once. */
class UpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val updater: Updater by inject()
    private val repository: UpdateRepository by inject()
    private val notifier: PhoneNotifier by inject()

    override suspend fun doWork(): Result {
        if (!updater.enabled || !repository.current().autoCheck) return Result.success()
        val check = updater.check()
        if (check is Updater.Check.Available) {
            val version = check.release.version.toString()
            if (repository.current().notifiedVersion != version) {
                notifier.updateAvailable(version, check.release.isBeta)
                repository.markNotified(version)
            }
        }
        return if (check is Updater.Check.Failed) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "update-check"

        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
