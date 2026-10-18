package tech.elephantclock.privatemail.core.triage.people

/**
 * The user's address book. Optional: the app implements it on top of the Contacts provider
 * only when the user grants the permission; without it, pass null to [Protections].
 */
fun interface ContactsSource {
    /** [email] is lower-case. */
    fun isContact(email: String): Boolean
}
