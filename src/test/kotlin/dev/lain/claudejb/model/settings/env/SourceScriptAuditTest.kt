package dev.lain.claudejb.model.settings.env

import dev.lain.claudejb.model.permission.SensitiveGuard
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SourceScriptAuditTest {

    @TempDir
    lateinit var tmp: Path

    private val policy = SensitiveGuard.Policy()

    @Test
    fun `no script, a missing script and an oversized script are not findings`() {
        assertNull(SourceScriptAudit.findingIn(null, policy))
        assertNull(SourceScriptAudit.findingIn("  ", policy))
        assertNull(SourceScriptAudit.findingIn(tmp.resolve("missing.sh").toString(), policy))
        val huge = tmp.resolve("huge.sh")
        Files.writeString(huge, "x".repeat(600 * 1024))
        assertNull(SourceScriptAudit.findingIn(huge.toString(), policy))
    }

    @Test
    fun `a harmless script passes and a destructive one is named`() {
        val fine = tmp.resolve("fine.sh")
        Files.writeString(fine, "export PATH=/usr/local/bin:\$PATH\n")
        assertNull(SourceScriptAudit.findingIn(fine.toString(), policy))
        val bad = tmp.resolve("bad.sh")
        Files.writeString(bad, "rm -rf " + "/" + "\n")
        assertNotNull(SourceScriptAudit.findingIn(bad.toString(), policy))
    }

    @Test
    fun `the refusals only log outside an application`() {
        SourceScriptAudit.untrusted("x.sh")
        SourceScriptAudit.refused("x.sh", "because")
    }
}
