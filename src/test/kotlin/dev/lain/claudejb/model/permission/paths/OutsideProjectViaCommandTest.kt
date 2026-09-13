package dev.lain.claudejb.model.permission.paths

import dev.lain.claudejb.model.permission.GuardFixture
import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OutsideProjectViaCommandTest :
    GuardProbe(GuardFixture.basePolicy().copy(guardedRoots = GuardFixture.GUARDED_ROOTS, wslHost = false)) {

    @Test
    fun `a shell command reaching outside the project is refused, like the file tools already were`() {
        listOf(
            "cat ~/text.txt",
            "tail /var/log/dnf5.log",
            "ls /opt/other",
            "cp /srv/shared/notes.txt .",
        ).forEach { assertEquals(Verdict.DENY, v(bash(it)), it) }
    }

    @Test
    fun `the same reach through a file tool and through a command reach the same verdict`() {
        val throughTool = read("/var/log/dnf5.log")

        assertEquals(v(throughTool), v(bash("cat /var/log/dnf5.log")))
        assertEquals(rule(throughTool), rule(bash("cat /var/log/dnf5.log")))
    }

    @Test
    fun `work inside the project is untouched`() {
        listOf(
            "cat src/App.kt",
            "./gradlew test",
            "git status",
            "npm test",
            "cat /home/me/proj/README.md",
        ).forEach { assertEquals(Verdict.ALLOW, v(bash(it)), it) }
    }

    @Test
    fun `a system binary and an inert device are not reaches`() {
        listOf(
            "/usr/bin/git status",
            "/bin/ls src",
            "./gradlew test 2>/dev/null",
            "prog >/dev/null 2>&1",
        ).forEach { assertEquals(Verdict.ALLOW, v(bash(it)), it) }
    }

    @Test
    fun `declaring a path in a variable is not reaching it`() {
        assertEquals(Verdict.ALLOW, v(bash("JAVA_HOME=~/.jdks/jbr-21 ./gradlew check")))
        assertEquals(Verdict.ALLOW, v(bash("OUT=/home/me/other-build ./gradlew assemble")))
    }

    @Test
    fun `expanding that variable in the same command is reaching it`() {
        assertEquals(Verdict.DENY, v(bash("OUT=/home/me/other-build; cat \$OUT/log.txt")))
        assertEquals(Verdict.DENY, v(bash("X=/var/log; tail \$X/dnf5.log")))
    }

    @Test
    fun `an assignment that redirects which code runs is never an innocent declaration`() {
        listOf(
            "PATH=/home/me/evil:\$PATH git status",
            "export LD_PRELOAD=/home/me/evil.so; ls src",
            "BASH_ENV=/home/me/evil.sh bash -c 'ls'",
            "GIT_SSH_COMMAND=/home/me/evil.sh git fetch",
        ).forEach { assertEquals(Verdict.DENY, v(bash(it)), it) }
    }
}
