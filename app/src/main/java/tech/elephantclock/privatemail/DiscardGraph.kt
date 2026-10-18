package tech.elephantclock.privatemail

import tech.elephantclock.privatemail.core.data.secure.StoredSecret
import tech.elephantclock.privatemail.core.data.triage.DiscardedActions
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.smtp.SmtpSender
import tech.elephantclock.privatemail.feature.discarded.AppUnsubscriber
import tech.elephantclock.privatemail.feature.discarded.DataDiscardedCommands
import tech.elephantclock.privatemail.feature.discarded.DiscardedViewModel
import tech.elephantclock.privatemail.feature.discarded.RoomDiscardedSource

/**
 * The Discarded digest's wiring (T47), kept out of [MailGraph] so lanes don't collide. Triage
 * settings are today's defaults, as in [MailGraph.orchestrator]; pass the real ones once T48
 * backs them with DataStore (they decide which `Authentication-Results` one-click trusts).
 */
fun MailGraph.discardedViewModel(): DiscardedViewModel =
    DiscardedViewModel(
        source = RoomDiscardedSource(database),
        commands = DataDiscardedCommands(DiscardedActions(database, mover)),
        unsubscriber =
            AppUnsubscriber(
                db = database,
                credentials = { id, username ->
                    (secrets.get(id) as? StoredSecret.Found)?.let { MailCredentials(username, it.password) }
                },
                smtp = SmtpSender(certificatePins),
            ),
    )
