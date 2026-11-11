package `in`.bleuphoenix.flutterpreview.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The render of one preview. */
data class PreviewImage(
    val png: ByteArray,
    val pixelRatio: Double,
    /** Layout errors, overflow messages and other warnings captured while rendering. */
    val problems: List<String>,
    /** Harmless observations, e.g. "animation still running". */
    val notes: List<String>,
)

/** The render of all previews of one file. */
data class BatchResult(
    /** [PreviewEntry.key] → image. Previews that failed to render are missing. */
    val images: Map<String, PreviewImage>,
    /** Full `flutter test` output. */
    val log: String,
    val exitCode: Int,
    val millis: Long,
)

class RenderCancelledException : RuntimeException("Render cancelled")

/**
 * Renders previews by writing the generated harness into `<package>/.dart_tool/flutter_preview/`
 * and running `flutter test` on it. Plain JVM code, so it can run (and be tested) outside the IDE.
 */
class PreviewRenderer(
    private val flutterSdk: File,
    private val environment: Map<String, String> = System.getenv(),
) {
    /** One render at a time: renders share the harness file. */
    private val lock = ReentrantLock()

    fun render(root: PubRoot, harness: Harness, isCancelled: () -> Boolean = { false }): BatchResult = lock.withLock {
        if (isCancelled()) throw RenderCancelledException()
        val started = System.currentTimeMillis()
        val harnessFile = File(root.harnessDir, "preview_test.dart")
        harnessFile.parentFile.mkdirs()
        harnessFile.writeText(harness.code)
        val outDir = Files.createTempDirectory("flutter-preview-").toFile()

        try {
            val command = buildList {
                add(File(flutterSdk, "bin/flutter").path)
                add("--no-color")
                add("test")
                if (File(root.dir, ".dart_tool/package_config.json").isFile) add("--no-pub")
                add("--timeout=60s")
                add("--reporter=expanded")
                add(harnessFile.relativeTo(root.dir).invariantSeparatorsPath)
            }
            val process = ProcessBuilder(command)
                .directory(root.dir)
                .redirectErrorStream(true)
                .also { builder ->
                    val env = builder.environment()
                    env.putAll(environment)
                    env["FLUTTER_ROOT"] = flutterSdk.path
                    env["FP_OUT_DIR"] = outDir.path
                    // GUI-launched IDEs on macOS have a minimal PATH; flutter's script needs git & co.
                    env["PATH"] = listOf(env["PATH"].orEmpty(), "/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin", "/opt/homebrew/bin")
                        .filter { it.isNotEmpty() }.joinToString(File.pathSeparator)
                }
                .start()

            val log = StringBuffer()
            val reader = Thread {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { log.append(it).append('\n') } }
            }.apply { isDaemon = true; start() }

            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    reader.join(1000)
                    throw RenderCancelledException()
                }
            }
            reader.join(2000)

            val images = harness.ids.mapNotNull { (id, key) ->
                val png = File(outDir, "$id.png").takeIf { it.isFile && it.length() > 0 } ?: return@mapNotNull null
                val json = File(outDir, "$id.png.json").takeIf { it.isFile }?.readText().orEmpty()
                key to PreviewImage(
                    png = png.readBytes(),
                    pixelRatio = Regex(""""pixelRatio"\s*:\s*([0-9.]+)""").find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 2.0,
                    problems = stringArray(json, "problems"),
                    notes = stringArray(json, "notes"),
                )
            }.toMap()
            BatchResult(images, log.toString(), process.exitValue(), System.currentTimeMillis() - started)
        } finally {
            outDir.deleteRecursively()
        }
    }

    /** Pulls a string array out of the harness's JSON without a JSON library. */
    private fun stringArray(json: String, key: String): List<String> {
        val array = Regex(""""$key"\s*:\s*\[((?:\s*"(?:\\.|[^"\\])*"\s*,?)*)\s*]""").find(json)?.groupValues?.get(1)
            ?: return emptyList()
        return Regex(""""((?:\\.|[^"\\])*)"""").findAll(array).map { match ->
            match.groupValues[1]
                .replace(Regex("""\\u([0-9a-fA-F]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }
                .replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
        }.toList()
    }
}
