package tech.elephantclock.privatemail.core.mail.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Timeouts
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.mail.imap.ImapConfig
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.support.DroppingProxy
import tech.elephantclock.privatemail.core.mail.support.ScriptedImapServer
import tech.elephantclock.privatemail.core.mail.support.TestCert
import tech.elephantclock.privatemail.core.mail.support.TestServers
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import kotlin.random.Random

class IdleLoopTest {
    private val servers = TestServers()
    private val proxy = DroppingProxy(servers.imapsPort)

    @After
    fun tearDown() {
        proxy.close()
        servers.close()
    }

    private fun connector(
        port: Int = proxy.port,
        credentials: MailCredentials = TestServers.good,
        timeouts: Timeouts = TestServers.fast,
    ): () -> MailResult<ImapConnection> =
        {
            ImapClient(CertificatePins.None, TestCert.LOCALHOST.trustedBy())
                .connect(ImapConfig("localhost", port, ConnectionSecurity.SSL_TLS, timeouts), credentials)
        }

    private class Collected(
        val events: Channel<IdleEvent>,
        val job: Job,
    ) {
        suspend fun next(timeoutMillis: Long = 5_000): IdleEvent = withTimeout(timeoutMillis) { events.receive() }

        suspend fun none(forMillis: Long): IdleEvent? = withTimeoutOrNull(forMillis) { events.receive() }
    }

    private fun CoroutineScope.collect(loop: IdleLoop): Collected {
        val events = Channel<IdleEvent>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { loop.events().collect { events.send(it) } }
        return Collected(events, job)
    }

    private fun deliver(n: Int) = servers.user.deliver(SyncFixtures.plain(n))

    @Test
    fun `a message delivered while idling is reported within 2 s`() =
        runBlocking {
            val c = collect(IdleLoop(connector()))
            assertEquals(IdleEvent.Connected, c.next())
            delay(500) // IDLE is under way
            val started = System.currentTimeMillis()
            deliver(1)
            assertEquals(IdleEvent.Changed, c.next(timeoutMillis = 2_000))
            assertTrue(System.currentTimeMillis() - started < 2_000)
            c.job.cancelAndJoin()
        }

    @Test
    fun `silence longer than the read timeout keeps the same connection idling`() =
        runBlocking {
            val c = collect(IdleLoop(connector(), renewMillis = 60_000))
            assertEquals(IdleEvent.Connected, c.next())
            // Read timeout is 2 s; Angus must not treat a quiet IDLE as a dead connection.
            assertNull(c.none(forMillis = 5_000))
            deliver(2)
            assertEquals(IdleEvent.Changed, c.next(timeoutMillis = 2_000))
            assertEquals(1, proxy.accepted)
            c.job.cancelAndJoin()
        }

    @Test
    fun `IDLE is renewed on the same connection`() =
        runBlocking {
            val c = collect(IdleLoop(connector(), renewMillis = 400, minCycleMillis = 0))
            assertEquals(IdleEvent.Connected, c.next())
            repeat(3) { assertEquals(IdleEvent.Changed, c.next(timeoutMillis = 2_000)) }
            assertEquals(1, proxy.accepted)
            c.job.cancelAndJoin()
        }

    @Test
    fun `a dropped connection is reported and reconnected`() =
        runBlocking {
            val c = collect(IdleLoop(connector(), backoff = Backoff(initialMillis = 100, maxMillis = 200)))
            assertEquals(IdleEvent.Connected, c.next())
            delay(300)
            proxy.dropAll()
            val dropped = c.next()
            assertTrue("$dropped", dropped is IdleEvent.Disconnected && dropped.retryInMillis <= 100)
            assertEquals(IdleEvent.Connected, c.next())
            assertEquals(2, proxy.accepted)
            c.job.cancelAndJoin()
        }

    @Test
    fun `a dead connection is noticed at the next renewal`() =
        runBlocking {
            val c = collect(IdleLoop(connector(), renewMillis = 500, backoff = Backoff(60_000, 60_000)))
            assertEquals(IdleEvent.Connected, c.next())
            proxy.freeze() // nothing gets through any more, and nothing errors
            // Renewal (0.5 s) sends DONE; its reply never comes, so the 2 s read timeout ends the session.
            val event = c.next(timeoutMillis = 6_000)
            assertTrue("$event", event is IdleEvent.Disconnected)
            c.job.cancelAndJoin()
        }

    @Test
    fun `cancelling returns promptly even when the connection is dead`() =
        runBlocking {
            val slow = Timeouts(connectMillis = 2_000, readMillis = 60_000)
            val c = collect(IdleLoop(connector(timeouts = slow), renewMillis = 60_000))
            assertEquals(IdleEvent.Connected, c.next())
            delay(300)
            proxy.freeze()
            val started = System.currentTimeMillis()
            c.job.cancelAndJoin()
            val took = System.currentTimeMillis() - started
            assertTrue("cancel took $took ms", took < 1_000)
        }

    @Test
    fun `a wrong password stops the loop after one attempt`() =
        runBlocking {
            val events =
                withTimeout(10_000) { IdleLoop(connector(credentials = TestServers.wrong)).events().toList() }
            assertEquals(listOf(IdleEvent.Stopped(AuthError.WrongCredentials)), events)
            assertEquals(1, proxy.accepted)
        }

    @Test
    fun `a server without IDLE is polled without holding a connection`() =
        runBlocking {
            ScriptedImapServer(capabilities = "IMAP4rev1 AUTH=PLAIN").use { server ->
                val c = collect(IdleLoop(connector(port = server.port), pollMillis = 200))
                assertEquals(IdleEvent.Connected, c.next())
                repeat(3) { assertEquals(IdleEvent.Changed, c.next(timeoutMillis = 1_000)) }
                c.job.cancelAndJoin()
                assertEquals(1, server.commands.count { it.startsWith("AUTHENTICATE") })
                assertTrue(server.commands.none { it.startsWith("IDLE") })
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transient failures back off exponentially up to the cap`() =
        runTest {
            val attempts = mutableListOf<Long>()
            val loop =
                IdleLoop(
                    connect = {
                        attempts += testScheduler.currentTime
                        MailResult.Failed(AuthError.PortBlockedOrTimeout("h", 993))
                    },
                    backoff = Backoff(initialMillis = 1_000, maxMillis = 8_000, random = Random(7)),
                    io = StandardTestDispatcher(testScheduler),
                    clock = { testScheduler.currentTime },
                )
            val waits =
                loop
                    .events()
                    .take(6)
                    .toList()
                    .map { (it as IdleEvent.Disconnected).retryInMillis }
            listOf(1_000L, 2_000, 4_000, 8_000, 8_000, 8_000).zip(waits).forEach { (base, wait) ->
                assertTrue("$wait for base $base", wait in base / 2..base)
            }
            // Each attempt happens after the previous wait, never sooner.
            attempts.zipWithNext().zip(waits).forEach { (pair, wait) -> assertEquals(wait, pair.second - pair.first) }
        }

    @Test
    fun `backoff stays within half and the full delay`() {
        val backoff = Backoff(initialMillis = 1_000, maxMillis = 300_000, random = Random(1))
        repeat(40) { attempt ->
            val base = (1_000L shl attempt.coerceAtMost(30)).coerceAtMost(300_000)
            assertTrue(backoff.delayFor(attempt) in base / 2..base)
        }
    }
}
