package dev.lain.claudejb.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * Everything the "Claude Code was not found" boot card needs: the install commands the host can run for
 * the user, and the validation that a user-supplied path really is the `claude` binary.
 *
 * The plugin still never downloads or bundles the binary (the architecture decision stands): installing
 * runs the OFFICIAL installer in the IDE's own terminal, where the user watches every line of it. This
 * object only knows which commands exist and how to check the result.
 */
object BinaryInstall {

    /**
     * One way of installing Claude Code on this OS.
     *
     * @param display the exact command, shown next to the button — corporate networks block installers
     *   (a proxy that strips `curl | bash`, a winget source policy), so the user must be able to READ what
     *   a button will run, copy it, and take it to whatever route their machine allows.
     * @param argv what actually runs, as argv — the first element is the shell/tool itself, so nothing
     *   here is ever concatenated into a shell string by us.
     * @param shell what the copy hint names ("or copy this command to bash / PowerShell / cmd") — the
     *   interpreter a user pasting [display] by hand needs to be in.
     */
    data class Method(val id: String, val label: String, val display: String, val argv: List<String>, val shell: String)

    /**
     * The install routes for the CURRENT platform, primary first. Several per OS on purpose: corporate
     * environments cut individual methods (blocked script CDNs, package-manager allowlists), so offering
     * one route is offering some users none.
     */
    fun methods(): List<Method> = when {
        SystemInfo.isWindows -> windowsMethods()
        SystemInfo.isMac -> listOf(officialScript(), brewMethod())
        else -> linuxMethods()
    }

    private fun windowsMethods() = listOf(
        Method(
            id = "ps1",
            label = "Install via PowerShell",
            display = "irm https://claude.ai/install.ps1 | iex",
            argv = listOf(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                "irm https://claude.ai/install.ps1 | iex",
            ),
            shell = "PowerShell",
        ),
        Method(
            id = "winget",
            label = "Install via winget",
            display = "winget install Anthropic.ClaudeCode",
            argv = listOf("winget", "install", "Anthropic.ClaudeCode"),
            shell = "PowerShell or cmd",
        ),
        Method(
            id = "cmd",
            label = "Install via cmd",
            display = "curl -fsSL https://claude.ai/install.cmd -o install.cmd && install.cmd && del install.cmd",
            // %TEMP% rather than the project directory: the transient script must not appear in the
            // user's working tree (or their VCS status) while it runs.
            argv = listOf(
                "cmd.exe",
                "/c",
                "curl -fsSL https://claude.ai/install.cmd -o \"%TEMP%\\claude-install.cmd\"" +
                    " && \"%TEMP%\\claude-install.cmd\" && del \"%TEMP%\\claude-install.cmd\"",
            ),
            shell = "cmd",
        ),
    )

    private fun officialScript() = Method(
        id = "sh",
        label = "Install via the official script",
        display = "curl -fsSL https://claude.ai/install.sh | bash",
        argv = listOf("/bin/bash", "-lc", "curl -fsSL https://claude.ai/install.sh | bash"),
        shell = "bash",
    )

    private fun brewMethod() = Method(
        id = "brew",
        label = "Install via Homebrew",
        display = "brew install --cask claude-code",
        // -l so the login profile runs: on Apple-silicon Macs brew lives in /opt/homebrew/bin,
        // which a GUI-launched IDE's environment does not have on PATH.
        argv = listOf("/bin/bash", "-lc", "brew install --cask claude-code"),
        shell = "bash",
    )

    // The distro's own package manager, when one is recognised: Anthropic publishes signed apt, dnf and
    // apk repositories, and on a machine that standardises on package-manager updates the script route may
    // be exactly what the environment blocks. The `sudo` stays INSIDE the command and the command runs in
    // the IDE terminal, where sudo prompts interactively like any shell — no elevation machinery on our
    // side, and the user watches every line.
    private fun linuxMethods() = buildList {
        add(officialScript())
        if (File("/etc/debian_version").isFile) add(aptMethod())
        if (File("/etc/fedora-release").isFile || File("/etc/redhat-release").isFile) add(dnfMethod())
        if (File("/etc/alpine-release").isFile) add(apkMethod())
    }

    // Commands mirror the official install docs verbatim (code.claude.com/docs/en/setup) — key, repo,
    // package — joined with && so a failed step stops the chain instead of half-configuring a repo.
    private fun aptMethod() = Method(
        id = "apt",
        label = "Install via apt",
        shell = "bash",
        display = "sudo install -d -m 0755 /etc/apt/keyrings && " +
            "sudo curl -fsSL https://downloads.claude.ai/keys/claude-code.asc -o /etc/apt/keyrings/claude-code.asc && " +
            "echo \"deb [signed-by=/etc/apt/keyrings/claude-code.asc] https://downloads.claude.ai/claude-code/apt/stable stable main\" | " +
            "sudo tee /etc/apt/sources.list.d/claude-code.list && sudo apt update && sudo apt install claude-code",
        argv = listOf(
            "/bin/bash",
            "-lc",
            buildString {
                append("sudo install -d -m 0755 /etc/apt/keyrings")
                append(" && sudo curl -fsSL https://downloads.claude.ai/keys/claude-code.asc")
                append(" -o /etc/apt/keyrings/claude-code.asc")
                append(" && echo \"deb [signed-by=/etc/apt/keyrings/claude-code.asc]")
                append(" https://downloads.claude.ai/claude-code/apt/stable stable main\"")
                append(" | sudo tee /etc/apt/sources.list.d/claude-code.list")
                append(" && sudo apt update && sudo apt install claude-code")
            },
        ),
    )

