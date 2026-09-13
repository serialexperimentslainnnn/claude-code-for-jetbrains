package dev.lain.claudejb.model.permission.broker

import dev.lain.claudejb.model.diff.DiffPresenter
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import dev.lain.claudejb.model.protocol.models.AskQuestion
import dev.lain.claudejb.model.protocol.models.ElicitField
import kotlinx.serialization.json.JsonObject

data class PendingPermission(
    val requestId: String,
    val toolName: String,
    val input: JsonObject,
    val title: String,
    val summary: String,
    val reviewable: Boolean,
    val questions: List<AskQuestion>? = null,
    val toolUseId: String? = null,
    val isPlan: Boolean = false,
    val planText: String? = null,
    val description: String? = null,
    val decisionReason: String? = null,
    val blockedPath: String? = null,
    val elicitation: ElicitationCard? = null,
    val guard: GuardAlert? = null,
) {
    val headline: String
        get() = DiffPresenter.filePathOf(input)?.substringAfterLast('/')?.let { "$toolName on $it" } ?: toolName
}

data class GuardBypass(
    val toolName: String,
    val reason: String?,
    val rule: SecurityRule,
    val command: String? = null,
    val action: String? = null,
    val toolUseId: String? = null,
    val detail: String? = null,
)

data class GuardDenial(
    val toolName: String,
    val reason: String?,
    val rule: SecurityRule?,
    val command: String? = null,
    val toolUseId: String? = null,
    val detail: String? = null,
)

data class GuardAlert(val rule: SecurityRule, val reason: String?) {
    val label: String get() = rule.label

    val category: String get() = rule.category.label
}

data class ElicitationCard(
    val serverName: String,
    val message: String,
    val description: String?,
    val mode: String?,
    val url: String?,
    val fields: List<ElicitField>,
)
