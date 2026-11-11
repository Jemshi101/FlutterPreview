package `in`.bleuphoenix.flutterpreview.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.Alarm
import com.intellij.util.EnvironmentUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.bleuphoenix.flutterpreview.core.BatchResult
import `in`.bleuphoenix.flutterpreview.core.FlutterSdkLocator
import `in`.bleuphoenix.flutterpreview.core.HarnessGenerator
import `in`.bleuphoenix.flutterpreview.core.PreviewEntry
import `in`.bleuphoenix.flutterpreview.core.PreviewParser
import `in`.bleuphoenix.flutterpreview.core.PreviewRenderer
import `in`.bleuphoenix.flutterpreview.core.PubRoot
import `in`.bleuphoenix.flutterpreview.core.RenderCancelledException
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import kotlin.math.roundToInt

/**
 * Tool window content in two columns: the `@Preview` entries of the focused Dart file on the left, the
 * selected preview (or a grid of all of them) on the right.
 *
 * All previews of a file render in one `flutter test` run and are cached per file, so selecting a
 * preview is instant. Focusing a file renders it if needed; saving a Dart file of the package
 * re-renders it while the previous images stay visible.
 */
class FlutterPreviewPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    /** The renders of one file. */
    private class FileState {
        var images: Map<String, CachedPreview> = emptyMap()
        var stale = true
        var log = ""
        var millis = 0L
        var exitCode = 0
        /** The latest render produced nothing (usually code that doesn't compile mid-edit); [images] are older. */
        var lastFailed = false
    }

    private val listModel = DefaultListModel<PreviewEntry>()
    private val list = JBList(listModel)
    private val fileLabel = JBLabel()
    private val status = JBLabel()
    private val zoomLabel = JBLabel()
    private val image = PreviewImageComponent()
    private val grid: PreviewGridPanel
    private val logArea = textArea()
    private val problemsArea = textArea().apply { foreground = JBColor.RED }
    private val problemsPane = JBScrollPane(problemsArea).apply {
        preferredSize = JBUI.size(100, 110)
        isVisible = false
    }
    private val cards = CardLayout()
    private val cardPanel = JPanel(cards)

    private var currentFile: VirtualFile? = null
    private var currentRoot: PubRoot? = null
    private var currentDocument: Document? = null
    private var documentDisposable: Disposable? = null
    private var entries: List<PreviewEntry> = emptyList()
    private var updatingList = false
    private var renderOnSave = true
    private var gridMode = false
    private var showingLog = false
    /** The log was opened by a failed render, not by the user: close it again once a render succeeds. */
    private var logShownForFailure = false

    /** Path → renders, most recently used last; a few files are kept so switching back is instant. */
    private val states = object : LinkedHashMap<String, FileState>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileState>) = size > MAX_CACHED_FILES
    }
    private var renderingPath: String? = null
    private var renderingCount = 0

    private val renderGeneration = AtomicInteger()
    private val runningIndicator = AtomicReference<ProgressIndicator?>()
    private val renderers = ConcurrentHashMap<String, PreviewRenderer>()
    private val saveAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val typingAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val caretAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val imageActions = DefaultActionGroup().apply {
        add(action("Copy Image", "Copy the preview to the clipboard", AllIcons.Actions.Copy, enabled = { selectedImage() != null }) {
            selectedImage()?.let(PreviewImages::copy)
        })
        add(action("Save Image As…", "Save the preview as a PNG file", AllIcons.Actions.MenuSaveall, enabled = { selectedImage() != null }) {
            val entry = list.selectedValue ?: return@action
            selectedImage()?.let { PreviewImages.save(project, it, entry.title) }
        })
        add(action("Go to Source", "Open the preview declaration", AllIcons.Actions.EditSource, enabled = { list.selectedValue != null }) {
            list.selectedValue?.let(::navigate)
        })
    }

    init {
        grid = PreviewGridPanel(onSelect = ::select, onOpen = { showGrid(false); select(it) }, popup = imageActions)
        setToolbar(createToolbar())
        setContent(createContent())
        installList()
        subscribe()
        FlutterPreviewService.getInstance(project).panel = this
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let(::showFile)
        if (currentFile == null) updateEmptyText()
        refreshView()
    }

    override fun dispose() {
        runningIndicator.getAndSet(null)?.cancel()
        documentDisposable?.let(Disposer::dispose)
        val service = FlutterPreviewService.getInstance(project)
        if (service.panel === this) service.panel = null
    }

    /** From a gutter icon: show [file] and select its preview [key]. */
    fun reveal(file: VirtualFile, key: String) {
        showFile(file)
        entries.firstOrNull { it.key == key }?.let(::select)
    }

    // region UI

    private fun createToolbar(): JPanel {
        val gridIcon = IconLoader.getIcon("/icons/grid.svg", FlutterPreviewPanel::class.java)
        val group = DefaultActionGroup().apply {
            add(action("Render", "Render all previews of this file again", AllIcons.Actions.Refresh) {
                reparse()
                renderFile()
            })
            add(action("Stop", "Stop the running render", AllIcons.Actions.Suspend, enabled = { runningIndicator.get() != null }) {
                runningIndicator.getAndSet(null)?.cancel()
            })
            addSeparator()
            add(toggle("Grid", "Show all previews of the file as thumbnails", gridIcon, get = { gridMode }, set = ::showGrid))
            add(toggle("Render on Save", "Re-render when a Dart file of the package is saved", AllIcons.Actions.Lightning,
                get = { renderOnSave }, set = { renderOnSave = it }))
            add(toggle("Show Log", "Show the flutter test output", AllIcons.Nodes.Console,
                get = { showingLog }, set = { showingLog = it; logShownForFailure = false; refreshView() }))
            addSeparator()
            add(action("Open on GitHub", "Source, docs and issues of the Flutter Preview plugin", AllIcons.Vcs.Vendors.Github) {
                BrowserUtil.browse(FlutterPreviewService.REPOSITORY_URL)
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("FlutterPreview", group, true)
        toolbar.targetComponent = this
        return JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.CENTER) }
    }

    private fun createContent(): JPanel {
        val previews = JPanel(BorderLayout()).apply {
            fileLabel.border = JBUI.Borders.empty(4, 8)
            fileLabel.foreground = UIUtil.getContextHelpForeground()
            add(fileLabel, BorderLayout.NORTH)
            add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }
        cardPanel.add(JBScrollPane(image).apply { border = JBUI.Borders.empty() }, CARD_IMAGE)
        cardPanel.add(JBScrollPane(grid).apply { border = JBUI.Borders.empty() }, CARD_GRID)
        cardPanel.add(JBScrollPane(logArea).apply { border = JBUI.Borders.empty() }, CARD_LOG)
        PopupHandler.installPopupMenu(image, imageActions, "FlutterPreviewImage")
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineBottom(JBColor.border())
            add(createPreviewToolbar(), BorderLayout.NORTH)
            status.border = JBUI.Borders.empty(2, 8, 4, 8)
            add(status, BorderLayout.CENTER)
        }
        val preview = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(cardPanel, BorderLayout.CENTER)
            add(problemsPane, BorderLayout.SOUTH)
        }
        image.onZoomChanged = { updateZoomLabel() }
        return OnePixelSplitter(false, "FlutterPreview.columns", 0.3f).apply {
            firstComponent = previews
            secondComponent = preview
        }
    }

    private fun createPreviewToolbar(): JPanel {
        val fitWidthIcon = IconLoader.getIcon("/icons/fitWidth.svg", FlutterPreviewPanel::class.java)
        val fitHeightIcon = IconLoader.getIcon("/icons/fitHeight.svg", FlutterPreviewPanel::class.java)
        fun fit(text: String, icon: Icon, mode: PreviewImageComponent.Mode) = toggle(
            text, text, icon,
            get = { !gridMode && image.mode == mode },
            set = { image.setMode(if (it) mode else PreviewImageComponent.Mode.CUSTOM) },
            enabled = { !gridMode },
        )
        val group = DefaultActionGroup().apply {
            add(action("Zoom Out", "Zoom out (⌘/Ctrl + wheel)", AllIcons.General.ZoomOut) {
                if (gridMode) grid.zoomOut() else image.zoomOut()
                updateZoomLabel()
            })
            add(action("Zoom In", "Zoom in (⌘/Ctrl + wheel)", AllIcons.General.ZoomIn) {
                if (gridMode) grid.zoomIn() else image.zoomIn()
                updateZoomLabel()
            })
            add(action("Actual Size", "Show at 100 %", AllIcons.General.ActualZoom, enabled = { !gridMode }) { image.actualSize() })
            addSeparator()
            add(fit("Fit All", AllIcons.General.FitContent, PreviewImageComponent.Mode.FIT_ALL))
            add(fit("Fit Width", fitWidthIcon, PreviewImageComponent.Mode.FIT_WIDTH))
            add(fit("Fit Height", fitHeightIcon, PreviewImageComponent.Mode.FIT_HEIGHT))
            addSeparator()
            addAll(imageActions.getChildren(ActionManager.getInstance()).take(2))
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("FlutterPreview.Zoom", group, true)
        toolbar.targetComponent = this
        zoomLabel.foreground = UIUtil.getContextHelpForeground()
        zoomLabel.border = JBUI.Borders.emptyLeft(4)
        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(zoomLabel, BorderLayout.CENTER)
        }
    }

    private fun installList() {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<PreviewEntry>() {
            override fun customizeCellRenderer(
                list: JList<out PreviewEntry>, value: PreviewEntry, index: Int, selected: Boolean, hasFocus: Boolean,
            ) {
                icon = if (value.problem != null) AllIcons.General.Warning else AllIcons.Actions.Preview
                append(value.title, if (value.problem != null) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                toolTipText = listOfNotNull(value.group, "${value.declaration} · line ${value.line}", value.problem)
                    .joinToString("<br>", "<html>", "</html>")
            }
        }
        list.addListSelectionListener { event ->
            if (event.valueIsAdjusting || updatingList) return@addListSelectionListener
            refreshView()
            // Nothing cached and nothing running for this file (e.g. render on save is off): render now.
            val path = currentFile?.path
            if (list.selectedValue != null && path != null && states[path] == null && renderingPath != path) renderFile()
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                list.selectedValue?.let(::navigate) ?: return false
                return true
            }
        }.installOn(list)
    }

    private fun select(entry: PreviewEntry) {
        if (list.selectedValue?.key == entry.key) return
        val index = entries.indexOfFirst { it.key == entry.key }
        if (index >= 0) {
            list.selectedIndex = index
            list.ensureIndexIsVisible(index)
        }
    }

    private fun showGrid(show: Boolean) {
        gridMode = show
        refreshView()
    }

    private fun updateEmptyText() {
        list.emptyText.text = when {
            currentFile == null -> "Open a Dart file with @Preview widgets"
            currentRoot == null -> "No pubspec.yaml above this file"
            else -> "No @Preview in ${currentFile!!.name}"
        }
    }

    private fun updateZoomLabel() {
        zoomLabel.text = if (gridMode) "" else "${(image.scale() * 100).roundToInt()}%"
    }

    private fun selectedImage(): CachedPreview? {
        val key = list.selectedValue?.key ?: return null
        return currentFile?.path?.let { states[it] }?.images?.get(key)
    }

    /** Re-draws the right column from the cache. Never renders. */
    private fun refreshView() {
        val state = currentFile?.path?.let { states[it] }
        val selected = list.selectedValue
        val cached = selected?.let { state?.images?.get(it.key) }
        val renderingThis = renderingPath != null && renderingPath == currentFile?.path

        image.setImage(cached?.image, cached?.pixelRatio ?: 2.0)
        val pending = when {
            renderingThis -> "Rendering…"
            state != null && !state.stale -> "Failed to render — see the log"
            else -> ""
        }
        grid.setItems(entries, state?.images.orEmpty(), selected?.key, pending)
        logArea.text = state?.log.orEmpty()
        logArea.caretPosition = 0
        setProblems(if (gridMode) emptyList() else cached?.problems.orEmpty())
        cards.show(cardPanel, when {
            showingLog -> CARD_LOG
            gridMode -> CARD_GRID
            else -> CARD_IMAGE
        })
        updateZoomLabel()
        updateStatus(state, selected, cached, renderingThis)
    }

    private fun updateStatus(state: FileState?, selected: PreviewEntry?, cached: CachedPreview?, rendering: Boolean) {
        val summary = state?.takeIf { !it.stale || it.images.isNotEmpty() }?.let {
            "${it.images.size} rendered in ${"%.1f".format(it.millis / 1000.0)} s"
        }
        when {
            currentFile == null || entries.isEmpty() -> setStatus("", null)
            !rendering && state?.lastFailed == true && state.images.isNotEmpty() -> setStatus(
                "Last render failed (code doesn't compile?) — showing previous previews · open the log for details",
                AllIcons.General.Error,
            )
            rendering && cached != null -> setStatus("Updating $renderingCount previews…", AllIcons.Process.Step_1)
            rendering -> setStatus("Rendering $renderingCount previews…", AllIcons.Process.Step_1)
            gridMode -> setStatus(summary.orEmpty(), null)
            selected == null -> setStatus("Select a preview", null)
            selected.problem != null -> setStatus(selected.problem, AllIcons.General.Warning)
            cached != null -> {
                val notes = cached.notes.joinToString("") { " · $it" }
                val icon = if (cached.problems.isEmpty()) AllIcons.General.InspectionsOK else AllIcons.General.Warning
                setStatus("${selected.title}$notes${summary?.let { " · $it" }.orEmpty()}", icon)
            }
            state != null && !state.stale -> setStatus("Failed to render (exit ${state.exitCode}) — see the log", AllIcons.General.Error)
            else -> setStatus("Not rendered yet", null)
        }
    }

    private fun setProblems(problems: List<String>) {
        problemsArea.text = problems.joinToString("\n\n────────\n\n")
        problemsArea.caretPosition = 0
        problemsPane.isVisible = problems.isNotEmpty()
        problemsPane.revalidate()
    }

    private fun setStatus(text: String, icon: Icon?) {
        status.text = text
        status.icon = icon
    }

    // endregion

    // region Files

    private fun subscribe() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                event.newFile?.let(::showFile)
            }
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val root = currentRoot?.dir?.path ?: return
                val relevant = events.any { event ->
                    val path = event.path
                    path.startsWith("$root/") && !path.contains("/.dart_tool/") &&
                        (path.endsWith(".dart") || path.endsWith("pubspec.yaml"))
                }
                if (!relevant) return
                saveAlarm.cancelAllRequests()
                saveAlarm.addRequest(::onSourcesSaved, 300)
            }
        })
        // Caret sync: moving into a preview function selects it. Caret events can arrive where the IDE forbids
        // read locks, so only compare the document and offsets here and do the rest later.
        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.editor.document !== currentDocument) return
                val offset = event.editor.caretModel.offset
                val entry = entries.firstOrNull { offset in it.offset..it.endOffset } ?: return
                if (entry.key == list.selectedValue?.key) return
                caretAlarm.cancelAllRequests()
                caretAlarm.addRequest({ select(entry) }, 150)
            }
        }, this)
    }

    /** Keeps showing the last Dart file while a non-Dart file is focused. */
    private fun showFile(file: VirtualFile) {
        if (file.extension != "dart" || file == currentFile) return
        currentFile = file
        currentRoot = PubRoot.find(File(file.path))
        fileLabel.text = file.name
        fileLabel.toolTipText = currentRoot?.let { "${it.packageName} · ${file.path.removePrefix(it.dir.path + "/")}" } ?: file.path
        watchDocument(file)
        reparse()
        if (list.selectedValue == null && entries.isNotEmpty()) selectSilently(0)
        refreshView()
        val state = states[file.path]
        if (state == null || state.stale) renderFile()
    }

    /** Re-reads the list as you type, so new previews appear and caret sync stays accurate. */
    private fun watchDocument(file: VirtualFile) {
        documentDisposable?.let(Disposer::dispose)
        val disposable = Disposer.newDisposable(this, "FlutterPreview.document")
        documentDisposable = disposable
        currentDocument = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(file) }
        currentDocument?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                typingAlarm.cancelAllRequests()
                typingAlarm.addRequest({ reparse(); refreshView() }, 500)
            }
        }, disposable)
    }

    private fun onSourcesSaved() {
        val root = currentRoot?.dir?.path ?: return
        // A saved widget can change any preview of the package: everything cached for it is stale.
        states.filterKeys { it.startsWith("$root/") }.values.forEach { it.stale = true }
        reparse()
        refreshView()
        if (renderOnSave) renderFile()
    }

    private fun currentText(file: VirtualFile): String = runReadActionBlocking {
        FileDocumentManager.getInstance().getDocument(file)?.text ?: String(file.contentsToByteArray(), file.charset)
    }

    /** Re-reads the previews of the current file, keeping the selection when the same preview still exists. */
    private fun reparse() {
        val file = currentFile?.takeIf { it.isValid } ?: return
        val selectedKey = list.selectedValue?.key
        entries = PreviewParser.parse(currentText(file))
        updatingList = true
        try {
            listModel.clear()
            entries.forEach(listModel::addElement)
            val index = entries.indexOfFirst { it.key == selectedKey }
            if (index >= 0) list.selectedIndex = index else list.clearSelection()
        } finally {
            updatingList = false
        }
        updateEmptyText()
    }

    private fun selectSilently(index: Int) {
        updatingList = true
        try {
            list.selectedIndex = index
        } finally {
            updatingList = false
        }
    }

    private fun navigate(entry: PreviewEntry) {
        val file = currentFile ?: return
        // Swing events don't hold the write-intent lock that navigation needs.
        WriteIntentReadAction.run(Runnable { OpenFileDescriptor(project, file, entry.offset).navigate(true) })
    }

    // endregion

    // region Rendering

    /** Renders every preview of the current file in one `flutter test` run. */
    private fun renderFile() {
        val file = currentFile ?: return
        val root = currentRoot ?: return
        val renderable = entries.filter { it.invocation != null }
        if (renderable.isEmpty()) return

        // The harness is compiled from disk. Swing events don't hold the write-intent lock saving needs.
        WriteIntentReadAction.run(Runnable { FileDocumentManager.getInstance().saveAllDocuments() })
        val environment = EnvironmentUtil.getEnvironmentMap()
        val sdk = FlutterSdkLocator.find(root, environment)
            ?: return setStatus("Flutter SDK not found. Run `flutter pub get` in ${root.dir.path}, or set FLUTTER_ROOT.", AllIcons.General.Error)
        val harness = try {
            HarnessGenerator.generate(root, File(file.path), currentText(file), renderable)
        } catch (e: Exception) {
            return setStatus("Could not generate the preview harness: ${e.message}", AllIcons.General.Error)
        }

        val generation = renderGeneration.incrementAndGet()
        runningIndicator.getAndSet(null)?.cancel()
        val path = file.path
        renderingPath = path
        renderingCount = renderable.size
        refreshView()
        val renderer = renderers.computeIfAbsent(sdk.path) { PreviewRenderer(sdk, environment) }

        object : Task.Backgroundable(project, "Rendering Flutter previews", true) {
            private var result: BatchResult? = null
            private var decoded: Map<String, CachedPreview> = emptyMap()
            private var myIndicator: ProgressIndicator? = null

            override fun run(indicator: ProgressIndicator) {
                myIndicator = indicator
                runningIndicator.set(indicator)
                indicator.isIndeterminate = true
                indicator.text = "Rendering ${renderable.size} previews of ${file.name}"
                val batch = renderer.render(root, harness) { indicator.isCanceled || generation != renderGeneration.get() }
                decoded = batch.images.mapValues { (_, img) ->
                    CachedPreview(img.png, ImageIO.read(ByteArrayInputStream(img.png)), img.pixelRatio, img.problems, img.notes)
                }
                result = batch
            }

            override fun onSuccess() {
                val batch = result ?: return
                val state = states.getOrPut(path) { FileState() }
                val keepPrevious = decoded.isEmpty() && state.images.isNotEmpty()
                state.apply {
                    // While editing, a save often catches the code mid-change: keep the last good images.
                    if (!keepPrevious) {
                        images = decoded
                        millis = batch.millis
                    }
                    lastFailed = decoded.isEmpty()
                    stale = false
                    log = batch.log
                    exitCode = batch.exitCode
                }
                if (generation == renderGeneration.get()) {
                    renderingPath = null
                    if (path == currentFile?.path) {
                        if (decoded.isEmpty() && !keepPrevious && !showingLog) {
                            // Nothing rendered at all usually means the harness didn't compile: show why.
                            showingLog = true
                            logShownForFailure = true
                        } else if (decoded.isNotEmpty() && logShownForFailure) {
                            showingLog = false
                            logShownForFailure = false
                        }
                    }
                }
                refreshView()
            }

            override fun onCancel() = finished()

            override fun onThrowable(error: Throwable) {
                finished()
                if (error !is RenderCancelledException && generation == renderGeneration.get()) {
                    setStatus("Render failed: ${error.message}", AllIcons.General.Error)
                }
            }

            override fun onFinished() {
                myIndicator?.let { runningIndicator.compareAndSet(it, null) }
            }

            private fun finished() {
                if (generation == renderGeneration.get()) {
                    renderingPath = null
                    refreshView()
                }
            }
        }.queue()
    }

    // endregion

    private fun action(
        text: String,
        description: String,
        icon: Icon,
        enabled: () -> Boolean = { true },
        perform: () -> Unit,
    ) = object : DumbAwareAction(text, description, icon) {
        override fun actionPerformed(e: AnActionEvent) = perform()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun toggle(
        text: String,
        description: String,
        icon: Icon,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
        enabled: () -> Boolean = { true },
    ) = object : DumbAwareToggleAction(text, description, icon) {
        override fun isSelected(e: AnActionEvent) = get()
        override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = enabled()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun textArea() = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, 12)
        border = JBUI.Borders.empty(6)
    }

    private companion object {
        const val CARD_IMAGE = "image"
        const val CARD_GRID = "grid"
        const val CARD_LOG = "log"
        const val MAX_CACHED_FILES = 6
    }
}
