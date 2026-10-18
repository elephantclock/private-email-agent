package tech.elephantclock.privatemail.feature.discarded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.elephantclock.privatemail.core.model.Address

class ListUnsubscribeTest {
    private val both = "\r\n <https://lists.example/unsub?u=1>, <mailto:unsub@lists.example?subject=stop%20please>"
    private val dkimPass = "mx.ourco.example; spf=pass smtp.mailfrom=x.example; dkim=pass header.d=x.example"

    private fun headers(
        unsubscribe: String? = both,
        post: String? = "List-Unsubscribe=One-Click",
        auth: String? = dkimPass,
    ) = buildMap {
        unsubscribe?.let { put("list-unsubscribe", listOf(it)) }
        post?.let { put("list-unsubscribe-post", listOf(it)) }
        auth?.let { put("authentication-results", listOf(it)) }
    }

    @Test
    fun `one-click needs the post header, an https uri and a DKIM pass`() {
        assertEquals(
            UnsubscribeMethod.OneClick("https://lists.example/unsub?u=1"),
            ListUnsubscribe.methodFor(headers()),
        )
        val mailto = UnsubscribeMethod.Mailto(Address.of("unsub@lists.example"), "stop please", "unsubscribe")
        assertEquals(mailto, ListUnsubscribe.methodFor(headers(post = null)))
        assertEquals(mailto, ListUnsubscribe.methodFor(headers(post = "List-Unsubscribe=Maybe")))
        assertEquals(mailto, ListUnsubscribe.methodFor(headers(auth = null)))
        assertEquals(mailto, ListUnsubscribe.methodFor(headers(auth = "mx.ourco.example; spf=pass; dkim=none")))
        assertEquals(mailto, ListUnsubscribe.methodFor(headers(auth = "mx.ourco.example; spf=fail; dkim=pass")))
    }

    @Test
    fun `only the trusted Authentication-Results counts`() {
        val forgedOnTop = listOf("evil.example; dkim=pass", "mx.ourco.example; dkim=fail")
        val h = headers() + ("authentication-results" to forgedOnTop)
        assertEquals(UnsubscribeMethod.OneClick("https://lists.example/unsub?u=1"), ListUnsubscribe.methodFor(h))
        assertEquals(
            UnsubscribeMethod.Mailto(Address.of("unsub@lists.example"), "stop please", "unsubscribe"),
            ListUnsubscribe.methodFor(h, trustedAuthservIds = setOf("mx.ourco.example")),
        )
    }

    @Test
    fun `an https link without one-click and without mailto offers nothing`() {
        assertNull(ListUnsubscribe.methodFor(headers(unsubscribe = "<https://lists.example/u>", post = null)))
        assertNull(ListUnsubscribe.methodFor(headers(unsubscribe = null)))
        assertNull(ListUnsubscribe.methodFor(headers(unsubscribe = "https://lists.example/u (no brackets)")))
    }

    @Test
    fun `one-click refuses http, userinfo and non-web schemes`() {
        for (bad in listOf(
            "http://lists.example/u",
            "https://user:pw@lists.example/u",
            "javascript:alert(1)",
            "ftp://x.example/",
        )) {
            assertNull(bad, ListUnsubscribe.methodFor(headers(unsubscribe = "<$bad>")))
        }
    }

    @Test
    fun `mailto takes one plain address and decodes subject and body`() {
        assertEquals(
            UnsubscribeMethod.Mailto(Address.of("list+u1@lists.example"), "Unsubscribe 42", "please remove me"),
            ListUnsubscribe.mailto("mailto:List+u1@lists.example?Subject=Unsubscribe%2042&body=please%20remove%20me"),
        )
        assertEquals(
            UnsubscribeMethod.Mailto(Address.of("u@lists.example"), "unsubscribe", "unsubscribe"),
            ListUnsubscribe.mailto("MAILTO:u@lists.example"),
        )
        for (bad in listOf(
            "mailto:a@x.example,b@y.example",
            "mailto:not-an-address",
            "mailto:a@b@c.example",
            "mailto:a%0D%0ABcc:%20victim@x.example@y.example",
            "mailto:a@localhost",
            "mailto:%ZZ@x.example",
            "mailto:",
        )) {
            assertNull(bad, ListUnsubscribe.mailto(bad))
        }
    }

    @Test
    fun `a header-injection subject becomes one line`() {
        val m = ListUnsubscribe.mailto("mailto:u@lists.example?subject=hi%0D%0ABcc:%20victim@x.example")!!
        assertEquals("hi  Bcc: victim@x.example", m.subject)
    }
}
