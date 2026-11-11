package `in`.bleuphoenix.flutterpreview.dart

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import `in`.bleuphoenix.flutterpreview.core.PreviewStubGenerator

/**
 * Alt+Enter on a `class X extends …Widget` header → adds a `@Preview` function for X after the class,
 * with placeholders for the required constructor arguments, plus the `widget_previews` import.
 */
class CreatePreviewIntention : IntentionAction {

    override fun getText() = "Create @Preview for this widget"

    override fun getFamilyName() = "Create Flutter @Preview"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file?.virtualFile?.extension != "dart") return false
        return PreviewStubGenerator.widgetClassAt(editor.document.text, editor.caretModel.offset) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        editor ?: return
        val document = editor.document
        val text = document.text
        val cls = PreviewStubGenerator.widgetClassAt(text, editor.caretModel.offset) ?: return
        val insertions = PreviewStubGenerator.insertions(text, cls)
        insertions.forEach { document.insertString(it.offset, it.text) }
        PsiDocumentManager.getInstance(project).commitDocument(document)

        // Put the caret on the new function: insertions before the class shift it.
        val functionInsertion = insertions.first { it.offset == cls.bodyClose + 1 }
        val shift = insertions.filter { it.offset < functionInsertion.offset }.sumOf { it.text.length }
        val functionStart = functionInsertion.offset + shift + functionInsertion.text.indexOf("Widget ")
        editor.caretModel.moveToOffset(functionStart)
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    override fun startInWriteAction() = true

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY
}
