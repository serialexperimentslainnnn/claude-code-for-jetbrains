package dev.lain.claudejb.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Sources a user-provided script and captures the environment it produces, so the `claude` process can
 * inherit the same `PATH`/env the user gets in their own shell — the reliable fix for a GUI IDE launched
 * without the login environment (custom node/claude locations, nvm/fnm, etc.).
 *
 * Linux/macOS: `source <script>.sh` in the login shell, then dump `env`.
 * Windows: dot-source the PowerShell profile/script, then dump `Env:`.
 */
object EnvScriptLoader {

    private val log = thisLogger()
    private const val TIMEOUT_MS = 15_000

    fun load(scriptPath: String?): Map<String, String> {
        val path = scriptPath?.trim().orEmpty()
        if (path.isEmpty()) return emptyMap()
        val script = File(path)
        if (!script.isFile) {
            log.warn("Source script not found: $path")
            return emptyMap()
        }

        val cmd = if (SystemInfo.isWindows) {
            GeneralCommandLine(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                ". '${script.absolutePath.replace("'", "''")}'; " +
                    "Get-ChildItem Env: | ForEach-Object { \"\$(\$_.Name)=\$(\$_.Value)\" }",
            )
        } else {
            val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/bash"
            // Pass the script path as a positional argument ($1) instead of interpolating it into the
            // `-lc` command string, so a path containing `"`, `$(...)` or backticks cannot break the
            // quoting and inject shell code. With `sh -lc CMD ARG0 ARG1 ...`, ARG0 becomes `$0` and ARG1
            // becomes `$1`; we use a dummy `$0` (the shell name) and reference the path as `$1`.
            GeneralCommandLine(shell, "-lc", ". \"$1\" && env", shell, script.absolutePath)
        }
        cmd.charset = StandardCharsets.UTF_8
        cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        return runCatching {
            val output = CapturingProcessHandler(cmd).runProcess(TIMEOUT_MS)
            if (output.isTimeout || output.exitCode != 0) {
                log.warn("Source script '$path' exited ${output.exitCode} (timeout=${output.isTimeout}): ${output.stderr.take(200)}")
            }
            parse(output.stdout)
        }.getOrElse {
            log.warn("Failed to source script '$path'", it)
            emptyMap()
        }
    }

    /** Keeps only well-formed `KEY=VALUE` lines (KEY has no whitespace); ignores multi-line value spillover. */
    internal fun parse(dump: String): Map<String, String> =
        dump.lineSequence()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val key = line.substring(0, eq)
                if (key.any { it.isWhitespace() }) return@mapNotNull null
                key to line.substring(eq + 1)
            }
            .toMap()
}
