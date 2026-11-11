package `in`.bleuphoenix.flutterpreview.core

/** One `@Preview(...)` declaration found in a Dart file. */
data class PreviewEntry(
    /** `name:` argument of the annotation, if any. */
    val name: String?,
    /** `group:` argument of the annotation, if any. */
    val group: String?,
    /** Dart expression that builds the preview widget, e.g. `MyWidget.preview()`; null when [problem] is set. */
    val invocation: String?,
    /** Declaration as written, e.g. `MyWidget.preview` or `buttonPreview`. */
    val declaration: String,
    /** 1-based line of the annotation. */
    val line: Int,
    /** Offset of the annotation in the file. */
    val offset: Int,
    /** Offset just after the annotated declaration (its body or `;`), for caret matching. */
    val endOffset: Int,
    /** `size: Size(w, h)` in logical pixels. */
    val width: Double?,
    val height: Double?,
    /** `wrapper:` function name, applied as `wrapper(child)`. */
    val wrapper: String?,
    /** `brightness:` argument; only `Brightness.light` / `Brightness.dark` are understood. */
    val brightness: String?,
    /** `textScaleFactor:` argument. */
    val textScaleFactor: Double?,
    /** Why this preview can't be rendered, if it can't. */
    val problem: String?,
) {
    val title: String get() = name ?: declaration

    /** Identity used to keep the selection across re-parses. */
    val key: String get() = "$declaration|${name.orEmpty()}"
}

/**
 * Finds `@Preview(...)` annotations in Dart source and works out how to call each annotated
 * function, static method or constructor from another library.
 */
object PreviewParser {

