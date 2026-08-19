package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.PlanInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object JcefPlanData {

    fun planJson(plan: PlanInfo?): JsonObject? {
        val body = plan?.content?.trim().orEmpty()
        if (body.isEmpty()) return null
        return buildJsonObject {
            put("body", body)
            put("path", plan?.path?.takeIf { it.isNotBlank() })
        }
    }
}
