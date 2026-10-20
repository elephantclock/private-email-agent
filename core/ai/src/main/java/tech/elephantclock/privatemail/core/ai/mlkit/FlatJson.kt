package tech.elephantclock.privatemail.core.ai.mlkit

/** A value [FlatJson] accepts: a JSON string or number. Anything else is not part of the schema. */
internal sealed interface FlatValue {
    data class Str(
        val value: String,
    ) : FlatValue

    data class Num(
        val value: Double,
    ) : FlatValue
}

/**
 * A strict reader for exactly one flat JSON object whose values are strings or numbers, and
 * nothing around it. No nesting, no `true`/`false`/`null`, no duplicate keys, no trailing commas,
 * no raw control characters in strings (RFC 8259). Fails with [FlatJsonException]; linear in the input.
 */
internal class FlatJson private constructor(
    private val s: String,
) {
    private var i = 0

    private fun fail(why: String): Nothing = throw FlatJsonException("$why at $i")

    private fun skipWs() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
    }

    private fun expect(c: Char) {
        if (i >= s.length || s[i] != c) fail("expected '$c'")
        i++
    }

    private fun readObject(): Map<String, FlatValue> {
        val out = LinkedHashMap<String, FlatValue>()
        skipWs()
        expect('{')
        skipWs()
        if (i < s.length && s[i] == '}') {
            i++
            return out
        }
        while (true) {
            skipWs()
            val key = readString()
            skipWs()
            expect(':')
            skipWs()
            val value = readValue()
            if (out.put(key, value) != null) fail("duplicate key '$key'")
            skipWs()
            if (i >= s.length) fail("unterminated object")
            when (s[i++]) {
                ',' -> continue
                '}' -> return out
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun readValue(): FlatValue {
        if (i >= s.length) fail("missing value")
        val c = s[i]
        return when {
            c == '"' -> FlatValue.Str(readString())
            c == '-' || c in '0'..'9' -> FlatValue.Num(readNumber())
            else -> fail("unsupported value")
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i++]
            when {
                c == '"' -> {
                    return out.toString()
                }

                c == '\\' -> {
                    if (i >= s.length) fail("bad escape")
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> out.append(readHex4())
                        else -> fail("bad escape")
                    }
                }

                c < ' ' -> {
                    fail("control character in string")
                }

                else -> {
                    out.append(c)
                }
            }
        }
    }

    private fun readHex4(): Char {
        if (i + 4 > s.length) fail("bad \\u escape")
        val hex = s.substring(i, i + 4)
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("bad \\u escape")
        i += 4
        return hex.toInt(16).toChar()
    }

    private fun readNumber(): Double {
        val start = i
        if (s[i] == '-') i++
        if (i >= s.length) fail("bad number")
        if (s[i] == '0') {
            i++
        } else if (s[i] in '1'..'9') {
            while (i < s.length && s[i] in '0'..'9') i++
        } else {
            fail("bad number")
        }
        if (i < s.length && s[i] == '.') {
            i++
            digits()
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            digits()
        }
        return s.substring(start, i).toDouble()
    }

    private fun digits() {
        val start = i
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == start) fail("bad number")
    }

    companion object {
        /** The one object in [text]; anything before or after it (but whitespace) is an error. */
        fun parseObject(text: String): Map<String, FlatValue> {
            val reader = FlatJson(text)
            val obj = reader.readObject()
            reader.skipWs()
            if (reader.i != text.length) reader.fail("text after the object")
            return obj
        }
    }
}

internal class FlatJsonException(
    message: String,
) : IllegalArgumentException(message)
