package tech.elephantclock.privatemail.core.triage.signals

/**
 * Multilingual keyword signals over the subject and preview: security codes, money and
 * orders/travel. Lists cover English, Russian, German, French, Spanish and Arabic. Matching is
 * plain substring search at a word start on normalised text (no regex), so it is linear.
 *
 * These feed protections, where a false positive only costs a discard, so the lists stay
 * specific: "code" alone would match every code-review notification.
 */
object Keywords {
    /** A security-code phrase and a 4–8 digit code (spaces or one dash allowed: `123 456`, `123-456`). */
    fun securityCode(text: String): Boolean {
        val t = prepare(text)
        return SECURITY_PHRASES.any { Text.containsWord(t, it, wholeWord = false) } && hasCode(t)
    }

    /** A money phrase: invoices, receipts, payments, refunds, transfers. */
    fun money(text: String): Boolean {
        val t = prepare(text)
        return MONEY_STEMS.any { Text.containsWord(t, it, wholeWord = false) } ||
            MONEY_WORDS.any { Text.containsWord(t, it, wholeWord = true) }
    }

    /** Orders, deliveries, bookings and tickets. */
    fun ordersOrTravel(text: String): Boolean {
        val t = prepare(text)
        return ORDER_STEMS.any { Text.containsWord(t, it, wholeWord = false) }
    }

    /** Reports and monitoring alerts. */
    fun report(text: String): Boolean {
        val t = prepare(text)
        return REPORT_STEMS.any { Text.containsWord(t, it, wholeWord = false) }
    }

    private fun prepare(text: String): String = Text.normalizeForSearch(Text.cap(text, Text.MAX_SCAN))

    /** True when [t] has a run of 4–8 digits not inside a longer number, letters or a date-like token. */
    internal fun hasCode(t: String): Boolean {
        var i = 0
        while (i < t.length) {
            if (!Character.isDigit(t[i]) || (i > 0 && isCodeNeighbour(t, i - 1))) {
                i++
                continue
            }
            var digits = 0
            var j = i
            var separators = 0
            while (j < t.length && digits <= MAX_CODE) {
                val c = t[j]
                when {
                    Character.isDigit(c) -> digits++

                    (c == ' ' || c == '-') && separators == 0 && j + 1 < t.length && Character.isDigit(t[j + 1]) &&
                        digits in 2..4 -> separators++

                    else -> break
                }
                j++
            }
            val endOk = j >= t.length || !isCodeNeighbour(t, j)
            if (digits in MIN_CODE..MAX_CODE && endOk) return true
            i = maxOf(j, i + 1)
        }
        return false
    }

    /**
     * Whether `t[k]` makes an adjacent digit run part of something else: a letter or digit, `/`, `#`,
     * or a `.`/`,` between digits (a decimal or a date). A full stop ending the sentence is fine.
     */
    private fun isCodeNeighbour(
        t: String,
        k: Int,
    ): Boolean {
        val c = t[k]
        if (c.isLetterOrDigit() || c == '/' || c == '#') return true
        if (c != '.' && c != ',') return false
        val before = k > 0 && Character.isDigit(t[k - 1])
        val after = k + 1 < t.length && Character.isDigit(t[k + 1])
        return before && after
    }

    private const val MIN_CODE = 4
    private const val MAX_CODE = 8

    // All entries lower-case, ё written as е (see Text.normalizeForSearch).
    private val SECURITY_PHRASES =
        listOf(
            // English
            "verification code",
            "security code",
            "confirmation code",
            "login code",
            "log-in code",
            "sign-in code",
            "signin code",
            "authentication code",
            "access code",
            "one-time",
            "one time code",
            "otp",
            "passcode",
            "2fa",
            "two-factor",
            "your code",
            "code is",
            "pin code",
            // Russian
            "код подтверждения",
            "код для входа",
            "код доступа",
            "код безопасности",
            "проверочный код",
            "одноразов",
            "ваш код",
            "код:",
            "код -",
            "код —",
            // German
            "bestätigungscode",
            "sicherheitscode",
            "verifizierungscode",
            "einmalcode",
            "einmalpasswort",
            "anmeldecode",
            "ihr code",
            "dein code",
            "zugangscode",
            // French
            "code de vérification",
            "code de sécurité",
            "code de confirmation",
            "code de connexion",
            "votre code",
            "code à usage unique",
            // Spanish
            "código de verificación",
            "código de seguridad",
            "código de confirmación",
            "código de acceso",
            "tu código",
            "su código",
            // Arabic
            "رمز التحقق",
            "رمز الأمان",
            "رمز التأكيد",
            "كود التحقق",
            "رمز التفعيل",
            "رمز الدخول",
            "لمرة واحدة",
            "رمزك",
        )

