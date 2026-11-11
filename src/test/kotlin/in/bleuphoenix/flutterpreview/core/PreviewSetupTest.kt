package `in`.bleuphoenix.flutterpreview.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreviewSetupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pkg(gitignore: String?): PubRoot {
        val dir = tmp.newFolder()
        File(dir, "pubspec.yaml").writeText("name: app\n")
        gitignore?.let { File(dir, ".gitignore").writeText(it) }
        return PubRoot(dir, "app")
    }

    @Test
    fun `creates the template once and gitignores it`() {
        val root = pkg(".dart_tool/\nbuild/\n")

        val first = PreviewSetup.ensure(root)
        val second = PreviewSetup.ensure(root)

        assertTrue(first.created && first.gitignoreUpdated)
        assertTrue(first.file.readText().contains("Future<void> flutterPreviewSetup() async {"))
        assertFalse(second.created || second.gitignoreUpdated)
        assertEquals(
            ".dart_tool/\nbuild/\n\n# Flutter Preview plugin: local preview setup (per developer)\n/.flutter_preview/\n",
            File(root.dir, ".gitignore").readText(),
        )
    }

    @Test
    fun `creates gitignore when missing and respects existing entries`() {
        val noIgnore = pkg(null)
        PreviewSetup.ensure(noIgnore)
        assertEquals(
            "# Flutter Preview plugin: local preview setup (per developer)\n/.flutter_preview/\n",
            File(noIgnore.dir, ".gitignore").readText(),
        )

        val alreadyIgnored = pkg("build/\n.flutter_preview/\n")
        assertFalse(PreviewSetup.ensure(alreadyIgnored).gitignoreUpdated)
        assertEquals("build/\n.flutter_preview/\n", File(alreadyIgnored.dir, ".gitignore").readText())
    }

    @Test
    fun `existing setup file is left alone, gitignore untouched`() {
        val root = pkg("build/")
        PreviewSetup.file(root).apply { parentFile.mkdirs(); writeText("Future<void> flutterPreviewSetup() async {}") }

        val result = PreviewSetup.ensure(root)

        assertFalse(result.created || result.gitignoreUpdated)
        assertEquals("build/", File(root.dir, ".gitignore").readText())
    }
}
