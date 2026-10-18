package tech.elephantclock.privatemail.core.mail.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UidCommandsTest {
    @Test
    fun `sets are sorted, deduplicated and compressed into ranges`() {
        assertEquals("1:3,7,9:10", UidCommands.set(listOf(10, 2, 9, 1, 3, 7, 2)))
        assertEquals("5", UidCommands.set(listOf(5L)))
    }

    @Test
    fun `sets never contain a star, zero or nothing`() {
        assertThrows(IllegalArgumentException::class.java) { UidCommands.set(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { UidCommands.set(listOf(0L, 1L)) }
    }

    @Test
    fun `250 UIDs make three batches of at most 100`() {
        val batches = UidCommands.batches((1L..250L).shuffled())
        assertEquals(listOf(100, 100, 50), batches.map { it.size })
        assertEquals((1L..250L).toList(), batches.flatten())
    }

    @Test
    fun `reply sets expand in the order written`() {
        assertEquals(listOf(4L, 5, 6, 9), UidCommands.parseSet("4:6,9"))
        assertEquals(listOf(3L, 2, 1), UidCommands.parseSet("3:1"))
        assertNull(UidCommands.parseSet("1:x"))
        assertNull(UidCommands.parseSet("1:4294967295"))
    }

    @Test
    fun `COPYUID pairs source and target UIDs`() {
        val lines = listOf("* 3 EXPUNGE", "A5 OK [COPYUID 77 4:6,9 101:104] Done")
        assertEquals(
            CopyUid(77, mapOf(4L to 101L, 5L to 102L, 6L to 103L, 9L to 104L)),
            UidCommands.parseCopyUid(lines),
        )
        // RFC 6851 MOVE sends it untagged before the expunges.
        assertEquals(
            CopyUid(5, mapOf(8L to 1L)),
            UidCommands.parseCopyUid(listOf("* OK [COPYUID 5 8 1] Moved", "* 2 EXPUNGE", "A1 OK Done")),
        )
    }

    @Test
    fun `a COPYUID with different set sizes is ignored`() {
        assertNull(UidCommands.parseCopyUid(listOf("A1 OK [COPYUID 77 4:6 101:102] Done")))
        assertNull(UidCommands.parseCopyUid(listOf("A1 OK Done")))
    }

    @Test
    fun `SEARCH lines give UIDs`() {
        assertEquals(listOf(3L, 9L), UidCommands.parseSearch(listOf("* SEARCH 3 9", "A1 OK SEARCH completed")))
        assertEquals(emptyList<Long>(), UidCommands.parseSearch(listOf("* SEARCH", "A1 OK")))
    }

    @Test
    fun `Message-IDs are taken from messy header values`() {
        assertEquals("<a@b>", UidCommands.messageId("  <a@b> (sent by x)"))
        assertNull(UidCommands.messageId("no id here"))
        assertEquals(listOf("<a@b>", "<c@d>"), UidCommands.messageIds(listOf("<a@b>\r\n <c@d>", "junk")))
    }
}
