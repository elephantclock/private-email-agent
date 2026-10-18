package tech.elephantclock.privatemail.core.triage.signals

import java.util.Locale

/**
 * A brand name hidden from filters by an invisible character inside it ("Pay​Pal"): the
 * name appears once hidden characters are removed, but not in the text as written. Legitimate
 * senders have no reason to do this, so it counts as phishing even when authentication passes.
 */
internal object BrandDisguise {
    /** Each brand's name (`paypal`) and its registrable domains. Short names would match ordinary words. */
    private val NAMES: List<Pair<String, Set<String>>> =
        Brands.ALL
            .map { group -> Domains.nameLabel(group.first()) to group.toSet() }
            .filter { it.first.length >= MIN_NAME }

    /** The disguised brand name in [text], or null. Mail from the brand's own domains is never flagged. */
    fun detect(
        text: String?,
        senderRegistrable: String?,
    ): String? {
        if (text.isNullOrEmpty() || !HiddenCharDetector.isSuspicious(text)) return null
        val capped = Text.cap(text, Text.MAX_HEADER)
        val visible = Text.normalizeForSearch(capped)
        val written = capped.lowercase(Locale.ROOT)
        return NAMES
            .firstOrNull { (name, domains) ->
                senderRegistrable !in domains &&
                    Text.containsWord(visible, name, wholeWord = true) &&
                    !Text.containsWord(written, name, wholeWord = true)
            }?.first
    }

    private const val MIN_NAME = 4
}
