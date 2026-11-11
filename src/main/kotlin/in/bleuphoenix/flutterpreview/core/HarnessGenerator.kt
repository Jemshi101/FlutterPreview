package `in`.bleuphoenix.flutterpreview.core

import java.io.File

data class RenderOptions(
    val defaultWidth: Double = 400.0,
    val defaultHeight: Double = 800.0,
    val pixelRatio: Double = 2.0,
)

/** Generated `flutter test` source plus the test id given to each preview (id → [PreviewEntry.key]). */
data class Harness(val code: String, val ids: Map<String, String>)

/**
 * Generates the `flutter test` file that renders previews to PNGs, one test per preview, so a whole
 * file renders in a single `flutter test` run and one failing preview doesn't stop the others.
 *
 * The harness imports the previewed library and every import of that library, so the preview
 * expressions and their `wrapper:` resolve exactly as they do in the source file. The harness's own
 * Flutter imports are prefixed (`fp_m.` …) so they never clash with the project's names.
 */
object HarnessGenerator {

    /** Optional per-project hook: `Future<void> flutterPreviewSetup()` in this file runs before rendering. */
    const val SETUP_FILE = PreviewSetup.FILE

    private val template: String by lazy {
        HarnessGenerator::class.java.getResource("/templates/preview_harness.dart")!!.readText()
    }

    /** Renders [entries] (those without an invocation are skipped). */
    fun generate(
        root: PubRoot,
        sourceFile: File,
        sourceText: String,
        entries: List<PreviewEntry>,
        options: RenderOptions = RenderOptions(),
    ): Harness {
        val renderable = entries.filter { it.invocation != null }
        require(renderable.isNotEmpty()) { "No previews to render" }
        val library = libraryOf(sourceFile, sourceText)
        val libraryText = if (library == sourceFile) sourceText else library.readText()
        val imports = buildList {
            add("import '${root.importUri(library)}';")
            addAll(importsOf(root, library, libraryText))
        }.distinct()

        val ids = LinkedHashMap<String, String>()
        val previews = renderable.mapIndexed { index, entry ->
            val id = "p$index"
            ids[id] = entry.key
            val build = entry.wrapper?.let { "${it}(${entry.invocation})" } ?: entry.invocation!!
            // Argument expressions go into Dart as written, so constants and getters resolve like in the source.
            "  FpPreview('$id', () => $build, ${entry.sizeExpression ?: "null"}, " +
                "${entry.textScaleExpression ?: "null"}, ${entry.brightness ?: "null"}),"
        }

        val setup = File(root.dir, SETUP_FILE).isFile
        val values = mapOf(
            "SETUP_IMPORT" to if (setup) "import '${root.importUri(File(root.dir, SETUP_FILE))}' as fp_setup;" else "",
            "SOURCE_IMPORTS" to imports.joinToString("\n"),
            "PIXEL_RATIO" to dart(options.pixelRatio),
            "DEFAULT_WIDTH" to dart(options.defaultWidth),
            "DEFAULT_HEIGHT" to dart(options.defaultHeight),
            "PREVIEWS" to previews.joinToString("\n"),
            "SETUP_CALL" to if (setup) "await fp_setup.flutterPreviewSetup();" else "",
        )
        val code = values.entries.fold(template) { text, (key, value) -> text.replace("/*FP:$key*/", value) }
        return Harness(code, ids)
    }

    /** A `part of` file can't be imported: use the library it belongs to. */
    internal fun libraryOf(file: File, text: String): File {
        val sanitized = DartText.sanitize(text)
        val partOf = Regex("""(?m)^\s*part\s+of\s+(['"])""").find(sanitized) ?: return file
        val start = partOf.range.last
        val end = sanitized.indexOf(';', start)
        val uri = DartText.stringLiteralValue(text.substring(start, end)) ?: return file
        return if (uri.startsWith("package:") || uri.startsWith("dart:")) file else File(file.parentFile, uri)
    }

    /** The library's import directives, with relative URIs rewritten so they resolve from the harness. */
    private fun importsOf(root: PubRoot, library: File, text: String): List<String> {
        val sanitized = DartText.sanitize(text)
        return Regex("""(?m)^\s*import\s""").findAll(sanitized).mapNotNull { match ->
            val end = sanitized.indexOf(';', match.range.first)
            if (end < 0) return@mapNotNull null
            val directive = text.substring(match.range.first, end + 1).trim()
            Regex("""(['"])([^'"]+)\1""").replace(directive) { literal ->
                val uri = literal.groupValues[2]
                if (uri.startsWith("dart:") || uri.startsWith("package:")) {
                    literal.value
                } else {
                    "'" + root.importUri(File(library.parentFile, uri)) + "'"
                }
            }
        }.toList()
    }

    private fun dart(value: Double): String = if (value % 1.0 == 0.0) "${value.toLong()}.0" else value.toString()
}
