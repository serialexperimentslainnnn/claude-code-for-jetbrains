package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.PlanInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The plan-mode plan, for the dashboard's Plan card.
 *
 * Null when there is no plan, so the card omits itself rather than drawing an empty panel — the same rule
 * every other card here follows. `get_plan` is READ-ONLY on the binary's side and never creates a plan file,
 * so an absent plan is an ordinary answer and not a failure to report.
 *
 * The body is markdown and is rendered as such by the page, which already sanitizes model text through the
 * same path the transcript uses: a plan is written BY the model, so it is exactly as untrusted as any other
 * thing the model emits.
 */
internal object JcefPlanData {

    fun planJson(plan: PlanInfo?): JsonObject? {
        val body = plan?.content?.trim().orEmpty()
        if (body.isEmpty()) return null
        return buildJsonObject {
            put("body", body)
            // The plan file's own path, so the card can say where it lives. Absent is normal — the binary
            // resolves its own plan slug and does not always name it.
            put("path", plan?.path?.takeIf { it.isNotBlank() })
        }
    }
}
