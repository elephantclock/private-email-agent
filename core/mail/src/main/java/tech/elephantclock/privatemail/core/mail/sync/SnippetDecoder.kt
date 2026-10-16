package tech.elephantclock.privatemail.core.mail.sync

import jakarta.mail.internet.MimeUtility
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Turns the first bytes of a body part (cut at an arbitrary byte) into text: undoes the
 * transfer encoding, decodes the charset leniently and drops a half character at the end.
 * Input is untrusted; callers still treat any exception as "no preview".
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

    private fun isBase64Byte(b: Byte): Boolean {
        // Compare byte values: a high byte such as 0xC3 must never pass as a "letter".
        val c = b.toInt()
        return c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '+'.code ||
            c == '/'.code
    }

    private fun base64(raw: ByteArray): ByteArray {
        val clean = raw.filter(::isBase64Byte).toByteArray()
        // Padding was filtered out and the decoder doesn't need it; a single leftover character carries no full byte.
        return Base64.getDecoder().decode(if (clean.size % 4 == 1) clean.copyOf(clean.size - 1) else clean)
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
        // Decide "undeclared" from the raw parameter: MimeUtility.javaCharset maps us-ascii to ISO-8859-1.
        val name = charset?.trim()?.lowercase()
        val undeclared = name == null || name == "us-ascii" || name == "ascii"
        val text =
            if (undeclared && bytes.any { it < 0 }) {
                // Undeclared (or wrongly "ASCII") 8-bit text: most likely UTF-8, else Windows-1252.
                strictUtf8(bytes, complete) ?: lenient(bytes, Charset.forName("windows-1252"))
            } else {
                val declared = name?.let { runCatching { Charset.forName(MimeUtility.javaCharset(it)) }.getOrNull() }
                lenient(bytes, declared ?: Charsets.ISO_8859_1)
            }
        return if (complete) text else text.trimEnd('�')
    }

    private fun lenient(
        bytes: ByteArray,
        cs: Charset,
    ): String =
        cs
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    /** Strict UTF-8; when the text was cut, up to 3 trailing bytes of a split character may be dropped. */
    private fun strictUtf8(
        bytes: ByteArray,
        complete: Boolean,
    ): String? =
        (0..(if (complete) 0 else 3)).firstNotNullOfOrNull { drop ->
            runCatching {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, (bytes.size - drop).coerceAtLeast(0)))
                    .toString()
            }.getOrNull()
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
