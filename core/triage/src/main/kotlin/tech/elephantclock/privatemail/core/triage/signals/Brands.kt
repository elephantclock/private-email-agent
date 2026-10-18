package tech.elephantclock.privatemail.core.triage.signals

/**
 * Brands that phishing imitates, each with the registrable domains it really sends from. The
 * first domain is the one reported in `Signals.lookalikeOf`. Generic words (mail, post, bank)
 * are deliberately absent: they would make ordinary domains look like imitations.
 */
internal object Brands {
    val ALL: List<List<String>> =
        listOf(
            // Global
            listOf("paypal.com", "paypal.me", "paypal-communication.com", "paypalobjects.com"),
            listOf(
                "google.com",
                "gmail.com",
                "googlemail.com",
                "youtube.com",
                "google.ru",
                "google.de",
                "googleusercontent.com",
                "googlegroups.com",
                "withgoogle.com",
            ),
            listOf("apple.com", "icloud.com", "me.com", "mac.com", "apple.news"),
            listOf(
                "microsoft.com",
                "outlook.com",
                "live.com",
                "hotmail.com",
                "office.com",
                "office365.com",
                "microsoftonline.com",
                "sharepoint.com",
                "onmicrosoft.com",
                "xbox.com",
                "skype.com",
            ),
            listOf("amazon.com", "amazon.de", "amazon.co.uk", "amazon.fr", "amazon.es", "amazonaws.com", "aws.com"),
            listOf("facebook.com", "facebookmail.com", "meta.com", "fb.com"),
            listOf("instagram.com", "mail.instagram.com"),
            listOf("whatsapp.com", "whatsapp.net"),
            listOf("linkedin.com", "e.linkedin.com"),
            listOf("netflix.com"),
            listOf("dropbox.com", "dropboxmail.com"),
            listOf("docusign.com", "docusign.net"),
            listOf("github.com", "githubusercontent.com"),
            listOf("gitlab.com"),
            listOf("atlassian.com", "atlassian.net"),
            listOf("slack.com"),
            listOf("zoom.us", "zoom.com"),
            listOf("adobe.com", "adobesign.com"),
            listOf("stripe.com"),
            listOf("revolut.com"),
            listOf("wise.com", "transferwise.com"),
            listOf("binance.com"),
            listOf("coinbase.com"),
            listOf("booking.com"),
            listOf("airbnb.com"),
            listOf("expedia.com"),
            listOf("dhl.com", "dhl.de"),
            listOf("fedex.com"),
            listOf("ups.com"),
            listOf("telegram.org"),
            listOf("twitter.com", "x.com"),
            listOf("steampowered.com", "steamcommunity.com"),
            listOf("ebay.com", "ebay.de"),
            listOf("aliexpress.com", "aliexpress.ru"),
            // Russia and nearby
            listOf("sberbank.ru", "sber.ru", "sberbank.com"),
            listOf("tinkoff.ru", "tbank.ru"),
            listOf("vtb.ru"),
            listOf("alfabank.ru"),
            listOf("gosuslugi.ru"),
            listOf("nalog.ru", "nalog.gov.ru"),
            listOf("yandex.ru", "yandex.com", "ya.ru", "yandex.net"),
            listOf("ozon.ru"),
            listOf("wildberries.ru", "wb.ru"),
            listOf("avito.ru"),
            listOf("vk.com", "vk.ru"),
            listOf("kaspi.kz"),
        ).map { group -> group.map { Domains.registrable(it) }.distinct() }

    /**
     * Big mail providers and sites that sit one typo from a brand (`mail.ru` vs `gmail`): never
     * reported as look-alikes, but not imitation targets themselves.
     */
    val WELL_KNOWN: Set<String> =
        setOf(
            "mail.ru",
            "bk.ru",
            "list.ru",
            "inbox.ru",
            "internet.ru",
            "rambler.ru",
            "yahoo.com",
            "aol.com",
            "gmx.de",
            "gmx.net",
            "web.de",
            "t-online.de",
            "proton.me",
            "protonmail.com",
            "zoho.com",
            "orange.fr",
            "free.fr",
            "laposte.net",
            "email.com",
            "mail.com",
        )

    /** Each brand's registrable domain mapped to the one reported for the brand. */
    val PRIMARY: Map<String, String> = ALL.flatMap { group -> group.map { it to group.first() } }.toMap()

    /** Registrable domains of every brand. */
    val OFFICIAL: Set<String> = PRIMARY.keys
}
