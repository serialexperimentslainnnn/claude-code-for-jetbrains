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
            "The advisory publishes no patched version, so establish whether one exists before planning " +
                "anything."
        } else {
            "The advisory names these patched versions: " + fixed.joinToString(", ") { "`$it`" } +
                ". The lowest one at or above `$version` is the candidate, unless what you find below " +
                "argues for a different one."
        }
        return "Move the dependency `$name` off version `$version` in `$manifest`, which advisory " +
            "`$advisory` reports as affected.\n\n$target"
    }

    private fun prohibitions(name: String, manifest: String): String =
        "Work out what the change costs before you make it. What changed in `$name` between the two " +
            "versions, breaking changes included; and what would have to be adjusted for the new version " +
            "to hold.\n\n" +
            "Read this project's own code to answer that, do not infer it from the manifest: find every " +
            "place `$name` is imported or called, which of those call sites touch what the new version " +
            "changed, and whether it arrives directly or through another dependency that pins it. A " +
            "package nothing calls costs nothing to move; one threaded through the code may cost a great " +
            "deal, and the manifest cannot tell them apart.\n\n" +
            "Look the release side up on the web rather than recalling it: releases, advisories and " +
            "deprecations move, and what you remember about this package may predate the version you are " +
            "moving to. Say what you found, cite where you found it, and what you propose, then carry it " +
            "out.\n\n" +
            "`$manifest` and its lockfile are the target. Anything you touch beyond them is part of making " +
            "the new version work, so name it and say why. If the update cannot be made safely at all, say " +
            "that instead of forcing it. Do not commit, tag, push or publish anything, and run whatever " +
            "tests this project has before you call it done."

    fun planPrompt(findings: List<VulnFinding>): String? {
        val lines = findings.mapNotNull(::line).distinct()
        if (lines.isEmpty()) return null
        val listed = lines.take(MAX_LISTED_FINDINGS)
        val omitted = lines.size - listed.size
        val tail = if (omitted > 0) "\n\nThere are $omitted more the view did not fit; ask for them if the " +
            "plan needs them." else ""
        return "These dependencies of this project are reported as affected:\n\n" +
            listed.joinToString("\n") { "- $it" } + tail + "\n\n" + planInstructions()
    }

    private fun line(finding: VulnFinding): String? {
        val name = token(finding.component.name, NAME_ALLOWED) ?: return null
        val version = token(finding.component.version, VERSION_ALLOWED) ?: return null
        val manifest = path(finding.component.manifest) ?: return null
        val advisory = token(finding.id, ADVISORY_ALLOWED) ?: return null
        val fixed = finding.fixedVersions.mapNotNull { token(it, VERSION_ALLOWED) }.take(MAX_LISTED_VERSIONS)
        val patched = if (fixed.isEmpty()) "no patched version published" else "patched in " +
            fixed.joinToString(", ") { "`$it`" }
        return "`$name` `$version` in `$manifest` — `$advisory`, $patched"
    }

    private fun planInstructions(): String =
        "Plan how to clear all of them, and do not start by editing anything.\n\n" +
            "Check every one against current information on the web instead of recalling it. Release notes, " +
            "advisories, patched versions and deprecations all move, and a plan built on what you remember " +
            "will be wrong in exactly the places that cost the most. Cite what you relied on.\n\n" +
            "Read this project's own code before you rank anything, rather than reasoning from the " +
            "manifests alone. For each package find where it is imported or called, and which of those " +
            "call sites touch what the new version changes: that is what separates an update nobody will " +
            "notice from one that rewrites a module, and no lockfile carries it. Note also which of these " +
            "packages arrive only through another dependency, since those are moved by updating their " +
            "parent and not by pinning them.\n\n" +
            "Work out then what each move actually costs: what changed between the version in use and the " +
            "candidate, breaking changes included; which of these updates pull the same transitive " +
            "dependency and could settle on one version instead of fighting each other; and which ones " +
            "need a source, build or CI change to hold, which is a cost to state rather than a step to " +
            "hide.\n\n" +
            "Then give me the order you would do them in and why, calling out any that are risky enough to " +
            "be worth doing alone, and any that cannot be done at all yet. Once I have agreed to the plan, " +
            "carry it out, running whatever tests this project has as you go. Do not commit, tag, push or " +
            "publish anything."

    private fun token(raw: String, allowed: Regex): String? =
        raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH && allowed.matches(it) }

    private fun path(raw: String): String? =
        raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_PATH_LENGTH && PATH_ALLOWED.matches(it) }

    private const val MAX_TOKEN_LENGTH = 200

    private const val MAX_PATH_LENGTH = 400

    private const val MAX_LISTED_VERSIONS = 8

    private const val MAX_LISTED_FINDINGS = 40

    private val NAME_ALLOWED = Regex("""[A-Za-z0-9._@/+-]+""")

    private val VERSION_ALLOWED = Regex("""[A-Za-z0-9._+-]+""")

    private val ADVISORY_ALLOWED = Regex("""[A-Za-z0-9._-]+""")

    private val PATH_ALLOWED = Regex("""[A-Za-z0-9._/ -]+""")
}
