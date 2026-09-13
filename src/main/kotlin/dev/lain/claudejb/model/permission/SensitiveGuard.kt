package dev.lain.claudejb.model.permission

import dev.lain.claudejb.model.permission.paths.CredentialPaths
import dev.lain.claudejb.model.permission.paths.PathPresence
import dev.lain.claudejb.model.permission.rules.CommandRules
import dev.lain.claudejb.model.permission.scan.CommandTokenizer
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import dev.lain.claudejb.model.permission.vocab.SecurityCategory
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import dev.lain.claudejb.util.ReasonSecrecy
import kotlinx.serialization.json.JsonObject

object SensitiveGuard {

    enum class Verdict { ALLOW, ASK, DENY }

    data class Policy(
        val globs: List<String> = CredentialPaths.SENSITIVE_GLOBS,
        val home: String? = null,
        val currentUser: String? = null,
        val guardedRoots: List<String> = emptyList(),
        val wslHost: Boolean = false,
        val caseInsensitivePaths: Boolean = false,
        val projectRoot: String? = null,
        val pathResolver: ((String) -> String?)? = null,
        val pathProbe: ((String) -> PathPresence?)? = null,
        val envValues: Map<String, String> = emptyMap(),
        val fileReader: ((String) -> String?)? = null,
        val permissiveRules: Set<SecurityRule> = emptySet(),
        val httpProxy: String? = null,
        val httpsProxy: String? = null,
        val noProxyHosts: List<String> = emptyList(),
        val extraBlockedDomains: List<String> = emptyList(),
        val commandWhitelist: List<String> = emptyList(),
        val categoryWhitelist: Map<SecurityCategory, Set<String>> = emptyMap(),
        val ruleWhitelist: Map<SecurityRule, Set<String>> = emptyMap(),
    )

    private const val SETTINGS_PATH = "Settings ▸ Claude Code Security"

    data class Decision(
        val verdict: Verdict,
        val reason: String?,
        val rule: SecurityRule? = null,
        val detail: String? = null,
    )

    fun evaluate(input: JsonObject, policy: Policy): Decision = withoutSecrets(decide(input, policy), policy)

    private fun decide(input: JsonObject, policy: Policy): Decision {
        val hit = GuardClassifier.classify(input, policy) ?: return Decision(Verdict.ALLOW, null)
        liftedByWhitelist(input, hit, policy)?.let { list ->
            return Decision(Verdict.ALLOW, "${hit.text} — allowed by the $list", hit.rule, hit.text)
        }
        return Decision(verdictFor(hit, policy), reasonFor(hit, policy), hit.rule, hit.text)
    }

    private fun withoutSecrets(decision: Decision, policy: Policy): Decision =
        if (policy.envValues.isEmpty()) {
            decision
        } else {
            decision.copy(
                reason = ReasonSecrecy.redact(decision.reason, policy.envValues),
                detail = ReasonSecrecy.redact(decision.detail, policy.envValues),
            )
        }

    private fun verdictFor(hit: GuardClassifier.Hit, policy: Policy): Verdict =
        if (isEnforced(hit, policy)) Verdict.DENY else Verdict.ASK

    private fun liftedByWhitelist(input: JsonObject, hit: GuardClassifier.Hit, policy: Policy): String? {
        val issued = ToolInputScanner.commandCandidates(input).map { canonicalCommand(it, policy) }
        if (issued.isEmpty() || issued.any { it.isEmpty() }) return null
        if (liftedBy(issued, policy.ruleWhitelist[hit.rule], policy)) return "whitelist for ${hit.rule.label}"
        if (liftedBy(issued, policy.categoryWhitelist[hit.rule.category], policy)) {
            return "whitelist for ${hit.rule.category.label}"
        }
        if (liftedBy(issued, policy.commandWhitelist, policy)) return "whitelist that applies everywhere"
        return null
    }

    private fun liftedBy(issued: List<String>, allowed: Collection<String>?, policy: Policy): Boolean {
        if (allowed.isNullOrEmpty()) return false
        val approved = allowed.map { canonicalCommand(it, policy) }.filter { it.isNotEmpty() }.toSet()
        if (approved.isEmpty()) return false
        return issued.all { command -> approved.any { entry -> covers(entry, command) } || everySegmentCovered(command, approved) }
    }

    fun covers(entry: String, command: String): Boolean {
        if (entry.isEmpty()) return false
        if (entry == command) return true
        val segments = CommandTokenizer.segments(command)
        return segments.isNotEmpty() && segments.all { it.startsWith(entry) }
    }

    private fun everySegmentCovered(command: String, approved: Set<String>): Boolean {
        val segments = CommandTokenizer.segments(command)
        return segments.isNotEmpty() && segments.all { segment -> approved.any { segment.startsWith(it) } }
    }

    internal fun canonicalCommand(command: String, policy: Policy): String =
        CommandRules.deobfuscate(command, policy.home, policy.envValues)
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun isEnforced(hit: GuardClassifier.Hit, policy: Policy): Boolean = hit.rule !in policy.permissiveRules

    private fun reasonFor(hit: GuardClassifier.Hit, policy: Policy): String =
        if (isEnforced(hit, policy)) {
            "${hit.text} — set this rule to Permissive in $SETTINGS_PATH"
        } else {
            "${hit.text} (asked rather than refused: this rule is Permissive in $SETTINGS_PATH)"
        }
}
