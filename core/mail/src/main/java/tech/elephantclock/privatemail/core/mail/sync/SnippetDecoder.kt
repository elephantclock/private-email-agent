package tech.elephantclock.privatemail.core.mail.sync

import jakarta.mail.internet.MimeUtility
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Turns the first bytes of a body part (cut at an arbitrary byte) into text: undoes the
 * transfer encoding, decodes the charset leniently and drops a half character at the end.
 */
object SnippetDecoder {
    fun decode(
        raw: ByteArray,
        encoding: String?,
        charset: String?,
        html: Boolean,
        complete: Boolean,
    ): String {
        val bytes =
            when (encoding?.lowercase()) {
                "base64" -> base64(raw)
                "quoted-printable" -> quotedPrintable(raw, complete)
                else -> raw
            }
        val text = decodeCharset(bytes, charset, complete)
        return if (html) htmlToText(text) else text.replace("\r\n", "\n")
    }

    private fun base64(raw: ByteArray): ByteArray {
        val clean =
            raw
                .filter {
                    it.toInt().toChar().let { c ->
                        c.isLetterOrDigit() || c == '+' || c == '/'
                    }
                }.toByteArray()
        return Base64.getDecoder().decode(clean.copyOf(clean.size - clean.size % 4))
    }

    private fun quotedPrintable(
        raw: ByteArray,
        complete: Boolean,
    ): ByteArray {
        // A cut can leave "=" or "=A" at the end; drop it rather than fail.
        var end = raw.size
        if (!complete) {
            val tail = raw.takeLast(2).map { it.toInt().toChar() }
            end -=
                when {
                    tail.lastOrNull() == '=' -> 1
                    tail.size == 2 && tail[0] == '=' -> 2
                    else -> 0
                }
        }
        return runCatching {
            MimeUtility.decode(raw.copyOf(end).inputStream(), "quoted-printable").readBytes()
        }.getOrDefault(raw)
    }

    private fun decodeCharset(
        bytes: ByteArray,
        charset: String?,
        complete: Boolean,
    ): String {
        val cs =
            runCatching { Charset.forName(MimeUtility.javaCharset(charset ?: "us-ascii")) }
                .getOrNull() ?: Charsets.ISO_8859_1
        val text =
            cs
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        return if (complete) text else text.trimEnd('�')
    }

    private val dropBlocks = Regex("(?is)<(script|style|head)[^>]*>.*?(</\\1>|$)")
    private val breaks = Regex("(?i)<\\s*(br|/p|/div|/li|/tr|/h[1-6])[^>]*>")
    private val tags = Regex("<[^>]*>?")
    private val entities =
        mapOf(
            "&nbsp;" to " ",
            "&amp;" to "&",
            "&lt;" to "<",
            "&gt;" to ">",
            "&quot;" to "\"",
            "&#39;" to "'",
        )

    /** Rough HTML → text for previews only. The full body is rendered properly elsewhere. */
    fun htmlToText(html: String): String {
        var t = html.replace(dropBlocks, " ").replace(breaks, "\n").replace(tags, " ")
        entities.forEach { (k, v) -> t = t.replace(k, v, ignoreCase = true) }
        return t
            .lines()
            .map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
