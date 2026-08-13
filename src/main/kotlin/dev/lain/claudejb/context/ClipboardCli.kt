package dev.lain.claudejb.context

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The external-clipboard subsystem: `wl-paste` (Wayland) / `xclip` (X11) — locating the tools, running them
 * under a deadline, and reading the image/text/uri-list targets they advertise.
 *
 * It exists because AWT's clipboard is unreliable on Linux: under the **native Wayland toolkit**
 * (`sun.awt.wl.WLToolkit`) both `imageFlavor` and `stringFlavor` come back empty, so [EditorContextProvider]
 * tries AWT first and falls back here. Everything is best-effort and IDE-free: a missing tool, a non-zero exit
 * or a clipboard owner that never closes the pipe yields null, never an exception and never a blocked caller.
 */
internal object ClipboardCli {

    private val log = thisLogger()

    fun isLinux() = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    private fun isWindows() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

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
        for (dir in candidates) {
            for (n in names) {
                val f = File(dir, n)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
        }
        return null
    }

    /** Common executable dirs to search beyond a possibly-trimmed PATH (the IDE inherits a desktop/Toolbox env). */
    private val COMMON_BIN_DIRS: List<String> by lazy {
        val home = System.getProperty("user.home").orEmpty()
        listOf(
            "/usr/bin", "/bin", "/usr/local/bin", "/usr/sbin", "/sbin",
            "/run/current-system/sw/bin", // NixOS
            "/var/lib/flatpak/exports/bin",
            "/snap/bin",
            "/opt/homebrew/bin", "/usr/local/sbin", // macOS Homebrew
            "$home/.local/bin", "$home/bin",
        )
    }

    /** Wayland/X11 clipboard image via external CLIs (no-op off Linux, or when none are installed). */
    fun image(): Attachment.Image? {
        if (!isLinux()) return null
        val wlPaste = findExecutable("wl-paste") // Wayland
        val xclip = findExecutable("xclip") // X11
        // Wayland: ask wl-paste which types it has, pick an image/* one, then fetch it.
        if (wlPaste != null) {
            val wlType = pickImageType(listOf(wlPaste, "--list-types"))
            if (wlType != null) {
                val bytes = runProcessBytes(listOf(wlPaste, "-t", wlType))
                val img = if (bytes != null) ImageAttachments.imageOf(bytes, wlType) else null
                if (img != null) return img
            }
        }
        // X11: same dance via xclip TARGETS.
        if (xclip != null) {
            val xType = pickImageType(listOf(xclip, "-selection", "clipboard", "-t", "TARGETS", "-o"))
            if (xType != null) {
                val bytes = runProcessBytes(listOf(xclip, "-selection", "clipboard", "-t", xType, "-o"))
                val img = if (bytes != null) ImageAttachments.imageOf(bytes, xType) else null
                if (img != null) return img
            }
        }
        // Image FILE copied from a file manager (Nautilus/Dolphin) → the clipboard holds a
        // text/uri-list of file:// paths, not raw image bytes. Resolve & read the file.
        if (wlPaste != null) imageFromUriList(listOf(wlPaste, "-t", "text/uri-list"))?.let { return it }
        if (xclip != null) imageFromUriList(listOf(xclip, "-selection", "clipboard", "-t", "text/uri-list", "-o"))?.let { return it }
        return null
    }

