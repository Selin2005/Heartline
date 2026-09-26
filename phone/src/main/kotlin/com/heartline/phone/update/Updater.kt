// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.heartline.shared.update.Release
import com.heartline.shared.update.Releases
import java.io.File
import java.io.IOException

/**
 * Finds, downloads, verifies and installs updates from GitHub releases. Only the GitHub build
 * has it enabled ([enabled]); on Google Play the store updates the app.
 */
class Updater(
    private val context: Context,
    private val source: ReleaseSource,
    private val repository: UpdateRepository,
    private val installedVersion: String,
    val enabled: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Check {
        data class Available(val release: Release) : Check

        /** [latest] is the newest release of the chosen channels (to compare the watch against). */
        data class UpToDate(val latest: Release?) : Check

        data class Failed(val message: String) : Check
    }

    suspend fun check(): Check {
        if (!enabled) return Check.UpToDate(null)
        return runCatching {
            val prefs = repository.current()
            val releases = source.releases()
            repository.markChecked(now())
            val update = Releases.update(releases, installedVersion, prefs.beta)
            Log.i(TAG, "checked ${releases.size} releases; installed=$installedVersion beta=${prefs.beta} update=${update?.version}")
            if (update != null) Check.Available(update) else Check.UpToDate(Releases.newest(releases, prefs.beta))
        }.getOrElse {
            Log.w(TAG, "update check failed", it)
            Check.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    /**
     * Downloads the phone APK of [release] and checks it against the release's SHA256SUMS.
     * Refuses to return a file it can't verify.
     */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): File {
        val apk = release.phoneApk ?: throw IOException("This release has no phone APK")
        val sums = release.checksums ?: throw IOException("This release has no checksums, so it can't be verified")
        val expected = Releases.parseChecksums(source.text(sums.url))[apk.name] ?: throw IOException("No checksum for ${apk.name}")
        val dir = File(context.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, apk.name)
        val actual = source.download(apk.url, file, onProgress)
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw IOException("Checksum mismatch: the download is damaged or not an official release")
        }
        Log.i(TAG, "downloaded and verified ${apk.name}")
        return file
    }

    /** Android asks once per app before it may install others ("Install unknown apps"). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun allowInstallIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the verified APK to the system installer, which asks the user to confirm. */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("heartline.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    private companion object {
        const val TAG = "Heartline/Update"
    }
}
