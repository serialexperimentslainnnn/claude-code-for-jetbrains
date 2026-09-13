package dev.lain.claudejb.model.permission

import dev.lain.claudejb.model.permission.GuardClassifier.Hit
import dev.lain.claudejb.model.permission.SensitiveGuard.Policy
import dev.lain.claudejb.model.permission.rules.AntiForensics
import dev.lain.claudejb.model.permission.rules.CodeExecution
import dev.lain.claudejb.model.permission.rules.CommandRules
import dev.lain.claudejb.model.permission.rules.ContainerEscape
import dev.lain.claudejb.model.permission.rules.DangerousDomains
import dev.lain.claudejb.model.permission.rules.DestructiveCommands
import dev.lain.claudejb.model.permission.rules.DisableDefences
import dev.lain.claudejb.model.permission.rules.InhibitRecovery
import dev.lain.claudejb.model.permission.rules.IntrusionTechniques
import dev.lain.claudejb.model.permission.rules.PrivilegeEscalation
import dev.lain.claudejb.model.permission.rules.ProxyRules
import dev.lain.claudejb.model.permission.rules.ResourceHijacking
import dev.lain.claudejb.model.permission.rules.Tunneling
import dev.lain.claudejb.model.permission.rules.VersionControlRules
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import kotlinx.serialization.json.JsonObject

internal object GuardCommandFamilies {

    internal fun hit(input: JsonObject, policy: Policy): Hit? =
        coreCommandFamilies(input, policy) ?: defenceCommandFamilies(input, policy)

    private fun coreCommandFamilies(input: JsonObject, policy: Policy): Hit? {
        val families: List<() -> Hit?> = listOf(
            {
                DangerousDomains.blockedHit(ToolInputScanner.urlCandidates(input), policy.extraBlockedDomains)
                    ?.let { Hit(SecurityRule.BLOCKED_DOMAIN, "talks to a known staging or exfiltration service: $it") }
            },
            {
                CommandRules.dangerousCommand(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.SECRET_DUMPING_COMMANDS, "runs a command that can expose secrets: $it") }
            },
            {
                IntrusionTechniques.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(it.rule, "runs a recognised intrusion technique: ${it.text}") }
            },
            {
                VersionControlRules.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(it.rule, "switches off a version-control safeguard: ${it.text}") }
            },
            {
                DestructiveCommands.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(it.rule, "runs an irreversible destructive operation: ${it.text}") }
            },
            {
                CodeExecution.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(it.rule, "makes this machine run code from elsewhere: ${it.text}") }
            },
        )
        return families.firstNotNullOfOrNull { it() }
    }

    private fun defenceCommandFamilies(input: JsonObject, policy: Policy): Hit? {
        val families: List<() -> Hit?> = listOf(
            {
                AntiForensics.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.ANTI_FORENSIC, "erases the record of what it did: $it") }
            },
            {
                ResourceHijacking.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.RESOURCE_HIJACKING, "runs a cryptocurrency miner: $it") }
            },
            {
                InhibitRecovery.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.INHIBIT_RECOVERY, "destroys the means to recover the system: $it") }
            },
            {
                ContainerEscape.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.CONTAINER_ESCAPE, "breaks a container out onto the host: $it") }
            },
            {
                Tunneling.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.TUNNELING, "opens a network tunnel or anonymiser: $it") }
            },
            {
                DisableDefences.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.DISABLE_DEFENCES, "turns off a security defence: $it") }
            },
            {
                PrivilegeEscalation.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.PRIVILEGE_ESCALATION, "runs with elevated privileges: $it") }
            },
            {
                ProxyRules.proxyHit(input, policy)
                    ?.let { Hit(SecurityRule.PROXY_BYPASS, "routes around the proxy you declared: $it") }
            },
        )
        return families.firstNotNullOfOrNull { it() }
    }
}
