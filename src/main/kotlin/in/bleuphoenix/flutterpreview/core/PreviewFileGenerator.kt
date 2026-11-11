package `in`.bleuphoenix.flutterpreview.core

import java.io.File

/**
 * Adds a `@Preview` for a widget to a separate preview file under `<package>/previews/`: either a new file
 * that mirrors the widget's location, or an existing preview file.
 */
object PreviewFileGenerator {

    const val PREVIEWS_DIR = "previews"

    class GenerationException(message: String) : RuntimeException(message)

    data class Result(
        /** Full new content of the preview file. */
        val text: String,
        val functionName: String,
        /** Offset of the generated `@Preview` in [text]. */
        val previewOffset: Int,
    )

    /**
     * `lib/widgets/system_inspection/row.dart` → `previews/system_inspection/row_previews.dart`: the path under
     * `lib/` without its top folder (`widgets`, `screens`, …) when there is a deeper one.
     */
    fun defaultPreviewFile(root: PubRoot, widgetFile: File): File {
        val lib = File(root.dir, "lib").canonicalFile
        val file = widgetFile.canonicalFile
        val base = if (file.startsWith(lib)) lib else root.dir.canonicalFile
        val folders = file.parentFile.relativeTo(base).invariantSeparatorsPath.split('/').filter { it.isNotEmpty() }
        val kept = if (folders.size >= 2) folders.drop(1) else folders
        val dir = kept.fold(File(root.dir, PREVIEWS_DIR)) { d, segment -> File(d, segment) }
        return File(dir, "${file.nameWithoutExtension}_previews.dart")
    }

    /** Dart files under `previews/` that already contain previews, those in [preferredDir] first. */
    fun existingPreviewFiles(root: PubRoot, preferredDir: File? = null): List<File> {
        val dir = File(root.dir, PREVIEWS_DIR)
        if (!dir.isDirectory) return emptyList()
        val preferred = preferredDir?.canonicalPath
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "dart" && it.readText().contains("@Preview") }
            .sortedWith(compareBy({ it.parentFile.canonicalPath != preferred }, { it.path }))
            .toList()
    }

    /**
     * Adds a preview of [cls] (declared in [widgetFile]) to [targetFile], whose current content is [targetText]
     * (null if the file doesn't exist yet). Reuses the `group:` and `wrapper:` of the target's last preview so the
     * new one matches the file's conventions.
     */
    fun generate(
        root: PubRoot,
        widgetFile: File,
        widgetText: String,
        cls: PreviewStubGenerator.WidgetClass,
        targetFile: File,
        targetText: String?,
    ): Result {
        if (cls.name.startsWith("_")) {
            throw GenerationException("${cls.name} is private: a preview in another file can't use it. Make the class public.")
        }
        val call = PreviewStubGenerator.constructorCall(widgetText, cls, allowPrivate = false)
            ?: throw GenerationException("${cls.name} only has private constructors, which a preview in another file can't call.")

        val existing = targetText.orEmpty()
        val last = PreviewParser.parse(existing).lastOrNull()
        val arguments = listOfNotNull(
            last?.groupExpression?.let { "group: $it" },
            "name: '${cls.name}'",
            last?.wrapper?.let { "wrapper: $it" },
        )
        val functionName = PreviewStubGenerator.uniqueName(
            cls.name.trimStart('_').replaceFirstChar { it.lowercase() } + "Preview",
            existing,
        )
        val function = PreviewStubGenerator.functionCode(functionName, call, arguments)

        val library = HarnessGenerator.libraryOf(widgetFile, widgetText)
        val widgetImport = root.importUri(library, from = targetFile.parentFile)
        val imports = buildList {
            if (!importsAny(existing, FLUTTER_UI_LIBRARIES)) add("package:flutter/material.dart")
            if (!importsAny(existing, listOf(PreviewStubGenerator.PREVIEW_IMPORT))) add(PreviewStubGenerator.PREVIEW_IMPORT)
            if (!importsFile(root, targetFile, existing, library)) add(widgetImport)
        }.map { "import '$it';" }

        val text = if (targetText == null) {
            imports.joinToString("\n") + "\n\n" + function
        } else {
            withImports(existing, imports).trimEnd() + "\n\n" + function
        }
        return Result(text, functionName, text.lastIndexOf("@Preview"))
    }

    private val FLUTTER_UI_LIBRARIES = listOf(
        "package:flutter/material.dart",
        "package:flutter/widgets.dart",
        "package:flutter/cupertino.dart",
    )

    private val importDirective = Regex("""(?m)^\s*import\s+(['"])([^'"]+)\1[^;]*;""")

    private fun importsAny(text: String, uris: List<String>): Boolean =
        importDirective.findAll(text).any { it.groupValues[2] in uris }

    /** Whether [text] (the content of [targetFile]) already imports [library], by package or relative URI. */
    private fun importsFile(root: PubRoot, targetFile: File, text: String, library: File): Boolean {
        val wanted = library.canonicalFile
        return importDirective.findAll(text).any { match ->
            val uri = match.groupValues[2]
            val resolved = when {
                uri.startsWith("package:${root.packageName}/") ->
                    File(File(root.dir, "lib"), uri.removePrefix("package:${root.packageName}/"))
                uri.startsWith("package:") || uri.startsWith("dart:") -> null
                else -> File(targetFile.parentFile, uri)
            }
            resolved?.canonicalFile == wanted
        }
    }

    /** [text] with [imports] added after its last import directive (or at the top). */
    private fun withImports(text: String, imports: List<String>): String {
        if (imports.isEmpty()) return text
        val sanitized = DartText.sanitize(text)
        val last = Regex("""(?m)^\s*import\s[^;]*;""").findAll(sanitized).lastOrNull()
        return if (last == null) {
            imports.joinToString("\n") + "\n\n" + text
        } else {
            val at = last.range.last + 1
            text.substring(0, at) + "\n" + imports.joinToString("\n") + text.substring(at)
        }
    }
}
