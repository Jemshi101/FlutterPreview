package `in`.bleuphoenix.flutterpreview.dart

import com.intellij.codeInsight.hint.HintManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.ui.ColoredListCellRenderer
import `in`.bleuphoenix.flutterpreview.core.PreviewFileGenerator
import `in`.bleuphoenix.flutterpreview.core.PreviewStubGenerator
import `in`.bleuphoenix.flutterpreview.core.PubRoot
import java.io.File
import javax.swing.JList

/**
 * Editor context menu → "Generate Flutter Preview…": adds a `@Preview` for the widget under the caret (its
 * class, or a usage such as `StatusTile(`) to a file under `<package>/previews/`, new or existing.
 */
class GeneratePreviewAction : DumbAwareAction() {

    private data class Widget(val file: VirtualFile, val text: String, val cls: PreviewStubGenerator.WidgetClass, val root: PubRoot)

    private data class Choice(val file: File, val label: String, val isNew: Boolean)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /** Cheap check only (no reference resolving): inside a widget class, or on a capitalised identifier. */
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (editor == null || file?.extension != "dart" || e.project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val text = editor.document.charsSequence
        val offset = editor.caretModel.offset
        e.presentation.isEnabledAndVisible =
            identifierAt(text, offset)?.first()?.isUpperCase() == true ||
            PreviewStubGenerator.widgetClassContaining(text.toString(), offset) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val widget = runReadActionBlocking { findWidget(psiFile, editor) }
            ?: return error(editor, "Put the caret on a widget class, or on a usage of a widget from this package.")

        val defaultFile = PreviewFileGenerator.defaultPreviewFile(widget.root, File(widget.file.path))
        val existing = PreviewFileGenerator.existingPreviewFiles(widget.root, defaultFile.parentFile)
        val choices = buildList {
            if (!defaultFile.exists()) add(Choice(defaultFile, "New file: ${relative(widget.root, defaultFile)}", isNew = true))
            existing.forEach { add(Choice(it, relative(widget.root, it), isNew = false)) }
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(choices)
            .setTitle("Add Preview for ${widget.cls.name} to")
            .setRenderer(object : ColoredListCellRenderer<Choice>() {
                override fun customizeCellRenderer(
                    list: JList<out Choice>, value: Choice, index: Int, selected: Boolean, hasFocus: Boolean,
                ) {
                    icon = if (value.isNew) AllIcons.Actions.AddFile else AllIcons.FileTypes.Any_type
                    append(value.label)
                }
            })
            .setItemChosenCallback { choice ->
                // Popup callbacks run without the write-intent lock that writing needs.
                ApplicationManager.getApplication().invokeLater { write(project, editor, widget, choice) }
            }
            .createPopup()
            .showInBestPositionFor(editor)
    }

    private fun write(project: Project, editor: Editor, widget: Widget, choice: Choice) {
        val existingFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(choice.file)
        val existingText = existingFile?.let { vf -> runReadActionBlocking { FileDocumentManager.getInstance().getDocument(vf)?.text } }
        val result = try {
            PreviewFileGenerator.generate(widget.root, File(widget.file.path), widget.text, widget.cls, choice.file, existingText)
        } catch (e: PreviewFileGenerator.GenerationException) {
            return error(editor, e.message ?: "Could not generate the preview.")
        }

        var target: VirtualFile? = null
        WriteCommandAction.runWriteCommandAction(project, "Generate Flutter Preview", null, Runnable {
            val file = existingFile ?: VfsUtil.createDirectoryIfMissing(choice.file.parentFile.path)
                ?.createChildData(this, choice.file.name)
                ?: return@Runnable
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@Runnable
            document.setText(result.text)
            FileDocumentManager.getInstance().saveDocument(document)
            target = file
        })
        val file = target ?: return error(editor, "Could not write ${choice.file.path}.")
        OpenFileDescriptor(project, file, result.previewOffset).navigate(true)
    }

    /**
     * A usage under the caret that resolves to a widget class of this package wins (right-clicking `StatusTile(`
     * inside another widget's build). Otherwise the widget class the caret is in.
     */
    private fun findWidget(psiFile: PsiFile, editor: Editor): Widget? {
        val offset = editor.caretModel.offset
        val reference = psiFile.findReferenceAt(offset) ?: if (offset > 0) psiFile.findReferenceAt(offset - 1) else null
        val declaration = runCatching { reference?.resolve() }.getOrNull()
        val declarationFile = declaration?.containingFile?.virtualFile
        if (declaration != null && declarationFile != null && declarationFile.extension == "dart") {
            val root = PubRoot.find(File(declarationFile.path))
            val ownRoot = psiFile.virtualFile?.let { PubRoot.find(File(it.path)) }
            if (root != null && root.dir == ownRoot?.dir) {
                val text = FileDocumentManager.getInstance().getDocument(declarationFile)?.text ?: declaration.containingFile.text
                PreviewStubGenerator.widgetClassContaining(text, declaration.textOffset)
                    ?.let { return Widget(declarationFile, text, it, root) }
            }
        }
        val file = psiFile.virtualFile ?: return null
        val root = PubRoot.find(File(file.path)) ?: return null
        val text = editor.document.text
        val cls = PreviewStubGenerator.widgetClassContaining(text, offset) ?: return null
        return Widget(file, text, cls, root)
    }

    private fun identifierAt(text: CharSequence, offset: Int): String? {
        fun isPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
        var start = offset.coerceIn(0, text.length)
        var end = start
        while (start > 0 && isPart(text[start - 1])) start--
        while (end < text.length && isPart(text[end])) end++
        return if (end > start) text.subSequence(start, end).toString() else null
    }

    private fun relative(root: PubRoot, file: File) = file.relativeTo(root.dir).invariantSeparatorsPath

    private fun error(editor: Editor, message: String) {
        ApplicationManager.getApplication().invokeLater { HintManager.getInstance().showErrorHint(editor, message) }
    }
}
