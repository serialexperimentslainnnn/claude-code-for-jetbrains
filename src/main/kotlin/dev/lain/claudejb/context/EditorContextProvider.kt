package dev.lain.claudejb.context

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.awt.image.RenderedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Pulls the current editor file/selection so the user can inject them as @-context, mirroring how the
 * CLI lets you reference files. Editor accessors must be called on the EDT; the clipboard/file image
 * helpers are pure (no project/editor) and confine all failures with [runCatching] — they never throw.
 */
object EditorContextProvider {

    /** Absolute path of the file open in the active editor, or null. */
    fun currentFilePath(project: Project): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val vFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
        return vFile?.path
    }

    fun currentFileName(project: Project): String? =
        currentFilePath(project)?.substringAfterLast('/')

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

    /** Markdown-fence language hint derived from the active file's extension, or null. */
    fun currentSelectionLang(project: Project): String? =
        currentFilePath(project)?.substringAfterLast('.', "")?.lowercase()?.let { langForExtension(it) }

    /** The current selection (file path, start line, text, lang) as an [Attachment.Selection], or null. */
    fun selectionAsAttachment(project: Project): Attachment.Selection? {
        val path = currentFilePath(project) ?: return null
        val text = currentSelection(project) ?: return null
        val line = currentSelectionStartLine(project) ?: return null
        return Attachment.Selection(path = path, startLine = line, text = text, lang = langForExtension(path.substringAfterLast('.', "").lowercase()))
    }

    /** The active file as an [Attachment.FileRef] (`@path` mention), or null when no editor is focused. */
    fun currentFileAsAttachment(project: Project): Attachment.FileRef? {
        val path = currentFilePath(project) ?: return null
        return Attachment.FileRef(path = path, displayName = path.substringAfterLast('/'))
    }

    /** Reads an image off the system clipboard and encodes it as a PNG [Attachment.Image], or null. */
    fun imageFromClipboard(): Attachment.Image? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        val rendered = image.toRenderedImage() ?: return null
        val base64 = pngBase64(rendered) ?: return null
        Attachment.Image(displayName = "clipboard.png", mediaType = "image/png", base64 = base64)
    }.getOrNull()

    /** Reads an image file from disk, detecting media type by extension, as an [Attachment.Image], or null. */
    fun imageFromFile(path: String): Attachment.Image? = runCatching {
        val file = File(path)
        val bytes = file.takeIf { it.isFile }?.readBytes() ?: return null
        if (bytes.isEmpty()) return null
        val mediaType = mediaTypeForExtension(file.extension.lowercase()) ?: return null
        Attachment.Image(
            displayName = file.name,
            mediaType = mediaType,
            base64 = Base64.getEncoder().encodeToString(bytes),
        )
    }.getOrNull()

    /** Maps a file extension to a Markdown-fence language hint, or null when unknown. */
    fun langForExtension(ext: String): String? = when (ext) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "js", "mjs", "cjs" -> "javascript"
        "ts" -> "typescript"
        "tsx" -> "tsx"
        "jsx" -> "jsx"
        "go" -> "go"
        "rs" -> "rust"
        "rb" -> "ruby"
        "php" -> "php"
        "c", "h" -> "c"
        "cpp", "cc", "cxx", "hpp" -> "cpp"
        "cs" -> "csharp"
        "swift" -> "swift"
        "sh", "bash", "zsh" -> "bash"
        "sql" -> "sql"
        "html", "htm" -> "html"
        "css" -> "css"
        "scss" -> "scss"
        "xml" -> "xml"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "toml" -> "toml"
        "md", "markdown" -> "markdown"
        "gradle" -> "groovy"
        else -> null
    }

    /** Maps an image file extension to its IANA media type, or null when not a supported image. */
    fun mediaTypeForExtension(ext: String): String? = when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> null
    }

    private fun java.awt.Image.toRenderedImage(): RenderedImage? {
        (this as? RenderedImage)?.let { return it }
        val width = getWidth(null)
        val height = getHeight(null)
        if (width <= 0 || height <= 0) return null
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        try {
            g.drawImage(this, 0, 0, null)
        } finally {
            g.dispose()
        }
        return buffered
    }

    private fun pngBase64(image: RenderedImage): String? = runCatching {
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", out)) return null
        Base64.getEncoder().encodeToString(out.toByteArray())
    }.getOrNull()
}
