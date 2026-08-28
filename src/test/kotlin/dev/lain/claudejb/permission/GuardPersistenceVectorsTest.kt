package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuardPersistenceVectorsTest {

    private val policy = SensitiveGuard.Policy(
        home = "/home/me",
        currentUser = "me",
        projectRoot = "/home/me/proj",
    )

    private fun v(cmd: String) = SensitiveGuard.evaluate(buildJsonObject { put("command", cmd) }, policy).verdict

    @Test
    fun `scheduling something to run later is a persistence mechanism, whatever schedules it`() {
        listOf(
            "systemd-run --user --on-calendar='*:0/5' /home/me/proj/x.sh",
            "systemctl --user enable myjob.service",
            "systemctl enable evil.socket",
            "systemctl start evil.path",
            "loginctl enable-linger me",
            "launchctl load ~/Library/LaunchAgents/x.plist",
            "launchctl bootstrap gui/1000 x.plist",
            "schtasks /create /sc minute /tn x /tr evil.exe",
            "Register-ScheduledTask -TaskName x -Action a",
            """reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v x /d evil.exe""",
            "cp payload ~/Library/LaunchAgents/x.plist",
            "cp payload /home/me/.config/autostart/x.desktop",
            "crontab evil.tab",
            "echo x > .githooks/pre-commit",
        ).forEach { assertEquals(Verdict.DENY, v(it), it) }
    }

    @Test
    fun `the obfuscation collapse and the chain anchor apply here too`() {
        listOf(
            "(systemd-run --on-calendar=hourly /x.sh)",
            "env schtasks /create /tn x /tr y",
            "systemd-ru\${X:-}n --on-calendar=hourly /x.sh",
        ).forEach { assertEquals(Verdict.DENY, v(it), it) }
    }

    @Test
    fun `reading or listing what is already scheduled is not scheduling something`() {
        listOf(
            "systemctl status nginx",
            "systemctl list-timers",
            "launchctl list",
            "git status",
            "ls /home/me/proj/deploy",
        ).forEach { assertEquals(Verdict.ALLOW, v(it), it) }
    }
}
