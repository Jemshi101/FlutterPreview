package `in`.bleuphoenix.flutterpreview.dev

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import `in`.bleuphoenix.flutterpreview.ui.FlutterPreviewPanel
import `in`.bleuphoenix.flutterpreview.ui.FlutterPreviewService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.RenderingHints
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.RootPaneContainer

/**
 * Dev-only: produces the README / Marketplace screenshots. Inert unless the IDE runs with
 * `-Dflutterpreview.screenshots=<output dir>` (see the `runIdeForScreenshots` Gradle task).
 *
 * It paints the IDE window into images itself, so it needs no macOS Screen Recording permission.
 */
class ScreenshotActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val outDir = System.getProperty(OUTPUT_PROPERTY)?.takeIf { it.isNotBlank() } ?: return
        // Not inline: waiting for indexing inside a startup activity holds up the project's initial scan.
        project.service<ScreenshotService>().start(File(outDir))
    }

    companion object {
        const val OUTPUT_PROPERTY = "flutterpreview.screenshots"
    }
}

@Service(Service.Level.PROJECT)
class ScreenshotService(private val project: Project, private val scope: CoroutineScope) {
    fun start(outDir: File) {
        scope.launch { ScreenshotRunner(project, outDir).run() }
    }
}

private class ScreenshotRunner(private val project: Project, private val outDir: File) {

    private val log = logger<ScreenshotRunner>()
    private val root = File(project.basePath!!)

    suspend fun run() {
        outDir.mkdirs()
        try {
            withContext(Dispatchers.IO) { DumbService.getInstance(project).waitForSmartMode() }
            delay(3_000)
            val frame = onEdt { layoutFrame() }

            // 1. Single view.
            openFile("previews/widgets/widgets_previews.dart", caretText = null)
            val panel = showToolWindow(frame)
            waitForRender(panel)
            onEdt { panel.selectByTitle("Confirm dialog") }
            settle()
            onEdt { capture(frame, "screenshot-single.png") }

            // 2. Grid.
            onEdt { panel.setGridMode(true) }
            settle()
            onEdt { capture(frame, "screenshot-grid.png") }
            onEdt { panel.setGridMode(false) }

            // 3. Generate Flutter Preview… on a widget: capture the chooser, then cancel it (nothing is written).
            //    The preview pane would only say "No @Preview in stat_tile.dart": give the editor the room.
            onEdt { ToolWindowManager.getInstance(project).getToolWindow(FlutterPreviewService.TOOL_WINDOW_ID)?.hide() }
            openFile("lib/widgets/stat_tile.dart", caretText = "class StatTile")
            settle()
            onEdt { invokeGenerate() }
            delay(2_000)
            onEdt { capture(frame, "screenshot-generate.png") }
            onEdt { JBPopupFactory.getInstance().getChildPopups(frame.rootPane).forEach { it.cancel() } }

            File(outDir, "DONE").writeText("ok")
        } catch (e: Throwable) {
            log.warn("Screenshot run failed", e)
            File(outDir, "FAILED").writeText(e.stackTraceToString())
        } finally {
            ApplicationManager.getApplication().invokeLater { ApplicationManager.getApplication().exit(true, true, false) }
        }
    }

    private fun layoutFrame(): JFrame {
        ToolWindowManager.getInstance(project).getToolWindow("Project")?.hide()
        val frame = WindowManager.getInstance().getFrame(project)!!
        frame.extendedState = JFrame.NORMAL
        frame.setBounds(40, 40, FRAME_WIDTH, FRAME_HEIGHT)
        frame.validate()
        return frame
    }

    private suspend fun openFile(path: String, caretText: String?) = onEdt {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(root, path))
            ?: error("Missing sample file $path")
        WriteIntentReadAction.run(Runnable {
            val offset = caretText?.let { text ->
                val content = String(file.contentsToByteArray())
                content.indexOf(text).takeIf { it >= 0 }?.plus(text.length - 3) // inside the class name
            } ?: 0
            OpenFileDescriptor(project, file, offset).navigate(true)
            (FileEditorManager.getInstance(project).selectedEditor as? TextEditor)?.editor?.scrollingModel?.let {
                it.scrollToCaret(ScrollType.CENTER)
                it.scrollHorizontally(0)
            }
        })
    }

    private suspend fun showToolWindow(frame: JFrame): FlutterPreviewPanel {
        onEdt {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(FlutterPreviewService.TOOL_WINDOW_ID)!!
            toolWindow.activate(null, false)
        }
        repeat(50) {
            FlutterPreviewService.getInstance(project).panel?.let { panel ->
                onEdt {
                    // Give the preview about half of the window.
                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(FlutterPreviewService.TOOL_WINDOW_ID)
                    (toolWindow as? ToolWindowEx)?.stretchWidth((frame.width * 0.5).toInt() - toolWindow.component.width)
                }
                return panel
            }
            delay(200)
        }
        error("Flutter Preview panel did not appear")
    }

    private suspend fun waitForRender(panel: FlutterPreviewPanel) {
        delay(1_000)
        repeat(240) {
            if (onEdt { panel.isRenderComplete() }) return
            delay(500)
        }
        error("Render did not finish within 2 minutes")
    }

    private fun invokeGenerate() {
        val editor = (FileEditorManager.getInstance(project).selectedEditor as? TextEditor)?.editor ?: error("No editor")
        val action = ActionManager.getInstance().getAction("FlutterPreview.GeneratePreview") ?: error("Action not registered")
        val context = DataManager.getInstance().getDataContext(editor.contentComponent)
        val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP, null)
        ActionUtil.performAction(action, event)
    }

    /**
     * Removes editor banners such as "Dart SDK is not configured": the sandbox has the Dart plugin but not the
     * Flutter plugin that would configure the SDK, so real users don't see it.
     */
    private fun hideEditorBanners(component: Component) {
        if (component.javaClass.name.contains("EditorNotificationPanel")) {
            component.isVisible = false
            return
        }
        (component as? java.awt.Container)?.components?.forEach(::hideEditorBanners)
    }

    /** Paints the frame, and any popup windows it owns at their positions, into a 2x PNG. */
    private fun capture(frame: JFrame, name: String) {
        hideEditorBanners(frame.rootPane)
        frame.rootPane.validate()
        val rootPane = frame.rootPane
        val img = BufferedImage(rootPane.width * SCALE, rootPane.height * SCALE, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(SCALE.toDouble(), SCALE.toDouble())
        rootPane.printAll(g)
        val origin = rootPane.locationOnScreen
        Window.getWindows()
            .filter { it !== frame && it.isShowing && ownedBy(it, frame) }
            .forEach { window ->
                val at = window.locationOnScreen
                val wg = g.create()
                wg.translate(at.x - origin.x, at.y - origin.y)
                val content: Component = (window as? RootPaneContainer)?.rootPane ?: window
                content.printAll(wg)
                wg.dispose()
            }
        g.dispose()
        ImageIO.write(img, "png", File(outDir, name))
        log.info("Screenshot written: $name")
    }

    private fun ownedBy(window: Window, frame: JFrame): Boolean {
        var owner: Window? = window.owner
        while (owner != null) {
            if (owner === frame) return true
            owner = owner.owner
        }
        return false
    }

    private suspend fun settle() = delay(1_500)

    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT) { block() }

    companion object {
        const val FRAME_WIDTH = 1500
        const val FRAME_HEIGHT = 900
        const val SCALE = 2
    }
}
