package tech.elephantclock.privatemail.core.mail.autoconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig
import java.io.File

class ClientConfigXmlTest {
    private fun config(
        incoming: String,
        outgoing: String,
    ) = """<clientConfig version="1.1"><emailProvider id="example.com"><domain>example.com</domain>
        |<displayName>Example</displayName>$incoming$outgoing</emailProvider></clientConfig>
        """.trimMargin()

    private fun server(
        tag: String,
        type: String,
        host: String,
        port: Int,
        socket: String,
        user: String = "%EMAILADDRESS%",
        vararg auth: String,
    ) = "<$tag type=\"$type\"><hostname>$host</hostname><port>$port</port><socketType>$socket</socketType>" +
        "<username>$user</username>${auth.joinToString("") { "<authentication>$it</authentication>" }}</$tag>"

    @Test
    fun `real ISPDB response for gmail`() {
        val xml = File("src/test/resources/autoconfig/ispdb-gmail.com.xml").readText()
        val cfg = ClientConfigXml.parse(xml, "me@gmail.com")!!
        val (imap, smtp) = cfg.best()!!
        assertEquals(ServerConfig("imap.gmail.com", 993, ConnectionSecurity.SSL_TLS), imap.server)
        assertEquals(ServerConfig("smtp.gmail.com", 465, ConnectionSecurity.SSL_TLS), smtp.server)
        assertEquals("me@gmail.com", imap.username)
        assertTrue(imap.passwordAuth)
    }

    @Test
    fun `plain-text servers are dropped and implicit TLS is preferred`() {
        val xml =
            config(
                server("incomingServer", "imap", "imap.example.com", 143, "plain") +
                    server("incomingServer", "imap", "imap.example.com", 143, "STARTTLS") +
                    server("incomingServer", "imap", "imap.example.com", 993, "SSL"),
                server("outgoingServer", "smtp", "smtp.example.com", 25, "plain") +
                    server("outgoingServer", "smtp", "smtp.example.com", 587, "STARTTLS"),
            )
        val cfg = ClientConfigXml.parse(xml, "a@example.com")!!
        assertEquals(2, cfg.imap.size)
        val (imap, smtp) = cfg.best()!!
        assertEquals(993, imap.server.port)
        assertEquals(ServerConfig("smtp.example.com", 587, ConnectionSecurity.STARTTLS), smtp.server)
    }

    @Test
    fun `placeholders and OAuth-only entries`() {
        val xml =
            config(
                server("incomingServer", "imap", "imap.%EMAILDOMAIN%", 993, "SSL", "%EMAILLOCALPART%", "OAuth2"),
                server("outgoingServer", "smtp", "smtp.%EMAILDOMAIN%", 465, "SSL", "%EMAILADDRESS%", "OAuth2"),
            )
        val (imap, smtp) = ClientConfigXml.parse(xml, "bob@example.com")!!.best()!!
        assertEquals("imap.example.com", imap.server.host)
        assertEquals("bob", imap.username)
        assertFalse(imap.passwordAuth)
        assertFalse(smtp.passwordAuth)
    }

    @Test
    fun `documents with a DOCTYPE or entities are refused`() {
        val xxe =
            """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>""" +
                config(
                    server("incomingServer", "imap", "&e;", 993, "SSL"),
                    server("outgoingServer", "smtp", "s", 465, "SSL"),
                )
        assertNull(ClientConfigXml.parse(xxe, "a@example.com"))
        assertNull(ClientConfigXml.parse("not xml", "a@example.com"))
        assertNull(ClientConfigXml.parse("<clientConfig/>", "a@example.com"))
    }

    @Test
    fun `missing side gives no pair`() {
        val xml = config(server("incomingServer", "imap", "imap.example.com", 993, "SSL"), "")
        assertNull(ClientConfigXml.parse(xml, "a@example.com")!!.best())
    }
}
