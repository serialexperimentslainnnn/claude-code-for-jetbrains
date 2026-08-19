package dev.lain.claudejb.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.SystemInfo
import java.io.File

object BinaryInstall {

    data class Method(val id: String, val label: String, val display: String, val argv: List<String>, val shell: String)

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
        argv = listOf("/bin/bash", "-lc", "brew install --cask claude-code"),
        shell = "bash",
    )

    private fun linuxMethods() = buildList {
        add(officialScript())
        if (File("/etc/debian_version").isFile) add(aptMethod())
        if (File("/etc/fedora-release").isFile || File("/etc/redhat-release").isFile) add(dnfMethod())
        if (File("/etc/alpine-release").isFile) add(apkMethod())
    }

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

    sealed interface Validation {
        data class Ok(val binary: File, val version: String) : Validation
        data class Invalid(val reason: String) : Validation
    }

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

    private fun probeIdentity(file: File): Validation {
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
        if (!text.contains("claude code", ignoreCase = true)) {
            val head = text.lineSequence().firstOrNull()?.take(ERROR_HEAD_CHARS).orEmpty().ifBlank { "no output" }
            return Validation.Invalid("That runs, but it isn't Claude Code ($head).")
        }
        return Validation.Ok(file, text.lineSequence().first().trim())
    }

    private fun candidatesIn(dir: File): List<File> =
        ClaudeBinaryLocator.executableNames.map { File(dir, it) }

    private const val VERSION_PROBE_TIMEOUT_MS = 15_000

    private const val ERROR_HEAD_CHARS = 80
}
