package dev.lain.claudejb.model.permission.paths

import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlternateDataStreamTest : GuardProbe(
    SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = """C:\Users\me""",
        currentUser = "me",
        projectRoot = """C:\proj""",
        caseInsensitivePaths = true,
    ),
) {

    @Test
    fun `an explicit data stream on a project file is a write no diff shows`() {
        val cmd = bash("type C:/proj/a.txt:secret:\$DATA")
        assertEquals(Verdict.DENY, v(cmd))
        assertEquals(SecurityRule.SHELL_FILE_WRITE, rule(cmd))
    }

    @Test
    fun `the stream signature is caught inside a file path too`() {
        assertEquals(Verdict.DENY, v(read("C:/proj/a.txt:secret:\$DATA")))
        assertEquals(SecurityRule.SHELL_FILE_WRITE, rule(read("C:/proj/a.txt:secret:\$DATA")))
    }

    @Test
    fun `a host and port, a container target and a label are not streams`() {
        assertEquals(Verdict.ALLOW, v(read("C:/proj/a.txt")))
        assertEquals(Verdict.ALLOW, v(bash("docker cp a.txt web:/srv/a.txt")))
        assertEquals(Verdict.ALLOW, v(bash("kubectl get pods -l app:web")))
        assertEquals(Verdict.ALLOW, v(bash("git -c http.proxy=http://proxy:8080 status")))
    }
}