    private fun dnfMethod(): Method {
        // printf rather than a heredoc: this string is already inside `bash -lc "…"`, and a heredoc nested
        // in a quoted argv element is exactly the kind of quoting puzzle argv exists to avoid.
        val repo = "[claude-code]\\nname=Claude Code\\nbaseurl=https://downloads.claude.ai/claude-code/rpm/stable\\n" +
            "enabled=1\\ngpgcheck=1\\ngpgkey=https://downloads.claude.ai/keys/claude-code.asc"
        return Method(
            id = "dnf",
            label = "Install via dnf",
            shell = "bash",
            display = "sudo tee /etc/yum.repos.d/claude-code.repo  (repo config)  && sudo dnf install claude-code",
            argv = listOf(
                "/bin/bash",
                "-lc",
                "printf '$repo\\n' | sudo tee /etc/yum.repos.d/claude-code.repo && sudo dnf install claude-code",
            ),
        )
    }

    private fun apkMethod() = Method(
        id = "apk",
        label = "Install via apk",
        shell = "sh (as root)",
        // As documented: Alpine's docs assume a root shell (sudo is often absent, doas varies).
        display = "wget -O /etc/apk/keys/claude-code.rsa.pub https://downloads.claude.ai/keys/claude-code.rsa.pub && " +
            "echo \"https://downloads.claude.ai/claude-code/apk/stable\" >> /etc/apk/repositories && apk add claude-code",
        argv = listOf(
            "/bin/sh",
            "-lc",
            "wget -O /etc/apk/keys/claude-code.rsa.pub https://downloads.claude.ai/keys/claude-code.rsa.pub" +
                " && echo \"https://downloads.claude.ai/claude-code/apk/stable\" >> /etc/apk/repositories" +
                " && apk add claude-code",
        ),
    )

    fun method(id: String): Method? = methods().firstOrNull { it.id == id }

    /** Outcome of validating a user-supplied path. [Invalid.reason] is shown verbatim on the boot card. */
    sealed interface Validation {
        data class Ok(val binary: File, val version: String) : Validation
        data class Invalid(val reason: String) : Validation
    }

    /**
     * Checks that [rawPath] is the `claude` binary — not merely a file that exists.
     *
     * Accepts a directory too (the installer's bin dir), resolving the platform's executable names inside
     * it, because "where is Claude installed?" is the question users can actually answer. The identity
     * check runs `--version` and requires the output to name Claude Code: an existence check alone would
     * accept any executable, and the failure would then surface later as an opaque protocol error on a
     * process that was never `claude` at all.
     *
     * BLOCKING (runs a process, seconds on a cold start) — call from a pooled thread, never the EDT.
     */
    fun validate(rawPath: String): Validation {
        val trimmed = rawPath.trim().removeSurrounding("\"")
        if (trimmed.isBlank()) return Validation.Invalid("Enter a path first.")
        var file = File(trimmed)
        if (file.isDirectory) {
            file = candidatesIn(file).firstOrNull { it.isFile }
                ?: return Validation.Invalid("No claude executable inside that directory.")
        }
        if (!file.isFile) return Validation.Invalid("That file does not exist.")
        if (!file.canExecute() && !SystemInfo.isWindows) {
            return Validation.Invalid("That file is not executable (chmod +x?).")
        }
        return probeIdentity(file)
    }

    /** Runs `--version` on an existing executable and demands the answer name Claude Code. */
    private fun probeIdentity(file: File): Validation {
        // A Windows npm shim cannot be probed through cmd.exe (see ClaudeBinaryLocator.resolveNodeScript);
        // probe the underlying cli.js through node exactly the way ClaudeProcess launches it.
        val script = ClaudeBinaryLocator.resolveNodeScript(file)
        val argv = if (script != null) {
            listOf(ClaudeBinaryLocator.locateNode(near = file), script.absolutePath, "--version")
        } else {
            listOf(file.absolutePath, "--version")
        }

        val output = runCatching {
            val cmd = GeneralCommandLine(argv)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            CapturingProcessHandler(cmd).runProcess(VERSION_PROBE_TIMEOUT_MS)
        }.getOrElse { e ->
            return Validation.Invalid("Could not run it: ${e.message}")
        }

        if (output.isTimeout) return Validation.Invalid("It did not answer --version within 15s.")
        val text = (output.stdout + output.stderr).trim()
        // The real binary identifies itself, e.g. "2.1.222 (Claude Code)". Matching on the NAME rather
        // than on a version shape is deliberate: any executable can print digits.
        if (!text.contains("claude code", ignoreCase = true)) {
            val head = text.lineSequence().firstOrNull()?.take(ERROR_HEAD_CHARS).orEmpty().ifBlank { "no output" }
            return Validation.Invalid("That runs, but it isn't Claude Code ($head).")
        }
        return Validation.Ok(file, text.lineSequence().first().trim())
    }

    // The executable names come from [ClaudeBinaryLocator], not from a second copy of the list: this one had
    // already drifted apart from the locator's in spirit — that list documents WHY the order matters on
    // Windows (the extensionless npm shim is a bash script CreateProcess rejects with error 193), and a
    // validator accepting a name the launcher will not run is a path the user is told is good and is not.
    private fun candidatesIn(dir: File): List<File> =
        ClaudeBinaryLocator.executableNames.map { File(dir, it) }

    private const val VERSION_PROBE_TIMEOUT_MS = 15_000

    /** How much of a stranger executable's first output line the rejection message quotes. */
    private const val ERROR_HEAD_CHARS = 80
}
