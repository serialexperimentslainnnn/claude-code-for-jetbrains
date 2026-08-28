package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.permission.SecurityCategory
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.GuardAlert
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object JcefGuardData {

    const val BLOCKED = "blocked"

    const val ALLOWED = "allowed"

    const val WHITELISTED = "whitelisted"

    const val DISABLED = "disabled"

    private val TAB_LABELS = linkedMapOf(
        BLOCKED to "Blocked",
        ALLOWED to "Allowed",
        WHITELISTED to "Whitelisted",
        DISABLED to "Disabled",
    )

    private val VERDICT_LABELS = mapOf(
        GuardAlert.DENIED to "Refused",
        GuardAlert.ASKED to "Asked you",
        GuardAlert.ALLOWED to "Allowed",
    )

    private val VIA_LABELS = mapOf(
        PermissionBroker.REMOVE_FROM_WHITELIST to "On a whitelist",
        PermissionBroker.ENABLE_GUARD to "The guard was off",
        PermissionBroker.REVOKE_APPROVAL to "Approved in this chat",
    )

    fun tabOf(alert: GuardAlert): String = when {
        alert.verdict == GuardAlert.DENIED || alert.verdict == GuardAlert.ASKED -> BLOCKED
        alert.via == PermissionBroker.REMOVE_FROM_WHITELIST -> WHITELISTED
        alert.via == PermissionBroker.ENABLE_GUARD -> DISABLED
        else -> ALLOWED
    }

    fun idOf(alert: GuardAlert): String =
        listOf(alert.at.toString(), alert.rule, alert.verdict, alert.toolUseId.orEmpty()).joinToString("|")

    fun guardJson(
        alerts: List<GuardAlert>,
        recorded: Int,
        dropped: Int,
        recording: Boolean,
        max: Int,
    ): JsonObject {
        val newestFirst = alerts.sortedByDescending { it.at }
        return buildJsonObject {
            put("recording", recording)
            put("window", windowJson(newestFirst.size, recorded, dropped, max))
            put("tabs", tabsJson(newestFirst))
            put("catalog", catalogJson())
            put("entries", entriesJson(newestFirst))
        }
    }

    private fun catalogJson(): JsonArray = buildJsonArray {
        SecurityCategory.entries.forEach { category ->
            add(
                buildJsonObject {
                    put("id", category.name)
                    put("label", category.label)
                    put(
                        "rules",
                        buildJsonArray {
                            SecurityRule.of(category).forEach { rule ->
                                add(
                                    buildJsonObject {
                                        put("id", rule.name)
                                        put("label", rule.label)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    private fun windowJson(kept: Int, recorded: Int, dropped: Int, max: Int): JsonObject = buildJsonObject {
        put("kept", kept)
        put("max", max)
        put("recorded", recorded)
        put("dropped", dropped)
        put("missing", (recorded - dropped - kept).coerceAtLeast(0))
    }

    private fun tabsJson(alerts: List<GuardAlert>): JsonArray {
        val counts = alerts.groupingBy { tabOf(it) }.eachCount()
        return buildJsonArray {
            TAB_LABELS.forEach { (id, label) ->
                add(
                    buildJsonObject {
                        put("id", id)
                        put("label", label)
                        put("count", counts[id] ?: 0)
                    },
                )
            }
        }
    }

    private fun entriesJson(alerts: List<GuardAlert>): JsonArray = buildJsonArray {
        alerts.forEach { add(entryJson(it)) }
    }

    private fun entryJson(alert: GuardAlert): JsonObject {
        val rule = SecurityRule.from(alert.rule)
        return buildJsonObject {
            put("id", idOf(alert))
            put("tab", tabOf(alert))
            put("at", alert.at)
            put("verdict", alert.verdict)
            put("verdictLabel", VERDICT_LABELS[alert.verdict] ?: alert.verdict)
            put("rule", alert.rule)
            put("ruleLabel", rule?.label ?: alert.rule)
            put("category", rule?.category?.label ?: alert.category)
            put("categoryId", rule?.category?.name ?: alert.category)
            put("explainable", tabOf(alert) == BLOCKED && rule != null)
            alert.tool?.takeIf { it.isNotBlank() }?.let { put("tool", it) }
            alert.detail?.takeIf { it.isNotBlank() }?.let { put("detail", it) }
            alert.command?.takeIf { it.isNotBlank() }?.let { put("command", it) }
            alert.via?.takeIf { it.isNotBlank() }?.let {
                put("via", it)
                put("viaLabel", VIA_LABELS[it] ?: it)
            }
        }
    }
}
