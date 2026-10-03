// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import com.heartline.datalayer.diag.HLog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.heartline.phone.notify.PhoneNotifier

/**
 * Receives the installer's result. When it asks the user to confirm, the confirmation opens
 * directly while Heartline is on screen; in the background Android would silently refuse that, so
 * a notification opens it instead. A failure is notified too; the downloaded file is kept.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        handle(context, status, message, confirmOf(intent), AppForeground.now(), PhoneNotifier(context))
    }

    companion object {
        private const val TAG = "Heartline/Update"

        private fun confirmOf(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

        internal fun handle(context: Context, status: Int, message: String, confirm: Intent?, foreground: Boolean, notifier: PhoneNotifier) {
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    if (confirm == null) return
                    if (foreground) {
                        HLog.i(TAG, "install status=pending: asking on screen")
                        notifier.cancelUpdateReady()
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        HLog.i(TAG, "install status=pending: in the background, asking with a notification")
                        notifier.installConfirm(confirm)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> HLog.i(TAG, "install status=success")
                // The user chose Cancel in the system dialog: the update stays ready on the Updates screen.
                PackageInstaller.STATUS_FAILURE_ABORTED -> HLog.i(TAG, "install status=aborted $message")
                else -> {
                    HLog.w(TAG, "install status=$status $message")
                    notifier.installFailed()
                }
            }
        }
    }
}
