package `in`.bleuphoenix.flutterpreview.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Renders every `@Preview` in a real Flutter package, one `flutter test` run per file. Skipped unless
 * FP_IT_PROJECT points at one:
 *
 *   FP_IT_PROJECT=/path/to/package ./gradlew test --tests '*RenderIntegrationTest*' --rerun
 *
 * PNGs are written to build/preview-renders/ for inspection.
 */
class RenderIntegrationTest {

    @Test
    fun `renders every preview in FP_IT_PROJECT`() {
        val projectPath = System.getenv("FP_IT_PROJECT").orEmpty()
        assumeTrue("FP_IT_PROJECT not set", projectPath.isNotEmpty())

        val root = PubRoot.find(File(projectPath, "pubspec.yaml"))!!
        val sdk = FlutterSdkLocator.find(root, System.getenv())
        assertNotNull("Flutter SDK not found", sdk)
        val renderer = PreviewRenderer(sdk!!)
        val outDir = File("build/preview-renders").apply { deleteRecursively(); mkdirs() }

        val skip = setOf(".dart_tool", "build", "android", "ios", "example", "test")
        val files = root.dir.walkTopDown()
            .onEnter { it == root.dir || (it.name !in skip && !it.name.startsWith(".")) }
            .filter { it.extension == "dart" && it.readText().contains("@Preview") }
        var rendered = 0
        for (file in files) {
            val text = file.readText()
            val entries = PreviewParser.parse(text).filter { it.invocation != null }
            if (entries.isEmpty()) continue
            val result = renderer.render(root, HarnessGenerator.generate(root, file, text, entries))
            println("${file.name}: ${entries.size} previews -> exit ${result.exitCode}, ${result.millis} ms")
            if (result.images.size != entries.size) println(result.log)
            for (entry in entries) {
                val image = result.images[entry.key]
                println("   ${entry.title}: ${if (image == null) "MISSING" else "ok"}, problems ${image?.problems?.size}, notes ${image?.notes}")
                image?.problems?.forEach { println("      problem: ${it.lineSequence().first()}") }
                image?.let { File(outDir, "${entry.title.replace(Regex("[^\\w-]+"), "_")}.png").writeBytes(it.png) }
            }
            assertEquals("Missing renders in ${file.name}", entries.size, result.images.size)
            rendered += entries.size
        }
        assertTrue("No previews found", rendered > 0)
    }
}

/** Generates a preview with [PreviewStubGenerator] for every widget class under lib/ and renders them. */
class StubIntegrationTest {

    @Test
    fun `generated previews compile and render`() {
        val projectPath = System.getenv("FP_IT_PROJECT").orEmpty()
        assumeTrue("FP_IT_PROJECT not set", projectPath.isNotEmpty())
        val root = PubRoot.find(File(projectPath, "pubspec.yaml"))!!
        val renderer = PreviewRenderer(FlutterSdkLocator.find(root, System.getenv())!!)
        val outDir = File("build/stub-renders").apply { deleteRecursively(); mkdirs() }
        val classDecl = Regex("""\bclass\s+\w+[^{;]*?\bextends\s+\w*Widget\b""")

        val sources = File(root.dir, "lib/widgets/system_inspection").walkTopDown()
            .filter { it.extension == "dart" && classDecl.containsMatchIn(it.readText()) }
            .toList()
        assumeTrue("no widget files", sources.isNotEmpty())
        // A copy of each file with the generated preview, compiled from the harness folder (the copy keeps package: imports).
        root.harnessDir.mkdirs()
        var total = 0
        for (source in sources) {
            var text = source.readText()
            if (text.contains(Regex("""(?m)^\s*(import|export|part)\s+['"](?!package:|dart:)"""))) continue // relative URIs would break in the copy
            var offset = 0
            while (true) {
                val match = classDecl.find(text, offset) ?: break
                val cls = PreviewStubGenerator.widgetClassAt(text, match.range.first) ?: break
                text = PreviewStubGenerator.insertions(text, cls)
                    .fold(StringBuilder(text)) { sb, ins -> sb.insert(ins.offset, ins.text) }.toString()
                offset = (PreviewStubGenerator.widgetClassAt(text, match.range.first)?.bodyClose ?: break) + 1
            }
            val copy = File(root.harnessDir, "stub_${source.nameWithoutExtension}.dart").apply { writeText(text) }
            try {
                val entries = PreviewParser.parse(text).filter { it.invocation != null }
                val result = renderer.render(root, HarnessGenerator.generate(root, copy, text, entries))
                println("${source.name}: ${entries.map { it.declaration }} -> exit ${result.exitCode}, rendered ${result.images.size}")
                if (result.images.isEmpty()) println(result.log.lines().filter { "Error" in it || "error" in it }.take(20).joinToString("\n"))
                entries.forEach { e -> result.images[e.key]?.let { File(outDir, "${e.declaration}.png").writeBytes(it.png) } }
                total += result.images.size
            } finally {
                copy.delete()
            }
        }
        assertTrue("Nothing rendered", total > 0)
    }
}

