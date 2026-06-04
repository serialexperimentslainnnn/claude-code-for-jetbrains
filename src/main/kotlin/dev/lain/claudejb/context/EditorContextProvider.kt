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

    /**
     * Reads an image off the system clipboard, or null. Tries AWT first; on Linux, where AWT's
     * `imageFlavor` is unreliable under Wayland (and often empty), falls back to the `wl-paste`
     * (Wayland) / `xclip` (X11) CLIs if present. This is what makes Ctrl+V image paste and the
     * composer's "Paste image" actually work on Wayland.
     */
    fun imageFromClipboard(): Attachment.Image? = awtClipboardImage() ?: linuxClipboardImage()

    /** True if the system clipboard currently holds plain text (so a paste is a text paste, not an image). */
    fun clipboardHasText(): Boolean = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
    }.getOrDefault(false)

    /** Plain-text contents of the system clipboard, or null. AWT's stringFlavor is reliable even on Wayland. */
    fun clipboardText(): String? = runCatching {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        if (!cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null
        (cb.getData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun awtClipboardImage(): Attachment.Image? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        val rendered = image.toRenderedImage() ?: return null
        val base64 = pngBase64(rendered) ?: return null
        Attachment.Image(displayName = "clipboard.png", mediaType = "image/png", base64 = base64)
    }.getOrNull()

    /** Wayland/X11 clipboard image via external CLIs (no-op off Linux, or when none are installed). */
    private fun linuxClipboardImage(): Attachment.Image? {
        if (!isLinux()) return null
        val wlPaste = findExecutable("wl-paste")   // Wayland
        val xclip = findExecutable("xclip")        // X11
        // Wayland: ask wl-paste which types it has, pick an image/* one, then fetch it.
        if (wlPaste != null) {
            val wlType = pickImageType(listOf(wlPaste, "--list-types"))
            if (wlType != null) {
                val bytes = runProcessBytes(listOf(wlPaste, "-t", wlType))
                val img = if (bytes != null) imageOf(bytes, wlType) else null
                if (img != null) return img
            }
        }
        // X11: same dance via xclip TARGETS.
        if (xclip != null) {
            val xType = pickImageType(listOf(xclip, "-selection", "clipboard", "-t", "TARGETS", "-o"))
            if (xType != null) {
                val bytes = runProcessBytes(listOf(xclip, "-selection", "clipboard", "-t", xType, "-o"))
                val img = if (bytes != null) imageOf(bytes, xType) else null
                if (img != null) return img
            }
        }
        // Image FILE copied from a file manager (Nautilus/Dolphin) → the clipboard holds a
        // text/uri-list of file:// paths, not raw image bytes. Resolve & read the file.
        if (wlPaste != null) imageFromUriList(listOf(wlPaste, "-t", "text/uri-list"))?.let { return it }
        if (xclip != null) imageFromUriList(listOf(xclip, "-selection", "clipboard", "-t", "text/uri-list", "-o"))?.let { return it }
        return null
    }

    private fun isLinux() = System.getProperty("os.name").orEmpty().lowercase().contains("linux")
    private fun isMac() = System.getProperty("os.name").orEmpty().lowercase().let { it.contains("mac") || it.contains("darwin") }

    /**
     * Locate an executable by name, searching PATH plus common bin dirs (the IDE, launched from
     * Toolbox/a desktop entry, often has a trimmed PATH that misses /usr/bin). Returns an absolute
     * path or null. On Windows also tries the `.exe` suffix.
     */
    fun findExecutable(name: String): String? {
        val candidates = LinkedHashSet<String>()
        System.getenv("PATH")?.split(File.pathSeparatorChar)?.forEach { if (it.isNotBlank()) candidates.add(it) }
        candidates.addAll(COMMON_BIN_DIRS)
        val names = if (isWindows()) listOf("$name.exe", name) else listOf(name)
        for (dir in candidates) for (n in names) {
            val f = File(dir, n)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return null
    }

    private fun isWindows() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Common executable dirs to search beyond a possibly-trimmed PATH (the IDE inherits a desktop/Toolbox env). */
    private val COMMON_BIN_DIRS: List<String> by lazy {
        val home = System.getProperty("user.home").orEmpty()
        listOf(
            "/usr/bin", "/bin", "/usr/local/bin", "/usr/sbin", "/sbin",
            "/run/current-system/sw/bin",        // NixOS
            "/var/lib/flatpak/exports/bin",
            "/snap/bin",
            "/opt/homebrew/bin", "/usr/local/sbin", // macOS Homebrew
            "$home/.local/bin", "$home/bin",
        )
    }

    /**
     * Help text for getting clipboard-image paste working, or null when it should already work
     * (Windows/macOS read images via AWT; or a Linux CLI is already installed — then the real issue
     * is just "no image in the clipboard"). On Linux without a tool, returns a distro-aware install hint.
     */
    fun clipboardImageHelp(): String? {
        if (!isLinux()) return null // Windows & macOS read images straight from AWT
        if (findExecutable("wl-paste") != null || findExecutable("xclip") != null) return null
        return "image paste needs 'wl-clipboard' (Wayland) or 'xclip' (X11): " + linuxInstallHint()
    }

    /** A best-effort `install` command for the detected distro family (from /etc/os-release). */
    private fun linuxInstallHint(): String {
        val rel = runCatching { File("/etc/os-release").readText().lowercase() }.getOrDefault("")
        return when {
            listOf("fedora", "rhel", "centos", "rocky", "alma").any { it in rel } -> "sudo dnf install wl-clipboard"
            listOf("debian", "ubuntu", "mint", "pop").any { it in rel } -> "sudo apt install wl-clipboard"
            "arch" in rel || "manjaro" in rel -> "sudo pacman -S wl-clipboard"
            "opensuse" in rel || "suse" in rel -> "sudo zypper install wl-clipboard"
            else -> "install 'wl-clipboard' (or 'xclip') with your package manager"
        }
    }

    /** Resolve a clipboard `text/uri-list` to the first readable image file, or null. */
    private fun imageFromUriList(cmd: List<String>): Attachment.Image? {
        val out = runProcessBytes(cmd)?.toString(Charsets.UTF_8) ?: return null
        for (line in out.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue
            val path = uriToPath(s) ?: continue
            imageFromFile(path)?.let { return it }
        }
        return null
    }

    /** "file:///a%20b.png" or "/a/b.png" → filesystem path; null for non-file/unparseable URIs. */
    private fun uriToPath(uri: String): String? = runCatching {
        when {
            uri.startsWith("file://") -> File(java.net.URI(uri)).path
            uri.startsWith("/") -> uri
            else -> null
        }
    }.getOrNull()

    /** From a tool's type listing, choose the best image MIME (png, then jpeg, then any image type), or null. */
    private fun pickImageType(listCmd: List<String>): String? {
        val out = runProcessBytes(listCmd)?.toString(Charsets.UTF_8) ?: return null
        val types = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return types.firstOrNull { it == "image/png" }
            ?: types.firstOrNull { it == "image/jpeg" || it == "image/jpg" }
            ?: types.firstOrNull { it.startsWith("image/") }
    }

    private fun imageOf(bytes: ByteArray, type: String): Attachment.Image? {
        if (bytes.size < 8) return null
        val mt = if (type == "image/jpg") "image/jpeg" else type
        val ext = mt.substringAfter('/').substringBefore('+').ifBlank { "png" }
        return Attachment.Image("clipboard.$ext", mt, Base64.getEncoder().encodeToString(bytes))
    }

    /** Run [cmd], returning its stdout bytes, or null on failure/timeout/non-zero exit/missing binary. */
    private fun runProcessBytes(cmd: List<String>): ByteArray? = runCatching {
        // Discard stderr (don't merge it into stdout: that would corrupt image bytes, and leaving it
        // unread can fill the pipe and hang the process). We only consume stdout.
        val proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = proc.inputStream.readBytes()
        if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) { proc.destroyForcibly(); return null }
        if (proc.exitValue() != 0) return null
        bytes.takeIf { it.isNotEmpty() }
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
