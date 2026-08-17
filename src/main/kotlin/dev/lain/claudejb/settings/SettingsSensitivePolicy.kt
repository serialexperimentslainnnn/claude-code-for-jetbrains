package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.CredentialPaths
import dev.lain.claudejb.permission.SensitiveGuard
import dev.lain.claudejb.session.RemoteMounts
import kotlinx.serialization.json.JsonObject

// How the persisted settings become the deterministic tool-call lock's SensitiveGuard.Policy. The guard
// itself is pure and lives in `permission/`; this file is only the wiring that reads the settings document
// and this host's mounts. Read FRESH on every call — a security toggle takes effect on the next tool call,
// never at the next IDE restart.

/** The active sensitive-path globs: the built-in blacklist **plus** the user's extras (additive, never less). */
fun ClaudeSettings.sensitiveGlobs(): List<String> {
    val extra = state.sensitiveExtraGlobs.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
    return CredentialPaths.SENSITIVE_GLOBS + extra
}

/**
 * The guard's deterministic verdict for a tool call (see [SensitiveGuard]) for this [projectRoot]. Enforcement
 * is per-rule configurable (Settings ▸ Claude Code ▸ Security) but never off entirely: a disabled rule still
 * downgrades to a permission card rather than a silent allow. Foreign-territory and remote-mount inputs come
 * from [RemoteMounts].
 */
fun ClaudeSettings.sensitiveDecision(
    toolName: String,
    input: JsonObject,
    projectRoot: String?,
): SensitiveGuard.Decision = SensitiveGuard.evaluate(toolName, input, sensitivePolicy(projectRoot))

/** Assembles the pure [SensitiveGuard.Policy] from settings + this host's mounts + the open project. */
fun ClaudeSettings.sensitivePolicy(projectRoot: String?): SensitiveGuard.Policy {
    val snap = RemoteMounts.snapshot()
    return SensitiveGuard.Policy(
        globs = sensitiveGlobs(),
        home = System.getProperty("user.home"),
        currentUser = System.getProperty("user.name"),
        guardedRoots = snap.remoteRoots,
        blockForeignWslMounts = snap.isWsl,
        projectRoot = projectRoot,
        // Canonicalise on disk so a symlink or `..` cannot launder a path past the rules by hiding its target.
        // Off the EDT already (broker callback runs on the reader thread); a failure just leaves the literal.
        pathResolver = { raw -> runCatching { java.io.File(raw).canonicalPath }.getOrNull() },
        enforceCredentials = state.securityBlockCredentials,
        enforceDangerousCommands = state.securityBlockDangerousCommands,
        enforceTempDirs = state.securityBlockTempDirs,
        enforceForeignOtherUserHome = state.securityBlockForeignOtherUserHome,
        enforceForeignNetworkMounts = state.securityBlockForeignNetworkMounts,
        enforceForeignWslMounts = state.securityBlockForeignWslMounts,
    )
}