    /**
     * Wayland/X11 clipboard **text** via external CLIs (no-op off Linux, or when none are installed) — the
     * fallback for the native Wayland toolkit, where AWT's stringFlavor is empty. Only a genuine `text/…`
     * target is read (via [preferredTextType]), so an image-only clipboard never leaks raw bytes here.
     */
    fun text(): String? {
        if (!isLinux()) return null
        findExecutable("wl-paste")?.let { wlPaste ->
            preferredTextType(listTypes(listOf(wlPaste, "--list-types")))?.let { type ->
                // -n: don't append the trailing newline wl-paste adds by default.
                runProcessBytes(listOf(wlPaste, "-t", type, "-n"))?.toString(Charsets.UTF_8)
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        findExecutable("xclip")?.let { xclip ->
            preferredTextType(listTypes(listOf(xclip, "-selection", "clipboard", "-t", "TARGETS", "-o")))?.let { type ->
                runProcessBytes(listOf(xclip, "-selection", "clipboard", "-t", type, "-o"))?.toString(Charsets.UTF_8)
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    /** The clipboard's preferred text target name, or null when no `text/…` is offered (cheap presence check). */
    fun textType(): String? {
        if (!isLinux()) return null
        findExecutable("wl-paste")?.let { wlPaste ->
            preferredTextType(listTypes(listOf(wlPaste, "--list-types")))?.let { return it }
        }
        findExecutable("xclip")?.let { xclip ->
            preferredTextType(listTypes(listOf(xclip, "-selection", "clipboard", "-t", "TARGETS", "-o")))?.let { return it }
        }
        return null
    }

    /**
     * Choose the best text target from a tool's advertised types/TARGETS, or null when none is textual.
     * Pure (no I/O) so the image-vs-text guard is unit-testable. Prefers a UTF-8 plain-text target; accepts
     * X11 atom names (`UTF8_STRING`/`STRING`/`TEXT`) from xclip TARGETS; and **excludes `text/uri-list`**
     * (copied file paths, handled as an image elsewhere) and `text/html` (markup, not the plain paste).
     */
    fun preferredTextType(types: List<String>): String? {
        fun first(p: (String) -> Boolean) = types.firstOrNull(p)
        return first { it.equals("text/plain;charset=utf-8", ignoreCase = true) }
            ?: first { it == "UTF8_STRING" }
            ?: first { it.equals("text/plain", ignoreCase = true) }
            ?: first { it == "STRING" || it == "TEXT" }
            ?: first {
                it.startsWith("text/", ignoreCase = true) &&
                    !it.equals("text/uri-list", ignoreCase = true) &&
                    !it.startsWith("text/html", ignoreCase = true)
            }
    }

    /** A best-effort `install` command for the detected distro family (from /etc/os-release). */
    fun installHint(): String {
        val rel = runCatching { File("/etc/os-release").readText().lowercase() }.getOrDefault("")
        return when {
            listOf("fedora", "rhel", "centos", "rocky", "alma").any { it in rel } -> "sudo dnf install wl-clipboard"
            listOf("debian", "ubuntu", "mint", "pop").any { it in rel } -> "sudo apt install wl-clipboard"
            "arch" in rel || "manjaro" in rel -> "sudo pacman -S wl-clipboard"
            "opensuse" in rel || "suse" in rel -> "sudo zypper install wl-clipboard"
            else -> "install 'wl-clipboard' (or 'xclip') with your package manager"
        }
    }

    /** Run a type-listing command and split its stdout into trimmed non-empty lines (empty on failure). */
    private fun listTypes(listCmd: List<String>): List<String> =
        runProcessBytes(listCmd)?.toString(Charsets.UTF_8)
            ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList()
            ?: emptyList()

    /** From a tool's type listing, choose the best image MIME (png, then jpeg, then any image type), or null. */
    private fun pickImageType(listCmd: List<String>): String? {
        val types = listTypes(listCmd)
        return types.firstOrNull { it == "image/png" }
            ?: types.firstOrNull { it == "image/jpeg" || it == "image/jpg" }
            ?: types.firstOrNull { it.startsWith("image/") }
    }

    /** Resolve a clipboard `text/uri-list` to the first readable image file, or null. */
    private fun imageFromUriList(cmd: List<String>): Attachment.Image? {
        val out = runProcessBytes(cmd)?.toString(Charsets.UTF_8) ?: return null
        for (line in out.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue
            val path = uriToPath(s) ?: continue
            ImageAttachments.imageFromFile(path)?.let { return it }
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

    /** Run [cmd], returning its stdout bytes, or null on failure/timeout/non-zero exit/missing binary. */
    private fun runProcessBytes(cmd: List<String>): ByteArray? = runCatching {
        // Discard stderr (don't merge it into stdout: that would corrupt image bytes, and leaving it
        // unread can fill the pipe and hang the process). We only consume stdout.
        val proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        // Read stdout on a separate thread with a deadline. `readBytes()` is unbounded and blocks until EOF, so a
        // clipboard owner that never closes the pipe would otherwise hang the caller forever (the EDT, before the
        // paste handlers were moved off-EDT). On timeout, kill the process and give up.
        val reader = CompletableFuture.supplyAsync {
            runCatching { proc.inputStream.readBytes() }.getOrNull()
        }
        val bytes = try {
            reader.get(3, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            // A timeout here is an expected outcome, not an error: a clipboard owner that never closes the pipe
            // is exactly what the deadline exists for. Logged at debug all the same — when someone reports
            // "paste does nothing on Wayland", this line is the difference between a diagnosis and a guess.
            log.debug("Clipboard helper ${cmd.firstOrNull()} timed out after 3s; killing it", e)
            proc.destroyForcibly()
            reader.cancel(true)
            return@runCatching null
        }
        if (!proc.waitFor(1, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return@runCatching null
        }
        if (proc.exitValue() != 0) return@runCatching null
        bytes?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
