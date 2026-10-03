// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.heartline.datalayer.diag.HLog
import com.heartline.phone.notify.PhoneNotifier
import com.heartline.shared.update.GitHubAsset
import com.heartline.shared.update.Release
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloads an update apart from the Updates screen (docs/RELEASING.md, "In-app updates"): it
 * keeps going after leaving the screen or the app, as a foreground job with a progress
 * notification; it waits for a network and continues the file where it stopped. When the file is
 * verified it installs right away if Heartline is on screen, or else posts "ready, tap to install".
 */
class UpdateDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val updater: Updater by inject()
    private val notifier: PhoneNotifier by inject()

    override suspend fun doWork(): Result {
        val version = inputData.getString(KEY_VERSION) ?: return Result.failure()
        val apk = GitHubAsset(inputData.getString(KEY_APK_NAME) ?: return Result.failure(), inputData.getLong(KEY_APK_SIZE, 0), inputData.getString(KEY_APK_URL) ?: return Result.failure())
        val sums = GitHubAsset(SUMS, url = inputData.getString(KEY_SUMS_URL) ?: return Result.failure())
        HLog.i(TAG, "download $version: attempt ${runAttemptCount + 1}")
        val done = AtomicLong(0)
        val total = AtomicLong(apk.size)
        foreground(version, 0, total.get())
        val file = try {
            coroutineScope {
                val reporter = launch {
                    while (true) {
                        delay(PROGRESS_MS)
                        setProgress(workDataOf(KEY_DONE to done.get(), KEY_TOTAL to total.get()))
                        foreground(version, done.get(), total.get())
                    }
                }
                try {
                    updater.download(version, apk, sums) { d, t ->
                        done.set(d)
                        if (t > 0) total.set(t)
                    }
                } finally {
                    reporter.cancel()
                }
            }
        } catch (e: Updater.ChecksumMismatch) {
            return failed(version, e)
        } catch (e: IOException) {
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) return failed(version, e)
            HLog.w(TAG, "download $version stopped at ${done.get()} of ${total.get()} bytes; continuing when connected", e)
            return Result.retry()
        }
        handOver(version, file)
        return Result.success(workDataOf(KEY_VERSION to version))
    }

    /** On screen: straight to the installer. In the background: a notification to tap. */
    private suspend fun handOver(version: String, file: java.io.File) {
        if (AppForeground.now()) {
            HLog.i(TAG, "$version ready: Heartline is on screen, installing")
            runCatching { updater.install(file) }.onFailure {
                HLog.w(TAG, "install of $version failed to start", it)
                notifier.updateReady(version)
            }
        } else {
            HLog.i(TAG, "$version ready: Heartline is in the background, notifying")
            notifier.updateReady(version)
        }
    }

    private fun failed(version: String, e: Exception): Result {
        HLog.w(TAG, "download $version failed", e)
        return Result.failure(workDataOf(KEY_VERSION to version, KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
    }

    /** False once Android refused the foreground service: the download carries on without it. */
    private var foregroundAllowed = true

    private suspend fun foreground(version: String, done: Long, total: Long) {
        if (!foregroundAllowed) return
        val notification = notifier.updateDownloading(version, done, total, WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
        val info = if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(PhoneNotifier.UPDATE_PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PhoneNotifier.UPDATE_PROGRESS_ID, notification)
        }
        // Android 12+ refuses a foreground service started from the background (after waiting for a
        // network). The download then runs as ordinary work; if stopped, it continues next time.
        runCatching { setForeground(info) }.onFailure {
            foregroundAllowed = false
            HLog.w(TAG, "no foreground service: ${it.javaClass.simpleName}")
        }
    }

    /** What the Updates screen shows of the download. */
    sealed interface State {
        val version: String

        data class Running(override val version: String, val done: Long, val total: Long) : State {
            val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        }

        /** Queued: starting, or ([retrying]) waiting for a network to continue after one dropped. */
        data class Waiting(override val version: String, val retrying: Boolean = false) : State

        data class Failed(override val version: String, val message: String) : State
    }

    companion object {
        const val NAME = "update-download"
        private const val TAG = "Heartline/Update"
        private const val SUMS = "SHA256SUMS"
        private const val PROGRESS_MS = 500L
        private const val MAX_ATTEMPTS = 8
        const val KEY_VERSION = "version"
        const val KEY_APK_NAME = "apk_name"
        const val KEY_APK_URL = "apk_url"
        const val KEY_APK_SIZE = "apk_size"
        const val KEY_SUMS_URL = "sums_url"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val TAG_VERSION = "update-version:"

        /**
         * Starts (or keeps) the download of [release]. The same version already downloading is
         * kept; another version replaces it.
         */
        suspend fun start(context: Context, release: Release) {
            val apk = release.phoneApk ?: throw IOException("This release has no phone APK")
            val sums = release.checksums ?: throw IOException("This release has no checksums, so it can't be verified")
            val version = release.version.toString()
            val work = WorkManager.getInstance(context)
            val running = work.getWorkInfosForUniqueWorkFlow(NAME).first().any { !it.state.isFinished && "$TAG_VERSION$version" in it.tags }
            val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_VERSION to version,
                        KEY_APK_NAME to apk.name,
                        KEY_APK_URL to apk.url,
                        KEY_APK_SIZE to apk.size,
                        KEY_SUMS_URL to sums.url,
                    ),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("$TAG_VERSION$version")
                .build()
            HLog.i(TAG, "download $version requested (${if (running) "already running" else "starting"})")
            work.enqueueUniqueWork(NAME, if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            HLog.i(TAG, "download cancelled")
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        /** The download's state, or null when none is running or it finished. */
        fun state(context: Context): Flow<State?> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME).map { infos ->
            infos.lastOrNull()?.let(::stateOf)
        }

        internal fun stateOf(info: WorkInfo): State? {
            val version = info.tags.firstOrNull { it.startsWith(TAG_VERSION) }?.removePrefix(TAG_VERSION) ?: return null
            return when (info.state) {
                WorkInfo.State.RUNNING -> State.Running(version, info.progress.getLong(KEY_DONE, 0), info.progress.getLong(KEY_TOTAL, 0))
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> State.Waiting(version, retrying = info.runAttemptCount > 0)
                WorkInfo.State.FAILED -> State.Failed(version, info.outputData.getString(KEY_ERROR) ?: "")
                WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> null
            }
        }
    }
}
