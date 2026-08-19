package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.permission.ElicitationCard
import dev.lain.claudejb.permission.GuardAlert
import dev.lain.claudejb.permission.PendingPermission
import dev.lain.claudejb.protocol.AskQuestion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object JcefCardPayload {

    fun permissionJson(p: PendingPermission, diff: String? = null): JsonObject = buildJsonObject {
        put("id", p.requestId)
        put("tool", p.toolName)
        put("title", p.title)
        put("summary", p.summary)
        put("headline", p.headline)
        put("reviewable", p.reviewable)
        put("isPlan", p.isPlan)
        p.planText?.let { put("planText", it) }
        p.description?.let { put("description", it) }
        p.decisionReason?.let { put("decisionReason", it) }
        p.blockedPath?.let { put("blockedPath", it) }
        diff?.takeIf { it.isNotBlank() }?.let { put("diff", it) }
        p.questions?.let { put("questions", questionsJson(it)) }
        p.elicitation?.let { put("elicitation", elicitationJson(it)) }
        p.guard?.let { put("guard", guardJson(it)) }
    }

    private fun guardJson(g: GuardAlert) = buildJsonObject {
        put("rule", g.rule.name)
        put("label", g.label)
        put("category", g.category)
        g.reason?.let { put("reason", it) }
    }

    private fun questionsJson(questions: List<AskQuestion>) = buildJsonArray {
        questions.forEach { q ->
            add(
                buildJsonObject {
                    put("question", q.question)
                    put("header", q.header)
                    put("multiSelect", q.multiSelect)
                    put(
                        "options",
                        buildJsonArray {
                            q.options.forEach { o ->
                                add(
                                    buildJsonObject {
                                        put("label", o.label)
                                        put("description", o.description)
                                        o.preview?.let { put("preview", it) }
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    private fun elicitationJson(e: ElicitationCard) = buildJsonObject {
        put("serverName", e.serverName)
        put("message", e.message)
        e.description?.let { put("description", it) }
        e.mode?.let { put("mode", it) }
        e.url?.let { put("url", it) }
        put(
            "fields",
            buildJsonArray {
                e.fields.forEach { f ->
                    add(
                        buildJsonObject {
                            put("name", f.name)
                            put("type", f.type)
                            put("title", f.title)
                            put("required", f.required)
                        },
                    )
                }
            },
        )
    }

    fun permissionsJson(list: List<PendingPermission>, diffByRequest: Map<String, String> = emptyMap()): String =
        JsonArray(list.map { permissionJson(it, diffByRequest[it.requestId]) }).toString()

    fun permissionsJson(groups: List<Group>): String =
        JsonArray(
            groups.flatMap { group ->
                group.cards.map { card ->
                    val json = permissionJson(card, group.diffByRequest[card.requestId])
                    if (group.scope.isEmpty()) json else JsonObject(json + ("scope" to JsonPrimitive(group.scope)))
                }
            },
        ).toString()

    data class Group(
        val cards: List<PendingPermission>,
        val scope: String = "",
        val diffByRequest: Map<String, String> = emptyMap(),
    )
}
