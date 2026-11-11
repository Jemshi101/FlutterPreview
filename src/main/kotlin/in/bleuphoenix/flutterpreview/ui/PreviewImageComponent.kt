package `in`.bleuphoenix.flutterpreview.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows the rendered PNG with zoom. At 100 % the image is drawn at its logical size (pixels divided by
 * the render pixel ratio), so a 2x render is pixel-sharp on a Retina screen. The fit modes follow the
 * viewport size; the zoom mode is kept when a new image arrives, so re-renders don't reset it.
 */
class PreviewImageComponent : JComponent(), Scrollable {

    enum class Mode { FIT_ALL, FIT_WIDTH, FIT_HEIGHT, CUSTOM }

    var mode: Mode = Mode.FIT_ALL
        private set
    private var customScale = 1.0
    private var image: BufferedImage? = null
    private var pixelRatio = 2.0
    private val margin get() = JBUI.scale(16)

    /** Called whenever the effective zoom changes, e.g. to update a "75 %" label. */
    var onZoomChanged: (Double) -> Unit = {}

    init {
        addMouseWheelListener { event ->
            val zoomModifier = if (System.getProperty("os.name").startsWith("Mac")) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
            if (event.modifiersEx and zoomModifier != 0 && image != null) {
                if (event.wheelRotation < 0) zoomIn() else zoomOut()
                event.consume()
            } else {
                // A wheel listener here stops the scroll pane from seeing the event: hand it on for normal scrolling.
                val scrollPane = parent?.parent ?: return@addMouseWheelListener
                scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(this, event, scrollPane))
            }
        }
    }

    override fun addNotify() {
        super.addNotify()
        (parent as? JViewport)?.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (mode != Mode.CUSTOM) changed()
            }
        })
    }

    fun setImage(image: BufferedImage?, pixelRatio: Double) {
        this.image = image
        this.pixelRatio = pixelRatio
        changed()
    }

    fun setMode(mode: Mode) {
        if (mode == Mode.CUSTOM) customScale = scale()
        this.mode = mode
        changed()
    }

    fun zoomIn() = zoomTo(scale() * ZOOM_STEP)
    fun zoomOut() = zoomTo(scale() / ZOOM_STEP)
    fun actualSize() = zoomTo(1.0)

    fun scale(): Double {
        val img = image ?: return 1.0
        val viewport = (parent as? JViewport)?.extentSize ?: return customScale
        val availableWidth = max(1, viewport.width - margin * 2).toDouble()
        val availableHeight = max(1, viewport.height - margin * 2).toDouble()
        val width = img.width / pixelRatio
        val height = img.height / pixelRatio
        return when (mode) {
            Mode.CUSTOM -> customScale
            Mode.FIT_WIDTH -> availableWidth / width
            Mode.FIT_HEIGHT -> availableHeight / height
            // Shrinks large previews to fit; never blows a small button up to fill the panel.
            Mode.FIT_ALL -> min(1.0, min(availableWidth / width, availableHeight / height))
        }.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private fun zoomTo(scale: Double) {
        customScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        mode = Mode.CUSTOM
        changed()
    }

    private fun changed() {
        revalidate()
        repaint()
        onZoomChanged(scale())
    }

    private fun imageSize(): Dimension {
        val img = image ?: return Dimension(0, 0)
        val s = scale()
        return Dimension((img.width / pixelRatio * s).roundToInt(), (img.height / pixelRatio * s).roundToInt())
    }

    override fun getPreferredSize(): Dimension {
        if (image == null) return Dimension(0, 0)
        val size = imageSize()
        return Dimension(size.width + margin * 2, size.height + margin * 2)
    }

    override fun paintComponent(g: Graphics) {
        val img = image ?: return
        val size = imageSize()
        // Centre in the visible area when smaller than it.
        val x = max(margin, (width - size.width) / 2)
        val y = max(margin, (height - size.height) / 2)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.drawImage(img, x, y, size.width, size.height, null)
            g2.color = JBColor.border()
            g2.drawRect(x - 1, y - 1, size.width + 1, size.height + 1)
        } finally {
            g2.dispose()
        }
    }

    // Scrollable: stretch to the viewport when the image is smaller, so it can be centred.
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
    override fun getScrollableTracksViewportWidth() = (parent as? JViewport)?.let { it.width > preferredSize.width } ?: false
    override fun getScrollableTracksViewportHeight() = (parent as? JViewport)?.let { it.height > preferredSize.height } ?: false

    private companion object {
        const val ZOOM_STEP = 1.25
        const val MIN_SCALE = 0.1
        const val MAX_SCALE = 8.0
    }
}
