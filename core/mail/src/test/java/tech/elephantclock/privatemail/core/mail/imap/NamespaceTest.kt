package tech.elephantclock.privatemail.core.mail.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.elephantclock.privatemail.core.model.SpecialUse

class NamespaceTest {
    @Test
    fun `INBOX-prefixed servers get folders under INBOX`() {
        // Angus strips the trailing separator from "INBOX."; accept both forms.
        assertEquals("INBOX.Discarded", Namespace("INBOX", '.').child("Discarded"))
        assertEquals("INBOX.Discarded", Namespace("INBOX.", '.').child("Discarded"))
    }

    @Test
    fun `root namespace servers get top-level folders`() {
        assertEquals("Discarded", Namespace("", '/').child("Discarded"))
        assertEquals("Work", Namespace("", null).child(" Work "))
    }

    @Test
    fun `a separator inside a name doesn't create a hierarchy`() {
        assertEquals("Clients-ACME", Namespace("", '/').child("Clients/ACME"))
        assertEquals("INBOX.v1-2", Namespace("INBOX", '.').child("v1.2"))
    }

    private fun folder(
        path: String,
        use: SpecialUse? = null,
        selectable: Boolean = true,
    ) = RemoteFolder(path, '/', emptySet(), use, selectable)

    @Test
    fun `existing folders are found ignoring case, never a Noselect one`() {
        val folders = listOf(folder("work"), folder("Archive", selectable = false))
        assertEquals("work", FolderPaths.findExisting(folders, "Work")?.path)
        assertNull(FolderPaths.findExisting(folders, "archive"))
        assertEquals("work", FolderPaths.findExisting(folders + folder("Work"), "work")?.path)
        assertEquals("Work", FolderPaths.findExisting(folders + folder("Work"), "Work")?.path)
    }
}
