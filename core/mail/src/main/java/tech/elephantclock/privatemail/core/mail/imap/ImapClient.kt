package tech.elephantclock.privatemail.core.mail.imap

import jakarta.mail.Folder
import jakarta.mail.Session
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.ErrorMapper
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.mailProperties
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
import tech.elephantclock.privatemail.core.mail.tls.TlsSocketFactory
import java.io.Closeable
import javax.net.ssl.X509TrustManager

/** A signed-in IMAP connection. Read-only operations only at this stage. */
class ImapConnection internal constructor(
    private val store: IMAPStore,
) : Closeable {
    val capabilities: Capabilities = Capabilities.from { store.hasCapability(it) }

    fun listFolders(): List<RemoteFolder> {
        val raw =
            store.defaultFolder.list("*").map { folder ->
                val imap = folder as IMAPFolder
                Triple(imap.fullName, imap.separator.takeIf { it != '\u0000' }, imap.attributes.toSet())
            }
        return FolderMapper.mapAll(raw)
    }

    /** EXAMINE: read-only, so nothing we fetch can change flags such as \Seen. */
    internal fun openReadOnly(path: String): IMAPFolder =
        (store.getFolder(path) as IMAPFolder).apply { open(Folder.READ_ONLY) }

    /** The signed-in store, for the send and move engines (APPEND, CREATE, read-write SELECT). */
    internal val imapStore: IMAPStore get() = store

    override fun close() = store.close()
}

/**
 * Signs in to an IMAP server with a password. Each call uses its own [Session]
 * (never the shared default) so accounts and pinned certificates can't leak into each other.
 */
class ImapClient(
    private val pins: CertificatePins,
    private val baseTrust: X509TrustManager = PinningTrustManager.platformTrustManager(),
) {
    fun connect(
        config: ImapConfig,
        credentials: MailCredentials,
    ): MailResult<ImapConnection> {
        val trust = PinningTrustManager(pins, baseTrust)
        val props = mailProperties("imap", config.security, TlsSocketFactory(trust), config.timeouts)
        val store = Session.getInstance(props).getStore("imap") as IMAPStore
        return try {
            store.connect(config.host, config.port, credentials.username, credentials.password.reveal())
            MailResult.Ok(ImapConnection(store))
        } catch (e: Exception) {
            runCatching { store.close() }
            MailResult.Failed(
                ErrorMapper.map(e, config.host, config.port, credentials.password, trust.lastRejected),
            )
        }
    }

    /** Signs in, reads capabilities and the folder list, signs out. Never selects a folder. */
    fun check(
        config: ImapConfig,
        credentials: MailCredentials,
    ): MailResult<ImapCheck> =
        when (val result = connect(config, credentials)) {
            is MailResult.Failed -> {
                result
            }

            is MailResult.Ok -> {
                result.value.use { c ->
                    try {
                        MailResult.Ok(ImapCheck(c.capabilities, c.listFolders()))
                    } catch (e: Exception) {
                        MailResult.Failed(ErrorMapper.map(e, config.host, config.port, credentials.password, null))
                    }
                }
            }
        }
}

data class ImapCheck(
    val capabilities: Capabilities,
    val folders: List<RemoteFolder>,
)
