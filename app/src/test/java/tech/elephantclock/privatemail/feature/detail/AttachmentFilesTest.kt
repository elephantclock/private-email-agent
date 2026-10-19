package tech.elephantclock.privatemail.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentFilesTest {
    @Test
    fun `attacker-chosen names can't leave the attachment folder`() {
        assertEquals("passwd", AttachmentFiles.safeName("../../etc/passwd"))
        assertEquals("evil.txt", AttachmentFiles.safeName("..\\..\\evil.txt"))
        assertEquals("attachment", AttachmentFiles.safeName(".."))
        assertEquals("attachment", AttachmentFiles.safeName(null))
        assertEquals("hidden", AttachmentFiles.safeName(".hidden"))
        assertEquals("a_b_c.pdf", AttachmentFiles.safeName("a\u0000b\nc.pdf"))
        assertEquals("Счёт 12.pdf", AttachmentFiles.safeName("Счёт 12.pdf"))
    }

    @Test
    fun `long names are cut but keep their extension`() {
        val name = AttachmentFiles.safeName("x".repeat(300) + ".docx")
        assertEquals(80, name.length)
        assertTrue(name.endsWith(".docx"))
    }

    @Test
    fun `installers are never opened`() {
        assertTrue(AttachmentFiles.isRefused("application/vnd.android.package-archive"))
        assertTrue(AttachmentFiles.isRefused("APPLICATION/VND.ANDROID.PACKAGE-ARCHIVE"))
        assertFalse(AttachmentFiles.isRefused("application/pdf"))
    }

    @Test
    fun `brief takes whole lines without quoted history and stops at a sentence`() {
        assertEquals("Hi Sam, see below.", briefFrom("Hi Sam,\n\n> old quoted text\nsee below."))
        val long = "First sentence here. " + "word ".repeat(120)
        assertTrue(briefFrom(long).length <= 421)
    }
}
