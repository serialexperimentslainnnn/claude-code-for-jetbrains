package dev.lain.claudejb.process

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **This plugin deletes exactly one file: `~/.claude/.credentials.json`, when it harvests it into the safe.
 * Nothing else. No exceptions, no "temporary" directory, no cleanup path.**
 *
 * THE INCIDENT THIS PINS (5.0.0, `ce49635`). The auth work built a per-session `CLAUDE_CONFIG_DIR` so the
 * binary could read its own credentials file without that file living in `~/.claude`, and — so the relocation
 * would not hide the user's real configuration — symlinked every entry of `~/.claude` into it. The teardown
 * then wiped the temp dir with one line:
 *
 * ```kotlin
 * runCatching { dir.deleteRecursively() }   // RuntimeConfigDir.collect
 * ```
 *
 * `File.deleteRecursively()` walks with `FileTreeWalk`, and `FileTreeWalk` FOLLOWS symlinks to directories
 * (`isDirectory` is true, `listFiles()` returns their contents). So every `stop()` — a logout, a closed tab,
 * a closed IDE — descended through those links and emptied the real directories: `projects` (every past
 * conversation, in every project), `skills`, `sessions`, `cache`, `ide`, and the user's own `backup-*` dirs.
 * It destroyed a real user's history, and there was no undo because there was no copy.
 *
 * Both halves were individually defensible and the composition was a `rm -rf` of someone's data. That is
 * precisely the kind of defect a review does not catch and a type system cannot see, so the rule is not
 * "delete carefully" — it is a source contract with no judgement in it:
 *
 *  1. **No recursive deletion anywhere**, including in the allowed file. There is not one legitimate use in
 *     this codebase, which is what makes the ban absolute and therefore checkable.
 *  2. **No deletion API outside [CredentialsVault]**, which deletes the one plaintext credential it just
 *     moved into the IDE safe — the whole reason that class exists.
 *
 * If a future change genuinely needs to remove something, it does not edit the allowlist: it explains itself
 * to the user first, because the last time this was decided unilaterally it cost them their conversations.
 */
class NoFileDeletionContractTest {

    /** Recursive-delete APIs. Banned outright — target file included. */
    private val recursive = listOf(
        "deleteRecursively",
        "FileUtil.delete(",
        "FileUtils.deleteDirectory",
        "FileUtils.forceDelete",
        "walkFileTree",
    )

    /** Single-file deletion APIs. Allowed ONLY in [ALLOWED]. */
    private val single = listOf(
        ".delete()",
        "deleteIfExists",
        "Files.delete(",
        "deleteOnExit",
    )

    /**
     * The files permitted to delete, and the one thing each is permitted to delete.
     *
     * `CredentialsVault` removes the plaintext credentials file it has just harvested into the IDE's
     * PasswordSafe — that removal IS the feature.
     *
     * `LegacyProjectSettings` removes the plugin's OWN leftovers in the project's `.idea/` once their
     * contents have been migrated to `~/.claude` (5.5.0), which the user asked for explicitly: a migration
     * that leaves the old file behind means the next reader has two sources and no way to tell which is
     * current. It deletes a file the plugin itself wrote, never a conversation and never anything the user
     * authored.
     *
     * `SettingsStore` removes `~/.claude/ide/claude-code-native/settings.json` after adopting it into the
     * PasswordSafe — the same shape as the vault's: harvest first, delete second, and only ever the file the
     * plugin wrote itself. The settings moved into the OS credential store because the env block belongs to
     * them and an env block holds secrets; leaving the plaintext copy behind would defeat the move.
     */
    private companion object {
        val ALLOWED = setOf(
            "CredentialsVault.kt",
            "LegacyProjectSettings.kt",
            "LegacySessionHistory.kt",
            "SettingsStore.kt",
        )
    }

    @Test
    fun `no source file deletes recursively`() {
        val offenders = ktFiles().flatMap { file ->
            hits(file, recursive).map { "${file.name}:${it.first}: ${it.second}" }
        }
        assertTrue(offenders.isEmpty()) {
            "Recursive deletion is banned in this codebase — it emptied a user's whole ~/.claude once, " +
                "through symlinks (see this test's KDoc). Remove it; do not \"fix\" it.\n" +
                offenders.joinToString("\n")
        }
    }

    @Test
    fun `only CredentialsVault deletes a file`() {
        val offenders = ktFiles().filterNot { it.name in ALLOWED }.flatMap { file ->
            hits(file, single).map { "${file.name}:${it.first}: ${it.second}" }
        }
        assertTrue(offenders.isEmpty()) {
            "Only ${ALLOWED.joinToString()} may delete a file, each for the one purpose documented there. " +
                "Everything else on the user's disk — conversations above all — is theirs.\n" +
                offenders.joinToString("\n")
        }
    }

    @Test
    fun `the one permitted deletion targets the credentials file and nothing else`() {
        val vault = ktFiles().first { it.name == "CredentialsVault.kt" }
        // Every deleting line in the vault must act on a `file` resolved from credentialsFile(). Pinned by
        // reading the receiver rather than trusting the filename: the allowlist is per-FILE, so without this
        // the vault would be a hole big enough to delete anything from.
        val bad = hits(vault, single).filterNot { (_, line) -> Regex("""\bfile\.delete\(\)""").containsMatchIn(line) }
        assertTrue(bad.isEmpty()) {
            "$ALLOWED may only delete the harvested credentials file (`file.delete()`, where `file` is " +
                "credentialsFile()).\n" + bad.joinToString("\n") { "${vault.name}:${it.first}: ${it.second}" }
        }
        assertTrue(vault.readText().contains("fun credentialsFile()")) {
            "$ALLOWED no longer resolves credentialsFile() — this contract is checking the wrong thing."
        }
    }

    /**
     * Matching lines as (1-based line number, trimmed text), skipping comments: these APIs are NAMED in the
     * KDoc that explains why they are banned, and a contract that fails on its own explanation is a contract
     * people delete.
     */
    private fun hits(file: File, needles: List<String>): List<Pair<Int, String>> =
        file.readLines().mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) return@mapIndexedNotNull null
            if (needles.any { it in line }) index + 1 to line else null
        }

    private fun ktFiles(): List<File> =
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Resolves `src/main/kotlin` whether the test runs from the module dir or the repo root. */
    private fun sourceRoot(): File =
        sequenceOf(File("src/main/kotlin"), File("../src/main/kotlin"))
            .firstOrNull { it.isDirectory }
            ?: error("could not locate src/main/kotlin from ${File("").absolutePath}")
}
