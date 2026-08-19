package dev.lain.claudejb.context

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal object ClipboardCli {

    private val log = thisLogger()

    fun isLinux() = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    private fun isWindows() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

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

    private val COMMON_BIN_DIRS: List<String> by lazy {
        val home = System.getProperty("user.home").orEmpty()
        listOf(
            "/usr/bin", "/bin", "/usr/local/bin", "/usr/sbin", "/sbin",
            "/run/current-system/sw/bin",
            "/var/lib/flatpak/exports/bin",
            "/snap/bin",
            "/opt/homebrew/bin", "/usr/local/sbin",
            "$home/.local/bin", "$home/bin",
        )
    }

    fun image(): Attachment.Image? {
        if (!isLinux()) return null
        val wlPaste = findExecutable("wl-paste")
        val xclip = findExecutable("xclip")
        if (wlPaste != null) {
            val wlType = pickImageType(listOf(wlPaste, "--list-types"))
            if (wlType != null) {
                val bytes = runProcessBytes(listOf(wlPaste, "-t", wlType))
                val img = if (bytes != null) ImageAttachments.imageOf(bytes, wlType) else null
                if (img != null) return img
            }
        }
        if (xclip != null) {
            val xType = pickImageType(listOf(xclip, "-selection", "clipboard", "-t", "TARGETS", "-o"))
            if (xType != null) {
                val bytes = runProcessBytes(listOf(xclip, "-selection", "clipboard", "-t", xType, "-o"))
                val img = if (bytes != null) ImageAttachments.imageOf(bytes, xType) else null
                if (img != null) return img
            }
        }
        if (wlPaste != null) imageFromUriList(listOf(wlPaste, "-t", "text/uri-list"))?.let { return it }
        if (xclip != null) imageFromUriList(listOf(xclip, "-selection", "clipboard", "-t", "text/uri-list", "-o"))?.let { return it }
        return null
    }

    fun text(): String? {
        if (!isLinux()) return null
        findExecutable("wl-paste")?.let { wlPaste ->
            preferredTextType(listTypes(listOf(wlPaste, "--list-types")))?.let { type ->
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

    private fun listTypes(listCmd: List<String>): List<String> =
        runProcessBytes(listCmd)?.toString(Charsets.UTF_8)
            ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList()
            ?: emptyList()

    private fun pickImageType(listCmd: List<String>): String? {
        val types = listTypes(listCmd)
        return types.firstOrNull { it == "image/png" }
            ?: types.firstOrNull { it == "image/jpeg" || it == "image/jpg" }
            ?: types.firstOrNull { it.startsWith("image/") }
    }

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

    private fun uriToPath(uri: String): String? = runCatching {
        when {
            uri.startsWith("file://") -> File(java.net.URI(uri)).path
            uri.startsWith("/") -> uri
            else -> null
        }
    }.getOrNull()

    private fun runProcessBytes(cmd: List<String>): ByteArray? = runCatching {
        val proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val reader = CompletableFuture.supplyAsync {
            runCatching { proc.inputStream.readBytes() }.getOrNull()
        }
        val bytes = try {
            reader.get(3, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
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
