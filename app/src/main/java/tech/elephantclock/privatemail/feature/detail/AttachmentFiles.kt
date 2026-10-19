package tech.elephantclock.privatemail.feature.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Attachments live in `cacheDir/attachments/<message id>/` (never backed up, cleared by the
 * system when space runs low) and are shared read-only through a FileProvider.
 */
object AttachmentFiles {
    const val DIR = "attachments"

    /** MIME types never handed to another app: an installer from an email is never what the user wants. */
    private val refused =
        setOf(
            "application/vnd.android.package-archive",
            "application/x-apk",
            "application/vnd.apple.installer+xml",
        )

    private const val MAX_NAME = 80
    private const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L

    fun dirFor(
        cacheDir: File,
        messageId: Long,
    ): File = File(File(cacheDir, DIR), messageId.toString())

    /**
     * A file name that is safe on disk: no directories ("../"), no control or reserved
     * characters, no leading dot, at most [MAX_NAME] characters, keeping the extension.
     */
    fun safeName(
        name: String?,
        fallback: String = "attachment",
    ): String {
        val base = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val cleaned =
            base
                .map { c -> if (c.isLetterOrDigit() || c in "._- ()") c else '_' }
                .joinToString("")
                .trim()
                .trimStart('.', ' ')
        if (cleaned.isEmpty() || cleaned.all { it == '_' || it == '.' }) return fallback
        if (cleaned.length <= MAX_NAME) return cleaned
        val ext = cleaned.substringAfterLast('.', "").take(10)
        val stem = cleaned.take(MAX_NAME - ext.length - 1)
        return if (ext.isEmpty()) cleaned.take(MAX_NAME) else "$stem.$ext"
    }

    fun isRefused(mimeType: String): Boolean = mimeType.lowercase() in refused || mimeType.lowercase().endsWith("+apk")

    /** Removes downloaded attachments older than a day, so the cache doesn't keep mail around. */
    fun pruneOld(
        cacheDir: File,
        now: Long = System.currentTimeMillis(),
    ) {
        File(cacheDir, DIR).listFiles()?.forEach { dir ->
            if (now - dir.lastModified() > MAX_AGE_MILLIS) dir.deleteRecursively()
        }
    }

    /** Opens [file] in another app, read-only. False when no installed app handles [mimeType]. */
    fun open(
        context: Context,
        file: File,
        mimeType: String,
    ): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType.lowercase())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
