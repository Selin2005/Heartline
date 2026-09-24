package com.heartline.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.share.AiShare
import com.heartline.phone.share.FileNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class SharingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun fileNamesAreSafeAndReadable() {
        assertEquals("My ECG.pdf", FileNames.sanitize("  My/ECG: ", "pdf", "x"))
        assertEquals("نوار قلب سارا.pdf", FileNames.sanitize("نوار قلب سارا.pdf", "pdf", "x"))
        assertEquals("fallback.pdf", FileNames.sanitize("???", "pdf", "fallback"))
        assertEquals(FileNames.MAX_LENGTH + 4, FileNames.sanitize("a".repeat(200), "pdf", "x").length)
        assertEquals(
            "Heartline_ECG_Sara-Karimi_2026-09-24_14-32",
            FileNames.default("ECG", "Sara Karimi", LocalDateTime.of(2026, 9, 24, 14, 32)),
        )
    }

    /** Registers [pkg] as an app whose share activity accepts [mimes]. */
    private fun install(pkg: String, vararg mimes: String) {
        val pm = shadowOf(context.packageManager)
        val component = ComponentName(pkg, "$pkg.Share")
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = pkg
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; name = pkg }
        })
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_SEND).apply { mimes.forEach { addDataType(it) } })
    }

    @Test
    fun onlyInstalledAppsThatAcceptSharesAreOffered() {
        install("com.openai.chatgpt", "text/plain", "application/pdf")
        install("com.anthropic.claude", "text/plain")
        // Installed but without a share target: never offered.
        shadowOf(context.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = "ai.x.grok"
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "ai.x.grok" }
        })
        val targets = AiShare.available(context)
        assertEquals(setOf("com.openai.chatgpt", "com.anthropic.claude"), targets.map { it.packageName }.toSet())
        assertTrue(targets.first { it.packageName == "com.openai.chatgpt" }.acceptsPdf)
        assertFalse(targets.first { it.packageName == "com.anthropic.claude" }.acceptsPdf)
    }

    @Test
    fun textOnlyAppsGetNoAttachment() {
        install("com.anthropic.claude", "text/plain")
        val target = AiShare.available(context).single()
        val pdf = File(context.cacheDir, "reports/x.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF") }
        val intent = AiShare.intent(context, target, "Explain my ECG", pdf, "application/pdf")
        assertEquals("text/plain", intent.type)
        assertEquals("com.anthropic.claude", intent.`package`)
        assertEquals("Explain my ECG", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
    }
}
