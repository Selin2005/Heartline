// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.heartline.phone.notify.PhoneNotifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** The background download: hand-over when done, waiting on a dropped network, and the screen's states. */
@RunWith(AndroidJUnit4::class)
class UpdateDownloadWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bytes = ByteArray(40_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val repository = UpdateRepository(context, "1.2.0")
    private var failWith: IOException? = null

    private val source = object : ReleaseSource("test") {
        override suspend fun text(url: String) = "$sha  Heartline-phone-1.3.0.apk\n"

        override suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit): String {
            failWith?.let { throw it }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
            return sha
        }
    }

    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before
    fun setUp() = runBlocking {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        startKoin {
            modules(
                module {
                    single { Updater(context, source, repository, "1.2.0", enabled = true) }
                    single { PhoneNotifier(context) }
                },
            )
        }
        repository.clearReady()
        File(context.filesDir, "updates").deleteRecursively()
        Unit
    }

    @After
    fun tearDown() {
        stopKoin()
        AppForeground.override = null
    }

    private fun worker(attempt: Int = 0) = TestListenableWorkerBuilder<UpdateDownloadWorker>(context)
        .setRunAttemptCount(attempt)
        .setInputData(
            workDataOf(
                UpdateDownloadWorker.KEY_VERSION to "1.3.0",
                UpdateDownloadWorker.KEY_APK_NAME to "Heartline-phone-1.3.0.apk",
                UpdateDownloadWorker.KEY_APK_URL to "https://example.invalid/apk",
                UpdateDownloadWorker.KEY_APK_SIZE to bytes.size.toLong(),
                UpdateDownloadWorker.KEY_SUMS_URL to "https://example.invalid/sums",
            ),
        )
        .build()

    @Test
    fun finishedInTheBackgroundItNotifiesReadyToInstall() = runBlocking {
        AppForeground.override = false
        val result = worker().doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("1.3.0", repository.current().ready?.version)
        val ready = notifications.allNotifications.single { shadowOf(it).contentTitle.toString().contains("1.3.0 is ready") }
        assertNotNull(ready.contentIntent)
    }

    @Test
    fun aDroppedConnectionWaitsAndTriesAgain() = runBlocking {
        failWith = IOException("connection reset")
        assertTrue(worker().doWork() is ListenableWorker.Result.Retry)
        assertNull(repository.current().ready)
        // After many tries it gives up and says why.
        val last = worker(attempt = 7).doWork()
        assertTrue(last is ListenableWorker.Result.Failure)
        assertEquals("connection reset", last.outputData.getString(UpdateDownloadWorker.KEY_ERROR))
    }

    @Test
    fun theScreenSeesProgressWaitingAndFailure() {
        fun info(state: WorkInfo.State, attempts: Int = 0, progress: androidx.work.Data = workDataOf(), output: androidx.work.Data = workDataOf()) =
            WorkInfo(UUID.randomUUID(), state, setOf("update-version:1.3.0"), output, progress, attempts)
        assertEquals(
            UpdateDownloadWorker.State.Running("1.3.0", 10, 40),
            UpdateDownloadWorker.stateOf(info(WorkInfo.State.RUNNING, progress = workDataOf(UpdateDownloadWorker.KEY_DONE to 10L, UpdateDownloadWorker.KEY_TOTAL to 40L))),
        )
        assertEquals(UpdateDownloadWorker.State.Waiting("1.3.0", retrying = false), UpdateDownloadWorker.stateOf(info(WorkInfo.State.ENQUEUED)))
        assertEquals(UpdateDownloadWorker.State.Waiting("1.3.0", retrying = true), UpdateDownloadWorker.stateOf(info(WorkInfo.State.ENQUEUED, attempts = 2)))
        assertEquals(
            UpdateDownloadWorker.State.Failed("1.3.0", "HTTP 404"),
            UpdateDownloadWorker.stateOf(info(WorkInfo.State.FAILED, output = workDataOf(UpdateDownloadWorker.KEY_ERROR to "HTTP 404"))),
        )
        assertNull(UpdateDownloadWorker.stateOf(info(WorkInfo.State.SUCCEEDED)))
    }

    @Test
    fun inTheBackgroundTheInstallerAsksWithANotification() {
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        InstallResultReceiver.handle(context, PackageInstaller.STATUS_PENDING_USER_ACTION, "", confirm, foreground = false, notifier = PhoneNotifier(context))
        // Android would refuse to open the confirmation from the background: nothing is started…
        assertNull(shadowOf(context as Application).nextStartedActivity)
        // …and the notification opens it instead.
        val asked = notifications.getNotification(PhoneNotifier.UPDATE_READY_ID)
        assertEquals("Finish installing the update", shadowOf(asked).contentTitle.toString())
    }

    @Test
    fun onScreenTheInstallerAsksDirectly() {
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        InstallResultReceiver.handle(context, PackageInstaller.STATUS_PENDING_USER_ACTION, "", confirm, foreground = true, notifier = PhoneNotifier(context))
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", shadowOf(context as Application).nextStartedActivity?.action)
    }

    @Test
    fun aFailedInstallIsNotifiedAndTheDownloadKept() {
        InstallResultReceiver.handle(context, PackageInstaller.STATUS_FAILURE_STORAGE, "no space", null, foreground = false, notifier = PhoneNotifier(context))
        assertEquals("The update wasn't installed", shadowOf(notifications.getNotification(PhoneNotifier.UPDATE_READY_ID)).contentTitle.toString())
    }
}
