package tech.elephantclock.privatemail.core.triage.signals

/**
 * Keyword lists for transactional mail that must not be mistaken for marketing (or the other way
 * round): order and booking updates, and duplicate charges. Same languages and form as
 * [KeywordLists]: lower-case, ё written as е, matched at a word start.
 */
internal object TransactionalLists {
    /**
     * Phrases that name *the user's* order, shipment or booking. Stricter than
     * `KeywordLists.ORDER_STEMS`: "free delivery" or "flights from 29 EUR" are promotions.
     */
    val ORDER_UPDATE_STEMS =
        listOf(
            // English
            "your order",
            "order confirm",
            "order #",
            "order no",
            "order number",
            "has shipped",
            "was shipped",
            "shipping confirm",
            "out for delivery",
            "tracking number",
            "your booking",
            "booking confirm",
            "your reservation",
            "reservation confirm",
            "itinerary",
            "e-ticket",
            "boarding pass",
            "check-in for",
            "your flight",
            // Russian
            "ваш заказ",
            "заказ №",
            "заказ n",
            "заказ оформлен",
            "трек-номер",
            "электронный билет",
            "посадочный талон",
            "регистрация на рейс",
            "ваше бронирование",
            "бронирование подтвержд",
            // German
            "ihre bestellung",
            "deine bestellung",
            "bestellbestätigung",
            "bestellnummer",
            "versandbestätigung",
            "sendungsnummer",
            "ihre buchung",
            "deine buchung",
            "buchungsbestätigung",
            "ihre reservierung",
            "bordkarte",
            // French
            "votre commande",
            "commande n",
            "numéro de suivi",
            "votre réservation",
            "billet électronique",
            "carte d'embarquement",
            // Spanish
            "tu pedido",
            "su pedido",
            "pedido n",
            "número de seguimiento",
            "tu reserva",
            "su reserva",
            "tarjeta de embarque",
            // Arabic
            "طلبك",
            "رقم الطلب",
            "رقم التتبع",
            "حجزك",
            "تأكيد الحجز",
            "بطاقة الصعود",
        )

    /** A payment taken twice: the user has to act, so it alerts. */
    val DUPLICATE_CHARGE_STEMS =
        listOf(
            // English
            "duplicate payment",
            "duplicate charge",
            "duplicate transaction",
            "charged twice",
            "billed twice",
            "paid twice",
            "double charge",
            "double-charge",
            // Russian
            "двойное списание",
            "повторное списание",
            "списали дважды",
            "двойной платеж",
            "двойная оплата",
            // German
            "doppelte abbuchung",
            "doppelt abgebucht",
            "doppelte zahlung",
            "doppelte belastung",
            "doppelt belastet",
            // French
            "paiement en double",
            "double prélèvement",
            "débité deux fois",
            // Spanish
            "cargo duplicado",
            "pago duplicado",
            "cobro duplicado",
            "cobrado dos veces",
            "doble cargo",
            // Arabic
            "دفعة مكررة",
            "دفع مكرر",
            "خصم مكرر",
            "تم الخصم مرتين",
        )
}
