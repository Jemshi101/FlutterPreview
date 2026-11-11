package `in`.bleuphoenix.flutterpreview.core

import java.io.File

/** A Dart/Flutter package: the directory holding `pubspec.yaml`, and its package name. */
data class PubRoot(val dir: File, val packageName: String) {

    val harnessDir: File get() = File(dir, ".dart_tool/flutter_preview")

    /** URI another library uses to import [file]: `package:` for files under `lib/`, otherwise a path relative to [from]. */
    fun importUri(file: File, from: File = harnessDir): String {
        val lib = File(dir, "lib").canonicalFile
        val canonical = file.canonicalFile
        return if (canonical.startsWith(lib)) {
            "package:$packageName/" + canonical.relativeTo(lib).invariantSeparatorsPath
        } else {
            canonical.relativeTo(from.canonicalFile).invariantSeparatorsPath
        }
    }

    companion object {
        /** Nearest enclosing package of [file], or null. */
        fun find(file: File): PubRoot? {
            var dir: File? = if (file.isDirectory) file else file.parentFile
            while (dir != null) {
                val pubspec = File(dir, "pubspec.yaml")
                if (pubspec.isFile) {
                    val name = Regex("""^name:\s*([A-Za-z_][\w]*)""", RegexOption.MULTILINE)
                        .find(pubspec.readText())?.groupValues?.get(1)
                    return name?.let { PubRoot(dir, it) }
                }
                dir = dir.parentFile
            }
            return null
        }
    }
}

/** Finds the Flutter SDK used by a package. */
object FlutterSdkLocator {

    fun find(root: PubRoot?, env: Map<String, String>): File? {
        val candidates = sequence {
            root?.let { yield(fromPackageConfig(it.dir)) }
            env["FLUTTER_ROOT"]?.let { yield(File(it)) }
            env["PATH"]?.split(File.pathSeparator)?.forEach { entry ->
                val flutter = File(entry, "flutter")
                if (flutter.isFile) yield(flutter.canonicalFile.parentFile?.parentFile)
            }
            val home = System.getProperty("user.home")
            listOf("flutter", "Developer/flutter", "Developer/Flutter", "development/flutter", "sdk/flutter")
                .forEach { yield(File(home, it)) }
        }
        return candidates.filterNotNull().firstOrNull(::isSdk)
    }

    fun isSdk(dir: File): Boolean = File(dir, "bin/flutter").isFile && File(dir, "packages/flutter").isDirectory

    /** The `flutter` package entry of `.dart_tool/package_config.json` points at `<sdk>/packages/flutter`. */
    private fun fromPackageConfig(packageDir: File): File? {
        val config = File(packageDir, ".dart_tool/package_config.json").takeIf { it.isFile } ?: return null
        val entry = Regex("""\{[^{}]*"name"\s*:\s*"flutter"[^{}]*}""").find(config.readText())?.value ?: return null
        val rootUri = Regex(""""rootUri"\s*:\s*"([^"]+)"""").find(entry)?.groupValues?.get(1) ?: return null
        val path = if (rootUri.startsWith("file://")) java.net.URI(rootUri).path else File(config.parentFile, rootUri).path
        return File(path).canonicalFile.parentFile?.parentFile
    }
}
