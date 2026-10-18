package tech.elephantclock.privatemail.core.mail.smtp

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Marks code that sends mail. Opt in **only** in the UI handler of the user's Send tap (and in
 * tests). Workers, triage, AI and retry logic must never opt in: nothing but a person decides
 * that a message leaves the device.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Sending mail must start from the user's explicit Send action. Opt in only in that UI handler.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FUNCTION)
annotation class UserInitiatedSend

/**
 * The user's permission to send one exact message, once, soon. Create it in the Send button's
 * handler from the message the user is looking at; [SmtpSender.send] then refuses when:
 * - the message differs in any field from the approved one (e.g. an AI rewrite landed after the tap),
 * - the approval was already used (a retry or a double tap can't send twice),
 * - more than [validForMillis] passed (a queued or restored approval can't fire later).
 */
class SendApproval
    @UserInitiatedSend
    internal constructor(
        message: OutgoingMessage,
        private val clock: () -> Long,
        private val validForMillis: Long = DEFAULT_VALIDITY_MILLIS,
    ) {
        /** The only public way in: real clock, fixed [DEFAULT_VALIDITY_MILLIS]; nothing can stretch it. */
        @UserInitiatedSend
        constructor(message: OutgoingMessage) : this(message, System::currentTimeMillis)

        private val fingerprint = message.fingerprint()
        private val createdAtMillis = clock()
        private val used = AtomicBoolean(false)

        /** Why [message] may not be sent with this approval, or null when it may (and the approval is now used). */
        internal fun consumeFor(message: OutgoingMessage): SendError.NotApproved? {
            if (message.fingerprint() != fingerprint) return SendError.NotApproved("message changed after approval")
            val age = clock() - createdAtMillis
            if (age !in 0..validForMillis) return SendError.NotApproved("approval expired")
            if (!used.compareAndSet(false, true)) return SendError.NotApproved("approval already used")
            return null
        }

        override fun toString() = "SendApproval(used=${used.get()})"

        companion object {
            const val DEFAULT_VALIDITY_MILLIS = 60_000L
        }
    }
