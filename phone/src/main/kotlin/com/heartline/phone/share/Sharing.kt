package com.heartline.phone.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** File names the user types, made safe for every file system (and kept readable). */
object FileNames {
    private val forbidden = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    const val MAX_LENGTH = 80

    /** Turns characters no file system accepts into spaces, collapses spaces, keeps letters in any script. */
    fun sanitize(raw: String, extension: String, fallback: String): String {
        val base = raw.trim()
            .removeSuffix(".$extension")
            .replace(forbidden, " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .take(MAX_LENGTH)
            .ifEmpty { fallback }
        return "$base.$extension"
    }

    /** e.g. Heartline_ECG_Sara-Karimi_2026-09-24_14-32 */
    fun default(kind: String, person: String?, at: LocalDateTime): String {
        val who = person?.trim()?.replace(Regex("\\s+"), "-")?.replace(forbidden, "")?.takeIf { it.isNotEmpty() }
        return listOfNotNull("Heartline", kind, who, at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))).joinToString("_")
    }
}

/** An AI assistant app that can receive a share, and what it accepts. */
data class AiTarget(val packageName: String, val label: String, val icon: Drawable?, val acceptsPdf: Boolean)

/**
 * "Share with AI" targets. A button is offered only for apps that are installed and actually
 * accept ACTION_SEND (checked at run time), so an app without a share target never appears.
 */
object AiShare {
    val CANDIDATES = listOf(
        "com.anthropic.claude",
        "com.openai.chatgpt",
        "com.google.android.apps.bard",
        "ai.x.grok",
    )

    fun available(context: Context, packages: List<String> = CANDIDATES): List<AiTarget> {
        val pm = context.packageManager
        return packages.mapNotNull { pkg ->
            val acceptsText = resolves(pm, pkg, "text/plain")
            val acceptsPdf = resolves(pm, pkg, "application/pdf")
            if (!acceptsText && !acceptsPdf) return@mapNotNull null
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            AiTarget(pkg, pm.getApplicationLabel(info).toString(), runCatching { pm.getApplicationIcon(pkg) }.getOrNull(), acceptsPdf)
        }
    }

    private fun resolves(pm: PackageManager, pkg: String, mime: String): Boolean =
        pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType(mime).setPackage(pkg), 0).isNotEmpty()

    /** Opens [target] with [text] (the results plus the user's prompt) and, if it takes PDFs, the report. */
    fun intent(context: Context, target: AiTarget, text: String, pdf: File?): Intent {
        val intent = Intent(Intent.ACTION_SEND).setPackage(target.packageName).putExtra(Intent.EXTRA_TEXT, text)
        if (pdf != null && target.acceptsPdf) {
            val uri = uriFor(context, pdf)
            intent.setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(pdf.name, uri)
        } else {
            intent.setType("text/plain")
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
}
