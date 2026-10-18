package tech.elephantclock.privatemail.core.triage.signals

/**
 * Finds sender domains that imitate a known brand or a domain the user often writes to.
 *
 * Never flagged: a domain whose registrable part is a brand's own or one of [frequentDomains]
 * (so `accounts.google.com` and `mail.izi.travel` are fine), and the same name under another
 * TLD (`google.de`: brands own many ccTLDs, so this would mostly be wrong).
 *
 * Flagged, comparing the name label (`paypa1` in `paypa1.com`) after decoding punycode:
 * - the same visual skeleton but different characters: Cyrillic/Greek homoglyphs, accents,
 *   digit swaps (`paypa1`, `g00gle`), `rn` for `m`;
 * - one typo away (both labels 5+ characters): a swapped pair, one letter added or missing
 *   (not a trailing plural `s`), or for labels of 8+ characters one letter replaced (shorter
 *   ones would hit real words: booking/cooking, slack/black);
 * - a brand's whole domain used as a subdomain (`paypal.com.secure-login.ru`);
 * - a brand name as one hyphenated part of the name (`paypal-secure.com`);
 * - a brand's domain written in the display name (`"service@paypal.com" <x@evil.ru>`).
 *
 * Build once per account (it precomputes skeletons) and reuse; [detect] never throws.
 */
class LookalikeDomain(
    frequentDomains: Collection<String> = emptyList(),
) {
    private class Candidate(
        val report: String,
        val label: String,
        val skeleton: String,
        val tokenCheck: Boolean,
    )

    private val frequentRegistrable: Set<String> =
        frequentDomains.mapNotNullTo(LinkedHashSet()) { Domains.normalize(it)?.let(Domains::registrable) }

    private val protectedRegistrable: Set<String> = Brands.OFFICIAL + frequentRegistrable

    /** Never flagged, but not imitation targets either. */
    private val neverFlagged: Set<String> = protectedRegistrable + Brands.WELL_KNOWN

    private val candidates: List<Candidate> =
        buildList {
            for (group in Brands.ALL) {
                val primary = group.first()
                group.map(Domains::nameLabel).distinct().forEach { label ->
                    val tokenCheck = label == Domains.nameLabel(primary) && label.length >= 4 && label !in GENERIC
                    add(Candidate(primary, label, Domains.skeleton(label), tokenCheck))
                }
            }
            for (reg in frequentRegistrable) {
                if (reg in Brands.OFFICIAL) continue
                val label = Domains.nameLabel(Domains.toUnicode(reg))
                add(Candidate(reg, label, Domains.skeleton(label), tokenCheck = false))
            }
        }

    /** The domain [senderDomain] imitates, or null. [displayName] is the sender's display name. */
    fun detect(
        senderDomain: String?,
        displayName: String? = null,
    ): String? {
        val ascii = Domains.normalize(senderDomain) ?: return null
        val registrable = Domains.registrable(ascii)
        if (registrable in neverFlagged) return null

        // A protected domain used as a subdomain (paypal.com.evil.example). Not when the sender's own
        // registrable name is the same (google.com.au, amazon.com.br): that's the brand's regional domain.
        val name = Domains.nameLabel(registrable)
        for (reg in protectedRegistrable) {
            val embedded = ascii.startsWith("$reg.") || ascii.contains(".$reg.")
            if (embedded && Domains.nameLabel(reg) != name) return report(reg)
        }

        val label = Domains.nameLabel(Domains.registrable(Domains.toUnicode(ascii)))
        val skeleton = Domains.skeleton(label)
        for (c in candidates) {
            if (matches(label, skeleton, c)) return c.report
        }
        return displayName?.let(::brandInDisplayName)
    }

    private fun matches(
        label: String,
        skeleton: String,
        c: Candidate,
    ): Boolean {
        if (skeleton.length >= MIN_SKELETON && skeleton == c.skeleton) return label != c.label
        if (minOf(skeleton.length, c.skeleton.length) >= MIN_TYPO) {
            val typo =
                when (Domains.singleEdit(skeleton, c.skeleton)) {
                    Domains.Edit.TRANSPOSITION -> true
                    Domains.Edit.INSERTION -> skeleton != c.skeleton + "s" && c.skeleton != skeleton + "s"
                    Domains.Edit.SUBSTITUTION -> c.skeleton.length >= MIN_SUBSTITUTION
                    null -> false
                }
            if (typo) return true
        }
        if (c.tokenCheck && '-' in label) {
            return label.split('-').any { it.isNotEmpty() && Domains.skeleton(it) == c.skeleton }
        }
        return false
    }

    private fun brandInDisplayName(name: String): String? {
        val normalized = Text.normalizeForSearch(Text.cap(name, MAX_NAME))
        if ('.' !in normalized) return null
        return Brands.OFFICIAL
            .firstOrNull { Text.containsWord(normalized, it, wholeWord = true) }
            ?.let { Brands.PRIMARY.getValue(it) }
    }

    private fun report(registrable: String): String = Brands.PRIMARY[registrable] ?: registrable

    private companion object {
        const val MIN_SKELETON = 3
        const val MIN_TYPO = 5
        const val MIN_SUBSTITUTION = 8
        const val MAX_NAME = 256

        /** Brand names that are ordinary words: too common as part of a hyphenated name. */
        val GENERIC = setOf("wise", "zoom", "slack", "meta", "live", "office", "apple", "booking", "stripe")
    }
}
