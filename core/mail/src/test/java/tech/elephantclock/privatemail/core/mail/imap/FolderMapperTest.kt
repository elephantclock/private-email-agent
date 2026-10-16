package tech.elephantclock.privatemail.core.mail.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tech.elephantclock.privatemail.core.model.SpecialUse

class FolderMapperTest {
    private fun use(
        path: String,
        sep: Char? = '/',
        vararg attrs: String,
    ) = FolderMapper.specialUseOf(path, sep, attrs.toList())

    @Test
    fun `attributes first, then common names`() {
        assertEquals(SpecialUse.INBOX, use("inbox"))
        assertEquals(SpecialUse.SENT, use("Gesendete Objekte", '/', "\\Sent"))
        assertEquals(SpecialUse.SENT, use("[Gmail]/Sent Mail"))
        assertEquals(SpecialUse.SENT, use("INBOX.Sent", '.'))
        assertEquals(SpecialUse.SENT, use("Sent Items"))
        assertEquals(SpecialUse.SENT, use("Отправленные"))
        assertEquals(SpecialUse.TRASH, use("Deleted Items"))
        assertEquals(SpecialUse.TRASH, use("[Gmail]/Bin"))
        assertEquals(SpecialUse.JUNK, use("Junk E-mail"))
        assertEquals(SpecialUse.ALL, use("[Gmail]/All Mail", '/', "\\All", "\\HasNoChildren"))
        assertEquals(SpecialUse.ARCHIVE, use("Archive"))
        assertEquals(null, use("Projects/Sent ideas"))
    }

    @Test
    fun `server attribute beats a folder that only has the name`() {
        val folders =
            FolderMapper
                .mapAll(
                    listOf(
                        Triple("INBOX", '/', emptySet()),
                        Triple("Sent", '/', emptySet()),
                        Triple("[Gmail]", '/', setOf("\\Noselect", "\\HasChildren")),
                        Triple("[Gmail]/Sent Mail", '/', setOf("\\Sent")),
                    ),
                ).associateBy { it.path }
        assertEquals(SpecialUse.SENT, folders.getValue("[Gmail]/Sent Mail").specialUse)
        assertEquals(null, folders.getValue("Sent").specialUse)
        assertFalse(folders.getValue("[Gmail]").selectable)
    }

    @Test
    fun `capability line parsing`() {
        val caps = Capabilities.parse("IMAP4rev1 IDLE MOVE UIDPLUS CONDSTORE SPECIAL-USE X-GM-EXT-1")
        assertEquals(
            Capabilities(idle = true, move = true, uidPlus = true, condStore = true, specialUse = true, gmail = true),
            caps,
        )
        assertEquals(Capabilities(false, false, false, false, false, false), Capabilities.parse("IMAP4rev1"))
    }
}
