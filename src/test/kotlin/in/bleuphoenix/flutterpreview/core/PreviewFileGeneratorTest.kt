package `in`.bleuphoenix.flutterpreview.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreviewFileGeneratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val widgetSource = """
        import 'package:flutter/material.dart';

        class StatusTile extends StatelessWidget {
          const StatusTile({super.key, required this.label});

          final String label;

          @override
          Widget build(BuildContext context) => Text(label);
        }

        class _Hidden extends StatelessWidget {
          const _Hidden();
          @override
          Widget build(BuildContext context) => const SizedBox();
        }

        class OnlyPrivate extends StatelessWidget {
          const OnlyPrivate._();
          @override
          Widget build(BuildContext context) => const SizedBox();
        }
    """.trimIndent()

    private fun pkg(): PubRoot {
        val dir = tmp.newFolder("app")
        File(dir, "pubspec.yaml").writeText("name: my_app\n")
        return PubRoot(dir, "my_app")
    }

    private fun widgetFile(root: PubRoot): File =
        File(root.dir, "lib/widgets/status/status_tile.dart").apply { parentFile.mkdirs(); writeText(widgetSource) }

    @Test
    fun `default file mirrors lib without the top folder`() {
        val root = pkg()
        assertEquals(
            File(root.dir, "previews/status/status_tile_previews.dart"),
            PreviewFileGenerator.defaultPreviewFile(root, widgetFile(root)),
        )
        val shallow = File(root.dir, "lib/screens/home.dart").apply { parentFile.mkdirs(); writeText("") }
        assertEquals(File(root.dir, "previews/screens/home_previews.dart"), PreviewFileGenerator.defaultPreviewFile(root, shallow))
    }

    @Test
    fun `new preview file with imports`() {
        val root = pkg()
        val widget = widgetFile(root)
        val cls = PreviewStubGenerator.widgetClassContaining(widgetSource, widgetSource.indexOf("Widget build"))!!
        val target = PreviewFileGenerator.defaultPreviewFile(root, widget)

        val result = PreviewFileGenerator.generate(root, widget, widgetSource, cls, target, null)

        assertEquals(
            """
            import 'package:flutter/material.dart';
            import 'package:flutter/widget_previews.dart';
            import 'package:my_app/widgets/status/status_tile.dart';

            @Preview(name: 'StatusTile')
            Widget statusTilePreview() {
              return StatusTile(
                label: 'Label',
              );
            }
            """.trimIndent() + "\n",
            result.text,
        )
        assertEquals("statusTilePreview()", PreviewParser.parse(result.text).single().invocation)
    }

    @Test
    fun `appends to an existing file reusing group and wrapper`() {
        val root = pkg()
        val widget = widgetFile(root)
        val cls = PreviewStubGenerator.widgetClassContaining(widgetSource, widgetSource.indexOf("StatusTile"))!!
        val target = File(root.dir, "previews/status/all_previews.dart")
        val existing = """
            import 'package:flutter/widgets.dart';
            import 'package:flutter/widget_previews.dart';
            import '../../lib/widgets/status/status_tile.dart';
            import 'preview_support.dart';

            @Preview(group: kStatusGroup, name: 'Old', wrapper: darkWrapper)
            Widget statusTilePreview() => const StatusTile(label: 'x');
        """.trimIndent()

        val result = PreviewFileGenerator.generate(root, widget, widgetSource, cls, target, existing)

        assertTrue(result.text.startsWith(existing)) // no duplicate imports
        assertTrue(result.text.endsWith(
            """
            @Preview(
              group: kStatusGroup,
              name: 'StatusTile',
              wrapper: darkWrapper,
            )
            Widget statusTilePreview2() {
              return StatusTile(
                label: 'Label',
              );
            }
            """.trimIndent() + "\n",
        ))
        assertEquals(2, PreviewParser.parse(result.text).size)
    }

    @Test
    fun `refuses private classes and private-only constructors`() {
        val root = pkg()
        val widget = widgetFile(root)
        val target = PreviewFileGenerator.defaultPreviewFile(root, widget)
        for (name in listOf("_Hidden", "OnlyPrivate")) {
            val cls = PreviewStubGenerator.widgetClassContaining(widgetSource, widgetSource.indexOf("class $name") + 7)!!
            val error = runCatching { PreviewFileGenerator.generate(root, widget, widgetSource, cls, target, null) }.exceptionOrNull()
            assertTrue(name, error is PreviewFileGenerator.GenerationException)
        }
    }
}
