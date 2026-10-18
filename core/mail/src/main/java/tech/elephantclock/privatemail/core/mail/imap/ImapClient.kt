package tech.elephantclock.privatemail.core.mail.imap

import jakarta.mail.Folder
import jakarta.mail.Session
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.eclipse.angus.mail.imap.ResyncData
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
    private val sockets: TlsSocketFactory? = null,
) : Closeable {
    val capabilities: Capabilities = Capabilities.from { store.hasCapability(it) }

    /** Angus turns CONDSTORE on with `ENABLE CONDSTORE`, so a server offering CONDSTORE without ENABLE can't use it. */
    internal val condStoreUsable: Boolean = capabilities.condStore && store.hasCapability("ENABLE")

    fun listFolders(): List<RemoteFolder> {
        val raw =
            store.defaultFolder.list("*").map { folder ->
                val imap = folder as IMAPFolder
                Triple(imap.fullName, imap.separator.takeIf { it != '\u0000' }, imap.attributes.toSet())
            }
        return FolderMapper.mapAll(raw)
    }

    /**
     * EXAMINE: read-only, so nothing we fetch can change flags such as \Seen. With [condStore]
     * (only if [condStoreUsable]) it is `ENABLE CONDSTORE` + `EXAMINE <path> (CONDSTORE)`, so the
     * server reports HIGHESTMODSEQ.
     */
    internal fun openReadOnly(
        path: String,
        condStore: Boolean = false,
    ): IMAPFolder =
        (store.getFolder(path) as IMAPFolder).apply {
            if (condStore) open(Folder.READ_ONLY, ResyncData.CONDSTORE) else open(Folder.READ_ONLY)
        }

    /** The signed-in store, for the send and move engines (APPEND, CREATE, read-write SELECT). */
    internal val imapStore: IMAPStore get() = store

    /** Signs out politely. Can block for up to the read timeout on a dead connection; see [abort]. */
    override fun close() = store.close()

    /**
     * Drops the connection at once: closes the sockets (unblocking any thread in IDLE or a
     * read), then lets Angus clean up. Safe to call from any thread, more than once.
     */
    fun abort() {
        sockets?.closeAll()
        runCatching { store.close() }
    }
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
        val sockets = TlsSocketFactory(trust)
        val props = mailProperties("imap", config.security, sockets, config.timeouts)
        val store = Session.getInstance(props).getStore("imap") as IMAPStore
        return try {
            store.connect(config.host, config.port, credentials.username, credentials.password.reveal())
            MailResult.Ok(ImapConnection(store, sockets))
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
