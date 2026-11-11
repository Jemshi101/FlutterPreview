package `in`.bleuphoenix.flutterpreview.core

/**
 * Builds a `@Preview` function for a widget class, with placeholder values for the required
 * constructor arguments. Text-based, like [PreviewParser], so it doesn't need the Dart plugin's PSI.
 */
object PreviewStubGenerator {

    const val PREVIEW_IMPORT = "package:flutter/widget_previews.dart"

    data class WidgetClass(
        val name: String,
        /** From `class` to the opening `{`: where the intention is offered. */
        val header: IntRange,
        val bodyOpen: Int,
        val bodyClose: Int,
    )

    /** A text insertion; apply a list of them in the given order (they are sorted last-first). */
    data class Insertion(val offset: Int, val text: String)

    private val classHeader = Regex(
        """\b(?:(?:abstract|base|final|sealed|interface)\s+)*class\s+([A-Za-z_$][\w$]*)[^{;]*?\bextends\s+([\w$.]+)""",
    )

    /** The widget class whose header contains [offset], if any. */
    fun widgetClassAt(source: String, offset: Int): WidgetClass? {
        val sanitized = DartText.sanitize(source)
        for (match in classHeader.findAll(sanitized)) {
            if (!match.groupValues[2].endsWith("Widget")) continue
            val open = sanitized.indexOf('{', match.range.last)
            if (open < 0) continue
            val classStart = sanitized.lastIndexOf("class", match.groups[1]!!.range.first)
            if (offset !in classStart..open) continue
            val close = DartText.matchingClose(sanitized, open)
            if (close < 0) return null
            return WidgetClass(match.groupValues[1], classStart..open, open, close)
        }
        return null
    }

    fun previewFunctionName(source: String, cls: WidgetClass): String {
        val base = cls.name.trimStart('_').replaceFirstChar { it.lowercase() } + "Preview"
        var name = base
        var n = 2
        while (Regex("""\b${Regex.escape(name)}\s*\(""").containsMatchIn(source)) name = base + n++
        return name
    }

    /** Insertions that add the preview function after the class and the `widget_previews` import if missing. */
    fun insertions(source: String, cls: WidgetClass): List<Insertion> {
        val sanitized = DartText.sanitize(source)
        val functionName = previewFunctionName(source, cls)
        val call = constructorCall(source, sanitized, cls)
        val function = buildString {
            append("\n\n@Preview(name: '${cls.name}')\n")
            append("Widget $functionName() {\n")
            append("  return $call;\n")
            append("}\n")
        }
        val result = mutableListOf(Insertion(cls.bodyClose + 1, function.trimEnd('\n')))

        if (!source.contains(PREVIEW_IMPORT)) {
            val imports = Regex("""(?m)^\s*import\s[^;]*;""").findAll(sanitized).toList()
            val offset = imports.lastOrNull()?.range?.last?.plus(1) ?: 0
            val text = if (imports.isEmpty()) "import '$PREVIEW_IMPORT';\n\n" else "\nimport '$PREVIEW_IMPORT';"
            result += Insertion(offset, text)
        }
        return result.sortedByDescending { it.offset }
    }

    private data class Param(val name: String, val type: String?, val named: Boolean)

    private data class Constructor(val callee: String, val params: List<Param>, val rank: Int)

    /**
     * `MyWidget(label: 'Label', onTap: () {})`. Picks the unnamed constructor, else the public named one with
     * the fewest required arguments (e.g. `Row.header()`), and a private one (`Row._`) only as a last resort.
     */
    private fun constructorCall(source: String, sanitized: String, cls: WidgetClass): String {
        val constructor = Regex("""(?<![\w$.])${Regex.escape(cls.name)}(\.[A-Za-z_$][\w$]*)?\s*\(""")
            .findAll(sanitized, cls.bodyOpen + 1)
            .filter { it.range.first < cls.bodyClose && depthInBody(sanitized, cls.bodyOpen, it.range.first) == 0 }
            .mapNotNull { match ->
                val open = match.range.last
                val close = DartText.matchingClose(sanitized, open)
                if (close < 0) return@mapNotNull null
                val suffix = match.groups[1]?.value.orEmpty()
                val rank = when {
                    suffix.isEmpty() -> 0
                    suffix.startsWith("._") -> 2
                    else -> 1
                }
                Constructor(cls.name + suffix, requiredParams(source, sanitized, open + 1, close, cls), rank)
            }
            .sortedWith(compareBy({ it.rank }, { it.params.size }))
            .firstOrNull()
            ?: return "${cls.name}()"
        val callee = constructor.callee
        val params = constructor.params
        if (params.isEmpty()) return "$callee()"
        val args = params.joinToString(",\n") { p ->
            val value = placeholder(p.name, p.type)
            "    " + (if (p.named) "${p.name}: " else "") + value
        }
        return "$callee(\n$args,\n  )"
    }

    private fun depthInBody(sanitized: String, bodyOpen: Int, at: Int): Int {
        var depth = 0
        for (i in bodyOpen + 1 until at) {
            when (sanitized[i]) {
                '{', '(', '[' -> depth++
                '}', ')', ']' -> depth--
            }
        }
        return depth
    }

