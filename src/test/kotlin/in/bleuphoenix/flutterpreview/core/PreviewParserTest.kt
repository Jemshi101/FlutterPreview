package `in`.bleuphoenix.flutterpreview.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewParserTest {

    @Test
    fun `finds top-level, static and constructor previews`() {
        val source = """
            import 'package:flutter/widget_previews.dart';

            @Preview(name: 'Top level', size: Size(320, 200))
            Widget topLevel() => const Text('hi');

            class Card extends StatelessWidget {
              const Card({super.key, this.label = 'x'});

              @Preview(group: 'Cards', name: "Static", wrapper: myWrapper, brightness: Brightness.dark)
              static Widget preview() => const Card();

              @Preview(name: 'Ctor', textScaleFactor: 1.5)
              const Card.preview() : label = 'p';

              @override
              Widget build(BuildContext context) => Text(label);
            }
        """.trimIndent()

        val entries = PreviewParser.parse(source)

        assertEquals(3, entries.size)
        with(entries[0]) {
            assertEquals("Top level", name)
            assertEquals("topLevel()", invocation)
            assertEquals(320.0, width)
            assertEquals(200.0, height)
            assertEquals(3, line)
        }
        with(entries[1]) {
            assertEquals("Static", name)
            assertEquals("Cards", group)
            assertEquals("Card.preview()", invocation)
            assertEquals("Card.preview", declaration)
            assertEquals("myWrapper", wrapper)
            assertEquals("Brightness.dark", brightness)
        }
        with(entries[2]) {
            assertEquals("Card.preview()", invocation)
            assertEquals(1.5, textScaleFactor)
        }
    }

    @Test
    fun `ignores previews in comments and strings`() {
        val source = """
            // @Preview(name: 'commented')
            /* @Preview(name: 'block /* nested */ comment') */
            const s = '@Preview(name: "in string")';
            @Preview(name: 'real, with comma (and parens)')
            Widget real() => const SizedBox();
        """.trimIndent()

        val entries = PreviewParser.parse(source)

        assertEquals(1, entries.size)
        assertEquals("real, with comma (and parens)", entries[0].name)
    }

    @Test
    fun `reports previews that can't be called`() {
        val source = """
            class A {
              @Preview()
              Widget instanceMethod() => const SizedBox();

              @Preview()
              static Widget _private() => const SizedBox();

              @Preview()
              static Widget needsArgs(int x) => const SizedBox();
            }
        """.trimIndent()

        val entries = PreviewParser.parse(source)

        assertEquals(3, entries.size)
        assertTrue(entries.all { it.problem != null })
        assertTrue(entries.all { it.invocation == null })
    }

    @Test
    fun `skips other annotations between preview and declaration`() {
        val source = """
            @Preview(name: 'x')
            @visibleForTesting
            @Deprecated('old')
            Widget x() => const SizedBox();
        """.trimIndent()

        val entry = PreviewParser.parse(source).single()

        assertEquals("x()", entry.invocation)
        assertNull(entry.problem)
    }
}
