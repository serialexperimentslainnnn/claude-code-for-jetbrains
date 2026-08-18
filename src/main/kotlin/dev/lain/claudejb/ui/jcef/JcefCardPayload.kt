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

/**
 * The request-card half of the outbound (Kotlin → JS) payloads: the permission cards the frontend renders,
 * including the two that carry their own nested payload (AskUserQuestion and an MCP elicitation).
 *
 * **Pure**, like the rest of the bridge ([JcefBridge] parses the answers these cards send back): every field
 * comes off a [PendingPermission], nothing is read from the IDE, and each optional key is omitted rather than
 * emitted as null — the card renders what it was given and hides what it was not.
 */
object JcefCardPayload {

    /** One pending permission as a card the frontend renders (Accept/Reject/View-diff, plan, or AskUserQuestion). */
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
        // A read-only unified diff for reviewable edits, so the card shows what's changing (red/green) — edits are
        // accepted/rejected as a whole; there is no per-line selection (that produced incoherent, broken code).
        diff?.takeIf { it.isNotBlank() }?.let { put("diff", it) }
        // The two card shapes that carry their own nested payload; each is only present on its own kind of card.
        p.questions?.let { put("questions", questionsJson(it)) }
        p.elicitation?.let { put("elicitation", elicitationJson(it)) }
        // Present ONLY on a card the guard raised, which is what the page keys the red alert treatment on: its
        // absence is the ordinary card, so nothing has to decide what "not an alert" looks like.
        p.guard?.let { put("guard", guardJson(it)) }
    }

    /**
     * The guard alert: which rule turned this call into a question, and the guard's own wording for it.
     *
     * Both the machine `rule` and the human `label` go on the wire, and they are not redundant. The label is
     * the exact text of the row in Settings ▸ Security and in the composer's ⚙ menu, so the user can find the
     * toggle by reading the card; the id is the precise name of the rule, which is what makes a report of a
     * false positive actionable instead of a paraphrase. `category` is the group that row lives under — the
     * Settings page is a category selector, so without it the label names a row on a page nobody can open.
     */
    private fun guardJson(g: GuardAlert) = buildJsonObject {
        put("rule", g.rule.name)
        put("label", g.rule.label)
        put("category", g.rule.category.label)
        put("reason", g.reason)
    }

    /** The AskUserQuestion payload: questions, each with its options. */
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

    /** The MCP elicitation payload: the server's ask, plus the form fields derived from its schema. */
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

    /**
     * Every card on screen, from EVERY conversation that can put one there, each tagged with its own.
     *
     * There is one permission region and there are two sessions that can ask: the chat this page was built
     * for, and the Git one embedded in its Git view. One region because a second place to look for the thing
     * that is blocking you is a place people do not look — and one renderer, so a card cannot be displayed
     * differently depending on which conversation raised it.
     *
     * `scope` is what tells the host which session an answer belongs to. Guessing from the request id would
     * resolve the wrong turn on a collision, and what is being resolved is a command about to run against
     * the working tree. An empty scope is the page's own session, so the ordinary path is unchanged.
     */
    fun permissionsJson(groups: List<Group>): String =
        JsonArray(
            groups.flatMap { group ->
                group.cards.map { card ->
                    val json = permissionJson(card, group.diffByRequest[card.requestId])
                    if (group.scope.isEmpty()) json else JsonObject(json + ("scope" to JsonPrimitive(group.scope)))
                }
            },
        ).toString()

    /** One conversation's pending cards, and the reconstructed diffs for the reviewable ones among them. */
    data class Group(
        val cards: List<PendingPermission>,
        val scope: String = "",
        val diffByRequest: Map<String, String> = emptyMap(),
    )
}