    private fun requiredParams(source: String, sanitized: String, from: Int, to: Int, cls: WidgetClass): List<Param> {
        val result = mutableListOf<Param>()
        for (range in DartText.splitTopLevel(sanitized.substring(from, to))) {
            val raw = sanitized.substring(from + range.first, from + range.last + 1).trim()
            when {
                raw.startsWith("{") -> {
                    val inner = raw.removePrefix("{").removeSuffix("}")
                    // Re-split the named group: splitTopLevel saw it as one bracketed part.
                    val groupStart = from + range.first + sanitized.substring(from + range.first).indexOf('{') + 1
                    for (named in DartText.splitTopLevel(inner)) {
                        val text = sanitized.substring(groupStart + named.first, groupStart + named.last + 1).trim()
                        if (!Regex("""^required\b""").containsMatchIn(text)) continue
                        param(source, sanitized, text.removePrefix("required").trim(), cls, named = true)?.let(result::add)
                    }
                }
                raw.startsWith("[") -> Unit // optional positional
                else -> param(source, sanitized, raw, cls, named = false)?.let(result::add)
            }
        }
        return result
    }

    /** `this.label`, `super.child`, `String label`, `void Function() onTap`, `Color color = x` (defaulted → null). */
    private fun param(source: String, sanitized: String, text: String, cls: WidgetClass, named: Boolean): Param? {
        val declaration = text.substringBefore('=').trim().replace(Regex("""@\w+(\([^)]*\))?\s*"""), "")
        if (named && text.contains('=')) return null
        val name = Regex("""([A-Za-z_$][\w$]*)\s*$""").find(declaration)?.groupValues?.get(1) ?: return null
        val type = when {
            declaration.startsWith("this.") -> fieldType(sanitized, cls, name)
            declaration.startsWith("super.") -> if (name == "key") return null else superType(name)
            else -> declaration.removeSuffix(name).trim().removePrefix("final").removePrefix("covariant").trim().ifEmpty { null }
        }
        return Param(name, type, named)
    }

    private fun superType(name: String): String? = when (name) {
        "child" -> "Widget"
        "children" -> "List<Widget>"
        else -> null
    }

    /** Type of field [name] declared directly in the class body. */
    private fun fieldType(sanitized: String, cls: WidgetClass, name: String): String? {
        val body = sanitized.substring(cls.bodyOpen + 1, cls.bodyClose)
        val match = Regex("""(?:^|[;{}])\s*((?:@\w+\s*)*(?:(?:final|late|const|static)\s+)*)([^;{}=]*?)\s+${Regex.escape(name)}\s*[;=]""")
            .find(body) ?: return null
        return match.groupValues[2].trim().ifEmpty { null }
    }

    private val sizeLike = Regex("(?i)size|width|height|padding|margin|radius|spacing|gap|extent")

    /** A value of [type] that compiles; unknown types get an explicit TODO that throws at render time. */
    fun placeholder(name: String, type: String?): String {
        val t = type?.trim().orEmpty()
        val nonNull = t.removeSuffix("?")
        val label = name.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
        return when {
            nonNull == "String" -> "'$label'"
            nonNull == "int" || nonNull == "num" -> "1"
            nonNull == "double" && name.contains(sizeLike) -> "16.0"
            nonNull == "double" -> "1.0"
            nonNull == "bool" -> "true"
            nonNull == "Color" -> "const Color(0xFF2196F3)"
            nonNull == "Duration" -> "const Duration(seconds: 1)"
            nonNull == "DateTime" -> "DateTime(2026)"
            nonNull == "Size" -> "const Size(100, 100)"
            nonNull == "Widget" -> "const Placeholder(fallbackHeight: 48)"
            nonNull == "VoidCallback" || nonNull == "GestureTapCallback" -> "() {}"
            nonNull == "AsyncCallback" -> "() async {}"
            nonNull.startsWith("ValueChanged") || nonNull.startsWith("ValueSetter") -> "(_) {}"
            nonNull.contains(" Function(") || nonNull.startsWith("Function(") -> functionPlaceholder(nonNull)
            nonNull.startsWith("List") || nonNull.startsWith("Iterable") -> "const []"
            nonNull.startsWith("Set") || nonNull.startsWith("Map") -> "const {}"
            t.endsWith("?") -> "null"
            else -> "throw UnimplementedError('TODO: $name${if (t.isNotEmpty()) " ($t)" else ""}')"
        }
    }

    private fun functionPlaceholder(type: String): String {
        val returnType = type.substringBefore("Function(").trim()
        val params = type.substringAfter("Function(").substringBeforeLast(")").trim()
        val count = if (params.isEmpty()) 0 else DartText.splitTopLevel(DartText.sanitize(params)).size
        val args = List(count) { "_".repeat(it + 1) }.joinToString(", ") // `_, __`: works before Dart 3.7 wildcards too
        return when {
            returnType.isEmpty() || returnType == "void" -> "($args) {}"
            returnType.startsWith("Future") -> "($args) async {}"
            else -> "($args) => throw UnimplementedError('TODO')"
        }
    }
}