    private val MONEY_STEMS =
        listOf(
            // English
            "invoice",
            "receipt",
            "refund",
            "payment",
            "billing",
            "chargeback",
            "remittance",
            "payout",
            "amount due",
            "overdue",
            "wire transfer",
            "bank transfer",
            "direct debit",
            "transaction",
            "your statement",
            // Russian
            "счет на оплату",
            "счет-фактур",
            "счет №",
            "счет n",
            "оплат",
            "платеж",
            "квитанц",
            "кассовый чек",
            "возврат средств",
            "возврат денег",
            "списан",
            "задолженност",
            "инвойс",
            "к оплате",
            "денежный перевод",
            "банковский перевод",
            // German
            "rechnung",
            "zahlung",
            "quittung",
            "rückerstattung",
            "überweisung",
            "lastschrift",
            "gutschrift",
            "mahnung",
            "zahlungserinnerung",
            // French
            "facture",
            "paiement",
            "reçu",
            "remboursement",
            "virement",
            "prélèvement",
            // Spanish
            "factura",
            "pago",
            "recibo",
            "reembolso",
            "transferencia bancaria",
            "cobro",
            // Arabic
            "فاتورة",
            "الفاتورة",
            "دفع",
            "الدفع",
            "إيصال",
            "ايصال",
            "استرداد",
            "تحويل بنكي",
            "حوالة",
        )

    /** Short words that must stand alone ("bill" but not "billion"). */
    private val MONEY_WORDS = listOf("bill", "paid", "charged", "счет", "чек")

    private val ORDER_STEMS =
        listOf(
            // English
            "your order",
            "order confirm",
            "order #",
            "order no",
            "shipped",
            "shipping confirm",
            "delivery",
            "tracking number",
            "booking",
            "reservation",
            "itinerary",
            "e-ticket",
            "boarding pass",
            "check-in",
            "flight",
            // Russian
            "ваш заказ",
            "заказ №",
            "заказ n",
            "заказ оформлен",
            "доставк",
            "отправлен",
            "трек-номер",
            "бронирован",
            "электронный билет",
            "посадочный талон",
            "регистрация на рейс",
            "рейс",
            // German
            "ihre bestellung",
            "deine bestellung",
            "bestellbestätigung",
            "versand",
            "lieferung",
            "buchung",
            "reservierung",
            "flugticket",
            "bordkarte",
            // French
            "votre commande",
            "commande n",
            "expédi",
            "livraison",
            "réservation",
            "billet électronique",
            "carte d'embarquement",
            "vol ",
            // Spanish
            "tu pedido",
            "su pedido",
            "pedido n",
            "envío",
            "entrega",
            "reserva",
            "billete",
            "tarjeta de embarque",
            "vuelo",
            // Arabic
            "طلبك",
            "رقم الطلب",
            "الشحن",
            "التوصيل",
            "الحجز",
            "حجز",
            "تذكرة",
            "بطاقة الصعود",
            "رحلة",
        )

    private val REPORT_STEMS =
        listOf(
            "report",
            "alert",
            "monitoring",
            "digest",
            "summary",
            "weekly",
            "daily",
            "monthly",
            "uptime",
            "downtime",
            "отчет",
            "сводка",
            "оповещени",
            "мониторинг",
            "bericht",
            "warnung",
            "zusammenfassung",
            "rapport",
            "alerte",
            "résumé",
            "informe",
            "alerta",
            "resumen",
            "تقرير",
            "تنبيه",
            "ملخص",
        )
}
