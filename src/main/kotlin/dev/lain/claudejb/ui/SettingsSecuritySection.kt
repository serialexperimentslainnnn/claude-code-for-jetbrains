package dev.lain.claudejb.ui

import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import dev.lain.claudejb.settings.ClaudeSettings

/**
 * The deterministic tool-call lock's six per-rule switches (see `permission/SensitiveGuard.kt`). Each is ON
 * by default, and OFF downgrades an automatic block to a permission card — never to a silent allow, which is
 * why the note spells it out on the page itself.
 */
internal class SettingsSecuritySection : SettingsSection {

    private val blockCredentialsCheck = JBCheckBox("Block credentials & key material (SSH/GPG keys, cloud/DB secrets, access tokens…)")
    private val blockDangerousCommandsCheck = JBCheckBox("Block dangerous commands (credential dumps, exfiltration, offensive tooling)")
    private val blockTempDirsCheck = JBCheckBox("Block actions on the system temporary directory (/tmp, /var/tmp, %TEMP%…)")
    private val blockForeignOtherUserHomeCheck = JBCheckBox("Block access to other users' home directories")
    private val blockForeignNetworkMountsCheck = JBCheckBox("Block access to network / UNC / removable mounts")
    private val blockForeignWslMountsCheck = JBCheckBox("Block access to foreign WSL drives (any /mnt/* other than C:)")

    override fun addTo(form: FormBuilder): FormBuilder = form
        .addSeparator()
        .addComponent(sectionLabel("Security — deterministic tool-call lock, evaluated before every permission"))
        .addComponent(blockCredentialsCheck)
        .addComponent(blockDangerousCommandsCheck)
        .addComponent(blockTempDirsCheck)
        .addComponent(blockForeignOtherUserHomeCheck)
        .addComponent(blockForeignNetworkMountsCheck)
        .addComponent(blockForeignWslMountsCheck)
        .addComponent(securityWarningLabel())

    override fun reset(s: ClaudeSettings.State) {
        blockCredentialsCheck.isSelected = s.securityBlockCredentials
        blockDangerousCommandsCheck.isSelected = s.securityBlockDangerousCommands
        blockTempDirsCheck.isSelected = s.securityBlockTempDirs
        blockForeignOtherUserHomeCheck.isSelected = s.securityBlockForeignOtherUserHome
        blockForeignNetworkMountsCheck.isSelected = s.securityBlockForeignNetworkMounts
        blockForeignWslMountsCheck.isSelected = s.securityBlockForeignWslMounts
    }

    override fun apply(s: ClaudeSettings.State) {
        s.securityBlockCredentials = blockCredentialsCheck.isSelected
        s.securityBlockDangerousCommands = blockDangerousCommandsCheck.isSelected
        s.securityBlockTempDirs = blockTempDirsCheck.isSelected
        s.securityBlockForeignOtherUserHome = blockForeignOtherUserHomeCheck.isSelected
        s.securityBlockForeignNetworkMounts = blockForeignNetworkMountsCheck.isSelected
        s.securityBlockForeignWslMounts = blockForeignWslMountsCheck.isSelected
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = listOf(
        blockCredentialsCheck.isSelected != s.securityBlockCredentials,
        blockDangerousCommandsCheck.isSelected != s.securityBlockDangerousCommands,
        blockTempDirsCheck.isSelected != s.securityBlockTempDirs,
        blockForeignOtherUserHomeCheck.isSelected != s.securityBlockForeignOtherUserHome,
        blockForeignNetworkMountsCheck.isSelected != s.securityBlockForeignNetworkMounts,
        blockForeignWslMountsCheck.isSelected != s.securityBlockForeignWslMounts,
    )

    private fun securityWarningLabel() = noteLabel(
        "⚠ <b>Security:</b> all six are <b>ON by default</b> and reproduce the plugin's original lock exactly. " +
            "Turning one OFF never allows a matching call silently — it only downgrades an automatic block to a " +
            "<b>permission card</b>, shown every time, for every caller (including MCP servers and Skills), so you " +
            "still decide case by case. Only disable a rule you understand and specifically need — a project on a " +
            "corporate network share, for example, needs the network-mount rule off, not the whole lock. " +
            "The <b>open project is exempt</b> from the location rules, the temporary directory included: they are " +
            "about what happens <i>outside</i> the surface you are looking at.",
    )
}
