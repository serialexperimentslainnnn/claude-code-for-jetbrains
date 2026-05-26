package dev.lain.claudejb.process

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import java.io.File

/**
 * Locates the preinstalled native `claude` binary. The plugin never downloads or bundles it
 * (per the project's architecture decision): if it is missing we surface an actionable notification
 * and fail clean.
 */
object ClaudeBinaryLocator {

    private val home: String get() = System.getProperty("user.home").orEmpty()

    private val typicalPaths: List<String>
        get() = listOf(
            "$home/.local/bin/claude",
            "$home/.claude/local/claude",
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
            "/usr/bin/claude",
        )

    /** Returns the executable, or null if it cannot be found on PATH or in a typical location. */
    fun locate(): File? {
        PathEnvironmentVariableUtil.findInPath("claude")?.let { if (it.canExecute()) return it }
        return typicalPaths.map(::File).firstOrNull { it.isFile && it.canExecute() }
    }
}
