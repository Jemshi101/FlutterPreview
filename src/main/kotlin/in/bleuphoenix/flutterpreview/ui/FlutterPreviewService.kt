package `in`.bleuphoenix.flutterpreview.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

/** Lets editor features (gutter icons) reach the tool window panel. */
@Service(Service.Level.PROJECT)
class FlutterPreviewService(private val project: Project) {

    internal var panel: FlutterPreviewPanel? = null

    /** Opens the tool window and selects the preview [key] of [file]. */
    fun reveal(file: VirtualFile, key: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate({ panel?.reveal(file, key) }, false)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Flutter Preview"
        const val REPOSITORY_URL = "https://github.com/Jemshi101/FlutterPreview"

        fun getInstance(project: Project): FlutterPreviewService = project.service()
    }
}
