package dev.lain.claudejb.model.permission.paths

import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuardPaths83Test : GuardProbe(
    SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = """C:\Users\me""",
        currentUser = "me",
        projectRoot = """C:\proj""",
        caseInsensitivePaths = true,
    ),
) {

    @Test
    fun `a short-name segment names a long form nobody here can expand, so it is never inside the project`() {
        assertEquals(Verdict.DENY, v(read("C:/proj/SRC~1/A.kt")))
        assertEquals(SecurityRule.OUTSIDE_PROJECT, rule(read("C:/proj/SRC~1/A.kt")))
    }

    @Test
    fun `climbing out of a short-name segment does not land inside the project either`() {
        assertEquals(Verdict.DENY, v(read("""C:\PROGRA~1\..\proj\A.kt""")))
        assertEquals(SecurityRule.OUTSIDE_PROJECT, rule(read("""C:\PROGRA~1\..\proj\A.kt""")))
    }

    @Test
    fun `a short-name spelling of another profile is still that profile`() {
        assertEquals(Verdict.DENY, v(read("""C:\Users\ADMINI~1\.ssh\id_rsa""")))
        assertEquals(SecurityRule.OTHER_USER_HOME, rule(read("""C:\Users\ADMINI~1\.ssh\id_rsa""")))
    }

    @Test
    fun `an ordinary project file is unaffected`() {
        assertEquals(Verdict.ALLOW, v(read("C:/proj/src/A.kt")))
    }
}
