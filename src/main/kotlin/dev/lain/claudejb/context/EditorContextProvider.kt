package dev.lain.claudejb.context

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/**
 * The composer's context sources: the current editor file/selection, so the user can inject them as
 * @-context the way the CLI lets you reference files, and the system clipboard.
 *
 * Editor accessors must be called on the EDT; the clipboard entry points are pure (no project/editor) and
 * confine all failures with [runCatching] — they never throw. What sits behind them lives next door:
 * [ClipboardCli] (the `wl-paste`/`xclip` fallback AWT needs on Linux) and [ImageAttachments] (bytes →
 * [Attachment.Image]). The policy — **AWT first, external tool second** — is here, in one place.
 */
object EditorContextProvider {

    /** Absolute path of the file open in the active editor, or null. */
    fun currentFilePath(project: Project): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val vFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
        return vFile?.path
    }

    /** Selected text in the active editor, or null if there is no selection. */
    fun currentSelection(project: Project): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }
    }

    /** 1-based line where the current selection starts (or the caret line if nothing is selected), or null. */
    fun currentSelectionStartLine(project: Project): Int? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val offset = editor.selectionModel.selectionStart
        return editor.document.getLineNumber(offset) + 1
    }

    /** The current selection (file path, start line, text, lang) as an [Attachment.Selection], or null. */
    fun selectionAsAttachment(project: Project): Attachment.Selection? {
        val path = currentFilePath(project) ?: return null
        val text = currentSelection(project) ?: return null
        val line = currentSelectionStartLine(project) ?: return null
        val lang = langForExtension(path.substringAfterLast('.', "").lowercase())
        return Attachment.Selection(path = path, startLine = line, text = text, lang = lang)
    }

    /** The active file as an [Attachment.FileRef] (`@path` mention), or null when no editor is focused. */
    fun currentFileAsAttachment(project: Project): Attachment.FileRef? {
        val path = currentFilePath(project) ?: return null
        return Attachment.FileRef(path = path, displayName = path.substringAfterLast('/'))
    }

    /**
     * Reads an image off the system clipboard, or null. Tries AWT first; on Linux, where AWT's
     * `imageFlavor` is unreliable under Wayland (and often empty), falls back to the `wl-paste`
     * (Wayland) / `xclip` (X11) CLIs if present. This is what makes Ctrl+V image paste and the
     * composer's "Paste image" actually work on Wayland.
     */
    fun imageFromClipboard(): Attachment.Image? = awtClipboardImage() ?: ClipboardCli.image()

    /**
     * True if the system clipboard currently holds plain text (so a paste is a text paste, not an image).
     * AWT first; on Linux under the **native Wayland toolkit** (`sun.awt.wl.WLToolkit`) AWT's clipboard is
     * unreliable, so we also consult `wl-paste`/`xclip`'s advertised types (a text MIME must be present).
     */
    fun clipboardHasText(): Boolean {
        val awt = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
        }.getOrDefault(false)
        if (awt) return true
        return ClipboardCli.textType() != null
    }

    /**
     * Plain-text contents of the system clipboard, or null. AWT first; on Linux it falls back to
     * `wl-paste`/`xclip` (AWT's stringFlavor is empty/unreliable under the native Wayland toolkit). The
     * fallback only reads a real `text/…` target — never `wl-paste -n` blindly, which on an image-only
     * clipboard emits raw image bytes.
     */
    fun clipboardText(): String? {
        runCatching {
            val cb = Toolkit.getDefaultToolkit().systemClipboard
            if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                (cb.getData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return ClipboardCli.text()
    }

    /**
     * Help text for getting clipboard-image paste working, or null when it should already work
     * (Windows/macOS read images via AWT; or a Linux CLI is already installed — then the real issue
     * is just "no image in the clipboard"). On Linux without a tool, returns a distro-aware install hint.
     */
    fun clipboardImageHelp(): String? {
        if (!ClipboardCli.isLinux()) return null // Windows & macOS read images straight from AWT
        if (ClipboardCli.findExecutable("wl-paste") != null || ClipboardCli.findExecutable("xclip") != null) return null
        return "image paste needs 'wl-clipboard' (Wayland) or 'xclip' (X11): " + ClipboardCli.installHint()
    }

    private fun awtClipboardImage(): Attachment.Image? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        val base64 = ImageAttachments.pngBase64(image) ?: return null
        Attachment.Image(displayName = "clipboard.png", mediaType = "image/png", base64 = base64)
    }.getOrNull()

    /**
     * File extension → Markdown-fence language hint.
     *
     * A table, not a branch: this never was control flow, it was a dictionary written as 26 `when` arms. As
     * data it reads as what it is, costs one hash lookup instead of a linear scan, and adding a language is a
     * one-line entry rather than another branch in a function.
     */
    private val LANG_BY_EXTENSION: Map<String, String> = buildMap {
        fun map(lang: String, vararg extensions: String) = extensions.forEach { put(it, lang) }
        map("kotlin", "kt", "kts")
        map("java", "java")
        map("python", "py")
        map("javascript", "js", "mjs", "cjs")
        map("typescript", "ts")
        map("tsx", "tsx")
        map("jsx", "jsx")
        map("go", "go")
        map("rust", "rs")
        map("ruby", "rb")
        map("php", "php")
        map("c", "c", "h")
        map("cpp", "cpp", "cc", "cxx", "hpp")
        map("csharp", "cs")
        map("swift", "swift")
        map("bash", "sh", "bash", "zsh")
        map("sql", "sql")
        map("html", "html", "htm")
        map("css", "css")
        map("scss", "scss")
        map("xml", "xml")
        map("json", "json")
        map("yaml", "yaml", "yml")
        map("toml", "toml")
        map("markdown", "md", "markdown")
        map("groovy", "gradle")
    }

    /** Maps a file extension to a Markdown-fence language hint, or null when unknown. */
    fun langForExtension(ext: String): String? = LANG_BY_EXTENSION[ext]
}
