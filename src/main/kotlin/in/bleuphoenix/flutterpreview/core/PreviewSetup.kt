package `in`.bleuphoenix.flutterpreview.core

import java.io.File

/**
 * The per-package setup hook: `<package>/.flutter_preview/setup.dart` with `Future<void> flutterPreviewSetup()`,
 * run once before previews render. It belongs to the plugin, not to the app or its tests, so it is created
 * from a commented template on the first render and gitignored.
 */
object PreviewSetup {

    const val DIR = ".flutter_preview"
    const val FILE = "$DIR/setup.dart"
    const val GITIGNORE_ENTRY = "/$DIR/"

    data class Ensured(val file: File, val created: Boolean, val gitignoreUpdated: Boolean)

    private val template: String by lazy {
        PreviewSetup::class.java.getResource("/templates/preview_setup.dart")!!.readText()
    }

    fun file(root: PubRoot): File = File(root.dir, FILE)

    /**
     * Creates the setup file from the template if it doesn't exist, and only then makes sure the package's
     * `.gitignore` ignores it. If someone later removes the entry on purpose, it isn't added back.
     */
    fun ensure(root: PubRoot): Ensured {
        val file = file(root)
        if (file.exists()) return Ensured(file, created = false, gitignoreUpdated = false)
        file.parentFile.mkdirs()
        file.writeText(template)
        return Ensured(file, created = true, gitignoreUpdated = ignore(File(root.dir, ".gitignore")))
    }

    /** Appends [GITIGNORE_ENTRY] to [gitignore] (creating it) unless an equivalent entry is already there. */
    internal fun ignore(gitignore: File): Boolean {
        val text = if (gitignore.exists()) gitignore.readText() else ""
        val equivalent = setOf(DIR, "$DIR/", "/$DIR", "/$DIR/", "$DIR/*", "/$DIR/*", "$DIR/**", "/$DIR/**")
        if (text.lineSequence().any { it.trim() in equivalent }) return false
        val separator = when {
            text.isEmpty() -> ""
            text.endsWith("\n\n") -> ""
            text.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        gitignore.appendText("$separator# Flutter Preview plugin: local preview setup (per developer)\n$GITIGNORE_ENTRY\n")
        return true
    }
}
