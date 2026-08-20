package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PrivilegeEscalationFuzzTest {

    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = "/home/me",
        currentUser = "me",
        guardedRoots = emptyList(),
        wslHost = false,
        projectRoot = "/home/me/proj",
    )

    private val escalators = listOf(
        "sudo", "sudoedit", "doas", "pkexec", "runuser", "setpriv", "gksudo", "gksu", "kdesudo", "kdesu", "run0",
    )

    private val prefixes = listOf("", "/usr/bin/", "/bin/", "/usr/local/bin/", "./")

    private val leaders = listOf("", "echo hi; ", "echo hi && ", "true | ", "if true; then ", "for f in a; do ")

    private val tails = listOf("ls", "id", "apt update", "systemctl restart nginx", "-u root -- ls")

    private val commandKeys = listOf("command", "cmd", "script", "shell", "exec", "run", "cmdline")

    private fun payload(key: String, value: String): JsonObject = buildJsonObject { put(key, value) }

    private fun verdict(input: JsonObject) = SensitiveGuard.evaluate(input, policy).verdict

    @Test
    fun `every escalator, in every position, through every command key, is refused`() {
        val rng = Random(20260820L)
        repeat(600) {
            val command = leaders.random(rng) +
                prefixes.random(rng) + escalators.random(rng) + " " + tails.random(rng)
            val input = payload(commandKeys.random(rng), command)

            assertEquals(Verdict.DENY, verdict(input), command)
            assertEquals(Verdict.DENY, verdict(input), command)
        }
    }

    @Test
    fun `padding an escalator with whitespace does not hide it`() {
        val rng = Random(20260820L + 1)
        repeat(300) {
            val gap = " ".repeat(rng.nextInt(1, 6))
            val command = "echo hi;" + gap + escalators.random(rng) + gap + tails.random(rng)

            assertEquals(Verdict.DENY, verdict(payload("command", command)), command)
        }
    }

    @Test
    fun `su is matched as a command and never inside a longer word`() {
        listOf("su ls", "su - root", "echo hi; su", "/bin/su -").forEach {
            assertEquals(Verdict.DENY, verdict(payload("command", it)), it)
        }
        listOf("npm run superbuild", "git submodule update", "echo summary", "ls subdir").forEach {
            assertEquals(Verdict.ALLOW, verdict(payload("command", it)), it)
        }
    }

    @Test
    fun `an escalator only matches as a whole word, never as the start of a longer one`() {
        listOf("sudoku --help", "superuser --version", "runuserinfo x", "doasd status", "run0ver x").forEach {
            assertEquals(Verdict.ALLOW, verdict(payload("command", it)), it)
        }
        listOf("./sudo-wrapper.sh", "sudo.backup/run.sh", "/opt/sudoedit-helper/go.sh").forEach {
            val decision = SensitiveGuard.evaluate(payload("command", it), policy)
            assertEquals(false, decision.rule == SecurityRule.PRIVILEGE_ESCALATION, "$it -> ${decision.rule}")
        }
    }

    @Test
    fun `an escalator named but not at a command position is never a hit`() {
        val rng = Random(20260820L + 2)
        val mentions = listOf(
            "git commit -m 'document %s in the runbook'",
            "echo 'we no longer use %s here'",
            "grep -rn %s docs/",
            "rg --fixed-strings %s src/",
        )
        repeat(300) {
            val command = mentions.random(rng).format(escalators.random(rng))

            assertEquals(Verdict.ALLOW, verdict(payload("command", command)), command)
        }
    }

    @Test
    fun `no escalator reaches the rules through a payload that is not a command`() {
        val rng = Random(20260820L + 3)
        val quiet = listOf("file_path", "content", "old_string", "new_string", "pattern")
        repeat(300) {
            val input = buildJsonObject {
                put("file_path", "/home/me/proj/notes.md")
                put(quiet.random(rng), "run ${escalators.random(rng)} ${tails.random(rng)} to finish the install")
            }

            assertEquals(Verdict.ALLOW, verdict(input), input.toString())
        }
    }
}
