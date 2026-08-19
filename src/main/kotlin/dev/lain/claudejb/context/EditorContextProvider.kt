package dev.lain.claudejb.context

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

object EditorContextProvider {

    fun currentFilePath(project: Project): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val vFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
        return vFile?.path
    }

    fun currentSelection(project: Project): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }
    }

    fun currentSelectionStartLine(project: Project): Int? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val offset = editor.selectionModel.selectionStart
        return editor.document.getLineNumber(offset) + 1
    }

    fun selectionAsAttachment(project: Project): Attachment.Selection? {
        val path = currentFilePath(project) ?: return null
        val text = currentSelection(project) ?: return null
        val line = currentSelectionStartLine(project) ?: return null
        val lang = langForExtension(path.substringAfterLast('.', "").lowercase())
        return Attachment.Selection(path = path, startLine = line, text = text, lang = lang)
    }

    fun currentFileAsAttachment(project: Project): Attachment.FileRef? {
        val path = currentFilePath(project) ?: return null
        return Attachment.FileRef(path = path, displayName = path.substringAfterLast('/'))
    }

    fun imageFromClipboard(): Attachment.Image? = awtClipboardImage() ?: ClipboardCli.image()

    fun clipboardHasText(): Boolean {
        val awt = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
        }.getOrDefault(false)
        if (awt) return true
        return ClipboardCli.textType() != null
    }

    fun clipboardText(): String? {
        runCatching {
            CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        runCatching {
            val cb = Toolkit.getDefaultToolkit().systemClipboard
            if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                (cb.getData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return ClipboardCli.text()
    }

    fun clipboardImageHelp(): String? {
        if (!ClipboardCli.isLinux()) return null
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

    fun langForExtension(ext: String): String? = LANG_BY_EXTENSION[ext]
}
