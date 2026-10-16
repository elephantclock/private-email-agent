package tech.elephantclock.privatemail.core.mail.sync

import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import java.util.Date
import java.util.Properties

/** Synthetic messages covering the formats the snippet code has to handle. */
object SyncFixtures {
    private val session = Session.getInstance(Properties())

    private fun base(subject: String) =
        MimeMessage(session).apply {
            setFrom(InternetAddress("sender@shop.example", "Шоп Sender", "UTF-8"))
            setRecipients(jakarta.mail.Message.RecipientType.TO, "tester@localhost")
            setSubject(subject, "UTF-8")
            sentDate = Date()
        }

    fun plain(
        i: Int,
        body: String = "Hello number $i",
    ) = base("Plain $i").apply {
        setText(body, "UTF-8")
        setHeader("List-Unsubscribe", "<mailto:unsub@shop.example>")
        setHeader("List-Id", "Shop news <news.shop.example>")
        setHeader("Precedence", "bulk")
        setHeader("Authentication-Results", "mx.localhost; spf=pass; dkim=pass; dmarc=pass")
        setHeader("References", "<root@shop.example>\r\n <prev@shop.example>")
        setHeader("In-Reply-To", "<prev@shop.example>")
        setHeader("X-Unrelated", "not fetched")
        saveChanges()
    }

    fun alternative() =
        base("Alternative").apply {
            setContent(
                MimeMultipart("alternative").apply {
                    addBodyPart(MimeBodyPart().apply { setText("Plain part wins", "UTF-8") })
                    addBodyPart(MimeBodyPart().apply { setContent("<p>HTML part</p>", "text/html; charset=UTF-8") })
                },
            )
            saveChanges()
        }

    fun withAttachment() =
        base("Invoice").apply {
            setContent(
                MimeMultipart("mixed").apply {
                    addBodyPart(
                        MimeBodyPart().apply {
                            setContent(
                                MimeMultipart("alternative").apply {
                                    addBodyPart(MimeBodyPart().apply { setText("See the invoice attached.", "UTF-8") })
                                    addBodyPart(
                                        MimeBodyPart().apply { setContent("<b>See</b>", "text/html; charset=UTF-8") },
                                    )
                                },
                            )
                        },
                    )
                    addBodyPart(
                        MimeBodyPart().apply {
                            setContent(ByteArray(3000) { it.toByte() }, "application/pdf")
                            fileName = "invoice-12.pdf"
                            disposition = MimeBodyPart.ATTACHMENT
                        },
                    )
                },
            )
            saveChanges()
        }

    /** HTML only, windows-1251, quoted-printable. */
    fun russianHtml() =
        base("Заказ").apply {
            setContent(
                "<html><head><style>p{}</style></head><body><p>Ваш заказ&nbsp;готов</p></body></html>",
                "text/html; charset=windows-1251",
            )
            setHeader("Content-Transfer-Encoding", "quoted-printable")
            saveChanges()
        }

    /** Long UTF-8 body in base64, so the 4 KB cut lands inside the text. */
    fun longBase64() =
        base("Long").apply {
            setText("Привет мир. ".repeat(800), "UTF-8")
            setHeader("Content-Transfer-Encoding", "base64")
            saveChanges()
        }

    fun latin1() =
        base("Latin").apply {
            setText("Café crème brûlée", "ISO-8859-1")
            saveChanges()
        }

    fun encodedSubject() = MimeUtility.encodeText("Шоп Sender", "UTF-8", "B")
}
