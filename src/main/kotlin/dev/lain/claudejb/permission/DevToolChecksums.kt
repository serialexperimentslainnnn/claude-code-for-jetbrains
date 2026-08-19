package dev.lain.claudejb.permission

import dev.lain.claudejb.settings.SecretStore

/**
 * **The verified half of [DevToolScripts]: vendor-published checksums, held in the IDE's PasswordSafe.**
 *
 * ### Why a checksum at all
 * [DevToolScripts] exempts a build wrapper from the script analyser by NAME, and a name is chosen by whoever
 * creates the file. That exposure is bounded (creating the file is itself a wall) but it is real, and the honest
 * answer to "is this really `gradlew`?" is not a nicer list — it is the publisher's own hash.
 *
 * ### Why the safe rather than a file
 * The database is an **integrity baseline**, so its whole value is that nothing but this plugin wrote it. Sitting
 * in the repository, or anywhere under the workspace, it would be editable by exactly the actor it defends
 * against — one `Write`, one shell redirect — and an attacker who can add a line certifies whatever they planted
 * alongside it. In the safe (OS keychain / KWallet / DPAPI / encrypted file) poisoning an entry costs the
 * keychain, not a file write. Same reasoning that put the settings document there.
 *
 * ### Empty means seed, never means allow
 * A first run — or a user who cleared the safe — finds nothing, and the answer is to **build it from the bundled
 * baseline** (`resources/guard/tool-checksums.txt`, shipped inside the signed plugin jar) rather than to treat an
 * empty database as "everything verifies". [verified] answers false for an unknown hash at all times: an empty
 * or unseedable database makes every tool UNVERIFIED, which is the direction that fails closed.
 *
 * ### What it does not do
 * It performs **no network I/O and no hashing** — `SensitiveGuard` is pure and runs on the thread that reads the
 * binary's entire stdout, where a download or a disk read of an arbitrary-sized jar has no business. Fetching a
 * newly published sum, and computing a file's digest, both belong outside the verdict path; this object only ever
 * answers from what is already in the safe.
 */
object DevToolChecksums {

    /** Where the baseline ships: inside the plugin jar, so it is covered by whatever signed the plugin. */
    private const val BASELINE_RESOURCE = "/guard/tool-checksums.txt"

    /** A lower-case hex SHA-256 and nothing else — the shape an entry's first column must have to be stored. */
    private val SHA256 = Regex("""^[0-9a-f]{64}$""")

    /**
     * The database as it is persisted: the file format verbatim, comments and all.
     *
     * Stored as text rather than as a parsed structure so the thing in the safe stays the thing a human can read,
     * diff and check by hand with `sha256sum -c` — an integrity baseline nobody can inspect is one nobody
     * audits.
     */
    private fun stored(): String? = SecretStore.get(SecretStore.TOOL_CHECKSUMS)

    /** The baseline shipped in the jar, or null when the resource is missing (a broken build, not a normal state). */
    internal fun baseline(): String? =
        DevToolChecksums::class.java.getResourceAsStream(BASELINE_RESOURCE)?.bufferedReader()?.use { it.readText() }

    /**
     * The database, seeding the safe from the bundled baseline when it holds nothing.
     *
     * The seed is written with [SecretStore.setVerified] — a `PasswordSafe.set` can fail silently when the OS
     * store rejects it, and a seed that reports success while keeping nothing would make every later lookup
     * answer "unverified" for a reason no one could see. A failed write is not an error here, only a database
     * that stays in memory for this session: the answers are identical either way, and nothing is deleted.
     */
    private fun database(): String {
        stored()?.takeIf { it.isNotBlank() }?.let { return it }
        val seed = baseline() ?: return ""
        SecretStore.setVerified(SecretStore.TOOL_CHECKSUMS, seed)
        return seed
    }

    /** Every checksum the database holds for [artifact], by exact file name. */
    internal fun checksumsFor(artifact: String): Set<String> = entries()
        .filter { it.second.equals(artifact, ignoreCase = true) }
        .map { it.first }
        .toSet()

    /**
     * Does [sha256] identify [artifact] as something its vendor published?
     *
     * **False for an unknown hash, always** — including when the database is empty or could not be seeded. The
     * caller's contract is that an unverified tool is simply not exempt, so a missing database costs analysis,
     * never a free pass.
     */
    fun verified(artifact: String, sha256: String): Boolean {
        val digest = sha256.trim().lowercase()
        if (!SHA256.matches(digest)) return false
        return digest in checksumsFor(artifact)
    }

    /**
     * Adds [sha256] for [artifact], keeping the file format and the note of where it came from.
     *
     * **[source] must be the vendor's own URL**, and it is recorded rather than validated: this object cannot
     * know what a vendor's domain is, so the check belongs to whoever fetched the value. What this refuses on its
     * own are the two things it CAN judge — a first column that is not a SHA-256, and a duplicate.
     *
     * Returns false when the safe did not keep the write, so a caller never reports having recorded something the
     * next session will not find.
     */
    fun record(artifact: String, sha256: String, version: String, source: String): Boolean {
        val digest = sha256.trim().lowercase()
        if (!SHA256.matches(digest) || artifact.isBlank()) return false
        if (digest in checksumsFor(artifact)) return true
        val line = "$digest  $artifact  $version"
        val updated = database().trimEnd() + "\n# added from $source\n" + line + "\n"
        return SecretStore.setVerified(SecretStore.TOOL_CHECKSUMS, updated)
    }

    /** `<sha256>  <artifact>  <version>` per line; `#` comments and blanks ignored. */
    private fun entries(): List<Pair<String, String>> = database().lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split(Regex("""\s+"""))
            if (parts.size < 2) return@mapNotNull null
            val digest = parts[0].lowercase()
            if (SHA256.matches(digest)) digest to parts[1] else null
        }
        .toList()
}
