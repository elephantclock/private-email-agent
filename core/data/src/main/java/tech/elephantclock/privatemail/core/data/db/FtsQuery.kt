package tech.elephantclock.privatemail.core.data.db

/** Builds a safe FTS4 MATCH expression from what the user typed: every word, as a prefix, all required. */
object FtsQuery {
    fun of(userText: String): String? {
        val words = userText.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.take(8)
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "\"${it.lowercase()}*\"" }
    }
}
