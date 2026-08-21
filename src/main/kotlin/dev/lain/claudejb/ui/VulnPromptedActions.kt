package dev.lain.claudejb.ui

import dev.lain.claudejb.vuln.VulnFinding

internal object VulnPromptedActions {

    fun updatePrompt(finding: VulnFinding): String? {
        val name = token(finding.component.name, NAME_ALLOWED) ?: return null
        val version = token(finding.component.version, VERSION_ALLOWED) ?: return null
        val manifest = path(finding.component.manifest) ?: return null
        val advisory = token(finding.id, ADVISORY_ALLOWED) ?: return null
        val fixed = finding.fixedVersions.mapNotNull { token(it, VERSION_ALLOWED) }.take(MAX_LISTED_VERSIONS)
        return command(name, version, manifest, advisory, fixed) + "\n\n" + prohibitions(name, manifest)
    }

    private fun command(
        name: String,
        version: String,
        manifest: String,
        advisory: String,
        fixed: List<String>,
    ): String {
        val target = if (fixed.isEmpty()) {
            "No patched version is published in the advisory, so find out whether one exists and tell me " +
                "what it is before you change anything."
        } else {
            "The advisory names these patched versions: " + fixed.joinToString(", ") { "`$it`" } +
                ". Pick the lowest one that is at or above `$version` and pin exactly that."
        }
        return "Update the dependency `$name` in `$manifest`. This project resolves it at version `$version`, " +
            "which advisory `$advisory` reports as affected.\n\n$target"
    }

    private fun prohibitions(name: String, manifest: String): String =
        "Change `$manifest` and the lockfile that belongs to it, and nothing else. Only the entry for " +
            "`$name`: do not upgrade, downgrade, add or remove any other dependency, and do not edit any " +
            "other manifest in this repository. Do not touch source files, build scripts or CI " +
            "configuration to make the new version fit. Do not commit, tag, push or publish anything. If " +
            "the update cannot be made without changing something outside `$manifest`, stop and tell me " +
            "what it would take instead of doing it. When you are done, tell me the exact version you " +
            "pinned and nothing about what the advisory says."

    private fun token(raw: String, allowed: Regex): String? =
        raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH && allowed.matches(it) }

    private fun path(raw: String): String? =
        raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_PATH_LENGTH && PATH_ALLOWED.matches(it) }

    private const val MAX_TOKEN_LENGTH = 200

    private const val MAX_PATH_LENGTH = 400

    private const val MAX_LISTED_VERSIONS = 8

    private val NAME_ALLOWED = Regex("""[A-Za-z0-9._@/+-]+""")

    private val VERSION_ALLOWED = Regex("""[A-Za-z0-9._+-]+""")

    private val ADVISORY_ALLOWED = Regex("""[A-Za-z0-9._-]+""")

    private val PATH_ALLOWED = Regex("""[A-Za-z0-9._/ -]+""")
}
