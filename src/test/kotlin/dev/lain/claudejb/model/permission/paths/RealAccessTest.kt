package dev.lain.claudejb.model.permission.paths

import dev.lain.claudejb.model.permission.GuardFixture
import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RealAccessTest : GuardProbe(GuardFixture.basePolicy().copy(guardedRoots = GuardFixture.GUARDED_ROOTS)) {

    private val onDisk = mapOf(
        "/opt/other" to PathPresence.DIRECTORY,
        "/opt/other/lib.so" to PathPresence.FILE,
        "/var/log" to PathPresence.DIRECTORY,
        "/var/log/dnf5.log" to PathPresence.FILE,
        "/home/me" to PathPresence.DIRECTORY,
        "/home/me/proj" to PathPresence.DIRECTORY,
    )

    private val probing = policy.copy(pathProbe = { path -> onDisk[path] ?: PathPresence.MISSING })

    @Test
    fun `a path that does not exist and is only read is a parameter, not a reach`() {
        listOf(
            "ls /loquesea",
            "cat /opt/missing/file.txt",
            "tool --output=/nothing/here",
            "grep -r pattern /var/log/absent.log",
        ).forEach { assertEquals(Verdict.ALLOW, v(bash(it), probing), it) }
    }

    @Test
    fun `an API endpoint is not a path, whether or not a file of that name exists`() {
        val userExists = policy.copy(pathProbe = { if (it == "/user") PathPresence.DIRECTORY else PathPresence.MISSING })
        listOf("gh api /user --jq '.login'", "gh api /repos/o/r/pulls --paginate", "gh api -X POST /repos/o/r/issues -f title=x")
            .forEach {
                assertEquals(Verdict.ALLOW, v(bash(it), probing), it)
                assertEquals(Verdict.ALLOW, v(bash(it), userExists), it)
                assertEquals(Verdict.ALLOW, v(bash(it)), it)
            }
        assertEquals(Verdict.ALLOW, v(read("/opt/missing/file.txt"), probing))
    }

    @Test
    fun `the same reads of a path that does exist stay refused`() {
        listOf("ls /opt/other", "cat /opt/other/lib.so", "tail /var/log/dnf5.log").forEach {
            assertEquals(Verdict.DENY, v(bash(it), probing), it)
            assertEquals(SecurityRule.OUTSIDE_PROJECT, rule(bash(it), probing), it)
        }
        assertEquals(Verdict.DENY, v(read("/opt/other/lib.so"), probing))
    }

    @Test
    fun `without a probe nothing changes, every verdict is what it was`() {
        listOf("ls /loquesea", "cat /opt/missing/file.txt", "ls /opt/other").forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
        }
        assertEquals(Verdict.DENY, v(read("/opt/missing/file.txt")))
    }

    @Test
    fun `writing to a path that does not exist is a write, and refused`() {
        listOf(
            "echo x > /srv/new/out.txt",
            "cp notes.txt /srv/new/notes.txt",
            "mkdir -p /opt/newdir",
            "touch /opt/missing/marker",
            "tee /opt/missing/log.txt",
        ).forEach { assertEquals(Verdict.DENY, v(bash(it), probing), it) }
        assertEquals(Verdict.DENY, v(edit("/opt/missing/file.txt", "a", "b"), probing))
        assertEquals(
            Verdict.DENY,
            v(
                buildJsonObject {
                    put("file_path", "/opt/missing/new.kt")
                    put("content", "x")
                },
                probing,
            ),
        )
    }

    @Test
    fun `a command that creates a path and then reads it is not rebated by the path being absent now`() {
        listOf(
            "mkdir -p /opt/x && cat /opt/x/y",
            "touch /srv/a; cat /srv/a",
            "cp src/App.kt /srv/App.kt && cat /srv/App.kt",
        ).forEach { assertEquals(Verdict.DENY, v(bash(it), probing), it) }
    }

    @Test
    fun `credentials, other homes and the temp dir never consult the disk`() {
        assertEquals(SecurityRule.CREDENTIALS, rule(bash("cat ~/.aws/credentials"), probing))
        assertEquals(SecurityRule.OTHER_USER_HOME, rule(bash("cat /home/other/notes.txt"), probing))
        assertEquals(SecurityRule.TEMP_DIR, rule(bash("cat /tmp/absent.txt"), probing))
        assertEquals(SecurityRule.NETWORK_MOUNT, rule(bash("ls /mnt/share/absent"), probing))
    }

    @Test
    fun `a container mount is judged by its host side only`() {
        listOf(
            "docker run -v /home/me/proj:/app node npm test",
            "docker run --mount type=bind,source=/home/me/proj,target=/app node npm test",
            "podman run -v /home/me/proj/data:/data:ro img",
            "kubectl cp mypod:/var/log/app.log ./app.log",
            "kubectl cp /home/me/proj/x default/mypod:/tmp/x",
            "oc cp ns/pod:/etc/config ./config",
            "docker run --rm -v /data alpine ls /data",
            "docker run -v mydata:/var/lib/data img",
            "docker run --mount type=volume,source=mydata,target=/data img",
        ).forEach { assertEquals(Verdict.ALLOW, v(bash(it), probing), it) }
    }

    @Test
    fun `a container mount whose host side leaves the project is refused, present or not`() {
        listOf(
            "docker run -v /opt/other:/app node npm test",
            "docker run -v /opt/missing:/app node npm test",
            "docker run --mount type=bind,source=/opt/other,target=/app img",
            "oc cp ns/pod:/etc/config /opt/other/config",
        ).forEach { assertEquals(Verdict.DENY, v(bash(it), probing), it) }
    }

    @Test
    fun `a container mount is not a path list, so the split needs the container verb`() {
        assertEquals(Verdict.DENY, v(bash("prog /opt/missing:/app"), probing))
        assertEquals(Verdict.DENY, v(bash("prog /home/me/proj:/app"), probing))
    }
}
