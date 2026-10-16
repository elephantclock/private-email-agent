package tech.elephantclock.privatemail.core.mail.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64

class SnippetDecoderTest {
    @Test
    fun `cut multibyte character at the end is dropped`() {
        val bytes = "Привет".toByteArray().let { it.copyOf(it.size - 1) }
        assertEquals("Приве", SnippetDecoder.decode(bytes, "8bit", "utf-8", html = false, complete = false))
    }

    @Test
    fun `base64 cut mid-quantum and with line breaks`() {
        val encoded = Base64.getMimeEncoder().encodeToString("Hello world, this is long".toByteArray())
        val cut = encoded.substring(0, encoded.length - 3).toByteArray()
        val text = SnippetDecoder.decode(cut, "BASE64", "us-ascii", html = false, complete = false)
        assertEquals(true, "Hello world, this is long".startsWith(text))
    }

    @Test
    fun `quoted-printable cut inside an escape`() {
        assertEquals(
            "caf",
            SnippetDecoder.decode("caf=C".toByteArray(), "quoted-printable", "utf-8", false, complete = false),
        )
        assertEquals(
            "café",
            SnippetDecoder.decode("caf=C3=A9".toByteArray(), "quoted-printable", "utf-8", false, complete = true),
        )
    }

    @Test
    fun `unknown charset falls back instead of failing`() {
        assertEquals("abc", SnippetDecoder.decode("abc".toByteArray(), null, "x-made-up", false, true))
    }

    @Test
    fun `html to text keeps words and breaks, drops styles and scripts`() {
        val html = "<style>.a{}</style><script>evil()</script><p>Hi&nbsp;there</p><div>Line&amp;two<br>three</div>"
        val text = SnippetDecoder.htmlToText(html)
        assertEquals("Hi there\nLine&two\nthree", text)
        assertFalse(text.contains("evil"))
    }
}
