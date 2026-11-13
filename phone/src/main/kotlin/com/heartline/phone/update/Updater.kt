// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import com.heartline.datalayer.diag.HLog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.heartline.shared.update.AppVersion
import com.heartline.shared.update.GitHubAsset
import com.heartline.shared.update.Release
import com.heartline.shared.update.Releases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        /** [releases]: all published releases, to compare the watch's version against. */
        data class Available(val release: Release, val releases: List<Release> = emptyList()) : Check

        /** [latest] is the newest release of the chosen channels (to compare the watch against). */
        data class UpToDate(val latest: Release?, val releases: List<Release> = emptyList()) : Check

        data class Failed(val message: String) : Check
    }

    suspend fun check(): Check {
        if (!enabled) return Check.UpToDate(null)
        return runCatching {
            val prefs = repository.current()
            val releases = source.releases()
            repository.markChecked(now())
            val update = Releases.update(releases, installedVersion, prefs.track)
            HLog.i(TAG, "checked ${releases.size} releases; installed=$installedVersion track=${prefs.track} update=${update?.version}")
            if (update != null) Check.Available(update, releases) else Check.UpToDate(Releases.newest(releases, prefs.track), releases)
        }.getOrElse {
            // Leaving the screen cancels the check: that's not a failure.
            if (it is CancellationException) throw it
            HLog.w(TAG, "update check failed", it)
            Check.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    /**
     * Downloads the phone APK of [release] and checks it against the release's SHA256SUMS.
     * Refuses to return a file it can't verify.
     */
    suspend fun download(release: Release, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val apk = release.phoneApk ?: throw IOException("This release has no phone APK")
        val sums = release.checksums ?: throw IOException("This release has no checksums, so it can't be verified")
        return download(release.version.toString(), apk, sums, onProgress)
    }

    /**
     * Downloads [apk] of [version] into `files/updates`, where it survives leaving the screen, the
     * app being closed and the system clearing caches:
     * - an update already downloaded and verified is used again without downloading;
     * - a partial download (`<apk>.part`) continues from where it stopped;
     * - other versions' files are deleted.
     * The finished file is verified against [sums] and recorded as ready ([UpdateRepository.Ready]).
     */
    suspend fun download(version: String, apk: GitHubAsset, sums: GitHubAsset, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        readyFile(version)?.let { file ->
            HLog.i(TAG, "$version already downloaded and verified: no download")
            return file
        }
        val name = safeAssetName(apk.name)
        val expected = Releases.parseChecksums(source.text(sums.url))[name] ?: throw IOException("No checksum for $name")
        val dir = dir()
        val part = File(dir, "$name.part")
        dir.listFiles()?.filter { it != part }?.forEach { it.deleteRecursively() }
        repository.clearReady()
        val actual = source.download(apk.url, part, onProgress)
        if (!actual.equals(expected, ignoreCase = true)) {
            part.delete()
            HLog.w(TAG, "$name: checksum mismatch, deleted")
            throw ChecksumMismatch()
        }
        val file = File(dir, name)
        if (!part.renameTo(file)) throw IOException("Couldn't keep the download")
        repository.setReady(UpdateRepository.Ready(version, file.name, expected.lowercase()))
        HLog.i(TAG, "downloaded and verified $name: ready to install")
        return file
    }

    /**
     * The downloaded update, when there is one (of [version], if given) and its file still matches
     * the checksum it was verified with; otherwise null, and a damaged or missing file is forgotten.
     */
    suspend fun readyFile(version: String? = null): File? = withContext(Dispatchers.IO) {
        val ready = repository.current().ready ?: return@withContext null
        if (version != null && ready.version != version) return@withContext null
        val name = runCatching { safeAssetName(ready.file) }.getOrNull()
        val file = name?.let { File(dir(), it) }
        if (file != null && file.isFile && ReleaseSource.sha256(file).equals(ready.sha256, ignoreCase = true)) return@withContext file
        HLog.w(TAG, "ready update ${ready.version} is missing or damaged: downloading again")
        file?.delete()
        repository.clearReady()
        null
    }

    /** Called on every start: once the ready update (or a newer one) is installed, its file is deleted. */
    suspend fun cleanUp() = withContext(Dispatchers.IO) {
        val ready = repository.current().ready
        val installed = AppVersion.parse(installedVersion)
        val readyVersion = ready?.let { AppVersion.parse(it.version) }
        if (ready != null && (installed == null || readyVersion == null || installed < readyVersion)) return@withContext
        if (ready != null) {
            HLog.i(TAG, "update ${ready.version} installed: deleting its download")
            repository.clearReady()
        }
        // Also the old cache folder of versions before this one.
        File(context.cacheDir, "updates").deleteRecursively()
        if (ready != null) dir().deleteRecursively()
    }

    private fun dir() = File(context.filesDir, "updates").apply { mkdirs() }

    private fun safeAssetName(name: String): String {
        val clean = File(name).name
        if (clean.isEmpty() || clean == "." || clean == ".." || clean != name) {
            throw IOException("Invalid update asset name: $name")
        }
        return clean
    }

    class ChecksumMismatch : IOException("Checksum mismatch: the download is damaged or not an official release")

    class InvalidPackage(message: String) : IOException(message)

    /** Android asks once per app before it may install others ("Install unknown apps"). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun allowInstallIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the verified APK to the system installer, which asks the user to confirm. */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        verifyApkMatchesApp(apk)
        HLog.i(TAG, "installing ${apk.name}")
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

    @Suppress("DEPRECATION")
    private fun verifyApkMatchesApp(apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw InvalidPackage("Unreadable update package")
        if (archive.packageName != context.packageName) {
            throw InvalidPackage("Package name mismatch: ${archive.packageName}")
        }
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archiveSigs = signingBytes(archive)
        val installedSigs = signingBytes(installed)
        if (archiveSigs.isEmpty() || archiveSigs != installedSigs) {
            throw InvalidPackage("Signing certificate mismatch for update package")
        }
    }

    @Suppress("DEPRECATION")
    private fun signingBytes(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { it.toCharsString() }.toSet()
    }

    private companion object {
        const val TAG = "Heartline/Update"
    }
}
