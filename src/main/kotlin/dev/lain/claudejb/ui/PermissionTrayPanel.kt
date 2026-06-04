package dev.lain.claudejb.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.diff.Hunk
import dev.lain.claudejb.diff.HunkSelection
import dev.lain.claudejb.permission.PendingPermission
import dev.lain.claudejb.protocol.AskOption
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.UIManager
import kotlinx.serialization.json.JsonObject

/**
 * The non-modal permission tray shown just above the composer: one card per [PendingPermission].
 *
 * A regular tool request renders an Accept / Reject / Always allow card (with a View-diff link and, for a
 * multi-hunk write, per-hunk checkboxes that narrow the approved input). An AskUserQuestion renders a
 * Submit / Cancel card whose option rows collect the user's answers.
 *
 * Autonomous: the panel knows nothing about `ClaudeSession`. Every action is routed through a callback and
 * keyed by `requestId` (or `toolName` for "Always allow"); the host owns resolution, the diff lifecycle
 * (open/reveal/close) and the snapshot store. The pure hunk reconstruction ([DiffPresenter]/[HunkSelection])
 * is done here only to compute the narrowed `overrideInput` handed back via [onAccept].
 *
 * [update] rebuilds the cards from the given list and hides the panel when it is empty.
 */
class PermissionTrayPanel(
    /** Accept a request; [overrideInput] is a hunk-narrowed input (file_path never altered) or null for all. */
    private val onAccept: (requestId: String, overrideInput: JsonObject?) -> Unit,
    private val onReject: (requestId: String) -> Unit,
    /** Host opens/reveals the persisted or live diff for this request. */
    private val onViewDiff: (requestId: String) -> Unit,
    /** Remember [toolName] as always-allowed, then approve this request. */
    private val onAlwaysAllow: (toolName: String) -> Unit,
    /** Answer an AskUserQuestion: question → comma-joined chosen labels. */
    private val onAnswer: (requestId: String, answers: Map<String, String>) -> Unit,
) : JPanel(VerticalLayout(JBUIScale.scale(6))) {

    init {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(6)
        isVisible = false
    }

    /** Rebuilds every card from the pending list; hides the tray when there is nothing to show. */
    fun update(pending: List<PendingPermission>) {
        removeAll()
        isVisible = pending.isNotEmpty()
        pending.forEach { request ->
            add(
                when {
                    request.isPlan -> planCard(request)
                    request.questions != null -> questionCard(request)
                    else -> permissionCard(request)
                }
            )
        }
        revalidate()
        repaint()
    }

    // -----------------------------------------------------------------------
    // permission card
    // -----------------------------------------------------------------------

    private fun permissionCard(request: PendingPermission): JComponent {
        val titleLabel = JBLabel(request.title).apply {
            foreground = ChatTheme.TEXT
            font = ChatTheme.small.asBold()
        }
        val texts = JPanel(VerticalLayout(JBUIScale.scale(2))).apply {
            isOpaque = false
            add(titleLabel)
            if (request.summary.isNotBlank()) {
                val area = JBTextArea(request.summary).apply {
                    isEditable = false
                    lineWrap = false
                    foreground = ChatTheme.TEXT_DIM
                    font = ChatTheme.mono
                    background = ChatTheme.CODE_BG
                    isOpaque = true
                    border = JBUI.Borders.empty(3, 6)
                }
                val naturalH = (area.preferredSize.height + JBUIScale.scale(6)).coerceAtMost(JBUIScale.scale(PERMISSION_SUMMARY_MAX_HEIGHT))
                add(JBScrollPane(area).apply {
                    border = JBUI.Borders.empty()
                    background = ChatTheme.CODE_BG
                    viewport.background = ChatTheme.CODE_BG
                    preferredSize = Dimension(0, naturalH)
                    verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                })
            }
            addRichFields(this, request)
        }

        // Per-hunk selection: for a reviewable write with >1 hunk, let the user pick which hunks to apply.
        // Computed once at card-build time so toggling doesn't re-read the file / recompute the diff.
        val hunkData: HunkSelectorData? = if (request.reviewable) {
            val path = DiffPresenter.filePathOf(request.input)
            val current = path?.let {
                val f = java.io.File(it)
                if (f.isFile) runCatching { f.readText() }.getOrDefault("") else ""
            } ?: ""
            val proposed = DiffPresenter.proposedContent(request.toolName, request.input, current)
            val hunks = if (proposed != null) DiffPresenter.computeHunks(current, proposed) else emptyList()
            if (hunks.size > 1 && proposed != null) HunkSelectorData(current, proposed, hunks) else null
        } else null

        val accepted: LinkedHashSet<Int>? = hunkData?.hunks?.mapTo(LinkedHashSet()) { it.index }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        if (request.reviewable) {
            buttons.add(linkButton("View diff") { onViewDiff(request.requestId) })
        }
        buttons.add(linkButton("Always allow") { onAlwaysAllow(request.toolName) })
        buttons.add(linkButton("Reject") { onReject(request.requestId) })
        buttons.add(RoundedActionButton("Accept") {
            if (hunkData != null && accepted != null) {
                val allIndices = hunkData.hunks.mapTo(HashSet()) { it.index }
                when {
                    accepted.isEmpty() ->
                        // Nothing selected: applying an empty subset would be a confusing no-op write, so reject.
                        onReject(request.requestId)
                    accepted == allIndices ->
                        onAccept(request.requestId, null)
                    else -> {
                        val selectedText = HunkSelection.reconstruct(
                            hunkData.current.split("\n"), hunkData.proposed.split("\n"), hunkData.hunks, accepted,
                        )
                        val override = HunkSelection.encodeInput(
                            request.toolName, request.input, hunkData.current, selectedText,
                        )
                        onAccept(request.requestId, override)
                    }
                }
            } else {
                onAccept(request.requestId, null)
            }
        })

        // Summary (+ optional hunk selector) live together in the CENTER region of the card's BorderLayout.
        val center = if (hunkData != null && accepted != null) {
            JPanel(VerticalLayout(JBUIScale.scale(6))).apply {
                isOpaque = false
                add(texts)
                add(hunkSelector(hunkData.hunks, accepted))
            }
        } else texts

        return ChatTheme.RoundedPanel(12, ChatTheme.CARD_BG, ChatTheme.ACCENT).apply {
            layout = BorderLayout(JBUIScale.scale(8), JBUIScale.scale(6))
            border = JBUI.Borders.empty(10, 12)
            add(center, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    /** Diff data captured once when a per-hunk permission card is built (no re-read on toggle). */
    private data class HunkSelectorData(
        val current: String,
        val proposed: String,
        val hunks: List<Hunk>,
    )

    /**
     * A height-capped, scrollable list of the change's hunks as checkbox rows (all checked by default). Toggling a
     * row mutates [accepted] in place and refreshes its glyph; the Accept button reads [accepted] when clicked.
     */
    private fun hunkSelector(hunks: List<Hunk>, accepted: LinkedHashSet<Int>): JComponent {
        val list = JPanel(VerticalLayout(JBUIScale.scale(2))).apply { isOpaque = false }
        for (hunk in hunks) {
            val glyph = JBLabel().apply {
                font = ChatTheme.small
                verticalAlignment = SwingConstants.TOP
                border = JBUI.Borders.emptyRight(8)
            }
            val text = "Lines ${hunk.start1 + 1}-${hunk.end1}: ${truncate(hunk.preview, HUNK_PREVIEW_MAX_CHARS)}"
            val label = wrappingArea(text, ChatTheme.small, ChatTheme.TEXT)
            val refresh = {
                val on = hunk.index in accepted
                glyph.text = if (on) "☑" else "☐"
                label.foreground = if (on) ChatTheme.TEXT else ChatTheme.TEXT_DIM
            }
            val toggle = {
                if (!accepted.add(hunk.index)) accepted.remove(hunk.index)
                refresh()
            }
            val click = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = toggle()
            }
            val row = JPanel(BorderLayout()).apply {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(2, 4)
                add(glyph, BorderLayout.WEST)
                add(label, BorderLayout.CENTER)
                addMouseListener(click)
            }
            glyph.addMouseListener(click)
            label.addMouseListener(click)
            refresh()
            list.add(row)
        }
        return JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            preferredSize = Dimension(0, JBUIScale.scale(PERMISSION_SUMMARY_MAX_HEIGHT))
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    /**
     * Appends the optional rich fields the binary may attach to a `can_use_tool` request — `description` (a short
     * sentence about the action), `decision_reason` (why the request was surfaced, e.g. a deny rule) and
     * `blocked_path` (the path that triggered it). Each is shown as a wrapping line only when present, so a plain
     * request renders exactly as before.
     */
    private fun addRichFields(into: JPanel, request: PendingPermission) {
        request.description?.takeIf { it.isNotBlank() }?.let {
            into.add(wrappingArea(it, ChatTheme.small, ChatTheme.TEXT_DIM))
        }
        request.blockedPath?.takeIf { it.isNotBlank() }?.let {
            into.add(wrappingArea("Blocked path: $it", ChatTheme.small, ChatTheme.TEXT_DIM))
        }
        request.decisionReason?.takeIf { it.isNotBlank() }?.let {
            into.add(wrappingArea("Reason: $it", ChatTheme.small, ChatTheme.TEXT_DIM))
        }
    }

    // -----------------------------------------------------------------------
    // Plan card (ExitPlanMode)
    // -----------------------------------------------------------------------

    /**
     * ExitPlanMode card: shows the proposed plan body in a scrollable, wrapping area with Approve plan / Keep
     * planning actions. Approve plan resolves the request via [onAccept] (allow); Keep planning denies it via
     * [onReject] (deny), keeping the agent in plan mode. No new callbacks — it reuses the standard wiring.
     */
    private fun planCard(request: PendingPermission): JComponent {
        val body = JPanel(VerticalLayout(JBUIScale.scale(4))).apply { isOpaque = false }
        body.add(JBLabel(request.title).apply { foreground = ChatTheme.TEXT; font = ChatTheme.small.asBold() })

        val planText = request.planText?.takeIf { it.isNotBlank() } ?: "(no plan provided)"
        val area = wrappingArea(planText, ChatTheme.small, ChatTheme.TEXT)
        body.add(JBScrollPane(area).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            preferredSize = Dimension(0, JBUIScale.scale(PLAN_BODY_MAX_HEIGHT))
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        })
        addRichFields(body, request)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        buttons.add(linkButton("Keep planning") { onReject(request.requestId) })
        buttons.add(RoundedActionButton("Approve plan") { onAccept(request.requestId, null) })

        return ChatTheme.RoundedPanel(12, ChatTheme.CARD_BG, ChatTheme.ACCENT).apply {
            layout = BorderLayout(JBUIScale.scale(8), JBUIScale.scale(6))
            border = JBUI.Borders.empty(10, 12)
            add(body, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    // -----------------------------------------------------------------------
    // AskUserQuestion card
    // -----------------------------------------------------------------------

    /**
     * AskUserQuestion card: renders each question's options as selectable rows (single- or multi-select),
     * with Submit/Cancel. Submit returns the chosen labels via [onAnswer]; Cancel denies via [onReject].
     */
    private fun questionCard(request: PendingPermission): JComponent {
        val questions = request.questions ?: return permissionCard(request)
        val selections = LinkedHashMap<String, LinkedHashSet<String>>()
        val refreshers = ArrayList<() -> Unit>()

        val submit = RoundedActionButton("Submit") {
            if (selections.values.all { it.isNotEmpty() }) {
                onAnswer(request.requestId, selections.mapValues { (_, v) -> v.joinToString(",") })
            }
        }.apply { isEnabled = false }

        val body = JPanel(VerticalLayout(JBUIScale.scale(4))).apply { isOpaque = false }
        body.add(JBLabel(request.title).apply { foreground = ChatTheme.TEXT; font = ChatTheme.small.asBold() })

        for (q in questions) {
            val sel = LinkedHashSet<String>()
            selections[q.question] = sel
            if (q.question.isNotBlank()) {
                body.add(JBLabel(q.question).apply {
                    foreground = ChatTheme.TEXT
                    font = ChatTheme.small
                    border = JBUI.Borders.emptyTop(6)
                })
            }
            for (opt in q.options) {
                val toggle = {
                    if (q.multiSelect) { if (!sel.add(opt.label)) sel.remove(opt.label) }
                    else { sel.clear(); sel.add(opt.label) }
                    refreshers.forEach { it() }
                    submit.isEnabled = selections.values.all { it.isNotEmpty() }
                }
                val (row, refresh) = optionRow(opt, q.multiSelect, isSelected = { opt.label in sel }, onToggle = toggle)
                refreshers.add(refresh)
                refresh()
                body.add(row)
            }
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        buttons.add(linkButton("Cancel") { onReject(request.requestId) })
        buttons.add(submit)

        return ChatTheme.RoundedPanel(12, ChatTheme.CARD_BG, ChatTheme.ACCENT).apply {
            layout = BorderLayout(JBUIScale.scale(8), JBUIScale.scale(6))
            border = JBUI.Borders.empty(10, 12)
            add(body, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    /**
     * One AskUserQuestion option as a wrapping, fully clickable row: a selection glyph plus the label, description
     * and (when present) preview, each in a word-wrapping area so long text stays readable inside the narrow tool
     * window instead of being clipped to one line. Returns the row and a refresher that syncs glyph/colors.
     */
    private fun optionRow(
        opt: AskOption,
        multiSelect: Boolean,
        isSelected: () -> Boolean,
        onToggle: () -> Unit,
    ): Pair<JComponent, () -> Unit> {
        val glyph = JBLabel().apply {
            font = ChatTheme.small
            verticalAlignment = SwingConstants.TOP
            border = JBUI.Borders.emptyRight(8)
        }
        val label = wrappingArea(opt.label, ChatTheme.small.asBold(), ChatTheme.TEXT)
        val center = JPanel(VerticalLayout(JBUIScale.scale(2))).apply {
            isOpaque = false
            add(label)
            if (opt.description.isNotBlank()) {
                add(wrappingArea(opt.description, ChatTheme.small, ChatTheme.TEXT_DIM))
            }
            opt.preview?.takeIf { it.isNotBlank() }?.let { preview ->
                val area = JBTextArea(preview).apply {
                    isEditable = false
                    lineWrap = false
                    font = ChatTheme.mono
                    foreground = ChatTheme.TEXT_DIM
                    background = ChatTheme.CODE_BG
                    isOpaque = true
                    border = JBUI.Borders.empty(3, 6)
                }
                val naturalH = (area.preferredSize.height + JBUIScale.scale(6))
                    .coerceAtMost(JBUIScale.scale(PERMISSION_SUMMARY_MAX_HEIGHT))
                add(JBScrollPane(area).apply {
                    border = JBUI.Borders.empty()
                    background = ChatTheme.CODE_BG
                    viewport.background = ChatTheme.CODE_BG
                    preferredSize = Dimension(0, naturalH)
                    verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                })
            }
        }
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(3, 4)
            add(glyph, BorderLayout.WEST)
            add(center, BorderLayout.CENTER)
        }
        // The whole row toggles; attach to the row and to the text components (which would otherwise swallow the
        // click). The description/preview keep text selection but the click still toggles via these listeners.
        val click = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onToggle()
        }
        row.addMouseListener(click)
        glyph.addMouseListener(click)
        label.addMouseListener(click)
        val refresh = {
            val on = isSelected()
            glyph.text = if (multiSelect) (if (on) "☑" else "☐") else (if (on) "◉" else "◯")
            label.foreground = if (on) ChatTheme.TEXT else ChatTheme.TEXT_DIM
        }
        return row to refresh
    }

    // -----------------------------------------------------------------------
    // small shared controls
    // -----------------------------------------------------------------------

    /**
     * A non-editable, word-wrapping text area. JBTextArea (not a JLabel) so the text is taken literally — no HTML
     * escaping of labels/descriptions. Before the first layout the area has width 0 and would report a one-line
     * height (collapsing the text); falling back to the parent's width makes the wrapped height correct on the
     * first pass too.
     */
    private fun wrappingArea(content: String, font: JBFont, color: Color): JBTextArea =
        object : JBTextArea(content) {
            override fun getPreferredSize(): Dimension {
                if (width == 0) parent?.width?.takeIf { it > 0 }?.let { setSize(it, Short.MAX_VALUE.toInt()) }
                return super.getPreferredSize()
            }
        }.apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            this.font = font
            foreground = color
            border = JBUI.Borders.empty()
            margin = JBUI.emptyInsets()
        }

    private fun truncate(s: String, max: Int): String =
        s.replace('\n', ' ').let { if (it.length > max) it.take(max) + "…" else it }

    private fun linkButton(text: String, onClick: () -> Unit): JButton = JButton(text).apply {
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        foreground = ChatTheme.TEXT_DIM
        font = ChatTheme.small
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        margin = JBUI.insets(2, 8)
        addActionListener { onClick() }
    }

    /** A compact pill button (Accept / Submit) painted with the IDE's primary button color. */
    private class RoundedActionButton(text: String, private val onClick: () -> Unit) : JButton(text) {
        init {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            font = ChatTheme.small.asBold()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(5, 14)
            addActionListener { onClick() }
        }

        override fun getForeground(): Color =
            UIManager.getColor("Button.default.foreground") ?: Color.WHITE

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val base: Color = UIManager.getColor("Button.default.startBackground") ?: Color(0x4B6EAF)
                g2.color = when {
                    !isEnabled -> base.darker()
                    model.isPressed -> base.darker()
                    else -> base
                }
                val arc = JBUIScale.scale(14)
                g2.fillRoundRect(0, 0, width, height, arc, arc)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    companion object {
        /** Max height (unscaled) of a permission card's summary box before it scrolls. */
        private const val PERMISSION_SUMMARY_MAX_HEIGHT = 82
        /** Max characters of a hunk preview shown in a per-hunk selector row before truncation. */
        private const val HUNK_PREVIEW_MAX_CHARS = 80
        /** Max height (unscaled) of a plan card's body before it scrolls. */
        private const val PLAN_BODY_MAX_HEIGHT = 220
    }
}
