package dev.lain.claudejb.process

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * Locates the preinstalled native `claude` binary. The plugin never downloads or bundles it
 * (per the project's architecture decision): if it is missing we surface an actionable notification
 * and fail clean.
 */
object ClaudeBinaryLocator {

    private val home: String get() = System.getProperty("user.home").orEmpty()

    /**
     * Executable names to try, in priority order. On Windows we must NOT pick the extensionless npm
     * shim (`%APPDATA%\npm\claude`) — it is a bash script and `CreateProcess` rejects it with
     * "%1 is not a valid Win32 application" (error 193). Prefer the native `.exe`, then the `.cmd`.
     */
    private val executableNames: List<String>
        get() = if (SystemInfo.isWindows) listOf("claude.exe", "claude.cmd", "claude.bat")
        else listOf("claude")

    private val typicalDirs: List<String>
        get() = if (SystemInfo.isWindows) listOfNotNull(
            "$home\\.local\\bin",
            System.getenv("APPDATA")?.let { "$it\\npm" },
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\claude" },
            "$home\\scoop\\shims",
            "$home\\.volta\\bin",
            System.getenv("ChocolateyInstall")?.let { "$it\\bin" },
        )
        else listOf(
            "$home/.local/bin",
            "$home/.claude/local",
            "/usr/local/bin",
            "/opt/homebrew/bin",
            "/usr/bin",
        )

    /**
     * Returns the executable, or null if it cannot be found. An explicit [override] (from Settings) wins
     * over all auto-detection — the only catch-all for non-standard installs (custom dirs, version managers,
     * a GUI IDE that doesn't inherit the user's PATH).
     */
    fun locate(override: String? = null): File? {
        override?.takeIf { it.isNotBlank() }?.let { path ->
            // A configured path wins — but if it has gone stale (binary moved/updated), fall through to
            // auto-detection rather than failing hard.
            File(path).takeIf { it.isFile && it.canExecute() }?.let { return it }
        }
        for (name in executableNames) {
            PathEnvironmentVariableUtil.findInPath(name)?.let { if (it.canExecute()) return it }
        }
        for (dir in typicalDirs) {
            for (name in executableNames) {
                val candidate = File(dir, name)
                if (candidate.isFile && candidate.canExecute()) return candidate
            }
        }
        return null
    }

    /**
     * If [binary] is a Windows npm `.cmd`/`.bat` shim, returns the underlying `cli.js` so the caller can
     * launch `node cli.js …` directly, bypassing cmd.exe. Running the shim through cmd.exe corrupts the
     * streaming stdio contract: the batch layer mangles argument quoting and turns our stdin EOF (graceful
     * shutdown) into a blocking "Terminate batch job (Y/N)?" prompt. Returns null when no bypass is needed.
     */
    fun resolveNodeScript(binary: File): File? {
        if (!SystemInfo.isWindows) return null
        val name = binary.name.lowercase()
        if (!name.endsWith(".cmd") && !name.endsWith(".bat")) return null
        val dir = binary.parentFile ?: return null
        // npm global layout: <prefix>\claude.cmd  with  <prefix>\node_modules\@anthropic-ai\claude-code\cli.js
        File(dir, "node_modules\\@anthropic-ai\\claude-code\\cli.js").takeIf { it.isFile }?.let { return it }
        // Fallback: extract the .js entrypoint the shim invokes (paths are %~dp0-relative).
        return runCatching {
            Regex("%~dp0[\\\\/]?([^\"\\s]+\\.js)").find(binary.readText())
                ?.groupValues?.get(1)
                ?.let { File(dir, it) }
                ?.takeIf { it.isFile }
        }.getOrNull()
    }

    /**
     * Resolves a launchable `node` for driving an npm shim. A GUI IDE launched from a shortcut may not
     * inherit the user's PATH, so we look near the shim and in the standard install dir before falling
     * back to the bare name `node` (resolved at spawn time from the console parent environment).
     */
    fun locateNode(near: File?, override: String? = null): String {
        override?.takeIf { it.isNotBlank() }?.let { path ->
            File(path).takeIf { it.isFile && it.canExecute() }?.let { return it.absolutePath }
        }
        if (!SystemInfo.isWindows) return "node"
        near?.let { File(it.parentFile, "node.exe") }?.takeIf { it.isFile }?.let { return it.absolutePath }
        PathEnvironmentVariableUtil.findInPath("node.exe")?.let { if (it.canExecute()) return it.absolutePath }
        System.getenv("ProgramFiles")?.let { File("$it\\nodejs\\node.exe") }?.takeIf { it.isFile }
            ?.let { return it.absolutePath }
        return "node"
    }
}
