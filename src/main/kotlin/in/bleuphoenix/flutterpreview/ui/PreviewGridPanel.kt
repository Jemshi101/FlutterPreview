package `in`.bleuphoenix.flutterpreview.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.bleuphoenix.flutterpreview.core.PreviewEntry
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import kotlin.math.max
import kotlin.math.min

/** Thumbnails of all previews of a file, wrapped into as many columns as fit. */
class PreviewGridPanel(
    private val onSelect: (PreviewEntry) -> Unit,
    private val onOpen: (PreviewEntry) -> Unit,
    private val popup: ActionGroup,
) : JPanel(null), Scrollable {

    /** Edge of the square thumbnail box, in unscaled pixels. */
    var thumbSize = DEFAULT_THUMB
        private set
    private val gap get() = JBUI.scale(12)
    private val labelHeight get() = JBUI.scale(22)
    private var cards: List<Card> = emptyList()

    init {
        background = UIUtil.getPanelBackground()
    }

    fun zoomIn() = setThumb(thumbSize * 5 / 4)
    fun zoomOut() = setThumb(thumbSize * 4 / 5)

    private fun setThumb(size: Int) {
        thumbSize = size.coerceIn(80, 800)
        revalidate()
        repaint()
    }

    /** [pending]: shown on cards without an image (e.g. "Rendering…"). */
    fun setItems(entries: List<PreviewEntry>, images: Map<String, CachedPreview>, selectedKey: String?, pending: String) {
        removeAll()
        cards = entries.map { entry ->
            Card(entry, images[entry.key], entry.key == selectedKey, entry.problem ?: pending).also(::add)
        }
        revalidate()
        repaint()
    }

    private fun cellSize() = Dimension(JBUI.scale(thumbSize), JBUI.scale(thumbSize) + labelHeight)

    private fun columns(): Int {
        val width = (parent as? JViewport)?.extentSize?.width ?: width
        return max(1, (width - gap) / (cellSize().width + gap))
    }

    override fun doLayout() {
        val cell = cellSize()
        val cols = columns()
        cards.forEachIndexed { i, card ->
            card.setBounds(gap + (i % cols) * (cell.width + gap), gap + (i / cols) * (cell.height + gap), cell.width, cell.height)
        }
    }

    override fun getPreferredSize(): Dimension {
        val cell = cellSize()
        val cols = columns()
        val rows = (cards.size + cols - 1) / cols
        return Dimension(gap + cols * (cell.width + gap), gap + rows * (cell.height + gap))
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(24)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = (parent as? JViewport)?.let { it.height > preferredSize.height } ?: false

    private inner class Card(
        val entry: PreviewEntry,
        val preview: CachedPreview?,
        val selected: Boolean,
        val placeholder: String,
    ) : JPanel(BorderLayout()) {

        init {
            isOpaque = false
            toolTipText = entry.title
            add(Thumb(), BorderLayout.CENTER)
            add(JBLabel(entry.title).apply {
                border = JBUI.Borders.empty(3, 4, 0, 4)
                if (selected) foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            }, BorderLayout.SOUTH)
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    onSelect(entry)
                    if (e.clickCount == 2) onOpen(entry)
                }
            }
            addMouseListener(mouse)
            PopupHandler.installPopupMenu(this, popup, "FlutterPreviewGrid")
        }

        private inner class Thumb : JComponent() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.color = THUMB_BACKGROUND
                    g2.fillRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
                    val img = preview?.image
                    if (img != null) {
                        val logicalW = img.width / preview.pixelRatio
                        val logicalH = img.height / preview.pixelRatio
                        val pad = JBUI.scale(6)
                        val scale = min(1.0, min((width - pad * 2) / logicalW, (height - pad * 2) / logicalH))
                        val w = (logicalW * scale).toInt()
                        val h = (logicalH * scale).toInt()
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                        g2.drawImage(img, (width - w) / 2, (height - h) / 2, w, h, null)
                    } else {
                        g2.color = UIUtil.getContextHelpForeground()
                        g2.font = JBUI.Fonts.smallFont()
                        val text = placeholder.lineSequence().first().take(60)
                        val fm = g2.fontMetrics
                        g2.drawString(text, max(4, (width - fm.stringWidth(text)) / 2), height / 2)
                    }
                    g2.color = if (selected) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()
                    g2.stroke = java.awt.BasicStroke(if (selected) JBUIScale.scale(2f) else 1f)
                    g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_THUMB = 220
        val THUMB_BACKGROUND = JBColor(java.awt.Color(0xF2F3F5), java.awt.Color(0x2B2D30))
    }
}
