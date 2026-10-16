package tech.elephantclock.privatemail.core.mail.autoconfig

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * GET over HTTPS only, for autoconfig XML. Plain http is refused, including on redirects,
 * because a tampered config could send the password to an attacker's server.
 */
class HttpsFetcher(
    private val socketFactory: SSLSocketFactory? = null,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
    private val maxBytes: Int = 64 * 1024,
    private val maxRedirects: Int = 3,
) {
    /** Body of a 200 response, or null for anything else (404, timeout, not HTTPS, too large). */
    fun get(url: String): String? {
        var current = url
        repeat(maxRedirects + 1) {
            if (!current.startsWith("https://")) return null
            val conn =
                try {
                    URL(current).openConnection() as HttpsURLConnection
                } catch (_: IOException) {
                    return null
                }
            try {
                socketFactory?.let { conn.sslSocketFactory = it }
                conn.instanceFollowRedirects = false
                conn.connectTimeout = connectTimeoutMillis
                conn.readTimeout = readTimeoutMillis
                conn.setRequestProperty("Accept", "application/xml, text/xml")
                when (conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        return readCapped(conn)
                    }

                    in 301..308 -> {
                        current =
                            conn.getHeaderField("Location")?.let { URL(URL(current), it).toString() } ?: return null
                    }

                    else -> {
                        return null
                    }
                }
            } catch (_: IOException) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun readCapped(conn: HttpURLConnection): String? {
        val out = ByteArrayOutputStream()
        conn.inputStream.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > maxBytes) return null
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
