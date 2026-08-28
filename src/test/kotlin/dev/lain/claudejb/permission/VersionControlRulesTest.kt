package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class VersionControlRulesTest {

    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = "/home/me",
        currentUser = "me",
        projectRoot = "/home/me/proj",
    )

    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }

    private fun v(input: JsonObject) = SensitiveGuard.evaluate(input, policy).verdict

    private fun rule(input: JsonObject) = SensitiveGuard.evaluate(input, policy).rule

    @Test
    fun `switching off a version-control safeguard is refused`() {
        listOf(
            "git add -f build/out.bin",
            "git stage --force dist/bundle.js",
            "git commit --no-verify -m wip",
            "git push --no-verify origin main",
            "git merge --no-verify feature",
            "git commit -n -m wip",
            "git commit --no-gpg-sign -m x",
            "git tag --no-gpg-sign v1",
            "git -c commit.gpgsign=false commit -m x",
            "git -c commit.gpgsign=no commit -m x",
            "git -c tag.gpgsign=off tag v1",
            "git -c gpg.program=echo commit -m x",
            "git -c core.hooksPath=/dev/null commit -m x",
            "GIT_CONFIG_KEY_0=core.hooksPath GIT_CONFIG_VALUE_0=nohooks git commit -m x",
            "SKIP=flake8 git commit -m x",
            "PRE_COMMIT_ALLOW_NO_CONFIG=1 git commit -m x",
            "HUSKY=0 git commit -m x",
        ).forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
            assertEquals(SecurityRule.VCS_PROTECTION_BYPASS, rule(bash(it)), it)
        }
    }

    @Test
    fun `the uppercase message flag and a later line do not trip the force rule`() {
        listOf(
            "git add README.md\ngit commit -F commitmsg.txt",
            "git commit -F -",
            "git push -n origin main",
            "git add .",
            "git add -A",
            "git commit -a -m x",
            "git commit -m x",
            "git merge feature",
            "git -c commit.gpgsign=true commit -m x",
            "git -c user.name=me commit -m x",
        ).forEach { assertNotEquals(SecurityRule.VCS_PROTECTION_BYPASS, rule(bash(it)), it) }
    }
}