/**
 * "Generate Flutter Preview…": for each widget file under lib/widgets/system_inspection, writes a new preview file
 * and appends to a copy of an existing preview file (next to the original, so its relative imports work), then
 * renders both. Temporary files are named zz_fp_check_* and always deleted.
 */
class GenerateFileIntegrationTest {

    @Test
    fun `generated preview files compile and render`() {
        val projectPath = System.getenv("FP_IT_PROJECT").orEmpty()
        assumeTrue("FP_IT_PROJECT not set", projectPath.isNotEmpty())
        val root = PubRoot.find(File(projectPath, "pubspec.yaml"))!!
        val renderer = PreviewRenderer(FlutterSdkLocator.find(root, System.getenv())!!)
        val existing = PreviewFileGenerator.existingPreviewFiles(root).firstOrNull { it.name.endsWith("widgets_previews.dart") }
        assumeTrue("no existing preview file", existing != null)
        val outDir = File("build/generated-renders").apply { deleteRecursively(); mkdirs() }
        val classDecl = Regex("""\bclass\s+[A-Z]\w*[^{;]*?\bextends\s+\w*Widget\b""")
        val widgets = File(root.dir, "lib/widgets/system_inspection").walkTopDown()
            .filter { it.extension == "dart" && classDecl.containsMatchIn(it.readText()) }.toList()

        val temp = mutableListOf<File>()
        try {
            var appended = existing!!.readText()
            val appendFile = File(existing.parentFile, "zz_fp_check_append.dart").also { temp += it }
            for (widget in widgets) {
                val text = widget.readText()
                val cls = PreviewStubGenerator.widgetClassContaining(text, classDecl.find(text)!!.range.first + 6)!!
                val newFile = File(existing.parentFile, "zz_fp_check_new_${widget.nameWithoutExtension}.dart").also { temp += it }
                newFile.writeText(PreviewFileGenerator.generate(root, widget, text, cls, newFile, null).text)
                appended = PreviewFileGenerator.generate(root, widget, text, cls, appendFile, appended).text
            }
            appendFile.writeText(appended)

            for (file in temp) {
                val text = file.readText()
                val entries = PreviewParser.parse(text).filter { it.invocation != null }
                val result = renderer.render(root, HarnessGenerator.generate(root, file, text, entries))
                println("${file.name}: ${entries.size} previews -> exit ${result.exitCode}, rendered ${result.images.size}")
                if (result.images.size != entries.size) println(result.log.lines().filter { "rror" in it }.take(15).joinToString("\n"))
                entries.forEach { e -> result.images[e.key]?.let { File(outDir, "${file.nameWithoutExtension}-${e.declaration}.png").writeBytes(it.png) } }
                assertEquals("Missing renders in ${file.name}", entries.size, result.images.size)
            }
        } finally {
            temp.forEach { it.delete() }
        }
    }
}
