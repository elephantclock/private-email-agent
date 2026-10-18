package tech.elephantclock.privatemail.feature.discarded

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/** What happened to an unsubscribe request. */
enum class UnsubscribeOutcome {
    /** The sender's server accepted it (a 2xx answer, or the SMTP server took the email). */
    DONE,

    /** The server answered, but not with success (4xx, 5xx, a redirect we don't follow). */
    REFUSED,

    /** Nothing was sent (not HTTPS, no connection, the approval was refused). */
    FAILED,

    /** The connection dropped mid-send: the request may or may not have arrived. */
    UNKNOWN,
}

/**
 * RFC 8058 one-click unsubscribe: exactly one HTTPS POST with the body
 * `List-Unsubscribe=One-Click`. Nothing else, on purpose:
 * - **https only**, and **no redirects** at all: a 3xx is [UnsubscribeOutcome.REFUSED] (so an
 *   http redirect target is never contacted, and a POST is never replayed somewhere else);
 * - **no cookies, credentials, referrer or device details**: caches off, no cookie handler is
 *   consulted (none is installed in the app; the header is never set), a fixed neutral
 *   User-Agent instead of Android's default (which names the device model);
 * - connect and read **timeouts**, the response body is read at most [maxResponseBytes] and
 *   thrown away. Never opens a browser.
 */
class OneClickUnsubscriber(
    private val socketFactory: SSLSocketFactory? = null,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
    private val maxResponseBytes: Int = 16 * 1024,
) {
    fun post(url: String): UnsubscribeOutcome {
        if (ListUnsubscribe.httpsUrl(url) == null) return UnsubscribeOutcome.FAILED
        val conn =
            try {
                URL(url).openConnection() as? HttpsURLConnection ?: return UnsubscribeOutcome.FAILED
            } catch (_: IOException) {
                return UnsubscribeOutcome.FAILED
            }
        var sent = false
        return try {
            socketFactory?.let { conn.sslSocketFactory = it }
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.allowUserInteraction = false
            conn.connectTimeout = connectTimeoutMillis
            conn.readTimeout = readTimeoutMillis
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            val body = BODY.toByteArray(Charsets.US_ASCII)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            sent = true
            val code = conn.responseCode
            drain(conn, code)
            if (code in 200..299) UnsubscribeOutcome.DONE else UnsubscribeOutcome.REFUSED
        } catch (_: IOException) {
            if (sent) UnsubscribeOutcome.UNKNOWN else UnsubscribeOutcome.FAILED
        } finally {
            conn.disconnect()
        }
    }

    private fun drain(
        conn: HttpURLConnection,
        code: Int,
    ) {
        val stream = runCatching { if (code < 400) conn.inputStream else conn.errorStream }.getOrNull() ?: return
        stream.use {
            val buf = ByteArray(4096)
            var total = 0
            while (total < maxResponseBytes) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
            }
        }
    }

    companion object {
        const val BODY = "List-Unsubscribe=One-Click"

        /** The same for every user and device. */
        const val USER_AGENT = "PrivateEmailAgent-Unsubscribe/1"
    }
}
