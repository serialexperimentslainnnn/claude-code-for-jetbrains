package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class InhibitRecoveryTest {

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
    fun `destroying backups and recovery is refused`() {
        listOf(
            "wbadmin delete catalog -quiet",
            "wbadmin delete systemstatebackup -keepVersions:0",
            "bcdedit /set {default} recoveryenabled no",
            "bcdedit /set {default} bootstatuspolicy ignoreallfailures",
            "bcdedit /deletevalue {default} safeboot",
            "vssadmin resize shadowstorage /for=c: /on=c: /maxsize=401MB",
            "Get-WmiObject Win32_Shadowcopy | ForEach-Object { \$_.Delete() }",
            "net stop VSS",
            "sc config VSS start= disabled",
            "Set-Service -Name VSS -StartupType Disabled",
            "Disable-ComputerRestore -Drive C:",
            "reg add HKLM\\Software\\Policies\\SystemRestore /v DisableSR /t REG_DWORD /d 1",
            "tmutil disable",
            "tmutil deletelocalsnapshots /",
            "diskutil apfs deleteSnapshot / -uuid ABC",
            "reagentc /disable",
            "vim-cmd vmsvc/snapshot.removeall 12",
        ).forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
            assertEquals(SecurityRule.INHIBIT_RECOVERY, rule(bash(it)), it)
        }
    }

    @Test
    fun `deleting the shadow copies is blocked by the destructive family`() {
        listOf(
            "vssadmin delete shadows /all /quiet",
            "wmic shadowcopy delete /nointeractive",
        ).forEach { assertEquals(Verdict.DENY, v(bash(it)), it) }
    }

    @Test
    fun `ordinary backup and system inspection is not touched`() {
        listOf(
            "wbadmin get status",
            "vssadmin list shadows",
            "vssadmin list shadowstorage",
            "bcdedit /enum",
            "net start VSS",
            "sc query VSS",
            "tmutil startbackup",
            "tmutil listbackups",
            "tmutil listlocalsnapshots /",
            "diskutil apfs list",
        ).forEach { assertNotEquals(SecurityRule.INHIBIT_RECOVERY, rule(bash(it)), it) }
    }
}
