package `in`.bleuphoenix.flutterpreview.dart

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import `in`.bleuphoenix.flutterpreview.core.PreviewEntry
import `in`.bleuphoenix.flutterpreview.core.PreviewParser
import `in`.bleuphoenix.flutterpreview.ui.FlutterPreviewService

/**
 * Gutter icon on each `@Preview` annotation: opens the Flutter Preview tool window on that preview.
 *
 * Works on the `@` leaf of the annotation and the text-based [PreviewParser], so it doesn't depend on
 * the shape of the Dart plugin's PSI.
 */
class PreviewLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null || element.textLength != 1 || element.text != "@") return null
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val offset = element.textRange.startOffset
        val entry = previews(file).firstOrNull { it.offset == offset } ?: return null
        val project = element.project
        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Actions.Preview,
            { "Show Flutter preview '${entry.title}'" },
            { _, _ -> FlutterPreviewService.getInstance(project).reveal(virtualFile, entry.key) },
            GutterIconRenderer.Alignment.LEFT,
            { "Flutter preview" },
        )
    }

    private fun previews(file: PsiFile): List<PreviewEntry> = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(PreviewParser.parse(file.text), file)
    }
}
