package tech.elephantclock.privatemail.core.ai.mlkit

import tech.elephantclock.privatemail.core.ai.LanguageTag

/**
 * Weekday names, so a translation can be told which weekday is which and checked afterwards.
 * Gemini Nano on the Fold7 translated "Tuesday" as "понедельник" (Monday) every time, even greedy:
 * in an email that moves a meeting. Weekdays are a small closed set, so code checks them.
 *
 * Index 0 is Monday. A word matches when it is a stem plus one of the language's endings
 * (Russian is inflected: "во вторник", "в среду", "до пятницы").
 */
internal object Weekdays {
    private class Language(
        /** Monday first; several stems where a language has two names (Samstag/Sonnabend). */
        val stems: List<List<String>>,
        val endings: Set<String>,
        /** How the prompt names each weekday. */
        val names: List<String>,
    )

    private val RU_MASCULINE = setOf("", "а", "у", "ом", "е", "и", "ов", "ам", "ами", "ах")
    private val RU_FEMININE = setOf("", "а", "у", "ы", "е", "ой", "ам", "ами", "ах")
    private val RU_NEUTER = setOf("е", "я", "ю", "ем", "и", "й", "ям", "ями", "ях")

    private val languages =
        mapOf(
            "en" to
                Language(
                    listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").map(::listOf),
                    setOf("", "s"),
                    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
                ),
            "de" to
                Language(
                    listOf(
                        listOf("montag"),
                        listOf("dienstag"),
                        listOf("mittwoch"),
                        listOf("donnerstag"),
                        listOf("freitag"),
                        listOf("samstag", "sonnabend"),
                        listOf("sonntag"),
                    ),
                    setOf("", "s", "e", "en"),
                    listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"),
                ),
            "fr" to
                Language(
                    listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche").map(::listOf),
                    setOf("", "s"),
                    listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"),
                ),
            "es" to
                Language(
                    listOf(
                        listOf("lunes"),
                        listOf("martes"),
                        listOf("miércoles", "miercoles"),
                        listOf("jueves"),
                        listOf("viernes"),
                        listOf("sábado", "sabado"),
                        listOf("domingo"),
                    ),
                    setOf("", "s"),
                    listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"),
                ),
            "it" to
                Language(
                    listOf(
                        listOf("lunedì", "lunedi"),
                        listOf("martedì", "martedi"),
                        listOf("mercoledì", "mercoledi"),
                        listOf("giovedì", "giovedi"),
                        listOf("venerdì", "venerdi"),
                        listOf("sabato"),
                        listOf("domenica"),
                    ),
                    setOf(""),
                    listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica"),
                ),
        )

    /** Russian endings differ by gender, so each weekday carries its own set. */
    private val russian =
        listOf(
            "понедельник" to RU_MASCULINE,
            "вторник" to RU_MASCULINE,
            "сред" to RU_FEMININE,
            "четверг" to RU_MASCULINE,
            "пятниц" to RU_FEMININE,
            "суббот" to RU_FEMININE,
            "воскресень" to RU_NEUTER,
        )
    private val russianNames = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")

    private val word = Regex("\\p{L}+")

    fun covers(language: LanguageTag): Boolean = language.primary == "ru" || language.primary in languages

    /** The weekdays named in [text], in order (Monday = 0). */
    fun find(
        text: String,
        language: LanguageTag,
    ): List<Int> =
        word
            .findAll(text.lowercase())
            .mapNotNull { dayOf(it.value, language.primary) }
            .toList()

    /** "Tuesday = вторник" for each weekday in [text]; null when there is none. */
    fun glossary(
        text: String,
        from: LanguageTag,
        to: LanguageTag,
    ): String? {
        if (!covers(from) || !covers(to)) return null
        val days = find(text, from).distinct()
        if (days.isEmpty()) return null
        return days.joinToString(", ") { "${nameOf(it, from)} = ${nameOf(it, to)}" }
    }

    /** True unless both languages are covered and the weekdays differ (as a multiset). */
    fun kept(
        source: String,
        from: LanguageTag,
        translation: String,
        to: LanguageTag,
    ): Boolean {
        if (!covers(from) || !covers(to)) return true
        return find(source, from).sorted() == find(translation, to).sorted()
    }

    private fun nameOf(
        day: Int,
        language: LanguageTag,
    ): String = if (language.primary == "ru") russianNames[day] else languages.getValue(language.primary).names[day]

    private fun dayOf(
        w: String,
        primary: String,
    ): Int? {
        if (primary == "ru") {
            return russian
                .indexOfFirst { (stem, endings) -> w.startsWith(stem) && w.removePrefix(stem) in endings }
                .takeIf { it >= 0 }
        }
        val language = languages[primary] ?: return null
        return language.stems
            .indexOfFirst { stems -> stems.any { w.startsWith(it) && w.removePrefix(it) in language.endings } }
            .takeIf { it >= 0 }
    }
}
