package `in`.bleuphoenix.flutterpreview.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PreviewVariantsTest {

    private fun entry(source: String) = PreviewParser.parse(source).single()

    @Test
    fun `all dimensions give the declared preview plus six variants`() {
        val variants = PreviewVariants.forEntry(entry("@Preview()\nWidget a() => X();"), PreviewVariants.DEFAULT_DIMENSIONS)
        assertEquals(listOf("base", "dark", "text1.5", "text2.0", "rtl", "small", "tablet"), variants.map { it.id })
    }

    @Test
    fun `a dark preview gets a light variant`() {
        val variants = PreviewVariants.forEntry(
            entry("@Preview(brightness: Brightness.dark)\nWidget a() => X();"),
            setOf(VariantDimension.THEME),
        )
        assertEquals(listOf("base", "light"), variants.map { it.id })
    }

    @Test
    fun `a text scale the preview already declares is not repeated`() {
        val variants = PreviewVariants.forEntry(
            entry("@Preview(textScaleFactor: 2.0)\nWidget a() => X();"),
            setOf(VariantDimension.TEXT_SCALE),
        )
        assertEquals(listOf("base", "text1.5"), variants.map { it.id })
    }

    @Test
    fun `no dimensions leaves the declared preview only`() {
        assertEquals(listOf("base"), PreviewVariants.forEntry(entry("@Preview()\nWidget a() => X();"), emptySet()).map { it.id })
    }

    @Test
    fun `the harness overrides only what a variant changes`() {
        val dir = Files.createTempDirectory("fp-variants").toFile()
        try {
            File(dir, "pubspec.yaml").writeText("name: demo\n")
            val source = File(dir, "lib/a.dart").apply { parentFile.mkdirs() }
            val text = "@Preview(size: Size(300, 200), brightness: Brightness.light)\nWidget a() => X();"
            val previewEntry = entry(text)
            val root = PubRoot.find(source)!!
            val variants = PreviewVariants.forEntry(previewEntry, PreviewVariants.DEFAULT_DIMENSIONS)
            val harness = HarnessGenerator.generate(
                root, source, text, emptyList(),
                RenderOptions(accessibility = false), VariantRequest(previewEntry, variants),
            )
            val lines = harness.code.lines().filter { it.trimStart().startsWith("FpPreview('v") }
            assertEquals(variants.size, lines.size)
            assertTrue(lines[0], lines[0].endsWith("Size(300, 200), null, Brightness.light),"))
            assertTrue(lines[1], lines[1].endsWith("Size(300, 200), null, fp_ui.Brightness.dark),"))
            assertTrue(lines[3], lines[3].endsWith("Size(300, 200), 2.0, Brightness.light),"))
            assertTrue(lines[4], lines[4].endsWith("Brightness.light, rtl: true),"))
            assertTrue(lines[6], lines[6].endsWith("Brightness.light, device: fp_ui.Size(800.0, 1280.0)),"))
            assertTrue(harness.code.contains("const bool fpAccessibility = false;"))
            assertEquals(PreviewVariants.imageKey(previewEntry.key, "rtl"), harness.ids["v4"])
        } finally {
            dir.deleteRecursively()
        }
    }
}