    private val previewAnnotation = Regex("""@Preview(?![\w$])""")
    private val containerDeclaration =
        Regex("""\b(?:(?:abstract|base|final|sealed|interface)\s+)*(class|mixin|extension)\s+([A-Za-z_$][\w$]*)""")
    private val declarationName = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*$""")
    private val sizeExpression =
        Regex("""Size\s*\(\s*([0-9]+(?:\.[0-9]+)?)\s*,\s*([0-9]+(?:\.[0-9]+)?)\s*\)""")

    fun parse(source: String): List<PreviewEntry> {
        val sanitized = DartText.sanitize(source)
        val containers = containers(sanitized)
        return previewAnnotation.findAll(sanitized).mapNotNull { match ->
            parseOne(source, sanitized, match.range.first, match.range.last + 1, containers)
        }.toList()
    }

    private data class Container(val name: String, val body: IntRange)

    private fun containers(sanitized: String): List<Container> {
        val result = mutableListOf<Container>()
        for (match in containerDeclaration.findAll(sanitized)) {
            val brace = sanitized.indexOf('{', match.range.last)
            val semicolon = sanitized.indexOf(';', match.range.last)
            if (brace < 0 || (semicolon in 0 until brace)) continue // e.g. `class A = B with C;`
            val close = DartText.matchingClose(sanitized, brace)
            if (close > brace) result += Container(match.groupValues[2], brace..close)
        }
        return result
    }

    private fun parseOne(
        source: String,
        sanitized: String,
        annotationStart: Int,
        annotationEnd: Int,
        containers: List<Container>,
    ): PreviewEntry? {
        val open = skipWhitespace(sanitized, annotationEnd)
        if (open >= sanitized.length || sanitized[open] != '(') return null
        val close = DartText.matchingClose(sanitized, open)
        if (close < 0) return null

        val args = namedArguments(source, sanitized, open + 1, close)
        val declarationStart = skipOtherAnnotations(sanitized, close + 1)
        val paramsOpen = sanitized.indexOf('(', declarationStart)
        val line = DartText.lineOf(source, annotationStart)
        if (paramsOpen < 0) return null

        val header = sanitized.substring(declarationStart, paramsOpen)
        val declaration = declarationName.find(header)?.groupValues?.get(1)?.takeUnless { header.contains(';') }
            ?: return null
        val paramsClose = DartText.matchingClose(sanitized, paramsOpen)
        val params = if (paramsClose > paramsOpen) sanitized.substring(paramsOpen + 1, paramsClose).trim() else ""
        val endOffset = if (paramsClose > paramsOpen) declarationEnd(sanitized, paramsClose + 1) else paramsOpen
        val container = containers.filter { annotationStart in it.body }.minByOrNull { it.body.last - it.body.first }

        val size = args["size"]?.let { sizeExpression.find(it) }
        var problem: String? = null
        val invocation: String? = when {
            container == null -> "$declaration()"
            declaration == container.name || declaration.startsWith("${container.name}.") -> "$declaration()"
            Regex("""\bstatic\b""").containsMatchIn(header) -> "${container.name}.$declaration()"
            else -> {
                problem = "Only top-level functions, static methods and constructors can be previewed."
                null
            }
        }
        val names = listOfNotNull(container?.name) + declaration.split('.')
        if (problem == null && names.any { it.startsWith("_") }) {
            problem = "Private declarations can't be previewed: make `${names.joinToString(".")}` public."
        }
        if (problem == null && params.isNotEmpty() && (params.contains("required") || params.first() !in "{[")) {
            problem = "Preview functions must not take required arguments."
        }

        return PreviewEntry(
            name = args["name"]?.let(DartText::stringLiteralValue),
            group = args["group"]?.let(DartText::stringLiteralValue),
            invocation = if (problem == null) invocation else null,
            declaration = if (container != null && declaration != container.name &&
                !declaration.startsWith("${container.name}.")
            ) {
                "${container.name}.$declaration"
            } else {
                declaration
            },
            line = line,
            offset = annotationStart,
            endOffset = endOffset,
            width = size?.groupValues?.get(1)?.toDouble(),
            height = size?.groupValues?.get(2)?.toDouble(),
            wrapper = args["wrapper"]?.trim()?.takeIf { it.matches(Regex("""[A-Za-z_$][\w$.]*""")) },
            brightness = args["brightness"]?.trim(),
            textScaleFactor = args["textScaleFactor"]?.trim()?.toDoubleOrNull(),
            problem = problem,
        )
    }

    /** End of a declaration whose parameter list closed just before [from]: a `{}` body or the `;` ending `=>`/initializers. */
    private fun declarationEnd(sanitized: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < sanitized.length) {
            when (sanitized[i]) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                '{' -> if (depth == 0) {
                    val close = DartText.matchingClose(sanitized, i)
                    return if (close < 0) sanitized.length else close + 1
                } else {
                    depth++
                }
                '}' -> if (depth == 0) return i else depth--
                ';' -> if (depth == 0) return i + 1
            }
            i++
        }
        return sanitized.length
    }

    private fun namedArguments(source: String, sanitized: String, from: Int, to: Int): Map<String, String> {
        val inner = sanitized.substring(from, to)
        val result = mutableMapOf<String, String>()
        for (range in DartText.splitTopLevel(inner)) {
            val original = source.substring(from + range.first, from + range.last + 1)
            val colon = sanitized.substring(from + range.first, from + range.last + 1).indexOf(':')
            if (colon < 0) continue
            val key = original.substring(0, colon).trim()
            if (key.matches(Regex("""[A-Za-z_$][\w$]*"""))) result[key] = original.substring(colon + 1).trim()
        }
        return result
    }

    /** Skips whitespace and any further annotations (`@foo`, `@foo.bar(...)`) after the preview annotation. */
    private fun skipOtherAnnotations(sanitized: String, from: Int): Int {
        var i = skipWhitespace(sanitized, from)
        while (i < sanitized.length && sanitized[i] == '@') {
            i++
            while (i < sanitized.length && (sanitized[i].isLetterOrDigit() || sanitized[i] in "_$.")) i++
            i = skipWhitespace(sanitized, i)
            if (i < sanitized.length && sanitized[i] == '(') {
                val close = DartText.matchingClose(sanitized, i)
                if (close < 0) return sanitized.length
                i = skipWhitespace(sanitized, close + 1)
            }
        }
        return i
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }
}
