package tech.elephantclock.privatemail.core.triage

import tech.elephantclock.privatemail.core.model.MessageBody
import tech.elephantclock.privatemail.core.model.MessageHeader

/**
 * One message as triage sees it: headers, plus the text preview when it has been fetched.
 * Everything in here is attacker-controlled.
 */
data class MailItem(
    val header: MessageHeader,
    val body: MessageBody? = null,
)
