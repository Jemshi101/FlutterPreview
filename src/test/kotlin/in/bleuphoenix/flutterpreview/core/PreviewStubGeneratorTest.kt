package `in`.bleuphoenix.flutterpreview.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewStubGeneratorTest {

    private val source = """
        import 'package:flutter/material.dart';

        class StatusTile extends StatelessWidget {
          final String label;
          final Color? color;
          final void Function(String value, int index) onChanged;
          final VoidCallback onTap;
          final MyEnum mode;

          const StatusTile({
            super.key,
            required this.label,
            required this.onChanged,
            required this.onTap,
            required this.mode,
            this.color,
            required super.child,
          });

          @override
          Widget build(BuildContext context) => Text(label);
        }
    """.trimIndent()

    private fun apply(text: String, insertions: List<PreviewStubGenerator.Insertion>): String =
        insertions.fold(StringBuilder(text)) { sb, ins -> sb.insert(ins.offset, ins.text) }.toString()

    @Test
    fun `offered only on a widget class header`() {
        assertEquals("StatusTile", PreviewStubGenerator.widgetClassAt(source, source.indexOf("StatusTile"))?.name)
        assertNull(PreviewStubGenerator.widgetClassAt(source, source.indexOf("Widget build")))
        assertNull(PreviewStubGenerator.widgetClassAt(source, 5))
    }

    @Test
    fun `generates a preview with placeholders and the import`() {
        val cls = PreviewStubGenerator.widgetClassAt(source, source.indexOf("class"))!!
        val result = apply(source, PreviewStubGenerator.insertions(source, cls))

        val expected = """
            @Preview(name: 'StatusTile')
            Widget statusTilePreview() {
              return StatusTile(
                label: 'Label',
                onChanged: (_, __) {},
                onTap: () {},
                mode: throw UnimplementedError('TODO: mode (MyEnum)'),
                child: const Placeholder(fallbackHeight: 48),
              );
            }
        """.trimIndent()
        assert(result.contains("import 'package:flutter/material.dart';\nimport 'package:flutter/widget_previews.dart';")) { result }
        assert(result.trimEnd().endsWith(expected)) { result }
        // The generated preview is itself picked up by the parser.
        assertEquals("statusTilePreview()", PreviewParser.parse(result).single().invocation)
    }

    @Test
    fun `no-arg widget and unique function name`() {
        val text = """
            import 'package:flutter/widget_previews.dart';
            class Empty extends StatelessWidget {
              const Empty();
            }
            Widget emptyPreview() => const Empty();
        """.trimIndent()
        val cls = PreviewStubGenerator.widgetClassAt(text, text.indexOf("class"))!!
        val insertions = PreviewStubGenerator.insertions(text, cls)
        assertEquals(1, insertions.size)
        assert(insertions.single().text.contains("Widget emptyPreview2() {\n  return Empty();")) { insertions.single().text }
    }
}
