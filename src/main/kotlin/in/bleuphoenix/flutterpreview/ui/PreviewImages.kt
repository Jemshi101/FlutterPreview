package `in`.bleuphoenix.flutterpreview.ui

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage

/** A decoded render kept in the panel's cache. */
class CachedPreview(
    val png: ByteArray,
    val image: BufferedImage,
    val pixelRatio: Double,
    val problems: List<String>,
    val notes: List<String>,
)

object PreviewImages {

    fun copy(preview: CachedPreview) {
        CopyPasteManager.getInstance().setContents(ImageTransferable(preview.image))
    }

    /** Asks where to save, then writes the original PNG bytes. Returns false if cancelled. */
    fun save(project: Project, preview: CachedPreview, suggestedName: String): Boolean {
        val descriptor = FileSaverDescriptor("Save Preview Image", "Save the rendered preview as a PNG file", "png")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(null as java.nio.file.Path?, fileName(suggestedName)) ?: return false
        target.file.writeBytes(preview.png)
        return true
    }

    fun fileName(title: String): String = title.replace(Regex("""[^\w-]+"""), "_").trim('_').ifEmpty { "preview" } + ".png"

    private class ImageTransferable(private val image: Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
}
