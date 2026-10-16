package tech.elephantclock.privatemail.core.mail.autoconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPresetsTest {
    @Test
    fun `lookup by email domain and by MX host`() {
        assertEquals("gmail", ProviderPresets.forDomain("gmail.com")?.id)
        assertEquals("icloud", ProviderPresets.forDomain("me.com")?.id)
        assertEquals("gmail", ProviderPresets.forMxHost("aspmx.l.google.com")?.id)
        assertEquals("microsoft", ProviderPresets.forMxHost("contoso-com.mail.protection.outlook.com")?.id)
        assertEquals("fastmail", ProviderPresets.forMxHost("in1-smtp.messagingengine.com")?.id)
        assertEquals("yahoo", ProviderPresets.forMxHost("mta5.am0.yahoodns.net")?.id)
        assertNull(ProviderPresets.forDomain("izi.travel"))
    }

    @Test
    fun `matching respects label boundaries`() {
        assertNull(ProviderPresets.forMxHost("mx.evilgoogle.com"))
        assertNull(ProviderPresets.forDomain("notgmail.com"))
        assertTrue(domainMatches("Mail.Example.COM.", "example.com"))
        assertFalse(domainMatches("example.com.evil", "example.com"))
    }

    @Test
    fun `notes for app passwords and Microsoft`() {
        listOf("gmail", "icloud", "yahoo", "fastmail").forEach { id ->
            assertTrue(id, ProviderPresets.all.first { it.id == id }.note is ProviderNote.AppPasswordRequired)
        }
        val ms = ProviderPresets.all.first { it.id == "microsoft" }
        assertTrue(ms.note is ProviderNote.MicrosoftPasswordBlocked)
        assertFalse(ms.passwordSupported)
        ProviderPresets.all.forEach { assertTrue(it.note?.helpUrl?.startsWith("https://") ?: true) }
    }
}
