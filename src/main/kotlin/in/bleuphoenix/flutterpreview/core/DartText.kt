package `in`.bleuphoenix.flutterpreview.core

/**
 * Small text utilities for scanning Dart source without a full parser.
 *
 * [sanitize] returns a copy of the source with the same length in which comments and the contents of
 * string literals are replaced by spaces (newlines and the quote characters are kept). Searching the
 * sanitized copy for `@Preview`, `class`, brackets or commas never hits text inside a string or a
 * comment, and every offset still points at the same character in the original source.
 */
object DartText {

    fun sanitize(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            i = when {
                source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    blank(out, i, end)
                    end
                }
                source.startsWith("/*", i) -> {
                    val end = blockCommentEnd(source, i)
                    blank(out, i, end)
                    end
                }
                c == '\'' || c == '"' -> skipString(source, i, out)
                else -> i + 1
            }
        }
        return out.toString()
    }

    /** Index of the bracket that closes the one at [openIndex], or -1. Use on sanitized text. */
    fun matchingClose(sanitized: String, openIndex: Int): Int {
        val open = sanitized[openIndex]
        val close = when (open) {
            '(' -> ')'
            '{' -> '}'
            '[' -> ']'
            '<' -> '>'
            else -> return -1
        }
        var depth = 0
        for (i in openIndex until sanitized.length) {
            when (sanitized[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** Splits [sanitized] at commas that are not nested inside brackets; returns index ranges. */
    fun splitTopLevel(sanitized: String, separator: Char = ','): List<IntRange> {
        val parts = mutableListOf<IntRange>()
        var depth = 0
        var start = 0
        for (i in sanitized.indices) {
            when (sanitized[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                separator -> if (depth == 0) {
                    parts += start until i
                    start = i + 1
                }
            }
        }
        parts += start until sanitized.length
        return parts.filter { range -> sanitized.substring(range).isNotBlank() }
    }

    /** Concatenated value of the (adjacent) string literals in [expression], or null if there are none. */
    fun stringLiteralValue(expression: String): String? {
        val regex = Regex("""r?('''|""${'"'}|'|")((?:\\.|(?!\1).)*?)\1""", RegexOption.DOT_MATCHES_ALL)
        val matches = regex.findAll(expression).toList()
        if (matches.isEmpty()) return null
        return matches.joinToString("") { match ->
            val raw = match.value.startsWith("r")
            val body = match.groupValues[2]
            if (raw) body else unescape(body)
        }
    }

    fun lineOf(text: String, offset: Int): Int {
        var line = 1
        for (i in 0 until minOf(offset, text.length)) if (text[i] == '\n') line++
        return line
    }

    private fun unescape(body: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (val next = body[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun blank(out: StringBuilder, from: Int, to: Int) {
        for (k in from until minOf(to, out.length)) if (out[k] != '\n') out.setCharAt(k, ' ')
    }

    /** Dart block comments nest. */
    private fun blockCommentEnd(source: String, start: Int): Int {
        var depth = 0
        var j = start
        while (j < source.length) {
            when {
                source.startsWith("/*", j) -> {
                    depth++
                    j += 2
                }
                source.startsWith("*/", j) -> {
                    depth--
                    j += 2
                    if (depth == 0) return j
                }
                else -> j++
            }
        }
        return source.length
    }

    /** Skips the string literal starting at [start] (a quote), blanking its contents. Returns the index after it. */
    private fun skipString(source: String, start: Int, out: StringBuilder): Int {
        val quote = source[start]
        val raw = start > 0 && source[start - 1] == 'r' &&
            (start < 2 || !(source[start - 2].isLetterOrDigit() || source[start - 2] == '_'))
        val triple = source.startsWith("$quote$quote$quote", start)
        val delimiter = if (triple) "$quote$quote$quote" else quote.toString()
        var j = start + delimiter.length
        while (j < source.length) {
            val c = source[j]
            when {
                !raw && c == '\\' -> {
                    blank(out, j, j + 2)
                    j += 2
                }
                !triple && c == '\n' -> return j // unterminated single-line string
                source.startsWith(delimiter, j) -> return j + delimiter.length
                !raw && c == '$' && j + 1 < source.length && source[j + 1] == '{' -> {
                    val end = interpolationEnd(source, j + 1, out)
                    blank(out, j, end)
                    j = end
                }
                else -> {
                    blank(out, j, j + 1)
                    j++
                }
            }
        }
        return source.length
    }

    private fun interpolationEnd(source: String, openBrace: Int, out: StringBuilder): Int {
        var depth = 0
        var j = openBrace
        while (j < source.length) {
            when (source[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return j + 1
                }
                '\'', '"' -> {
                    j = skipString(source, j, out)
                    continue
                }
            }
            j++
        }
        return source.length
    }
}
