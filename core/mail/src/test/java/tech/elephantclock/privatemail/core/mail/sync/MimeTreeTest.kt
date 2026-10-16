package tech.elephantclock.privatemail.core.mail.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MimeTreeTest {
    private fun leaf(
        section: String,
        type: String,
        subtype: String,
        disposition: String? = null,
        name: String? = null,
    ) = MimePart(
        section,
        type,
        subtype,
        disposition = disposition,
        dispositionParams =
            listOfNotNull(
                name?.let {
                    "filename" to
                        it
                },
            ).toMap(),
    )

    @Test
    fun `plain preferred over html, attachments skipped, forwarded message not used for text`() {
        val root =
            MimePart(
                "TEXT",
                "multipart",
                "mixed",
                children =
                    listOf(
                        MimePart(
                            "1",
                            "multipart",
                            "alternative",
                            children = listOf(leaf("1.1", "text", "html"), leaf("1.2", "text", "plain")),
                        ),
                        leaf("2", "text", "plain", disposition = "attachment", name = "notes.txt"),
                        leaf("3", "message", "rfc822"),
                    ),
            )
        assertEquals("1.2", MimeTree.textPart(root)?.section)
        assertEquals(listOf("2", "3"), MimeTree.attachments(root).map { it.partId })
    }

    @Test
    fun `no text part at all`() {
        val root = MimePart("TEXT", "multipart", "mixed", children = listOf(leaf("1", "image", "png", name = "a.png")))
        assertNull(MimeTree.textPart(root))
        assertEquals("a.png", MimeTree.attachments(root).single().fileName)
    }
}
